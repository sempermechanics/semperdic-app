"""Google Drive: create the session folder tree and initiate resumable uploads.

Metadata calls, plus one small write: replace_content() overwrites a session's
metadata.json (a few KB) in place. Every other file's BYTES never pass through
here — the client PUTs directly to the resumable session URI returned by
init_resumable().
"""
import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests
from requests.adapters import HTTPAdapter

from . import backoff, errors
from .config import settings
from .google_auth import drive_access_token

log = logging.getLogger("semper.drive")

API = "https://www.googleapis.com/drive/v3"
UPLOAD = (
    "https://www.googleapis.com/upload/drive/v3/files"
    "?uploadType=resumable&supportsAllDrives=true"
    "&fields=id,md5Checksum,size"
)
FOLDER_MIME = "application/vnd.google-apps.folder"

# The timeout ladder must decrease inward: Cloud Run request timeout (300s,
# deploy-backend.yml) ≥ content-route gateway deadline (300s, gateway/
# openapi.yaml) ≥ any single Drive call. Other JSON routes keep a 60s gateway
# deadline. A Drive timeout longer than the outer budget is wasted work on a
# request the client has already been told timed out. Quick metadata calls keep
# their own tighter 30s; this is the ceiling for the slower ones (recursive
# delete, and the time-to-first-byte of a streaming download — the body itself
# then streams under the outer Cloud Run / content-route gateway limits).
_TIMEOUT_S = 45

_RETRY_STATUSES = frozenset({429, 500, 502, 503, 504})
_MAX_ATTEMPTS = 5

# Ceiling on time spent *sleeping* between retries within one call. Backoff is
# min(2**attempt, 16), so five attempts could sleep ~30s — inside a request whose
# total Cloud Run budget is 60s. Without a cap, a slow Drive turned one retrying
# call into a request that timed out with nothing to show for it.
_RETRY_BUDGET_S = 20.0

# One pooled session for every Drive call. Bare `requests.request` opens a fresh
# TCP + TLS connection per call; a session that provisions N files made N+ full
# handshakes to the same host. Sized for Cloud Run's --concurrency=40.
_session_lock = threading.Lock()
_http: requests.Session | None = None


def http() -> requests.Session:
    """Process-wide pooled HTTP session (thread-safe, like the Firestore client)."""
    global _http
    if _http is None:
        with _session_lock:
            if _http is None:
                session = requests.Session()
                adapter = HTTPAdapter(pool_connections=8, pool_maxsize=64, max_retries=0)
                session.mount("https://", adapter)
                _http = session
    return _http


def access_token() -> str:
    return drive_access_token()


def _headers(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _retry_delay(response: requests.Response, attempt: int) -> float:
    """Seconds to wait before retrying: honor Retry-After (capped), else exponential."""
    return backoff.retry_delay(attempt, response.headers.get("Retry-After"))


def _request_with_retry(
    method: str,
    url: str,
    *,
    headers: dict | None = None,
    params: dict | None = None,
    json: dict | None = None,
    timeout: float = 30,
    stream: bool = False,
    max_attempts: int = _MAX_ATTEMPTS,
) -> requests.Response:
    """HTTP call with exponential backoff on 429/5xx (honors Retry-After).

    Used for idempotent Drive GETs and folder find-or-create. Timeouts are
    kept per attempt so a stuck socket still fails within the outer budget.
    """
    last: requests.Response | None = None
    slept = 0.0
    for attempt in range(max_attempts):
        r = http().request(
            method,
            url,
            headers=headers,
            params=params,
            json=json,
            timeout=timeout,
            stream=stream,
        )
        if r.status_code not in _RETRY_STATUSES or attempt == max_attempts - 1:
            return r
        delay = _retry_delay(r, attempt)
        # Stop retrying once the sleep budget is spent, and return the last
        # response so the caller reports Drive's own status rather than having
        # the whole request killed by the Cloud Run timeout mid-backoff.
        if slept + delay > _RETRY_BUDGET_S:
            log.warning(
                "Drive %s %s → HTTP %s; retry budget spent after %.1fs, giving up",
                method, url, r.status_code, slept,
            )
            return r
        log.warning(
            "Drive %s %s → HTTP %s; retry in %.1fs (%d/%d)",
            method, url, r.status_code, delay, attempt + 1, max_attempts,
        )
        if stream:
            r.close()
        time.sleep(delay)
        slept += delay
        last = r
    assert last is not None
    return last


def _escape_q_value(value: str) -> str:
    """Escape a literal for a single-quoted Drive `q=` string value.

    The backslash must be escaped first. Escaping only the quote leaves a
    trailing backslash free to consume the closing quote we add (`x\\` becomes
    `x\\'`, which closes the literal), letting a crafted name alter the query.
    """
    return value.replace("\\", "\\\\").replace("'", "\\'")


def _find_or_create_folder(token: str, name: str, parent: str) -> str:
    safe = _escape_q_value(name)
    q = (
        f"name='{safe}' and mimeType='{FOLDER_MIME}' and "
        f"'{parent}' in parents and trashed=false"
    )
    r = _request_with_retry(
        "GET",
        f"{API}/files",
        headers=_headers(token),
        params={
            "q": q,
            "fields": "files(id)",
            "supportsAllDrives": "true",
            "includeItemsFromAllDrives": "true",
            "corpora": "drive",
            "driveId": settings.SHARED_DRIVE_ID,
        },
        timeout=30,
    )
    r.raise_for_status()
    files = r.json().get("files", [])
    if files:
        return files[0]["id"]
    return _create_folder(token, name, parent)


def _create_folder(token: str, name: str, parent: str) -> str:
    """Create a folder without looking for an existing one first.

    For names that cannot exist yet — a session id minted moments ago — the
    search in _find_or_create_folder is a Drive round-trip that always comes
    back empty.
    """
    r = _request_with_retry(
        "POST",
        f"{API}/files",
        headers=_headers(token),
        params={"supportsAllDrives": "true"},
        json={"name": name, "mimeType": FOLDER_MIME, "parents": [parent],
              "driveId": settings.SHARED_DRIVE_ID},
        timeout=30,
    )
    r.raise_for_status()
    return r.json()["id"]


def _find_folder(token: str, name: str, parent: str):
    """Look a folder up WITHOUT creating it — used on the erasure path."""
    safe = _escape_q_value(name)
    q = (
        f"name='{safe}' and mimeType='{FOLDER_MIME}' and "
        f"'{parent}' in parents and trashed=false"
    )
    r = _request_with_retry(
        "GET",
        f"{API}/files",
        headers=_headers(token),
        params={
            "q": q,
            "fields": "files(id)",
            "supportsAllDrives": "true",
            "includeItemsFromAllDrives": "true",
            "corpora": "drive",
            "driveId": settings.SHARED_DRIVE_ID,
        },
        timeout=30,
    )
    r.raise_for_status()
    files = r.json().get("files", [])
    return files[0]["id"] if files else None


def find_user_folder(token: str, uid: str):
    """The user's whole Drive subtree (…/Research Storage/user/{uid}), or None.

    Best-effort cleanup only. Callers deleting real data must go through the
    stored `driveFolderId` per session — a name walk that quietly returns None
    (renamed folder, wrong ROOT_FOLDER_ID, a level missing) would look exactly
    like "nothing to delete" and strand the user's blobs.
    """
    research = _find_folder(token, "Research Storage", settings.ROOT_FOLDER_ID)
    if not research:
        log.warning("find_user_folder: no 'Research Storage' under root %s", settings.ROOT_FOLDER_ID)
        return None
    user_dir = _find_folder(token, "user", research)
    if not user_dir:
        log.warning("find_user_folder: no 'user' folder under Research Storage")
        return None
    found = _find_folder(token, uid, user_dir)
    if not found:
        log.warning("find_user_folder: no folder named %s under user/", uid)
    return found


def ensure_session_folders(token: str, uid: str, sid: str, roles=None,
                           cached: dict | None = None,
                           session_folder_id: str | None = None) -> dict:
    """Build Research Storage/user/{uid}/session/{sid}/ plus the role subfolders
    the manifest actually uses. "bundle" (Session.zip), "extras" (Extras.zip) and
    "metadata" live at the session root — no subfolder, no extra Drive round-trips.

    `cached` is the user doc's stored {"userFolderId", "sessionsFolderId"}. The
    name walk down to session/ is four sequential Drive lists (~3 s) and its
    answer never changes for a user, so when the stored session/ folder is
    still alive the walk is skipped. Alive is checked, not assumed: a folder
    deleted or trashed straight in Drive would otherwise take the new upload
    with it. The check runs alongside creating {sid}/ under it, so a live cache
    costs one Drive round-trip rather than three; when the check fails, that
    {sid}/ is deleted and the walk runs.

    `session_folder_id` is the session's stored driveFolderId from an earlier
    attempt (a Cloud Tasks retry). It is reused instead of making a second
    {sid}/ folder.

    Partial failure mid-walk may leave an empty orphan folder under session/;
    that is monitored / accepted rather than rolled back.
    """
    cached = cached or {}
    uid_dir, sess_dir = cached.get("userFolderId"), cached.get("sessionsFolderId")
    alive, sid_dir = False, None
    if uid_dir and sess_dir:
        if session_folder_id:
            alive = file_exists(token, sess_dir)
            sid_dir = session_folder_id
        else:
            alive, sid_dir = _check_and_create(token, sess_dir, sid)
    if not alive:
        root = settings.ROOT_FOLDER_ID
        research = _find_or_create_folder(token, "Research Storage", root)
        user_dir = _find_or_create_folder(token, "user", research)
        uid_dir = _find_or_create_folder(token, uid, user_dir)
        sess_dir = _find_or_create_folder(token, "session", uid_dir)
        # Searched, not blindly created: after a walk nothing says whether an
        # earlier attempt already made {sid}/ here.
        sid_dir = _find_or_create_folder(token, sid, sess_dir)
    # userFolderId / sessionsFolderId are returned so they can be persisted on
    # the user doc: account deletion erases Drive via the stored id instead of
    # re-walking names, and the next upload skips the walk.
    folders = {"sessionFolderId": sid_dir, "userFolderId": uid_dir,
               "sessionsFolderId": sess_dir,
               "bundle": sid_dir, "extras": sid_dir, "metadata": sid_dir}
    wanted = roles if roles is not None else ("raw", "processed", "reports", "csv", "dat")
    for role in wanted:
        if role not in folders:
            folders[role] = _find_or_create_folder(token, role, sid_dir)
    return folders


def _check_and_create(token: str, sess_dir: str, sid: str) -> tuple[bool, str | None]:
    """Check the cached session/ folder and create {sid}/ in it, concurrently.

    Returns (alive, sid_dir). When session/ is gone or trashed, a {sid}/ made
    anyway (a trashed parent still accepts children) is deleted, best effort,
    and (False, None) comes back so the caller walks.
    """
    with ThreadPoolExecutor(max_workers=2) as pool:
        check = pool.submit(file_exists, token, sess_dir)
        create = pool.submit(_create_folder, token, sid, sess_dir)
        alive = check.result()
        try:
            sid_dir = create.result()
        except requests.HTTPError:
            if alive:
                raise
            sid_dir = None
    if alive:
        return True, sid_dir
    if sid_dir:
        try:
            delete_file(token, sid_dir)
        except Exception as e:  # noqa: BLE001
            log.warning("could not delete %s made under a stale session folder: %s", sid_dir, e)
    return False, None


def file_exists(token: str, file_id: str) -> bool:
    """Is this file/folder still really in Drive (and not trashed)?

    Firestore is only an index — if someone deletes a session folder straight in
    Drive, the index still claims COMPLETED. This is the check that catches that.
    """
    r = _request_with_retry(
        "GET",
        f"{API}/files/{file_id}",
        headers=_headers(token),
        params={"fields": "id,trashed", "supportsAllDrives": "true"},
        timeout=30,
    )
    if r.status_code == 404:
        return False
    r.raise_for_status()
    return not r.json().get("trashed", False)


# Probe outcomes. Deliberately tristate: the caller deletes user data on
# MISSING, so "we could not tell" must never collapse into "it is gone".
ALIVE = "alive"
MISSING = "missing"
UNKNOWN = "unknown"


def probe_files(token: str, file_ids: list[str], *, max_workers: int = 8) -> dict[str, str]:
    """Bounded parallel existence checks for one page of session folders.

    Replaces a serial N+1 verify loop with a capped fan-out (default 8 workers)
    over the current page only — callers must paginate so |file_ids| stays small.

    Returns ALIVE / MISSING / UNKNOWN per id. A probe that raises (Drive 5xx, a
    timeout, an expired token) yields UNKNOWN, not MISSING: the caller purges
    Firestore session metadata on MISSING, so mapping a transient outage to
    "missing" turned a Drive blip into permanent data loss.
    """
    from concurrent.futures import ThreadPoolExecutor, as_completed

    unique = [fid for fid in dict.fromkeys(file_ids) if fid]
    if not unique:
        return {}
    out: dict[str, str] = {}
    workers = max(1, min(max_workers, len(unique)))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(file_exists, token, fid): fid for fid in unique}
        for fut in as_completed(futures):
            fid = futures[fut]
            try:
                out[fid] = ALIVE if fut.result() else MISSING
            except Exception:  # noqa: BLE001 - unreachable != absent
                log.warning("Drive existence probe failed for %s — treating as unknown", fid)
                out[fid] = UNKNOWN
    return out


def ping(timeout_s: float = 5.0) -> None:
    """Cheap Drive reachability probe for readiness (Shared Drive about)."""
    from .observability import DependencyError

    try:
        token = access_token()
        r = http().get(
            f"{API}/drives/{settings.SHARED_DRIVE_ID}",
            headers=_headers(token),
            params={"fields": "id"},
            timeout=timeout_s,
        )
    except Exception as e:  # noqa: BLE001
        raise DependencyError(errors.DRIVE_UNREACHABLE, "drive") from e
    if r.status_code >= 400:
        raise DependencyError(errors.DRIVE_UNHEALTHY, "drive")


def delete_file(token: str, file_id: str) -> None:
    """**Permanently** delete a file/folder (GDPR erasure).

    files.delete on a Shared Drive skips the trash and removes descendants, so
    deleting a session folder erases every artifact inside it. A 404 is treated
    as success — the goal is "it is gone", and it already is.
    """
    r = http().delete(
        f"{API}/files/{file_id}",
        headers=_headers(token),
        params={"supportsAllDrives": "true"},
        timeout=_TIMEOUT_S,
    )
    if r.status_code in (200, 204):
        return  # explicit success

    if r.status_code == 404:
        # Ambiguous: Drive returns 404 both for "already gone" AND for "you may
        # not touch this" (it hides existence instead of returning 403). Taking
        # it as success once meant erasure silently no-op'd while reporting that
        # it had deleted everything. Never believe a 404 — check.
        if file_exists(token, file_id):
            log.error("drive delete %s: 404 but the file is STILL THERE — permission problem", file_id)
            raise PermissionError(
                f"Drive refused to delete {file_id}. The service account needs "
                "Manager (organizer) rights on the shared drive: files.delete "
                "requires organizer rights on the parent."
            )
        return  # genuinely absent

    log.error("drive delete %s FAILED: HTTP %s %s", file_id, r.status_code, r.text[:500])
    r.raise_for_status()


class DriveDownload:
    """Open Drive media response — status/headers for Range, then chunked body."""

    def __init__(self, response: requests.Response):
        self.status_code = response.status_code
        self.headers = response.headers
        self._response = response

    def iter_chunks(self, chunk_size: int = 256 * 1024):
        try:
            for chunk in self._response.iter_content(chunk_size=chunk_size):
                if chunk:
                    yield chunk
        finally:
            self._response.close()


def open_download(
    token: str,
    drive_file_id: str,
    byte_range: str | None = None,
) -> DriveDownload:
    """Open a Drive file media stream, optionally with an HTTP Range.

    Drive supports `Range` on `alt=media` and answers with 206 + Content-Range
    when a range is honored. There is still no anonymous signed download URL,
    so restore bytes remain proxied through Cloud Run — but clients can resume
    a truncated transfer from the last byte instead of restarting.
    """
    headers = _headers(token)
    if byte_range:
        headers["Range"] = byte_range
    r = _request_with_retry(
        "GET",
        f"{API}/files/{drive_file_id}",
        headers=headers,
        params={"alt": "media", "supportsAllDrives": "true"},
        stream=True,
        timeout=_TIMEOUT_S,
    )
    if r.status_code not in (200, 206):
        r.raise_for_status()
    return DriveDownload(r)


def get_file_meta(token: str, drive_file_id: str) -> dict:
    """The size (bytes), md5, and parents Drive recorded for an uploaded file.

    The client PUTs bytes straight to Drive, so its claimed size/checksum are not
    authoritative — this is how the backend verifies the upload landed intact.
    `parents` lets complete_file reject a driveFileId that is not in the
    session's own folder (confused-deputy / shared-drive object binding).
    Returns {"size": int|None, "md5": str|None, "parents": list[str]}.
    """
    r = _request_with_retry(
        "GET",
        f"{API}/files/{drive_file_id}",
        headers=_headers(token),
        params={"fields": "size,md5Checksum,parents", "supportsAllDrives": "true"},
        timeout=30,
    )
    r.raise_for_status()
    j = r.json()
    size = j.get("size")
    return {
        "size": int(size) if size is not None else None,
        "md5": j.get("md5Checksum"),
        "parents": list(j.get("parents") or []),
    }


def init_resumable(token: str, parent_folder_id: str, filename: str, size_bytes: int) -> str:
    """Start a resumable session; return the URI the client uploads bytes to."""
    r = http().post(
        UPLOAD,
        headers={**_headers(token), "Content-Type": "application/json; charset=UTF-8",
                 "X-Upload-Content-Length": str(size_bytes)},
        json={"name": filename, "parents": [parent_folder_id],
              "driveId": settings.SHARED_DRIVE_ID},
        timeout=30,
    )
    r.raise_for_status()
    return r.headers["Location"]


def replace_content(token: str, drive_file_id: str, data: bytes, mime: str = "application/json") -> dict:
    """Overwrite a small file's bytes in place: same id, same folder.

    Only for a session's metadata.json, which the app re-sends when a change
    made after the backup (a bending deflection correction) must reach the
    cloud copy. One media PUT with no retry loop: the bytes are the whole
    payload, and the app retries a failed call itself.
    Returns {"size": int|None, "md5": str|None} as Drive recorded them.
    """
    r = http().patch(
        f"https://www.googleapis.com/upload/drive/v3/files/{drive_file_id}",
        headers={**_headers(token), "Content-Type": mime},
        params={"uploadType": "media", "supportsAllDrives": "true", "fields": "size,md5Checksum"},
        data=data,
        timeout=30,
    )
    r.raise_for_status()
    j = r.json()
    size = j.get("size")
    return {"size": int(size) if size is not None else None, "md5": j.get("md5Checksum")}
