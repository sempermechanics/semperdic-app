"""The session flows behind `routers/sessions.py` (TD-293): verifying a listed
page against Drive, the quota and the steps of recording an analysis, and the
streamed one-analysis archive."""
import io
import logging
import re
import uuid
import zipfile
from datetime import datetime

import requests
from fastapi import HTTPException

from .. import audit, drive, errors, repo, statuses, tasks
from .. import observability as obs
from ..config import settings
from ..licenses import MODE_DEMO
from ..models import SessionCreate
from ..session_provision import provision_session
from ._shared import json_dumps

log = logging.getLogger("semper")


# ---------------- GET /v1/sessions?verify=true ----------------
def verify_listed(uid: str, sessions: list[dict]) -> tuple[list[dict], int, int]:
    """`sessions` with every one Drive confirms missing purged, and the counts
    `(alive, purged, indeterminate)`: one bounded probe for the page, not a
    full-account N+1."""
    token = drive.access_token()
    folders = [s.get("driveFolderId") for s in sessions if s.get("driveFolderId")]
    probe = drive.probe_files(token, folders)
    alive = []
    purged = indeterminate = 0
    for s in sessions:
        folder = s.get("driveFolderId")
        state = probe.get(folder, drive.UNKNOWN) if folder else drive.ALIVE
        # Purge ONLY on a confirmed miss. An unreachable Drive (5xx, timeout,
        # token failure) reports UNKNOWN, and deleting the user's session
        # metadata on that would turn a transient outage into data loss —
        # the Drive bytes would survive with nothing left pointing at them.
        if state == drive.MISSING:
            repo.delete_session(s["sessionId"])
            audit.record(uid, action="SESSION_ORPHAN_PURGED",
                         target={"type": "session", "id": s["sessionId"]})
            obs.log_event(
                log, logging.INFO, "session_orphan_purged",
                outcome="ok", errorCode="orphan_purged", dependency="drive",
            )
            purged += 1
            continue
        if state == drive.UNKNOWN:
            indeterminate += 1
        alive.append(s)
    if indeterminate:
        obs.log_event(
            log, logging.WARNING, "session_verify_indeterminate",
            outcome="degraded", errorCode="drive_probe_unknown",
            dependency="drive", count=indeterminate,
        )
    return alive, purged, indeterminate


# ---------------- POST /v1/sessions ----------------
#: The sentence after the counts in a quota refusal, by why the cap is what it
#: is. A licence that has ended, or a shared seat not held, drops the cap to
#: the demo's while keeping every analysis stored — "120/25" is then not
#: something deleting one would fix, and saying so sent people to the wrong
#: remedy. The code before the colon is what the app branches on, unchanged.
_QUOTA_REMEDY = {
    "": "Delete an older analysis to back up a new one.",
    repo.INACTIVE_LICENCE_ENDED:
        "Your licence has ended, so the Demo limit applies. Renew it to back up "
        "new analyses; nothing stored has been removed.",
    repo.INACTIVE_NO_SEAT:
        "No shared seat is free right now, so the Demo limit applies. New "
        "analyses are backed up once you have a seat; nothing stored has been removed.",
}


def _device_cap_reached(cfg: dict, device_id: str | None) -> bool:
    """Whether a demo account's analysis would go over the demo cap counted
    per device: every account's stored analyses from this phone
    (`repo.count_device_sessions`).

    The demo cap is `max(account's stored, device's stored) >= maxSessions`;
    the account half is checked first, in the caller. Without the device half
    a new account on the same phone was a fresh 25. A licensed account is
    held to its own cap only: its allowance is bought per person, and a lab
    phone may well carry several licensed members' analyses.
    """
    if cfg["mode"] != MODE_DEMO or not device_id:
        return False
    return repo.count_device_sessions(device_id) >= cfg["maxSessions"]


def _device_quota_detail(cap: int) -> str:
    """The 409 when the device, not the account, is at the demo cap.

    Says neither how many analyses the device holds nor whose they are: the
    other analyses may belong to another account, and the caller must not
    learn that one exists, let alone its count. Its own code, not the
    account's (TD-279): the app's Re-check counts the account, finds room,
    and would lift a stop the next upload refuses again. A build that
    predates the code fails that one backup as refused (`Kind.REJECTED`).
    """
    return (f"{errors.DEVICE_QUOTA_EXCEEDED}: Demo allows {cap} analyses stored "
            "from one phone. Delete an older analysis from this phone, or use a "
            "licence, to back up a new one.")


def resumed(existing: dict) -> dict:
    """The answer to a retried create: the session already in flight for the
    same `localSessionId`, with its pending upload slots."""
    sid = existing["sessionId"]
    uploads, next_token = repo.list_pending_uploads(sid)
    return {
        "sessionId": sid,
        "status": existing.get("status"),
        "uploads": uploads,
        "nextPageToken": next_token,
    }


def require_room(user: dict, cfg: dict, body: SessionCreate, device_id: str | None,
                 app: str) -> None:
    """413 or 409 unless one more analysis of `body`'s size fits the caller's
    quota: files per session, analyses per account and app (ADR-033), and for
    a demo account analyses per device (`_device_cap_reached`)."""
    if len(body.files) > cfg["maxFilesPerSession"]:
        raise HTTPException(413, errors.TOO_MANY_FILES)
    used = repo.count_user_sessions(user["uid"], app)
    if used >= cfg["maxSessions"]:
        raise HTTPException(
            409,
            f"{errors.SESSION_QUOTA_EXCEEDED}: {used}/{cfg['maxSessions']} analyses stored. "
            + _QUOTA_REMEDY.get(repo.inactive_licence_reason(user, app), _QUOTA_REMEDY[""]),
        )
    if _device_cap_reached(cfg, device_id):
        raise HTTPException(409, _device_quota_detail(cfg["maxSessions"]))


def record_session(user: dict, device: dict, device_id: str | None,
                   body: SessionCreate, app: str) -> tuple[dict, dict]:
    """Reserve the session, write its file docs, audit it, and open its upload
    targets inline or through Cloud Tasks. Returns the route's answer and the
    usage counts the access log reads (`request.state.usage_counts`)."""
    sid = uuid.uuid4().hex
    # Reserve the session doc immediately after the quota check and BEFORE any
    # Drive work, so a failure while staging folders/files can never leave file
    # docs or a Drive subtree with no parent session (which would be invisible to
    # the quota and never reclaimed). The count→reserve window is now two
    # back-to-back Firestore ops with no Drive I/O between them; the residual
    # concurrent-create race is on a *soft* quota, not a security boundary, and is
    # accepted deliberately (a transactional cross-doc count is not modelled by
    # the Firestore client uniformly and adds no security value here).
    # Tagged with the asking app (ADR-014), so each app lists only its own.
    session = repo.create_session(sid, user, device, body, app)

    # Write the file docs (cheap, no Drive I/O) so the manifest is durable before
    # any upload target exists. Provisioning then only has to fill in uploadUrl,
    # which is what makes the task idempotent and resumable. Batched — a
    # 3-object split-bundle session was 3 round trips here for no reason.
    try:
        repo.create_files_batch(sid, user["uid"], [(f"{sid}_{f.role}_{f.name}", f) for f in body.files])
    except Exception:
        repo.delete_session(sid)
        raise

    counts = obs.metrics_counts(body.metrics, file_count=len(body.files))
    audit.record(
        user["uid"],
        device_id,
        action="SESSION_CREATE",
        target={"type": "session", "id": sid},
        detail={**counts, "app": app,
                **({"testAccount": True} if repo.test_account_of(user) else {})},
    )

    # Opening a Drive resumable session per file is ~2 round-trips each; at the
    # 600-file ceiling that cannot fit in a 60s request. Hand it to Cloud Tasks
    # and let the client poll /uploads, which it already does for resume.
    # A small manifest (the common bundle upload) is quicker inline: the task
    # hop and the client's first poll cost more than the work itself.
    small = len(body.files) <= settings.INLINE_PROVISION_MAX_FILES
    if not small and tasks.enqueue_provision(sid):
        # `create_session` stored PROVISIONING already. Nothing is written
        # here: the task may have run by now, and writing PROVISIONING over
        # its UPLOADING left the session waiting on a task that had finished.
        obs.log_event(log, logging.INFO, "session_provision_queued",
                      outcome="ok", stage="queued", count=len(body.files))
        return {"sessionId": sid, "status": statuses.SESSION_PROVISIONING, "uploads": []}, counts

    # Small manifest, or no queue configured / the enqueue failed: provision
    # inline. Same outcome from the client's point of view. Because
    # nothing will retry, a failure here rolls the whole session back rather
    # than leaving a shell against the user's quota.
    # The session and its files were written by this request, so the targets
    # just opened are the whole pending manifest: answer from them rather than
    # reading the session and every file doc back (docs/perf/request-volume.md).
    try:
        provisioned = provision_session(sid, purge_on_failure=True, session=session)
    except requests.RequestException as e:
        # Rolled back already; a Drive outage is a deliberate 502 the app
        # retries, not an unhandled 500 (readiness TE3-007).
        raise HTTPException(502, errors.DRIVE_WRITE_FAILED) from e
    return {
        "sessionId": sid,
        "status": provisioned["status"],
        "uploads": provisioned["uploads"],
        # Every target is in this one response, so there is no next page.
        "nextPageToken": None,
    }, counts


# ---------------- GET /v1/sessions/{sid}/bundle ----------------
#: Listed first in the archive so a reader has the inventory before the bytes,
#: and can tell which entry is missing if the transfer died half way.
_BUNDLE_MANIFEST = "manifest.json"

#: Zip cannot represent a date before this.
_ZIP_EPOCH = (1980, 1, 1, 0, 0, 0)


class _ZipSink(io.RawIOBase):
    """A write-only file object that hands each write straight back out.

    `zipfile` wants something it can call `write()` on; `StreamingResponse`
    wants a generator it can pull from. This is the join between the two: the
    zip writer writes, the generator drains. Nothing accumulates beyond the
    chunk in flight, which is the entire point — at the 600-file ceiling an
    analysis must not be assembled in memory first.
    """

    def __init__(self):
        self._buf = bytearray()

    def writable(self) -> bool:
        return True

    def write(self, data) -> int:
        self._buf += data
        return len(data)

    def drain(self) -> bytes:
        out = bytes(self._buf)
        del self._buf[:]
        return out


def _entry_name(artifact: dict) -> str:
    """`<role>/<name>`, matching the app's own `SessionZip.entryName`.

    The name is attacker-supplied in the sense that it came from a client
    upload, so it is reduced to a bare leaf here: no directory components, no
    drive letters, nothing that starts with a dot. A zip that unpacks outside
    the directory it was extracted into is the oldest bug in the format.
    """
    role = re.sub(r"[^A-Za-z0-9_-]", "_", str(artifact.get("role") or "extras"))
    leaf = str(artifact.get("name") or "").replace("\\", "/").rsplit("/", 1)[-1]
    leaf = re.sub(r'[\r\n:"|?*]', "_", leaf).lstrip(". ")
    return f"{role}/{leaf or artifact['fileId']}"


def _zip_time(value) -> tuple:
    if isinstance(value, datetime) and value.year >= 1980:
        return (value.year, value.month, value.day, value.hour, value.minute, value.second)
    return _ZIP_EPOCH


def bundle_manifest(sid: str, session: dict, artifacts: list[dict]) -> bytes:
    """The archive's first entry: the inventory of what follows."""
    return json_dumps({
        "sessionId": sid,
        "specimen": session.get("specimen"),
        "createdAt": session.get("createdAt"),
        "fileCount": len(artifacts),
        "files": [{
            "entry": _entry_name(a), "fileId": a["fileId"], "role": a.get("role"),
            "name": a.get("name"), "sizeBytes": a.get("sizeBytes", 0),
            "sha256": a.get("sha256"),
        } for a in artifacts],
    }).encode()


def bundle_stream(token: str, manifest: bytes, artifacts: list[dict]):
    """The stored (not deflated) zip of `artifacts`, yielded as Drive's bytes
    arrive: the manifest first, the central directory last."""
    sink = _ZipSink()
    with zipfile.ZipFile(sink, "w", zipfile.ZIP_STORED, allowZip64=True) as zf:
        zf.writestr(_BUNDLE_MANIFEST, manifest)
        if (chunk := sink.drain()):
            yield chunk
        for artifact in artifacts:
            info = zipfile.ZipInfo(_entry_name(artifact),
                                   date_time=_zip_time(artifact.get("createdAt")))
            # Declared up front so zipfile can decide on zip64 headers
            # before it has seen the bytes; it cannot seek back to fix
            # them on an unseekable sink.
            info.file_size = int(artifact.get("sizeBytes") or 0)
            try:
                dl = drive.open_download(token, artifact["driveFileId"])
                with zf.open(info, "w") as dst:
                    for part in dl.iter_chunks():
                        dst.write(part)
                        if (chunk := sink.drain()):
                            yield chunk
            except requests.RequestException:
                obs.log_event(log, logging.ERROR, "session_bundle_failed",
                              outcome="error", errorCode=errors.DRIVE_DOWNLOAD_FAILED,
                              dependency="drive")
                raise
            if (chunk := sink.drain()):
                yield chunk
    yield sink.drain()
