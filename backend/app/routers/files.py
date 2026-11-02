import logging
import re

import requests
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse

from .. import audit, drive, errors, repo, statuses
from .. import rate_limit
from ..deps import rate_limited, request_app, unless_paused, verified_device
from ..models import FileComplete
from ..validation import DocumentId
from ._shared import drive_failure

log = logging.getLogger("semper")
router = APIRouter()


def _owned_file(file_id: str, user: dict) -> dict:
    """The caller's file [file_id]. Someone else's reads as absent, not 403, so
    a file id cannot be probed for existence (as `sessions._owned_session`)."""
    rec = repo.get_file(file_id)
    if not rec or rec.get("uid") != user["uid"]:
        raise HTTPException(404, errors.FILE_NOT_FOUND)
    return rec


def _readable_file(file_id: str, user: dict) -> dict:
    """The caller's file [file_id], if its bytes may be read back now."""
    f = _owned_file(file_id, user)
    # A binned session's files are not served (ADR-035): absent until restored.
    if f.get("binnedAt"):
        raise HTTPException(404, errors.FILE_NOT_FOUND)
    # The licence of the app the file's analysis was backed up from (ADR-033),
    # as the bundle route checks: a Semper licence does not read Semper
    # Classroom's backups back. One session read per range window.
    sid = f.get("sessionId")
    if not repo.cloud_backup_enabled(user, repo.session_app(repo.get_session(sid) if sid else None)):
        raise HTTPException(403, errors.feature_not_licensed_detail())
    return f


def _is_first_byte_request(byte_range: str | None) -> bool:
    """True for a whole-file GET (no Range) or a Range window starting at byte 0 —
    used to log exactly one FILE_DOWNLOAD audit entry per file, not one per
    adaptive download window."""
    if not byte_range:
        return True
    match = re.match(r"bytes=(\d+)-", byte_range.strip())
    return bool(match) and match.group(1) == "0"


def _download_headers(f: dict, file_id: str, dl) -> dict:
    """The headers a proxied download answers with: Drive's range and length
    passed through, and a filename that cannot break out of its quotes."""
    # The stored name is client-supplied (validated for length only), so strip
    # anything that could break out of the quoted filename or inject a header.
    raw_name = f.get("name") or file_id
    safe_name = re.sub(r'[\r\n"\\]', "_", str(raw_name))[:256] or file_id
    out_headers = {
        "Content-Disposition": f'attachment; filename="{safe_name}"',
        "Accept-Ranges": "bytes",
    }
    content_range = dl.headers.get("Content-Range")
    if content_range:
        out_headers["Content-Range"] = content_range
    content_length = dl.headers.get("Content-Length")
    if content_length:
        out_headers["Content-Length"] = content_length
    elif dl.status_code == 200 and f.get("sizeBytes"):
        out_headers["Content-Length"] = str(f.get("sizeBytes", 0))
    return out_headers


@router.get("/v1/files/{file_id}/content", dependencies=[
    rate_limited(rate_limit.download_bucket), unless_paused("restore"),
])
def download_file(file_id: DocumentId, request: Request, ctx=Depends(verified_device),
                  app=Depends(request_app)):
    """Stream one file back from Drive (restore).

    Drive has no anonymous signed download, so — unlike uploads, which go
    device→Drive directly — these bytes are proxied through Cloud Run.
    Requires the same device attestation as writes (`verified_device`).
    Clients may send `Range: bytes=N-` (unsigned header; signature covers
    method + path + empty body only); we forward Range to Drive and return
    206 + Content-Range so a truncated restore can resume into a partial file.

    Budget: Cloud Run `--timeout=300` and the content route's API Gateway
    `deadline: 300` (other JSON routes stay at 60s). Empty HTTP 500 from the
    edge usually means that budget was exhausted mid-stream.
    """
    user = ctx.user
    f = _readable_file(file_id, user)
    drive_file_id = f.get("driveFileId")
    if not drive_file_id:
        raise HTTPException(409, errors.FILE_NOT_UPLOADED)
    try:
        token = drive.access_token()
    except drive.FAILURES as e:
        raise drive_failure(e, "download token", drive_file_id,
                            gone=404, failed=errors.DRIVE_DOWNLOAD_FAILED) from e
    byte_range = request.headers.get("range")
    # A restore fetches one file in many adaptive-size Range windows (see
    # DriveTransfer.nextWindowBytes on the client), each hitting this route —
    # logging on every one would be one FILE_DOWNLOAD audit write per window
    # instead of one per file. Log only the window that starts at byte 0 (a
    # resumed download after a dropped connection restarts there too, so a
    # resume can log a second entry — rarer, and still far fewer than per-window).
    if _is_first_byte_request(byte_range):
        audit.record(user["uid"], action="FILE_DOWNLOAD", target={"type": "file", "id": file_id})
    try:
        dl = drive.open_download(token, drive_file_id, byte_range=byte_range)
    except requests.RequestException as e:
        if isinstance(e, requests.HTTPError) and e.response is not None \
                and e.response.status_code == 416:
            raise HTTPException(416, errors.RANGE_NOT_SATISFIABLE) from e
        raise drive_failure(e, "download", drive_file_id,
                            gone=404, failed=errors.DRIVE_DOWNLOAD_FAILED) from e

    return StreamingResponse(
        dl.iter_chunks(),
        status_code=dl.status_code,
        media_type="application/octet-stream",
        headers=_download_headers(f, file_id, dl),
    )


def _require_landed(rec: dict, body: FileComplete) -> None:
    """Refuse a completion unless Drive holds the object intact, in this
    session's folder: right size, matching md5, the right parent."""
    try:
        meta = drive.get_file_meta(drive.access_token(), body.driveFileId)
    except drive.FAILURES as e:
        # 400, not 404: the app discards the session and rebuilds it. A
        # timeout or a token that could not be minted is 502, as any other
        # Drive failure, not an unhandled 500.
        raise drive_failure(e, "meta", body.driveFileId,
                            gone=400, failed=errors.DRIVE_META_FAILED) from e
    if meta["size"] != rec.get("sizeBytes"):
        raise HTTPException(422, errors.SIZE_MISMATCH)
    # Whenever Drive reports an md5 (always, for our binary blobs), the client
    # MUST supply a matching one. Previously a client that simply omitted md5
    # skipped the checksum entirely — a corrupt-but-right-sized upload could be
    # accepted. md5 is only skipped when Drive itself has none (Docs-native
    # types we never store).
    if meta["md5"] and body.md5 != meta["md5"]:
        raise HTTPException(422, errors.CHECKSUM_MISMATCH)
    # Bind the object to this session's Drive folder — never trust a client
    # pointer into an arbitrary shared-drive file the download proxy would
    # then stream under the SA.
    session = repo.get_session(rec["sessionId"])
    folder = (session or {}).get("driveFolderId")
    parents = meta.get("parents") or []
    if not folder or folder not in parents:
        raise HTTPException(403, errors.FILE_NOT_IN_SESSION)


@router.post(
    "/v1/files/{file_id}/complete",
    dependencies=[rate_limited(rate_limit.file_complete_bucket), unless_paused("upload", "backup")],
)
def complete_file(file_id: DocumentId, body: FileComplete, ctx=Depends(verified_device)):
    user = ctx.user
    rec = _owned_file(file_id, user)
    # The session was binned mid-upload (ADR-035): the app stops, rather than
    # completing into a session that is no longer listed.
    if rec.get("binnedAt"):
        raise errors.Refusal(errors.SESSION_IN_RECYCLE_BIN, rec.get("sessionId") or "")
    # Verify the upload actually landed intact before trusting this completion.
    # The client uploads straight to Drive, so ask Drive for the real size/md5
    # and reject a truncated or corrupted object. Skipped on an idempotent retry
    # (already COMPLETED), which carries no new bytes.
    if rec.get("status") != statuses.FILE_COMPLETED:
        _require_landed(rec, body)
    outcome = repo.complete_file(file_id, user["uid"], body)
    if outcome is repo.Completion.REJECTED:
        raise HTTPException(409, errors.SIZE_OR_STATE_MISMATCH)
    # Only a FIRST completion advances the counter — a retried completion
    # (ALREADY) must not double-count toward session COMPLETED.
    #
    # Advance the session the FILE belongs to, never the one the client named:
    # body.sessionId is unauthenticated input, and bump_session_progress does no
    # ownership check of its own, so trusting it let a caller complete someone
    # else's session. The binding was fixed at upload time (create_files_batch records
    # sessionId on the file doc), so the client's copy is redundant anyway.
    if outcome is repo.Completion.DONE:
        repo.bump_session_progress(rec["sessionId"])
    audit.record(user["uid"], ctx.device_id, action="UPLOAD_COMPLETE",
                 target={"type": "file", "id": file_id})
    return {"status": "ok"}
