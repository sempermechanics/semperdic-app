import hashlib
import json
import logging

import requests
from fastapi import APIRouter, Body, Depends, HTTPException, Query, Request
from fastapi.responses import StreamingResponse

from .. import apps, audit, drive, errors, repo, statuses
from .. import rate_limit
from .. import session_provision
from ..deps import (
    current_user,
    rate_limited,
    request_app,
    unless_paused,
    verified_device,
)
from ..models import SessionCreate
from ..services import recycle_bin
from ..services import sessions as session_service
from ..step_up import attested_or_mfa_user
from ..validation import PageToken, SessionId
from ._shared import clamp_page_size, drive_failure, named_app, page_block

log = logging.getLogger("semper")
router = APIRouter()


#: Retry-After on a delete refused while provisioning runs: a provisioning
#: attempt usually ends within seconds, and the app retries a failed delete on
#: its own schedule anyway.
DELETE_PROVISIONING_RETRY_AFTER_SECONDS = 30


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
    if app and app.strip().lower() == _ALL_APPS:
        return None
    return named_app(app, header_app)


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
    `?app=semper|semperclassroom` (or the old `materialtesting`) names one or
    `?app=all` asks for the whole account (the console). Each entry carries its
    `app`, by its name today. Semper and Semper Classroom share accounts, and each restoring the other's backups dropped
    what it did not understand.

    Cursor-paginated (`page_size` 1..100, `page_token`, `nextPageToken`). Quota
    `used` is the asking app's count (ADR-033), whichever app is listed, not
    the page length: each app's licence has its own cap.

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

    purged = indeterminate = 0
    if verify and sessions:
        sessions, purged, indeterminate = session_service.verify_listed(user["uid"], sessions)

    used = repo.count_user_sessions(user["uid"], header_app)
    return {
        "sessions": sessions,
        "quota": {"used": used, "max": repo.resolve_user_config(user, header_app)["maxSessions"]},
        "page": page_block(page_size, len(sessions), next_token),
        # `indeterminate` tells the client the verification was incomplete, so a
        # session still listed is not proof it was confirmed present.
        "verify": (
            {"requested": verify, "purged": purged, "indeterminate": indeterminate}
            if verify else None
        ),
    }


@router.delete("/v1/sessions/{sid}", dependencies=[rate_limited(rate_limit.session_erase_bucket)])
def delete_session(sid: SessionId, ctx=Depends(verified_device), app=Depends(request_app)):
    """Delete one analysis from the cloud: into the account's recycle bin
    (ADR-035), where it is held `RECYCLE_BIN_DAYS` and then purged — its
    Drive folder and every Firestore record of it erased, an erased marker of
    ids left for `ERASED_MARKER_DAYS`. `DELETE /v1/recycle-bin/{sid}` erases
    it sooner; `POST /v1/recycle-bin/{sid}/restore` puts it back.

    No Drive call here: the files are flagged `binnedAt` and the session doc
    moves to `deleted_sessions`. Answers `deleted` and `filesRemoved` (the
    files binned) as before, plus `binned: true` and `purgeAt`. A session
    already in the caller's bin answers the same again (idempotent).
    """
    user = ctx.user
    session = repo.get_session(sid)
    if not session or session.get("uid") != user["uid"]:
        return recycle_bin.already_binned(sid, user["uid"])
    # The worker may be creating the session's Drive folder right now: binned
    # first, the purge would miss the folder it then records nowhere. The app
    # keeps its pointer and deletes again later.
    if session_provision.provisioning_in_progress(session):
        raise HTTPException(409, errors.SESSION_PROVISIONING,
                            headers={"Retry-After": str(DELETE_PROVISIONING_RETRY_AFTER_SECONDS)})
    return recycle_bin.bin_session(user, {**session, "sessionId": sid}, ctx.device_id, app)


@router.get("/v1/sessions/{sid}/uploads", dependencies=[
    rate_limited(rate_limit.listing_bucket), unless_paused("upload", "backup"),
])
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
    user = ctx.user
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
    if not apps.same_app(repo.session_app(session), app):
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


@router.put("/v1/sessions/{sid}/metadata", dependencies=[
    rate_limited(rate_limit.session_bucket), unless_paused("upload", "backup"),
])
def replace_session_metadata(sid: SessionId, payload: dict = Body(...), ctx=Depends(verified_device)):
    """Replace a backed-up analysis's metadata.json with the app's current one.

    Every other file in a session is written once. This one changes when the
    user edits the analysis after its backup (Semper Classroom's bending
    deflection correction, its TD-150), and a restore reads the correction back from it, so without a
    replace the cloud copy restores the old deflection and E (ADR-013).

    The session must be COMPLETED: before that, the upload itself still carries
    a metadata.json and the app waits for it. The body must be this session's
    metadata (same `localSessionId`, a known schema). The bytes are written over
    the same Drive object, and the file doc's size and checksums follow them,
    so the restore's size check and the bundle manifest stay true.
    Device-signed like every other write.
    """
    user = ctx.user
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
        raise drive_failure(e, "metadata replace", rec["driveFileId"],
                            gone=409, failed=errors.DRIVE_WRITE_FAILED) from e
    if written["size"] is not None and written["size"] != len(data):
        log.error("metadata replace %s: Drive holds %s bytes, sent %d", rec["driveFileId"], written["size"], len(data))
        raise HTTPException(502, errors.DRIVE_WRITE_FAILED)

    # A rename is sent this way too: the session doc takes the new name, so the
    # backup list shows it (TD-208). A body with no name leaves the old one.
    repo.replace_file_content(sid, file_id, len(data), hashlib.sha256(data).hexdigest(), written["md5"],
                              name=repo.session_name_of(payload))
    audit.record(user["uid"], ctx.device_id, action="SESSION_METADATA_REPLACE",
                 target={"type": "session", "id": sid}, detail={"bytes": len(data)})
    return {"sessionId": sid, "sizeBytes": len(data)}


@router.get("/v1/sessions/{sid}/bundle", dependencies=[
    rate_limited(rate_limit.download_bucket), unless_paused("restore"),
])
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
    user = ctx.user
    session = _owned_session(sid, user)
    # The analysis's own app's licence (ADR-033): a browser names no app.
    if not repo.cloud_backup_enabled(user, repo.session_app(session)):
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

    manifest = session_service.bundle_manifest(sid, session, artifacts)
    audit.record(
        user["uid"], ctx.device_id,
        action="SESSION_BUNDLE_DOWNLOAD",
        target={"type": "session", "id": sid},
        detail={"fileCount": len(artifacts), "via": ctx.via},
    )
    return StreamingResponse(
        session_service.bundle_stream(token, manifest, artifacts),
        media_type="application/zip",
        headers={
            "Content-Disposition": f'attachment; filename="semper-analysis-{sid}.zip"',
            "Cache-Control": "no-store",
        },
    )


@router.post("/v1/sessions", dependencies=[
    rate_limited(rate_limit.session_bucket), unless_paused("upload", "backup"),
])
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

    The cap counts analyses stored at once, per account and app (ADR-033);
    for a demo account also per device, whichever account stored them
    (`services.sessions._device_cap_reached`).
    """
    user = ctx.user
    cfg = repo.resolve_user_config(user, app)
    # Idempotent retry: same localSessionId + still in flight → return existing.
    existing = repo.find_incomplete_session(user["uid"], body.localSessionId)
    if existing:
        return session_service.resumed(existing)
    # The same analysis is in the recycle bin (ADR-035): a phone that still
    # holds it as backed up must not upload it again behind the holder's back.
    recycle_bin.refuse_if_binned(user["uid"], body.localSessionId)
    # Quotas: one session == one analysis.
    session_service.require_room(user, cfg, body, ctx.device_id, app)
    answer, counts = session_service.record_session(user, ctx.device, ctx.device_id, body, app)
    # Access-log middleware reads this after the response returns.
    request.state.usage_counts = counts
    return answer
