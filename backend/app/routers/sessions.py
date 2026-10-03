import hashlib
import io
import json
import logging
import re
import uuid
import zipfile
from datetime import datetime

import requests
from fastapi import APIRouter, Body, Depends, HTTPException, Query, Request
from fastapi.responses import StreamingResponse

from .. import apps, audit, drive, errors, firestore_repo as repo, statuses
from .. import observability as obs
from .. import rate_limit
from .. import tasks
from ..config import settings
from ..deps import (
    attested_or_mfa_user,
    current_user,
    rate_limited,
    request_app,
    verified_device,
)
from ..models import SessionCreate
from ..session_provision import provision_session
from ..validation import PageToken, SessionId
from ._shared import clamp_page_size, json_dumps, page_block

log = logging.getLogger("semper")
router = APIRouter()


def _owned_session(sid: str, user: dict) -> dict:
    """The caller's session [sid]. Someone else's reads as absent, not 403, so
    a session id cannot be probed for existence."""
    session = repo.get_session(sid)
    if not session or session.get("uid") != user["uid"]:
        raise HTTPException(404, errors.SESSION_NOT_FOUND)
    return session


#: `GET /v1/sessions?app=all`: every app's sessions, for the account console.
_ALL_APPS = "all"


def _listed_app(app: str, header_app: str) -> str | None:
    """Which app's sessions `GET /v1/sessions` lists: the caller's (by
    `X-App-Id`) when `?app=` is absent, the named one, or None for `all`.
    Anything else is 400 `unknown_app`, as `?app=` on `/v1/licenses/unbind`."""
    if not app:
        return header_app
    if app.strip().lower() == _ALL_APPS:
        return None
    named = apps.from_name(app)
    if named is None:
        raise HTTPException(400, errors.UNKNOWN_APP)
    return named


@router.get("/v1/sessions")
def list_sessions(
    verify: bool = False,
    page_size: int = 50,
    page_token: PageToken = "",
    app: str = Query(default="", max_length=32),
    user=Depends(current_user),
    header_app=Depends(request_app),
):
    """The caller's cloud analyses. The app reconciles local sync state against
    this, so a session deleted in the cloud stops showing as 'synced'.

    One app's analyses (ADR-014): the asking app's, by `X-App-Id`, unless
    `?app=semper|materialtesting` names one or `?app=all` asks for the whole
    account (the console). Each entry carries its `app`. Semper and Material
    Testing share accounts, and each restoring the other's backups dropped
    what it did not understand.

    Cursor-paginated (`page_size` 1..100, `page_token`, `nextPageToken`). Quota
    `used` is the full account count, every app's, not the page length: the
    cap is the account's.

    Firestore is only an index. `?verify=true` additionally confirms each
    session on the *current page* still exists in Drive (bounded parallel
    probes — not a full-account N+1). Orphaned metadata on that page is purged.
    """
    listed_app = _listed_app(app, header_app)
    page_size = clamp_page_size(page_size, 100)
    if verify:
        rate_limit.enforce(rate_limit.session_verify_bucket, user["uid"])
    sessions, next_token = repo.list_user_sessions(
        user["uid"], limit=page_size, page_token=page_token or None, app=listed_app,
    )

    purged = 0
    indeterminate = 0
    if verify and sessions:
        token = drive.access_token()
        folders = [s.get("driveFolderId") for s in sessions if s.get("driveFolderId")]
        probe = drive.probe_files(token, folders)
        alive = []
        for s in sessions:
            folder = s.get("driveFolderId")
            state = probe.get(folder, drive.UNKNOWN) if folder else drive.ALIVE
            # Purge ONLY on a confirmed miss. An unreachable Drive (5xx, timeout,
            # token failure) reports UNKNOWN, and deleting the user's session
            # metadata on that would turn a transient outage into data loss —
            # the Drive bytes would survive with nothing left pointing at them.
            if state == drive.MISSING:
                repo.delete_session(s["sessionId"])
                audit.record(user["uid"], action="SESSION_ORPHAN_PURGED",
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
        sessions = alive
        if indeterminate:
            obs.log_event(
                log, logging.WARNING, "session_verify_indeterminate",
                outcome="degraded", errorCode="drive_probe_unknown",
                dependency="drive", count=indeterminate,
            )

    used = repo.count_user_sessions(user["uid"])
    return {
        "sessions": sessions,
        "quota": {"used": used, "max": repo.resolve_user_config(user)["maxSessions"]},
        "page": page_block(page_size, len(sessions), next_token),
        # `indeterminate` tells the client the verification was incomplete, so a
        # session still listed is not proof it was confirmed present.
        "verify": (
            {"requested": verify, "purged": purged, "indeterminate": indeterminate}
            if verify else None
        ),
    }


@router.delete("/v1/sessions/{sid}", dependencies=[rate_limited(rate_limit.session_erase_bucket)])
def delete_session(sid: SessionId, ctx=Depends(verified_device)):
    """Erase one analysis from the cloud (GDPR right to erasure).

    Permanently deletes the Drive folder — every raw image, .dat, csv and report
    inside it — then hard-deletes the Firestore metadata (which carries the
    user's email, device id and engine parameters). Nothing is soft-deleted; the
    only trace kept is the audit record that the erasure happened.
    """
    user, device = ctx["user"], ctx["device"]
    session = _owned_session(sid, user)

    folder = session.get("driveFolderId")
    if folder:
        drive.delete_file(drive.access_token(), folder)
    removed = repo.delete_session(sid)

    audit.record(user["uid"], device.get("deviceId"), action="SESSION_DELETE",
                 target={"type": "session", "id": sid},
                 detail={"filesRemoved": removed, "localSessionId": session.get("localSessionId", "")})
    log.info("Erased session %s for uid %s (%d files)", sid, user["uid"], removed)
    return {"deleted": sid, "filesRemoved": removed}


@router.get("/v1/sessions/{sid}/uploads", dependencies=[rate_limited(rate_limit.listing_bucket)])
def session_uploads(
    sid: SessionId,
    page_size: int = 1000,
    page_token: PageToken = "",
    ctx=Depends(verified_device),
):
    """What still needs uploading for a session — the resume path.

    An interrupted upload re-reads this instead of calling POST /v1/sessions
    again, so it continues into the same session/Drive folder rather than
    creating a duplicate.

    Device-signed, not merely token-authenticated: the response carries Drive
    resumable upload URIs, which are bearer capabilities to write into the
    user's Drive folder. Every other endpoint that mints or consumes those URIs
    (POST /v1/sessions, POST /v1/files/{id}/complete) requires attestation, so a
    stolen ID token alone must not be able to recover them here either. (An
    ID-token-only read was accepted during the fleet migration; retired
    2026-09-26 after 30 days of logs showed no such caller — TD-45.)
    """
    user = ctx["user"]
    session = _owned_session(sid, user)
    page_size = clamp_page_size(page_size, 1000)
    uploads, next_token = repo.list_pending_uploads(
        sid, limit=page_size, page_token=page_token or None,
    )
    return {
        "sessionId": sid,
        # PROVISIONING means the upload targets are still being opened — the
        # client should poll rather than treat an empty list as "nothing to do".
        "status": session.get("status"),
        "provisionError": session.get("provisionError"),
        "uploads": uploads,
        "page": page_block(page_size, len(uploads), next_token),
    }


@router.get("/v1/sessions/{sid}/files")
def list_session_files(
    sid: SessionId,
    page_size: int = 1000,
    page_token: PageToken = "",
    user=Depends(current_user),
    app=Depends(request_app),
):
    """The manifest for one analysis — what the app needs to restore it.

    Cursor-paginated. This silently truncated at 2000 files before, which for a
    restore means a manifest quietly missing entries.

    Only the app that backed the analysis up gets its manifest (ADR-014): the
    other app's session reads as absent, so a stale id cannot start a restore
    that drops what the restoring app does not understand.
    """
    rate_limit.enforce(rate_limit.listing_bucket, user["uid"])
    session = _owned_session(sid, user)
    if repo.session_app(session) != app:
        raise HTTPException(404, errors.SESSION_NOT_FOUND)
    page_size = clamp_page_size(page_size, 1000)
    files, next_token = repo.list_session_files(
        sid, limit=page_size, page_token=page_token or None,
    )
    return {
        "sessionId": sid,
        "localSessionId": session.get("localSessionId", ""),
        "specimen": session.get("specimen"),
        "status": session.get("status"),
        "files": files,
        "page": page_block(page_size, len(files), next_token),
    }


#: A metadata.json is a few KB, and tens at the frame ceiling; this bounds what
#: one call can write into the user's Drive folder.
_METADATA_MAX_BYTES = 256 * 1024

#: Every schema the app has written ("indic.session.metadata/1" … "/6").
_METADATA_SCHEMA_PREFIX = "indic.session.metadata/"


@router.put("/v1/sessions/{sid}/metadata", dependencies=[rate_limited(rate_limit.session_bucket)])
def replace_session_metadata(sid: SessionId, payload: dict = Body(...), ctx=Depends(verified_device)):
    """Replace a backed-up analysis's metadata.json with the app's current one.

    Every other file in a session is written once. This one changes when the
    user edits the analysis after its backup (Material Testing's bending
    deflection correction, its TD-150), and a restore reads the correction back from it, so without a
    replace the cloud copy restores the old deflection and E (ADR-013).

    The session must be COMPLETED: before that, the upload itself still carries
    a metadata.json and the app waits for it. The body must be this session's
    metadata (same `localSessionId`, a known schema). The bytes are written over
    the same Drive object, and the file doc's size and checksums follow them,
    so the restore's size check and the bundle manifest stay true.
    Device-signed like every other write.
    """
    user, device = ctx["user"], ctx["device"]
    session = _owned_session(sid, user)
    if session.get("status") != statuses.SESSION_COMPLETED:
        raise HTTPException(409, errors.SESSION_NOT_COMPLETE)

    schema = payload.get("schema")
    if not isinstance(schema, str) or not schema.startswith(_METADATA_SCHEMA_PREFIX):
        raise HTTPException(422, errors.METADATA_INVALID)
    if payload.get("localSessionId") != session.get("localSessionId"):
        raise HTTPException(422, errors.METADATA_INVALID)
    data = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
    if len(data) > _METADATA_MAX_BYTES:
        raise HTTPException(413, errors.METADATA_TOO_LARGE)

    file_id = repo.metadata_file_id(sid)
    rec = repo.get_file(file_id)
    if (not rec or rec.get("uid") != user["uid"] or rec.get("status") != statuses.FILE_COMPLETED
            or not rec.get("driveFileId")):
        raise HTTPException(404, errors.METADATA_NOT_FOUND)

    try:
        written = drive.replace_content(drive.access_token(), rec["driveFileId"], data)
    except requests.RequestException as e:
        if isinstance(e, requests.HTTPError) and e.response is not None and e.response.status_code == 404:
            log.error("metadata replace %s: object gone from Drive", rec["driveFileId"])
            raise HTTPException(409, errors.DRIVE_FILE_GONE) from e
        log.error("metadata replace %s failed: %s", rec["driveFileId"], e)
        raise HTTPException(502, errors.DRIVE_WRITE_FAILED) from e
    if written["size"] is not None and written["size"] != len(data):
        log.error("metadata replace %s: Drive holds %s bytes, sent %d", rec["driveFileId"], written["size"], len(data))
        raise HTTPException(502, errors.DRIVE_WRITE_FAILED)

    repo.replace_file_content(sid, file_id, len(data), hashlib.sha256(data).hexdigest(), written["md5"])
    audit.record(user["uid"], device.get("deviceId"), action="SESSION_METADATA_REPLACE",
                 target={"type": "session", "id": sid}, detail={"bytes": len(data)})
    return {"sessionId": sid, "sizeBytes": len(data)}


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


@router.get("/v1/sessions/{sid}/bundle", dependencies=[rate_limited(rate_limit.download_bucket)])
def download_session_bundle(sid: SessionId, ctx=Depends(attested_or_mfa_user)):
    """One analysis as a single zip — how the data leaves through a browser.

    `GET /v1/files/{id}/content` already serves the bytes, but it is
    device-attested and one file at a time: the phone's restore path, useless
    to someone sitting at a desk who has lost the phone. This route is the
    same data at the step-up tier, which a browser can satisfy with a second
    factor and a recent sign-in, and in one request instead of six hundred.

    Every artifact goes in at `<role>/<name>`, the layout the app writes and
    reads, so an archive pulled from the web unpacks into something the app
    recognises. Modern analyses store two entries (`bundle/Session.zip` and
    `extras/Extras.zip`); older ones store a file per artifact. Both are the
    same loop — the archive is of whatever was stored, with no special case.

    Stored, not deflated: the contents are already-compressed PNG and zip
    data, so compressing again would spend CPU per byte to save nothing, and
    the stream would run at the speed of the compressor rather than of Drive.

    **Every refusal happens before the first byte.** Once a response body has
    started there is no status code left to send, so the ownership check, the
    entitlement check and the Drive token are all resolved up front. A failure
    after that can only truncate the archive, which is why the manifest is
    written first and why the zip's central directory — written last — is the
    signal that the transfer completed.
    """
    user = ctx["user"]
    session = _owned_session(sid, user)
    if not repo.cloud_backup_enabled(user):
        raise HTTPException(403, errors.feature_not_licensed_detail())
    artifacts = repo.list_session_artifacts(sid)
    if not artifacts:
        # The session exists but nothing finished uploading, so there is
        # nothing to archive. Same code the single-file route uses for it.
        raise HTTPException(409, errors.FILE_NOT_UPLOADED)
    try:
        token = drive.access_token()
    except Exception:
        log.exception("session_bundle_token_failed")
        raise HTTPException(502, errors.DRIVE_DOWNLOAD_FAILED) from None

    manifest = json_dumps({
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

    audit.record(
        user["uid"], (ctx.get("device") or {}).get("deviceId"),
        action="SESSION_BUNDLE_DOWNLOAD",
        target={"type": "session", "id": sid},
        detail={"fileCount": len(artifacts), "via": ctx.get("via") or ""},
    )

    def stream():
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

    return StreamingResponse(
        stream(),
        media_type="application/zip",
        headers={
            "Content-Disposition": f'attachment; filename="semper-analysis-{sid}.zip"',
            "Cache-Control": "no-store",
        },
    )


#: The sentence after the counts in a quota refusal, by why the cap is what it
#: is. A licence that has ended, or a shared seat not held, drops the cap to
#: the demo's while keeping every analysis stored — "120/25" is then not
#: something deleting one would fix, and saying so sent people to the wrong
#: remedy. The code before the colon is what the app branches on, unchanged.
_QUOTA_REMEDY = {
    "": "Delete an older analysis to sync a new one.",
    repo.INACTIVE_LICENCE_ENDED:
        "Your licence has ended, so the demo limit applies. Renew it to sync "
        "new analyses; nothing stored has been removed.",
    repo.INACTIVE_NO_SEAT:
        "No shared seat is free right now, so the demo limit applies. New "
        "analyses sync once you hold a seat; nothing stored has been removed.",
}


@router.post("/v1/sessions", dependencies=[rate_limited(rate_limit.session_bucket)])
def create_session(body: SessionCreate, request: Request, ctx=Depends(verified_device),
                   app=Depends(request_app)):
    """Record an analysis: create the session and hand back its upload slots.

    Open to every approved account, demo included. Recording is not the
    licensed feature — retrieval is. A demo account's frames and results are
    stored under the same quota (`DEMO_MAX_ANALYSES`) and are never deleted
    on downgrade; what a licence buys is getting them back (`/content` and
    the session bundle), so the `cloudBackupEnabled` gate lives on those two
    routes and deliberately not here. Installed builds that predate licensing
    retry a 403 from this route forever, which is one more reason the gate
    would be the wrong shape.
    """
    user, device = ctx["user"], ctx["device"]
    cfg = repo.resolve_user_config(user)

    # Idempotent retry: same localSessionId + still in flight → return existing.
    existing = repo.find_incomplete_session(user["uid"], body.localSessionId)
    if existing:
        sid = existing["sessionId"]
        uploads, next_token = repo.list_pending_uploads(sid)
        return {
            "sessionId": sid,
            "status": existing.get("status"),
            "uploads": uploads,
            "nextPageToken": next_token,
        }

    # Quotas: one session == one analysis.
    if len(body.files) > cfg["maxFilesPerSession"]:
        raise HTTPException(413, errors.TOO_MANY_FILES)
    used = repo.count_user_sessions(user["uid"])
    if used >= cfg["maxSessions"]:
        raise HTTPException(
            409,
            f"{errors.SESSION_QUOTA_EXCEEDED}: {used}/{cfg['maxSessions']} analyses stored. "
            + _QUOTA_REMEDY.get(repo.inactive_licence_reason(user), _QUOTA_REMEDY[""]),
        )

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
        device.get("deviceId"),
        action="SESSION_CREATE",
        target={"type": "session", "id": sid},
        detail={**counts, "app": app},
    )
    # Access-log middleware reads this after the response returns.
    request.state.usage_counts = counts

    # Opening a Drive resumable session per file is ~2 round-trips each; at the
    # 600-file ceiling that cannot fit in a 60s request. Hand it to Cloud Tasks
    # and let the client poll /uploads, which it already does for resume.
    # A small manifest (the common bundle upload) is quicker inline: the task
    # hop and the client's first poll cost more than the work itself.
    small = len(body.files) <= settings.INLINE_PROVISION_MAX_FILES
    if not small and tasks.enqueue_provision(sid):
        repo.set_session_status(sid, statuses.SESSION_PROVISIONING)
        obs.log_event(log, logging.INFO, "session_provision_queued",
                      outcome="ok", stage="queued", count=len(body.files))
        return {"sessionId": sid, "status": statuses.SESSION_PROVISIONING, "uploads": []}

    # Small manifest, or no queue configured / the enqueue failed: provision
    # inline. Same outcome from the client's point of view. Because
    # nothing will retry, a failure here rolls the whole session back rather
    # than leaving a shell against the user's quota.
    # The session and its files were written by this request, so the targets
    # just opened are the whole pending manifest: answer from them rather than
    # reading the session and every file doc back (docs/perf/request-volume.md).
    provisioned = provision_session(sid, purge_on_failure=True, session=session)
    return {
        "sessionId": sid,
        "status": provisioned["status"],
        "uploads": provisioned["uploads"],
        # Every target is in this one response, so there is no next page.
        "nextPageToken": None,
    }
