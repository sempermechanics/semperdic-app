"""The account's recycle bin (ADR-035): list it, restore one analysis from it,
or delete one for good. `DELETE /v1/sessions/{sid}` is what puts an analysis
here; the daily job `POST /v1/tasks/recycle-bin-purge` (in
`provision_tasks.py`, with the other OIDC callbacks) purges what is due."""
import requests
from fastapi import APIRouter, Depends, Query
from google.auth import exceptions as google_auth_errors

from .. import errors, rate_limit
from ..deps import current_user, rate_limited, request_app, unless_paused, verified_device
from ..services import recycle_bin
from ..validation import PageToken, SessionId
from ._shared import clamp_page_size, drive_failure, named_app

router = APIRouter()

#: What a Drive delete raises when Drive, not our code, failed: an HTTP error
#: or lost connection, a delete Drive refused (`drive.delete_file`'s
#: PermissionError), or a token that could not be minted.
_DRIVE_FAILURES = (requests.RequestException, PermissionError, google_auth_errors.GoogleAuthError)

#: `GET /v1/recycle-bin?app=all`: every app's entries, as `GET /v1/sessions`.
_ALL_APPS = "all"


@router.get("/v1/recycle-bin", dependencies=[rate_limited(rate_limit.listing_bucket)])
def list_recycle_bin(
    page_size: int = 50,
    page_token: PageToken = "",
    app: str = Query(default="", max_length=32),
    user=Depends(current_user),
    header_app=Depends(request_app),
):
    """The caller's recycle bin for one app (by `X-App-Id`, or `?app=` as
    `GET /v1/sessions` takes it, `all` for the whole account).

    `items` are the analyses held, each with its status as it was when
    binned, `deletedAt` and `purgeAt`; `erased` are the markers of those
    purged in the last `ERASED_MARKER_DAYS` (ids alone), so a phone that
    still holds one as backed up can tell it was deleted rather than lost.
    Cursor-paginated (`page_size` 1..100, `page_token`); a page may hold
    fewer than `page_size` items because markers share it. Up to three of
    the page's entries past their `purgeAt` are purged on the way.
    """
    listed = None if app and app.strip().lower() == _ALL_APPS else named_app(app, header_app)
    page_size = clamp_page_size(page_size, 100)
    return recycle_bin.listing(user["uid"], listed, page_size, page_token or None)


@router.post("/v1/recycle-bin/{sid}/restore", dependencies=[
    rate_limited(rate_limit.session_bucket), unless_paused("upload", "backup"),
])
def restore_from_recycle_bin(sid: SessionId, ctx=Depends(verified_device), app=Depends(request_app)):
    """Put a binned analysis back in the cloud backups, with the status it
    had. Device-signed, like the delete that binned it.

    404 `session_not_found` for one that is not in the caller's bin for this
    app, is being purged, or is past its `purgeAt`. 409
    `session_quota_exceeded` / `device_quota_exceeded` when the caller has no
    room for it: a binned analysis counts toward neither cap, so a restore
    takes a place as a new backup does. A repeat of a restore that went
    through answers the same.
    """
    return recycle_bin.restore(ctx.user, sid, ctx.device_id, app)


@router.delete("/v1/recycle-bin/{sid}", dependencies=[rate_limited(rate_limit.session_erase_bucket)])
def delete_from_recycle_bin(sid: SessionId, ctx=Depends(verified_device), app=Depends(request_app)):
    """Delete a binned analysis for good, now: its Drive folder, then its
    file docs, then the entry, which leaves an erased marker of ids alone
    (GDPR right to erasure; the audit row `SESSION_PURGE` records it).

    404 `session_not_found` for one that is not in the caller's bin for this
    app; a repeat after it went answers the same as the first. 502 when Drive
    refuses or cannot be reached, with the entry left in the bin to try again.
    """
    try:
        return recycle_bin.purge_permanently(ctx.user, sid, ctx.device_id, app)
    except _DRIVE_FAILURES as e:
        raise drive_failure(e, "purge", sid, gone=502, failed=errors.DRIVE_WRITE_FAILED) from e
