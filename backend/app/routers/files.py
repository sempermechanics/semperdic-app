import logging
import re

import requests
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse

from .. import audit, drive, errors, firestore_repo as repo, statuses
from .. import rate_limit
from ..deps import rate_limited, verified_device
from ..models import FileComplete
from ..validation import DocumentId

log = logging.getLogger("semper")
router = APIRouter()


def _owned_file(file_id: str, user: dict) -> dict:
    """The caller's file [file_id]. Someone else's reads as absent, not 403, so
    a file id cannot be probed for existence (as `sessions._owned_session`)."""
    rec = repo.get_file(file_id)
    if not rec or rec.get("uid") != user["uid"]:
        raise HTTPException(404, errors.FILE_NOT_FOUND)
    return rec


def _is_first_byte_request(byte_range: str | None) -> bool:
    """True for a whole-file GET (no Range) or a Range window starting at byte 0 —
    used to log exactly one FILE_DOWNLOAD audit entry per file, not one per
    adaptive download window."""
    if not byte_range:
        return True
    match = re.match(r"bytes=(\d+)-", byte_range.strip())
    return bool(match) and match.group(1) == "0"


@router.get("/v1/files/{file_id}/content", dependencies=[rate_limited(rate_limit.download_bucket)])
def download_file(file_id: DocumentId, request: Request, ctx=Depends(verified_device)):
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
    user = ctx["user"]
    f = _owned_file(file_id, user)
    if not repo.cloud_backup_enabled(user):
        raise HTTPException(403, errors.feature_not_licensed_detail())
    drive_file_id = f.get("driveFileId")
    if not drive_file_id:
        raise HTTPException(409, errors.FILE_NOT_UPLOADED)
    token = drive.access_token()
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
        if isinstance(e, requests.HTTPError) and e.response is not None:
            if e.response.status_code == 416:
                raise HTTPException(416, errors.RANGE_NOT_SATISFIABLE) from e
            if e.response.status_code == 404:
                log.error("drive download %s: object gone from Drive", drive_file_id)
                raise HTTPException(404, errors.DRIVE_FILE_GONE) from e
        log.error("drive download %s failed: %s", drive_file_id, e)
        raise HTTPException(502, errors.DRIVE_DOWNLOAD_FAILED) from e

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

    return StreamingResponse(
        dl.iter_chunks(),
        status_code=dl.status_code,
        media_type="application/octet-stream",
        headers=out_headers,
    )


@router.post(
    "/v1/files/{file_id}/complete",
    dependencies=[rate_limited(rate_limit.file_complete_bucket)],
)
def complete_file(file_id: DocumentId, body: FileComplete, ctx=Depends(verified_device)):
    user = ctx["user"]
    rec = _owned_file(file_id, user)
    # Verify the upload actually landed intact before trusting this completion.
    # The client uploads straight to Drive, so ask Drive for the real size/md5
    # and reject a truncated or corrupted object. Skipped on an idempotent retry
    # (already COMPLETED), which carries no new bytes.
    if rec.get("status") != statuses.FILE_COMPLETED:
        try:
            meta = drive.get_file_meta(drive.access_token(), body.driveFileId)
        except requests.HTTPError as e:
            log.error("drive meta for %s failed: %s", body.driveFileId, e)
            if e.response is not None and e.response.status_code == 404:
                raise HTTPException(400, errors.DRIVE_FILE_GONE) from e
            raise HTTPException(502, errors.DRIVE_META_FAILED) from e
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
    outcome = repo.complete_file(file_id, user["uid"], body)
    if not outcome:
        raise HTTPException(409, errors.SIZE_OR_STATE_MISMATCH)
    # Only a FIRST completion advances the counter — a retried completion
    # ("already") must not double-count toward session COMPLETED.
    #
    # Advance the session the FILE belongs to, never the one the client named:
    # body.sessionId is unauthenticated input, and bump_session_progress does no
    # ownership check of its own, so trusting it let a caller complete someone
    # else's session. The binding was fixed at upload time (create_file records
    # sessionId on the file doc), so the client's copy is redundant anyway.
    if outcome == "ok":
        repo.bump_session_progress(rec["sessionId"])
    audit.record(user["uid"], ctx["device"].get("deviceId"), action="UPLOAD_COMPLETE",
                 target={"type": "file", "id": file_id})
    return {"status": "ok"}
