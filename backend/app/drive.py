"""Google Drive: create the session folder tree and initiate resumable uploads.

Metadata calls, plus one small write: replace_content() overwrites a session's
metadata.json (a few KB) in place. Every other file's BYTES never pass through
here — the client PUTs directly to the resumable session URI returned by
init_resumable().
"""
import logging
import threading
import time
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait

import requests
from google.auth import exceptions as google_auth_errors
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


# Connections kept per host; also the most Drive calls the pool below runs at
# once, so that a fan-out never opens connections the pool then discards.
_HTTP_POOL = 64

# One worker pool for every Drive fan-out in the process. Each call used to
# start its own ThreadPoolExecutor (8 workers), so forty concurrent requests
# could put 320 Drive calls against 64 pooled connections, and every call paid
# for thread start-up. Work submitted here never submits more work here: a
# task that waited on the pool from inside it could deadlock it.
_pool = ThreadPoolExecutor(max_workers=_HTTP_POOL, thread_name_prefix="drive")


def fan_out(fn, items, *, width: int) -> list:
    """`fn` over `items` on the shared Drive pool, at most `width` of this
    caller's calls in flight, so one large manifest cannot queue ahead of
    every other request. Results come back in input order. The first failure
    stops further submissions and is raised once the calls in flight finish.
    """
    items = list(items)
    results: list = [None] * len(items)
    pending = iter(enumerate(items))
    in_flight: dict = {}
    failure: BaseException | None = None

    def submit_next() -> None:
        nxt = next(pending, None)
        if nxt is not None:
            in_flight[_pool.submit(fn, nxt[1])] = nxt[0]

    for _ in range(max(1, min(width, len(items)))):
        submit_next()
    while in_flight:
        done, _ = wait(in_flight, return_when=FIRST_COMPLETED)
        for fut in done:
            index = in_flight.pop(fut)
            try:
                results[index] = fut.result()
            except BaseException as e:  # noqa: BLE001 - re-raised below
                failure = failure or e
            if failure is None:
                submit_next()
    if failure is not None:
        raise failure
    return results


def http() -> requests.Session:
    """Process-wide pooled HTTP session (thread-safe, like the Firestore client)."""
    global _http
    if _http is None:
        with _session_lock:
            if _http is None:
                session = requests.Session()
                adapter = HTTPAdapter(pool_connections=8, pool_maxsize=_HTTP_POOL, max_retries=0)
                session.mount("https://", adapter)
                _http = session
    return _http


class DriveAccessLost(PermissionError):
    """The service account cannot see the Shared Drive at all.

    Drive answers 404 for every file of a drive the caller has lost, the same
    answer as for a file that is gone. Read as "gone", that let the verify
    step purge metadata whose bytes were still in Drive, and an erasure
    report success with every blob left behind. Raised instead of believing
    the 404."""


#: What a Drive call raises when Drive (not our code) failed: an HTTP error,
#: a timeout or lost connection, a delete Drive refused, or a token that
#: could not be minted. Routes answer these 502 rather than an unhandled 500.
FAILURES = (requests.RequestException, PermissionError, google_auth_errors.GoogleAuthError)


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

    Used for idempotent Drive GETs; a folder create retries its own way
    (`_create_folder`). Timeouts are kept per attempt so a stuck socket still
    fails within the outer budget.
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
    # Every pass above returns on its last attempt, so only max_attempts < 1
    # gets here. A raise, not an assert: `python -O` strips asserts.
    if last is None:
        raise ValueError(f"max_attempts must be at least 1, got {max_attempts}")
    return last


def _escape_q_value(value: str) -> str:
    """Escape a literal for a single-quoted Drive `q=` string value.

    The backslash must be escaped first. Escaping only the quote leaves a
    trailing backslash free to consume the closing quote we add (`x\\` becomes
    `x\\'`, which closes the literal), letting a crafted name alter the query.
    """
    return value.replace("\\", "\\\\").replace("'", "\\'")


def _find_or_create_folder(token: str, name: str, parent: str) -> str:
    """The folder `name` under `parent`, created when there is none.

    Two first uploads at once can each find nothing and each create one, so
    a name can be doubled; the lookup takes the oldest, so every later walk
    lands in the same folder, and erasure deletes them all
    (`find_user_folders`)."""
    return _find_folder(token, name, parent) or _create_folder(token, name, parent)


def _create_folder(token: str, name: str, parent: str) -> str:
    """Create a folder without looking for an existing one first.

    For names that cannot exist yet — a session id minted moments ago — the
    search in _find_or_create_folder is a Drive round-trip that always comes
    back empty.

    The POST is not retried blindly. Drive can make the folder and still
    answer 5xx, or the answer can be lost to a timeout, and a second POST then
    made a second folder of the same name: two `user/{uid}` trees, one that
    erasure did not know about. After such a failure the folder is looked for
    by name first, and posted again only when it is not there.
    """
    slept = 0.0
    for attempt in range(_MAX_ATTEMPTS):
        try:
            r = http().request(
                "POST",
                f"{API}/files",
                headers=_headers(token),
                params={"supportsAllDrives": "true"},
                json={"name": name, "mimeType": FOLDER_MIME, "parents": [parent],
                      "driveId": settings.SHARED_DRIVE_ID},
                timeout=30,
            )
        except (requests.Timeout, requests.ConnectionError) as e:
            failure: requests.Response | Exception = e
            delay = backoff.retry_delay(attempt, None)
        else:
            if r.status_code not in _RETRY_STATUSES:
                r.raise_for_status()
                return r.json()["id"]
            failure, delay = r, _retry_delay(r, attempt)
        if attempt == _MAX_ATTEMPTS - 1 or slept + delay > _RETRY_BUDGET_S:
            break
        log.warning("Drive folder create %r -> %s; looking for it before trying again in %.1fs",
                    name, getattr(failure, "status_code", type(failure).__name__), delay)
        time.sleep(delay)
        slept += delay
        made = _find_folder(token, name, parent)
        if made:
            return made
    if isinstance(failure, requests.Response):
        failure.raise_for_status()
    raise failure


def _find_folders(token: str, name: str, parent: str) -> list[str]:
    """Every live folder `name` under `parent`, oldest first."""
    safe = _escape_q_value(name)
    q = (
        f"name='{safe}' and mimeType='{FOLDER_MIME}' and "
        f"'{parent}' in parents and trashed=false"
    )
    found: list[str] = []
    page_token = None
    while True:
        params = {
            "q": q,
            "fields": "nextPageToken,files(id)",
            "orderBy": "createdTime",
            "pageSize": "100",
            "supportsAllDrives": "true",
            "includeItemsFromAllDrives": "true",
            "corpora": "drive",
            "driveId": settings.SHARED_DRIVE_ID,
        }
        if page_token:
            params["pageToken"] = page_token
        r = _request_with_retry("GET", f"{API}/files", headers=_headers(token),
                                params=params, timeout=30)
        r.raise_for_status()
        body = r.json()
        found += [f["id"] for f in body.get("files", [])]
        page_token = body.get("nextPageToken")
        if not page_token:
            return found


def _find_folder(token: str, name: str, parent: str) -> str | None:
    """Look a folder up WITHOUT creating it: the lookup half of
    `_find_or_create_folder`. The oldest when the name is doubled, so every
    call picks the same one."""
    found = _find_folders(token, name, parent)
    return found[0] if found else None


#: The folder under "Research Storage" that holds every account's subtree,
#: and the one that holds test accounts' instead (ADR-027), so test uploads
#: never sit beside customers' and can be listed or cleared on their own.
USERS_FOLDER = "user"
TEST_USERS_FOLDER = "_test"


def find_user_folders(token: str, uid: str, *, test: bool = False) -> list[str]:
    """Every folder named {uid} under …/Research Storage/user/ — or, for a
    test account (`test`), …/Research Storage/_test/ — oldest first; [] when
    there is none.

    Every one, not the first: two first uploads at once, or a folder create
    Drive made but answered 5xx, can leave two `{uid}` folders, and erasure
    must delete both. A doubled scaffolding level is walked too.

    Not the only record erasure goes by: a name walk that quietly finds
    nothing (renamed folder, wrong ROOT_FOLDER_ID, a level missing) would look
    exactly like "nothing to delete", so each session's stored
    `driveFolderId` is deleted as well (`services.account._purge_drive`).
    """
    users_name = TEST_USERS_FOLDER if test else USERS_FOLDER
    research_dirs = _find_folders(token, "Research Storage", settings.ROOT_FOLDER_ID)
    if not research_dirs:
        log.warning("find_user_folders: no 'Research Storage' under root %s", settings.ROOT_FOLDER_ID)
        return []
    user_dirs = [d for research in research_dirs for d in _find_folders(token, users_name, research)]
    if not user_dirs:
        log.warning("find_user_folders: no '%s' folder under Research Storage", users_name)
        return []
    found = [f for user_dir in user_dirs for f in _find_folders(token, uid, user_dir)]
    if not found:
        log.warning("find_user_folders: no folder named %s under %s/", uid, users_name)
    elif len(found) > 1:
        log.warning("find_user_folders: %d folders named %s under %s/", len(found), uid, users_name)
    return found


def ensure_session_folders(token: str, uid: str, sid: str, roles=None,
                           cached: dict | None = None,
                           session_folder_id: str | None = None,
                           *, test: bool = False) -> dict:
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

    `test`: a test account's tree is under Research Storage/_test/ instead
    of user/ (ADR-027).
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
        user_dir = _find_or_create_folder(
            token, TEST_USERS_FOLDER if test else USERS_FOLDER, research)
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
    check = _pool.submit(file_exists, token, sess_dir)
    create = _pool.submit(_create_folder, token, sid, sess_dir)
    wait([check, create])
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


def _shared_drive_visible(token: str) -> bool:
    """Can the service account still see the Shared Drive? The same
    `drives/{id}` read `ping` makes: False on its 404, and any other failure
    raised, since that is no answer either way."""
    r = _request_with_retry(
        "GET",
        f"{API}/drives/{settings.SHARED_DRIVE_ID}",
        headers=_headers(token),
        params={"fields": "id"},
        timeout=30,
    )
    if r.status_code == 404:
        return False
    r.raise_for_status()
    return True


def _visibility_once(token: str):
    """`_shared_drive_visible`, asked at most once however many calls share
    it (a fan-out's probes). A failure is not kept, so the next caller asks."""
    lock = threading.Lock()
    answer: list[bool] = []

    def visible() -> bool:
        with lock:
            if not answer:
                answer.append(_shared_drive_visible(token))
            return answer[0]

    return visible


def file_exists(token: str, file_id: str, *, drive_visible=None) -> bool:
    """Is this file/folder still really in Drive (and not trashed)?

    Firestore is only an index — if someone deletes a session folder straight in
    Drive, the index still claims COMPLETED. This is the check that catches that.

    A 404 is believed only once the Shared Drive itself is visible: Drive
    answers 404 for every file of a drive the service account has lost, and
    that raises `DriveAccessLost` instead. `drive_visible` is that check,
    shared across calls (`_visibility_once`); by default each 404 asks.
    """
    r = _request_with_retry(
        "GET",
        f"{API}/files/{file_id}",
        headers=_headers(token),
        params={"fields": "id,trashed", "supportsAllDrives": "true"},
        timeout=30,
    )
    if r.status_code == 404:
        if not (drive_visible or (lambda: _shared_drive_visible(token)))():
            log.error("drive %s: 404, and the Shared Drive itself is not visible; "
                      "the service account has lost access", file_id)
            raise DriveAccessLost(
                f"Drive answered 404 for {file_id} and the Shared Drive "
                f"{settings.SHARED_DRIVE_ID} is not visible to the service account."
            )
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
    timeout, an expired token, a Shared Drive out of sight) yields UNKNOWN,
    not MISSING: the caller purges Firestore session metadata on MISSING, so
    mapping a transient outage to "missing" turned a Drive blip into permanent
    data loss. The Shared Drive's visibility is asked once for the whole call,
    on the first 404.
    """
    unique = [fid for fid in dict.fromkeys(file_ids) if fid]
    visible = _visibility_once(token)

    def probe(fid: str) -> str:
        try:
            return ALIVE if file_exists(token, fid, drive_visible=visible) else MISSING
        except Exception:  # noqa: BLE001 - unreachable != absent
            log.warning("Drive existence probe failed for %s — treating as unknown", fid)
            return UNKNOWN

    return dict(zip(unique, fan_out(probe, unique, width=max_workers)))


def delete_files(token: str, file_ids: list[str], *, max_workers: int = 8) -> int:
    """`delete_file` for several ids, with a bounded fan-out like `probe_files`.

    For the account-erase fallback, which used to delete one session folder
    per round trip. Any failure propagates, as one `delete_file` would, so the
    caller still stops before touching Firestore. Returns how many it deleted.
    """
    unique = [fid for fid in dict.fromkeys(file_ids) if fid]
    fan_out(lambda fid: delete_file(token, fid), unique, width=max_workers)
    return len(unique)


def ping(timeout_s: float = 3.0) -> None:
    """Cheap Drive reachability probe for readiness (Shared Drive about).

    `timeout_s` is `/readyz`'s per-probe deadline (`health.PROBE_TIMEOUT_S`).
    """
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
        # it had deleted everything. Never believe a 404 — check. The check
        # raises `DriveAccessLost` when the whole Shared Drive is out of sight.
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
    if r.status_code >= 400:
        # A streamed response holds its pooled connection until it is read or
        # closed, and nothing reads an error body here: close it, then raise.
        r.close()
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
