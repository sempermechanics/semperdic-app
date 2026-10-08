"""`app.drive` against a fake Drive at its HTTP boundary.

`test_drive_helpers.py` patches drive's own functions; this module replaces
only the pooled `requests.Session` (`drive._http`) with `FakeDrive`, a small
in-memory Drive that answers the v3 calls drive.py makes: folder search by
`q=`, folder create, metadata/media GETs, deletes, resumable-session starts and
media PATCHes. So every assertion here is about what drive.py does with what
Drive answers — the folder tree it leaves behind, what it retries, what it
raises — and not about which helper it happened to call.
"""
import hashlib
import json as jsonlib
import re
import threading

import pytest
import requests
from requests.structures import CaseInsensitiveDict

from app import backoff, drive, errors
from app.config import settings
from app.observability import DependencyError

TOKEN = "good-token"
FILES = f"{drive.API}/files"
UPLOAD_MEDIA = "https://www.googleapis.com/upload/drive/v3/files/"


def _response(status: int, body=None, *, headers: dict | None = None,
              content: bytes | None = None) -> requests.Response:
    """A real `requests.Response`, so raise_for_status / iter_content / close
    behave exactly as drive.py sees them in production."""
    r = requests.Response()
    r.status_code = status
    r.headers = CaseInsensitiveDict(headers or {})
    if content is None:
        content = b"" if body is None else jsonlib.dumps(body).encode()
    r._content = content
    r._content_consumed = True
    r.closed = False
    real_close = r.close

    def close():
        r.closed = True
        real_close()

    r.close = close
    return r


_Q_NAME = re.compile(r"name='((?:[^'\\]|\\.)*)'")
_Q_PARENT = re.compile(r"'([^']*)' in parents")


def _unescape(value: str) -> str:
    return re.sub(r"\\(.)", r"\1", value)


class FakeDrive:
    """In-memory Shared Drive. Folders and files are `{id: {...}}`.

    `fail(method, url_part, *responses)` queues canned responses for the next
    matching calls (FIFO), ahead of the emulated behaviour — the way to inject
    a 503, a 429 with Retry-After, or a 404 that is not the truth.
    """

    def __init__(self, root: str):
        self.root = root
        self.items: dict[str, dict] = {root: {"name": "root", "parents": [], "folder": True}}
        self.calls: list[tuple[str, str, dict]] = []
        self._faults: list[tuple[str, str, requests.Response]] = []
        self._next = 0
        self._lock = threading.Lock()

    # ---- seeding / inspection
    def add(self, name: str, parent: str, *, folder=True, trashed=False,
            content: bytes = b"") -> str:
        with self._lock:
            self._next += 1
            fid = f"id{self._next}"
            self.items[fid] = {"name": name, "parents": [parent], "folder": folder,
                               "trashed": trashed, "content": content}
            return fid

    def children(self, parent: str) -> dict[str, str]:
        return {i["name"]: fid for fid, i in self.items.items() if parent in i["parents"]}

    def path_of(self, fid: str) -> str:
        names = []
        while fid != self.root:
            item = self.items[fid]
            names.append(item["name"])
            fid = item["parents"][0]
        return "/".join(reversed(names))

    def fail(self, method: str, url_part: str, *responses: requests.Response) -> None:
        for r in responses:
            self._faults.append((method, url_part, r))

    def count(self, method: str, url_part: str = "") -> int:
        return sum(1 for m, u, _ in self.calls if m == method and url_part in u)

    # ---- the requests.Session surface drive.py uses
    def get(self, url, **kw):
        return self.request("GET", url, **kw)

    def post(self, url, **kw):
        return self.request("POST", url, **kw)

    def patch(self, url, **kw):
        return self.request("PATCH", url, **kw)

    def delete(self, url, **kw):
        return self.request("DELETE", url, **kw)

    def request(self, method, url, headers=None, params=None, json=None, data=None,
                timeout=None, stream=False):
        with self._lock:
            self.calls.append((method, url, {"headers": headers or {}, "params": params or {},
                                             "json": json, "data": data, "timeout": timeout,
                                             "stream": stream}))
            for i, (m, part, resp) in enumerate(self._faults):
                if m == method and part in url:
                    del self._faults[i]
                    return resp
            if (headers or {}).get("Authorization") != f"Bearer {TOKEN}":
                return _response(401, {"error": {"code": 401, "message": "Invalid Credentials"}})
            return self._route(method, url, params or {}, json, data, headers or {})

    def _route(self, method, url, params, body, data, headers):
        if url.startswith(UPLOAD_MEDIA) and method == "PATCH":
            fid = url[len(UPLOAD_MEDIA):]
            item = self.items.get(fid)
            if item is None:
                return _response(404, {"error": {"code": 404}})
            item["content"] = data
            return _response(200, {"size": str(len(data)),
                                   "md5Checksum": hashlib.md5(data).hexdigest()})
        if url == drive.UPLOAD and method == "POST":
            parent = body["parents"][0]
            if parent not in self.items:
                return _response(404, {"error": {"code": 404}})
            return _response(200, headers={"Location": f"https://upload.example/session/{body['name']}"})
        if url.startswith(f"{drive.API}/drives/"):
            return _response(200, {"id": url.rsplit("/", 1)[-1]})
        if url == FILES and method == "GET":
            name = _unescape(_Q_NAME.search(params["q"]).group(1))
            parent = _Q_PARENT.search(params["q"]).group(1)
            hits = [{"id": fid} for fid, i in self.items.items()
                    if i["name"] == name and parent in i["parents"]
                    and i.get("folder") and not i.get("trashed")]
            return _response(200, {"files": hits})
        if url == FILES and method == "POST":
            parent = body["parents"][0]
            if parent not in self.items:
                return _response(404, {"error": {"code": 404, "message": "parent not found"}})
            self._next += 1
            fid = f"id{self._next}"
            self.items[fid] = {"name": body["name"], "parents": [parent], "folder": True,
                               "trashed": False, "content": b""}
            return _response(200, {"id": fid})
        if url.startswith(FILES + "/"):
            fid = url[len(FILES) + 1:]
            item = self.items.get(fid)
            if method == "DELETE":
                if item is None:
                    return _response(404, {"error": {"code": 404}})
                self._delete_tree(fid)
                return _response(204)
            if item is None:
                return _response(404, {"error": {"code": 404}})
            if params.get("alt") == "media":
                content = item["content"]
                rng = headers.get("Range")
                if rng:
                    start = int(rng.split("=")[1].split("-")[0])
                    return _response(206, content=content[start:], headers={
                        "Content-Range": f"bytes {start}-{len(content) - 1}/{len(content)}"})
                return _response(200, content=content)
            meta = {"id": fid, "trashed": item.get("trashed", False),
                    "parents": item["parents"]}
            if not item.get("folder"):
                meta["size"] = str(len(item["content"]))
                meta["md5Checksum"] = hashlib.md5(item["content"]).hexdigest()
            return _response(200, meta)
        raise AssertionError(f"FakeDrive has no route for {method} {url}")

    def _delete_tree(self, fid):
        for child in [c for c, i in self.items.items() if fid in i["parents"]]:
            self._delete_tree(child)
        self.items.pop(fid, None)


@pytest.fixture
def fake(monkeypatch):
    fd = FakeDrive(settings.ROOT_FOLDER_ID)
    monkeypatch.setattr(drive, "_http", fd)
    return fd


@pytest.fixture
def sleeps(monkeypatch):
    """Every backoff sleep, in seconds, instead of sleeping."""
    out: list[float] = []
    monkeypatch.setattr(drive.time, "sleep", out.append)
    return out


# ------------------------------------------------------------------ http()

def test_http_is_one_pooled_session_with_no_adapter_retries(monkeypatch):
    monkeypatch.setattr(drive, "_http", None)
    first = drive.http()
    assert drive.http() is first
    adapter = first.get_adapter("https://www.googleapis.com/")
    assert adapter._pool_maxsize == drive._HTTP_POOL
    assert adapter.max_retries.total == 0  # retries are drive.py's, not urllib3's


def test_access_token_is_the_service_account_token(monkeypatch):
    monkeypatch.setattr(drive, "drive_access_token", lambda: "sa-token")
    assert drive.access_token() == "sa-token"


# ------------------------------------------------------------ retry policy

def test_a_transient_5xx_is_retried_with_exponential_backoff(fake, sleeps):
    folder = fake.add("f", fake.root)
    fake.fail("GET", f"/files/{folder}", _response(503), _response(500))
    assert drive.file_exists(TOKEN, folder) is True
    assert fake.count("GET", f"/files/{folder}") == 3
    assert sleeps == [1.0, 2.0]


def test_retry_after_is_honoured_on_a_429(fake, sleeps):
    folder = fake.add("f", fake.root)
    fake.fail("GET", f"/files/{folder}", _response(429, headers={"Retry-After": "3"}))
    assert drive.file_exists(TOKEN, folder) is True
    assert sleeps == [3.0]


def test_retries_stop_after_max_attempts_and_the_last_status_is_raised(fake, sleeps):
    folder = fake.add("f", fake.root)
    fake.fail("GET", f"/files/{folder}", *[_response(502) for _ in range(drive._MAX_ATTEMPTS)])
    with pytest.raises(requests.HTTPError) as err:
        drive.file_exists(TOKEN, folder)
    assert err.value.response.status_code == 502
    assert fake.count("GET", f"/files/{folder}") == drive._MAX_ATTEMPTS
    assert len(sleeps) == drive._MAX_ATTEMPTS - 1


def test_retries_stop_once_the_sleep_budget_would_be_overrun(fake, sleeps):
    """Two max-length Retry-Afters exceed the 20 s budget: the second 503 is
    answered as is rather than slept on."""
    folder = fake.add("f", fake.root)
    long_wait = {"Retry-After": str(int(backoff.MAX_DELAY_S))}
    fake.fail("GET", f"/files/{folder}", *[_response(503, headers=long_wait) for _ in range(5)])
    with pytest.raises(requests.HTTPError):
        drive.file_exists(TOKEN, folder)
    assert sleeps == [backoff.MAX_DELAY_S]
    assert sum(sleeps) <= drive._RETRY_BUDGET_S
    assert fake.count("GET", f"/files/{folder}") == 2


@pytest.mark.parametrize("status", [400, 401, 403])
def test_client_errors_are_not_retried(fake, sleeps, status):
    folder = fake.add("f", fake.root)
    fake.fail("GET", f"/files/{folder}", _response(status))
    with pytest.raises(requests.HTTPError) as err:
        drive.get_file_meta(TOKEN, folder)
    assert err.value.response.status_code == status
    assert fake.count("GET", f"/files/{folder}") == 1
    assert sleeps == []


def test_a_bad_token_surfaces_as_a_401_error_not_a_missing_file(fake, sleeps):
    folder = fake.add("f", fake.root)
    with pytest.raises(requests.HTTPError) as err:
        drive.file_exists("expired-token", folder)
    assert err.value.response.status_code == 401
    # The call went out with the token it was given.
    assert fake.calls[-1][2]["headers"]["Authorization"] == "Bearer expired-token"


# -------------------------------------------------------------- file_exists

def test_file_exists_maps_404_to_false_and_trashed_to_false(fake):
    live = fake.add("live", fake.root)
    binned = fake.add("binned", fake.root, trashed=True)
    assert drive.file_exists(TOKEN, live) is True
    assert drive.file_exists(TOKEN, binned) is False
    assert drive.file_exists(TOKEN, "never-was") is False


# ------------------------------------------------------------- probe_files

def test_probe_files_is_tristate_and_an_outage_is_never_missing(fake, sleeps):
    live = fake.add("live", fake.root)
    flaky = fake.add("flaky", fake.root)
    fake.fail("GET", f"/files/{flaky}", *[_response(503) for _ in range(drive._MAX_ATTEMPTS)])
    out = drive.probe_files(TOKEN, [live, "gone", flaky, live, ""])
    assert out == {live: drive.ALIVE, "gone": drive.MISSING, flaky: drive.UNKNOWN}


def test_probe_files_with_a_dead_token_is_unknown_for_everything(fake):
    live = fake.add("live", fake.root)
    assert drive.probe_files("bad", [live, "gone"]) == {live: drive.UNKNOWN, "gone": drive.UNKNOWN}


# --------------------------------------------------- folder find / create

def test_ensure_session_folders_builds_the_tree_on_first_use(fake):
    out = drive.ensure_session_folders(TOKEN, "u1", "s1")
    sid_dir = out["sessionFolderId"]
    assert fake.path_of(sid_dir) == "Research Storage/user/u1/session/s1"
    assert fake.path_of(out["userFolderId"]) == "Research Storage/user/u1"
    assert fake.path_of(out["sessionsFolderId"]) == "Research Storage/user/u1/session"
    # Bundle, extras and metadata live at the session root; the rest get a folder.
    assert out["bundle"] == out["extras"] == out["metadata"] == sid_dir
    assert set(fake.children(sid_dir)) == {"raw", "processed", "reports", "csv", "dat"}
    for role in ("raw", "processed", "reports", "csv", "dat"):
        assert fake.path_of(out[role]).endswith(f"session/s1/{role}")


def test_ensure_session_folders_reuses_what_a_retry_already_made(fake):
    first = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=["raw"])
    before = len(fake.items)
    again = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=["raw"])
    assert again == first
    assert len(fake.items) == before  # nothing duplicated


def test_ensure_session_folders_only_makes_the_roles_asked_for(fake):
    out = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=("bundle", "metadata", "dat"))
    assert fake.children(out["sessionFolderId"]) == {"dat": out["dat"]}
    assert "raw" not in out


def test_a_second_user_gets_their_own_subtree_under_the_shared_scaffolding(fake):
    a = drive.ensure_session_folders(TOKEN, "alice", "s1", roles=())
    b = drive.ensure_session_folders(TOKEN, "bob", "s1", roles=())
    assert a["userFolderId"] != b["userFolderId"]
    assert a["sessionFolderId"] != b["sessionFolderId"]
    research = fake.children(fake.root)["Research Storage"]
    assert len(fake.children(fake.root)) == 1
    assert set(fake.children(fake.children(research)["user"])) == {"alice", "bob"}


def test_a_live_cache_skips_the_name_walk(fake):
    first = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=())
    cached = {"userFolderId": first["userFolderId"], "sessionsFolderId": first["sessionsFolderId"]}
    fake.calls.clear()

    out = drive.ensure_session_folders(TOKEN, "u1", "s2", roles=(), cached=cached)

    assert fake.path_of(out["sessionFolderId"]) == "Research Storage/user/u1/session/s2"
    assert out["sessionsFolderId"] == cached["sessionsFolderId"]
    # One liveness GET and one create; no folder searches.
    assert fake.count("GET", FILES + "/") == 1
    assert fake.count("POST", FILES) == 1
    assert not [c for c in fake.calls if c[1] == FILES and c[0] == "GET"]


def test_a_cached_session_folder_from_an_earlier_attempt_is_reused(fake):
    first = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=())
    cached = {"userFolderId": first["userFolderId"], "sessionsFolderId": first["sessionsFolderId"]}
    before = len(fake.items)
    fake.calls.clear()
    out = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=(), cached=cached,
                                       session_folder_id=first["sessionFolderId"])
    assert out["sessionFolderId"] == first["sessionFolderId"]
    assert len(fake.items) == before
    assert fake.count("POST") == 0  # no second {sid}/ folder
    assert fake.count("GET") == 1  # only the liveness check


def test_a_deleted_cached_folder_is_noticed_and_the_tree_rebuilt(fake):
    first = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=())
    cached = {"userFolderId": first["userFolderId"], "sessionsFolderId": first["sessionsFolderId"]}
    fake._delete_tree(first["userFolderId"])  # someone deleted it straight in Drive

    out = drive.ensure_session_folders(TOKEN, "u1", "s2", roles=(), cached=cached)

    assert out["sessionsFolderId"] != cached["sessionsFolderId"]
    assert fake.path_of(out["sessionFolderId"]) == "Research Storage/user/u1/session/s2"


def test_a_trashed_cached_folder_does_not_keep_the_new_session(fake):
    """A trashed parent still accepts children: the {sid}/ made under it is
    deleted again and the session lands in a fresh, live tree."""
    first = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=())
    stale = first["sessionsFolderId"]
    cached = {"userFolderId": first["userFolderId"], "sessionsFolderId": stale}
    fake.items[first["userFolderId"]]["trashed"] = True
    fake.items[stale]["trashed"] = True

    out = drive.ensure_session_folders(TOKEN, "u1", "s2", roles=(), cached=cached)

    assert "s2" not in fake.children(stale)  # the orphan was removed
    assert out["sessionsFolderId"] != stale
    assert fake.items[out["sessionFolderId"]]["parents"] == [out["sessionsFolderId"]]


def test_an_orphan_that_cannot_be_deleted_does_not_fail_the_upload(fake):
    first = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=())
    stale = first["sessionsFolderId"]
    cached = {"userFolderId": first["userFolderId"], "sessionsFolderId": stale}
    fake.items[first["userFolderId"]]["trashed"] = True
    fake.items[stale]["trashed"] = True
    fake.fail("DELETE", "/files/", _response(500))

    out = drive.ensure_session_folders(TOKEN, "u1", "s2", roles=(), cached=cached)

    assert out["sessionsFolderId"] != stale
    assert "s2" in fake.children(stale)  # left behind, best effort, logged


def test_a_stale_cache_with_a_stored_session_folder_walks_and_finds_by_name(fake):
    first = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=())
    cached = {"userFolderId": "gone-u", "sessionsFolderId": "gone-s"}
    out = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=(), cached=cached,
                                       session_folder_id="gone-sid")
    assert out["sessionFolderId"] == first["sessionFolderId"]
    assert out["sessionsFolderId"] == first["sessionsFolderId"]


def test_a_create_failure_under_a_live_cache_is_raised(fake):
    first = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=())
    cached = {"userFolderId": first["userFolderId"], "sessionsFolderId": first["sessionsFolderId"]}
    fake.fail("POST", FILES, _response(403, {"error": {"code": 403}}))
    with pytest.raises(requests.HTTPError) as err:
        drive.ensure_session_folders(TOKEN, "u1", "s2", roles=(), cached=cached)
    assert err.value.response.status_code == 403


def test_a_failed_folder_search_is_raised_not_taken_for_absent(fake, sleeps):
    fake.fail("GET", FILES, _response(403))
    with pytest.raises(requests.HTTPError):
        drive.ensure_session_folders(TOKEN, "u1", "s1", roles=())
    assert fake.count("POST", FILES) == 0  # never created a duplicate root


def test_folder_names_are_escaped_in_the_drive_query(fake):
    """A uid with a quote or a trailing backslash is a literal name, not query syntax."""
    tricky = "o'brien\\"
    research = fake.add("Research Storage", fake.root)
    users = fake.add("user", research)
    other = fake.add("someone-else", users)
    assert drive.find_user_folder(TOKEN, tricky) is None
    mine = fake.add(tricky, users)
    assert drive.find_user_folder(TOKEN, tricky) == mine != other
    q = [c[2]["params"]["q"] for c in fake.calls if c[1] == FILES][-1]
    assert "name='o\\'brien\\\\'" in q
    assert q.endswith("trashed=false")


# --------------------------------------------------------- find_user_folder

def test_find_user_folder_finds_and_never_creates(fake):
    tree = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=())
    before = dict(fake.items)
    assert drive.find_user_folder(TOKEN, "u1") == tree["userFolderId"]
    assert drive.find_user_folder(TOKEN, "u2") is None
    assert fake.items == before


@pytest.mark.parametrize("levels", [[], ["Research Storage"], ["Research Storage", "user"]])
def test_find_user_folder_is_none_when_a_level_is_missing(fake, levels):
    parent = fake.root
    for name in levels:
        parent = fake.add(name, parent)
    before = len(fake.items)
    assert drive.find_user_folder(TOKEN, "u1") is None
    assert len(fake.items) == before


def test_find_user_folder_ignores_a_trashed_folder(fake):
    research = fake.add("Research Storage", fake.root)
    users = fake.add("user", research)
    fake.add("u1", users, trashed=True)
    assert drive.find_user_folder(TOKEN, "u1") is None


# ---------------------------------------------------------------- deletes

def test_delete_file_removes_the_whole_subtree(fake):
    tree = drive.ensure_session_folders(TOKEN, "u1", "s1")
    drive.delete_file(TOKEN, tree["userFolderId"])
    assert tree["sessionFolderId"] not in fake.items
    assert tree["raw"] not in fake.items
    assert drive.find_user_folder(TOKEN, "u1") is None


def test_delete_of_an_already_gone_file_is_success(fake):
    drive.delete_file(TOKEN, "never-was")
    assert fake.count("DELETE") == 1
    assert fake.count("GET", "/files/never-was") == 1  # the 404 was checked, not believed


def test_a_404_on_delete_for_a_file_still_there_is_a_permission_error(fake):
    """Drive hides 'you may not' behind 404; erasure must not report success."""
    folder = fake.add("u1", fake.root)
    fake.fail("DELETE", f"/files/{folder}", _response(404))
    with pytest.raises(PermissionError, match="organizer"):
        drive.delete_file(TOKEN, folder)
    assert folder in fake.items


@pytest.mark.parametrize("status", [403, 500, 503])
def test_other_delete_failures_raise_and_are_not_retried(fake, sleeps, status):
    folder = fake.add("u1", fake.root)
    fake.fail("DELETE", f"/files/{folder}", _response(status, content=b"nope"))
    with pytest.raises(requests.HTTPError) as err:
        drive.delete_file(TOKEN, folder)
    assert err.value.response.status_code == status
    assert fake.count("DELETE") == 1
    assert folder in fake.items


def test_delete_files_erases_each_session_folder(fake):
    a = drive.ensure_session_folders(TOKEN, "u1", "s1", roles=())["sessionFolderId"]
    b = drive.ensure_session_folders(TOKEN, "u1", "s2", roles=())["sessionFolderId"]
    assert drive.delete_files(TOKEN, [a, b, a, None]) == 2
    assert a not in fake.items and b not in fake.items
    assert fake.count("DELETE") == 2


# ---------------------------------------------------------------- downloads

def test_open_download_streams_the_bytes_and_closes_after(fake):
    fid = fake.add("Session.zip", fake.root, folder=False, content=b"x" * 1000)
    dl = drive.open_download(TOKEN, fid)
    assert dl.status_code == 200
    assert b"".join(dl.iter_chunks(chunk_size=64)) == b"x" * 1000
    assert dl._response.closed
    method, _url, kw = fake.calls[-1]
    assert kw["stream"] is True
    assert kw["params"] == {"alt": "media", "supportsAllDrives": "true"}


def test_open_download_forwards_a_range_and_returns_the_partial(fake):
    fid = fake.add("Session.zip", fake.root, folder=False, content=b"0123456789")
    dl = drive.open_download(TOKEN, fid, byte_range="bytes=4-")
    assert dl.status_code == 206
    assert dl.headers["Content-Range"] == "bytes 4-9/10"
    assert b"".join(dl.iter_chunks()) == b"456789"
    assert fake.calls[-1][2]["headers"]["Range"] == "bytes=4-"


def test_open_download_closes_each_retried_stream(fake, sleeps):
    fid = fake.add("f", fake.root, folder=False, content=b"ok")
    busy = _response(503)
    fake.fail("GET", f"/files/{fid}", busy)
    dl = drive.open_download(TOKEN, fid)
    assert busy.closed  # the pooled connection behind the 503 was handed back
    assert b"".join(dl.iter_chunks()) == b"ok"


def test_open_download_of_a_missing_file_raises_404(fake):
    with pytest.raises(requests.HTTPError) as err:
        drive.open_download(TOKEN, "gone")
    assert err.value.response.status_code == 404


# ----------------------------------------------------------- get_file_meta

def test_get_file_meta_reports_what_drive_recorded(fake):
    folder = fake.add("raw", fake.root)
    fid = fake.add("a.png", folder, folder=False, content=b"pixels")
    assert drive.get_file_meta(TOKEN, fid) == {
        "size": 6, "md5": hashlib.md5(b"pixels").hexdigest(), "parents": [folder],
    }


def test_get_file_meta_without_size_or_parents(fake):
    """A folder (or a Google-native file) has no size or md5."""
    folder = fake.add("raw", fake.root)
    fake.items[folder]["parents"] = []
    assert drive.get_file_meta(TOKEN, folder) == {"size": None, "md5": None, "parents": []}


def test_get_file_meta_of_a_missing_file_raises(fake):
    with pytest.raises(requests.HTTPError) as err:
        drive.get_file_meta(TOKEN, "gone")
    assert err.value.response.status_code == 404


# ------------------------------------------------------- resumable uploads

def test_init_resumable_returns_the_session_uri_and_declares_the_size(fake):
    folder = fake.add("raw", fake.root)
    uri = drive.init_resumable(TOKEN, folder, "frame_0001.png", 12345)
    assert uri == "https://upload.example/session/frame_0001.png"
    method, url, kw = fake.calls[-1]
    assert (method, url) == ("POST", drive.UPLOAD)
    assert kw["headers"]["X-Upload-Content-Length"] == "12345"
    assert kw["headers"]["Authorization"] == f"Bearer {TOKEN}"
    assert kw["json"] == {"name": "frame_0001.png", "parents": [folder],
                          "driveId": settings.SHARED_DRIVE_ID}


@pytest.mark.parametrize("status", [401, 404, 503])
def test_init_resumable_failures_raise_without_a_retry(fake, sleeps, status):
    folder = fake.add("raw", fake.root)
    fake.fail("POST", "uploadType=resumable", _response(status))
    with pytest.raises(requests.HTTPError) as err:
        drive.init_resumable(TOKEN, folder, "a.png", 1)
    assert err.value.response.status_code == status
    assert fake.count("POST") == 1


def test_init_resumable_into_a_missing_folder_raises(fake):
    with pytest.raises(requests.HTTPError):
        drive.init_resumable(TOKEN, "gone", "a.png", 1)


# ---------------------------------------------------------- replace_content

def test_replace_content_overwrites_in_place_and_reports_size_and_md5(fake):
    fid = fake.add("metadata.json", fake.root, folder=False, content=b"{}")
    out = drive.replace_content(TOKEN, fid, b'{"k": 1}')
    assert out == {"size": 8, "md5": hashlib.md5(b'{"k": 1}').hexdigest()}
    assert fake.items[fid]["content"] == b'{"k": 1}'
    _method, _url, kw = fake.calls[-1]
    assert kw["headers"]["Content-Type"] == "application/json"
    assert kw["params"]["uploadType"] == "media"


def test_replace_content_without_a_size_in_the_answer(fake):
    fid = fake.add("metadata.json", fake.root, folder=False)
    fake.fail("PATCH", fid, _response(200, {}))
    assert drive.replace_content(TOKEN, fid, b"x", mime="text/plain") == {"size": None, "md5": None}


def test_replace_content_of_a_missing_file_raises_once(fake, sleeps):
    with pytest.raises(requests.HTTPError) as err:
        drive.replace_content(TOKEN, "gone", b"{}")
    assert err.value.response.status_code == 404
    assert fake.count("PATCH") == 1


# -------------------------------------------------------------------- ping

def test_ping_passes_when_the_shared_drive_answers(fake, monkeypatch):
    monkeypatch.setattr(drive, "access_token", lambda: TOKEN)
    drive.ping()
    assert fake.calls[-1][1] == f"{drive.API}/drives/{settings.SHARED_DRIVE_ID}"


def test_ping_maps_an_error_status_to_unhealthy(fake, monkeypatch):
    monkeypatch.setattr(drive, "access_token", lambda: "revoked")
    with pytest.raises(DependencyError) as err:
        drive.ping()
    assert err.value.code == errors.DRIVE_UNHEALTHY
    assert err.value.dependency == "drive"


def test_ping_maps_a_token_or_network_failure_to_unreachable(fake, monkeypatch):
    def no_token():
        raise RuntimeError("metadata server down")

    monkeypatch.setattr(drive, "access_token", no_token)
    with pytest.raises(DependencyError) as err:
        drive.ping()
    assert err.value.code == errors.DRIVE_UNREACHABLE

    monkeypatch.setattr(drive, "access_token", lambda: TOKEN)

    def refused(*_a, **_k):
        raise requests.ConnectionError("refused")

    monkeypatch.setattr(fake, "get", refused)
    with pytest.raises(DependencyError) as err:
        drive.ping()
    assert err.value.code == errors.DRIVE_UNREACHABLE
