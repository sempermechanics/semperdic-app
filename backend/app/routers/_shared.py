"""Helpers more than one router uses.

Routers import from here, never from each other: `sessions` used to reach into
`account` for `json_dumps`, which made the order routers are loaded in matter
(TD-54).
"""
import logging

import requests
from fastapi import HTTPException

from .. import apps, errors

log = logging.getLogger("semper")


def clamp_page_size(page_size: int, ceiling: int) -> int:
    """A requested page size held to 1..ceiling."""
    return max(1, min(page_size, ceiling))


def page_block(size: int, count: int, next_token: str | None) -> dict:
    """The `page` object every cursor-paginated listing returns."""
    return {
        "size": size,
        "count": count,
        "nextPageToken": next_token,
        "hasMore": bool(next_token),
    }


def drive_failure(e: Exception, what: str, drive_file_id: str, *,
                  gone: int, failed: str) -> HTTPException:
    """The answer for a Drive call that raised one of `drive.FAILURES`.

    Drive answering 404 for an object the index points at is `drive_file_gone`
    with the route's own `gone` status — it was deleted straight in Drive, or
    the upload never landed, and a 5xx would make the app retry forever.
    Anything else (a 5xx, a timeout, a refused delete, a token that could not
    be minted) is 502 with the route's `failed` code.
    """
    if isinstance(e, requests.HTTPError) and e.response is not None \
            and e.response.status_code == 404:
        log.error("drive %s %s: object gone from Drive", what, drive_file_id)
        return HTTPException(gone, errors.DRIVE_FILE_GONE)
    log.error("drive %s %s failed: %s", what, drive_file_id, e)
    return HTTPException(502, failed)


def named_app(app: str, header_app: str) -> str:
    """The app a `?app=` names (`semper`, `semperclassroom`, or the old
    `materialtesting`, read as `semperclassroom`), or the caller's
    own (by `X-App-Id`) when there is none. A browser cannot send the header,
    so the consoles name the app. Anything else is 400 `unknown_app`."""
    if not app:
        return header_app
    named = apps.from_name(app)
    if named is None:
        raise HTTPException(400, errors.UNKNOWN_APP)
    return named
