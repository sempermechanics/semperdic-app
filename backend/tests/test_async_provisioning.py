"""Session provisioning moved off the request path.

POST /v1/sessions used to open one Drive resumable session per file inline. At
the 600-file ceiling that is ~1200 sequential round-trips inside a 60s Cloud Run
budget, so a large analysis could not be uploaded at all. The request now
reserves the session and a Cloud Task fills in the upload targets; the client
polls the /uploads endpoint it already uses for resume.
"""
import pytest

from app import audit, drive, rate_limit, session_provision, tasks
import repo_view as repo
from app.config import settings

DEV_UID = "dev-user"
_SHA = "a" * 64


def _file(name: str) -> dict:
    return {"name": name, "role": "bundle", "bytes": 10, "sha256": _SHA}


@pytest.fixture
def store(store, monkeypatch):
    store._data["users"] = {DEV_UID: {"email": "dev@test", "access_status": "APPROVED"}}
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(
        drive, "ensure_session_folders",
        lambda *a, **k: {"sessionFolderId": "sf", "userFolderId": "uf", "bundle": "sf"},
    )
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    monkeypatch.setattr(rate_limit.session_bucket, "allow", lambda uid: True)
    return store


@pytest.fixture
def queued(monkeypatch):
    """Pretend a Cloud Tasks queue exists; record what would be enqueued."""
    enqueued = []
    monkeypatch.setattr(
        tasks, "enqueue_provision", lambda sid: enqueued.append(sid) or True,
    )
    # Queue every manifest, however small, so these tests exercise the worker.
    monkeypatch.setattr(settings, "INLINE_PROVISION_MAX_FILES", 0)
    return enqueued


# --- the request no longer does the Drive work ------------------------------

async def test_create_returns_immediately_without_touching_drive(
    store, queued, client, monkeypatch,
):
    """The whole point: no resumable session is opened during the request."""
    opened = []
    monkeypatch.setattr(
        drive, "init_resumable",
        lambda *a, **k: opened.append(a) or "https://drive/resumable",
    )

    r = await client.post(
        "/v1/sessions",
        json={"specimen": "s", "files": [_file("a"), _file("b"), _file("c")]},
    )

    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "PROVISIONING"
    assert body["uploads"] == [], "upload targets handed out before they exist"
    assert opened == [], "Drive was called on the request path"
    assert queued == [body["sessionId"]]
    # The manifest is durable even though no upload target exists yet.
    assert len(store._data["files"]) == 3
    assert all(f["uploadUrl"] is None for f in store._data["files"].values())


async def test_worker_fills_in_every_upload_target(store, queued, client):
    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file("a"), _file("b")]},
    )).json()["sessionId"]

    result = session_provision.provision_session(sid)

    assert result["provisioned"] == 2
    assert store._data["sessions"][sid]["status"] == "UPLOADING"
    assert all(f["uploadUrl"] for f in store._data["files"].values())
    assert store._data["sessions"][sid]["driveFolderId"] == "sf"


async def test_a_task_that_finishes_before_the_answer_keeps_its_uploading(
    store, client, monkeypatch,
):
    """Cloud Tasks may run the task before the request answers. The request
    then wrote PROVISIONING over the task's UPLOADING, and the app polled a
    session nothing would ever move again (review L3-3)."""
    monkeypatch.setattr(settings, "INLINE_PROVISION_MAX_FILES", 0)
    monkeypatch.setattr(rate_limit.listing_bucket, "allow", lambda uid: True)
    monkeypatch.setattr(
        tasks, "enqueue_provision",
        lambda sid: session_provision.provision_session(sid) is not None,
    )

    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file("a"), _file("b")]},
    )).json()["sessionId"]

    assert store._data["sessions"][sid]["status"] == "UPLOADING"
    polled = (await client.get(f"/v1/sessions/{sid}/uploads")).json()
    assert polled["status"] == "UPLOADING" and len(polled["uploads"]) == 2


async def test_polling_uploads_reports_provisioning_then_the_targets(
    store, queued, client, monkeypatch,
):
    """The client's actual flow: create, poll, get told to wait, poll again."""
    monkeypatch.setattr(rate_limit.listing_bucket, "allow", lambda uid: True)
    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file("a")]},
    )).json()["sessionId"]

    waiting = (await client.get(f"/v1/sessions/{sid}/uploads")).json()
    assert waiting["status"] == "PROVISIONING"
    assert waiting["uploads"] == []

    session_provision.provision_session(sid)

    ready = (await client.get(f"/v1/sessions/{sid}/uploads")).json()
    assert ready["status"] == "UPLOADING"
    assert len(ready["uploads"]) == 1
    assert ready["uploads"][0]["uploadUrl"] == "https://drive/resumable"


# --- idempotence ------------------------------------------------------------

async def test_reprovisioning_does_not_remint_existing_targets(store, queued, client):
    """Cloud Tasks retries on any non-2xx, so the worker must be safe to re-run:
    a second URI for a file whose upload is already in flight would strand it."""
    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file("a"), _file("b")]},
    )).json()["sessionId"]
    session_provision.provision_session(sid)
    urls_before = {k: v["uploadUrl"] for k, v in store._data["files"].items()}

    second = session_provision.provision_session(sid)

    assert second["provisioned"] == 0, "already-provisioned files were re-minted"
    assert {k: v["uploadUrl"] for k, v in store._data["files"].items()} == urls_before


async def test_partial_provisioning_resumes_where_it_stopped(store, queued, client, monkeypatch):
    """A task that dies halfway must finish the rest, not start over."""
    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file("a"), _file("b"), _file("c")]},
    )).json()["sessionId"]

    calls = {"n": 0}

    def flaky(*a, **k):
        calls["n"] += 1
        if calls["n"] == 2:
            raise RuntimeError("drive blew up")
        return "https://drive/resumable"

    monkeypatch.setattr(drive, "init_resumable", flaky)
    with pytest.raises(RuntimeError):
        session_provision.provision_session(sid)
    assert store._data["sessions"][sid]["status"] == "PROVISION_FAILED"

    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    session_provision.provision_session(sid)

    assert store._data["sessions"][sid]["status"] == "UPLOADING"
    assert all(f["uploadUrl"] for f in store._data["files"].values())


async def test_retry_of_create_joins_the_provisioning_session(store, queued, client):
    """A create retried while provisioning is still running must not mint a
    second session — that would duplicate the Drive folder and burn quota."""
    body = {"specimen": "s", "localSessionId": "local-abc", "files": [_file("a")]}
    first = (await client.post("/v1/sessions", json=body)).json()
    second = (await client.post("/v1/sessions", json=body)).json()

    assert first["sessionId"] == second["sessionId"]
    assert len(store._data["sessions"]) == 1
    # The retry answers with the same shape as /uploads, cursor included.
    assert "nextPageToken" in second


# --- failure ----------------------------------------------------------------

async def test_queued_failure_marks_the_session_not_deletes_it(
    store, queued, client, monkeypatch,
):
    """A retry is coming, so the session must survive — but a polling client
    has to be told to stop waiting."""
    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file("a")]},
    )).json()["sessionId"]
    monkeypatch.setattr(
        drive, "init_resumable",
        lambda *a, **k: (_ for _ in ()).throw(RuntimeError("drive down")),
    )

    with pytest.raises(RuntimeError):
        session_provision.provision_session(sid)

    session = store._data["sessions"][sid]
    assert session["status"] == "PROVISION_FAILED"
    assert session["provisionError"] == "drive_provision_failed"


async def test_inline_failure_purges_the_drive_folder_too(store, client, monkeypatch):
    """With no queue there is no retry, so the rollback must be complete.
    repo.delete_session alone is Firestore-only and left the Drive subtree —
    plus any resumable sessions opened inside it — orphaned."""
    monkeypatch.setattr(tasks, "enqueue_provision", lambda sid: False)
    deleted = []
    monkeypatch.setattr(drive, "delete_file", lambda token, fid: deleted.append(fid))
    calls = {"n": 0}

    def flaky(*a, **k):
        calls["n"] += 1
        if calls["n"] == 2:
            raise RuntimeError("drive blew up mid-stage")
        return "https://drive/resumable"

    monkeypatch.setattr(drive, "init_resumable", flaky)

    with pytest.raises(RuntimeError):
        await client.post(
            "/v1/sessions",
            json={"specimen": "s", "files": [_file("a"), _file("b"), _file("c")]},
        )

    assert store._data.get("sessions", {}) == {}, "session left against the quota"
    assert store._data.get("files", {}) == {}, "file docs left with no parent"
    assert deleted == ["sf"], "Drive folder was not cleaned up"


async def test_inline_path_still_returns_usable_targets(store, client, monkeypatch):
    """No queue configured must remain fully functional, just slower."""
    monkeypatch.setattr(tasks, "enqueue_provision", lambda sid: False)

    r = await client.post("/v1/sessions", json={"specimen": "s", "files": [_file("a")]})

    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "UPLOADING"
    assert len(body["uploads"]) == 1
    assert body["uploads"][0]["uploadUrl"] == "https://drive/resumable"
    assert body["nextPageToken"] is None


async def test_inline_create_hands_back_what_the_uploads_listing_would(
    store, client, monkeypatch,
):
    """The inline path builds its response from the targets it just opened
    rather than reading them back, so it must match the stored state exactly:
    same status, same targets, same order, same fields."""
    monkeypatch.setattr(drive, "init_resumable", lambda _t, _f, name, _s: f"https://drive/{name}")

    created = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file(n) for n in "cab"]},
    )).json()

    uploads, next_token = repo.list_pending_uploads(created["sessionId"])
    assert created["uploads"] == uploads
    assert created["nextPageToken"] == next_token
    assert created["status"] == store._data["sessions"][created["sessionId"]]["status"]
    assert [u["uploadUrl"] for u in uploads] == [f"https://drive/{n}" for n in "abc"]


async def test_a_pending_upload_carries_the_hash_its_file_was_declared_with(
    store, queued, client, monkeypatch,
):
    """TD-281: the app pairs a pending upload with its staged file by role, name,
    size and this hash, so other bytes of the same size are never resumed into
    a session that declared the old ones."""
    monkeypatch.setattr(rate_limit.listing_bucket, "allow", lambda uid: True)
    other = "b" * 64
    sid = (await client.post(
        "/v1/sessions",
        json={"specimen": "s", "files": [_file("a"), {**_file("b"), "sha256": other}]},
    )).json()["sessionId"]

    provisioned = session_provision.provision_session(sid)
    listed = (await client.get(f"/v1/sessions/{sid}/uploads")).json()["uploads"]

    assert {u["name"]: u["sha256"] for u in provisioned["uploads"]} == {"a": _SHA, "b": other}
    assert {u["name"]: u["sha256"] for u in listed} == {"a": _SHA, "b": other}


def test_one_listing_page_holds_a_whole_session():
    """create_session returns the first page of upload targets. That page is
    the whole manifest only while the file cap stays below the page size."""
    import repo_view as repo
    from app.config import settings

    assert settings.MAX_FILES_PER_SESSION <= repo._LIST_SOFT_LIMIT


async def test_small_manifest_is_provisioned_inline_even_with_a_queue(
    store, client, monkeypatch,
):
    """A bundle upload is three files: the task hop plus the client's first
    1 s poll cost more than opening three targets, so it stays inline."""
    enqueued = []
    monkeypatch.setattr(tasks, "enqueue_provision", lambda sid: enqueued.append(sid) or True)
    monkeypatch.setattr(settings, "INLINE_PROVISION_MAX_FILES", 3)

    small = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file(n) for n in "abc"]},
    )).json()
    big = (await client.post(
        "/v1/sessions", json={"specimen": "t", "files": [_file(n) for n in "abcd"]},
    )).json()

    assert small["status"] == "UPLOADING" and len(small["uploads"]) == 3
    assert big["status"] == "PROVISIONING" and big["uploads"] == []
    assert enqueued == [big["sessionId"]]


# --- the Drive folder walk is cached on the user ----------------------------

async def test_folder_ids_are_stored_then_reused(store, client, monkeypatch):
    """The four-level name walk is done once per user; later uploads pass the
    stored ids, and the user doc is not rewritten when they have not changed."""
    seen = []

    def ensure(token, uid, sid, roles=None, cached=None, session_folder_id=None):
        seen.append(dict(cached or {}))
        return {"sessionFolderId": f"sf-{sid}", "userFolderId": "uf",
                "sessionsFolderId": "ss", "bundle": f"sf-{sid}"}

    monkeypatch.setattr(drive, "ensure_session_folders", ensure)
    writes = []
    real = repo.remember_user_folder

    def remember(*a):
        writes.append(a)
        real(*a)

    repo.patch(monkeypatch, "remember_user_folder", remember)

    for name in ("s1", "s2"):
        await client.post("/v1/sessions", json={"specimen": name, "files": [_file("a")]})

    assert seen[0] == {"userFolderId": None, "sessionsFolderId": None}
    assert seen[1] == {"userFolderId": "uf", "sessionsFolderId": "ss"}
    assert writes == [(DEV_UID, "uf", "ss")], "unchanged pointers were rewritten"
    user = store._data["users"][DEV_UID]
    assert (user["driveFolderId"], user["driveSessionsFolderId"]) == ("uf", "ss")


def _folder_fakes(monkeypatch, *, alive, create_error=None):
    """Record every Drive folder call ensure_session_folders makes."""
    calls = []

    def find_or_create(token, name, parent):
        calls.append(("find_or_create", name, parent))
        return f"id-{name}"

    def create(token, name, parent):
        calls.append(("create", name, parent))
        if create_error:
            raise create_error
        return f"new-{name}"

    def exists(token, fid):
        calls.append(("exists", fid))
        return alive

    def delete(token, fid):
        calls.append(("delete", fid))

    monkeypatch.setattr(drive, "_find_or_create_folder", find_or_create)
    monkeypatch.setattr(drive, "_create_folder", create)
    monkeypatch.setattr(drive, "file_exists", exists)
    monkeypatch.setattr(drive, "delete_file", delete)
    return calls


_CACHED = {"userFolderId": "uf", "sessionsFolderId": "ss"}


def test_ensure_session_folders_skips_the_walk_when_the_cache_is_alive(monkeypatch):
    """A live cache costs the existence check plus one create, run together —
    no name search for a session id that was minted moments ago."""
    calls = _folder_fakes(monkeypatch, alive=True)

    out = drive.ensure_session_folders("tok", "u1", "sid1", roles={"bundle"}, cached=_CACHED)

    assert sorted(calls) == [("create", "sid1", "ss"), ("exists", "ss")]
    assert out["sessionFolderId"] == out["bundle"] == "new-sid1"
    assert (out["userFolderId"], out["sessionsFolderId"]) == ("uf", "ss")


def test_ensure_session_folders_rewalks_when_the_cached_folder_is_gone(monkeypatch):
    """Deleted or trashed straight in Drive: never upload into it. The {sid}/
    made alongside the check is removed and the walk rebuilds the tree."""
    calls = _folder_fakes(monkeypatch, alive=False)

    out = drive.ensure_session_folders("tok", "u1", "sid1", roles={"bundle"}, cached=_CACHED)

    assert ("delete", "new-sid1") in calls, "left a folder under the stale session/"
    walked = [c[1] for c in calls if c[0] == "find_or_create"]
    assert walked == ["Research Storage", "user", "u1", "session", "sid1"]
    assert out["sessionFolderId"] == "id-sid1"
    assert (out["userFolderId"], out["sessionsFolderId"]) == ("id-u1", "id-session")


def test_ensure_session_folders_rewalks_when_the_create_fails_under_a_gone_folder(monkeypatch):
    """Drive 404s a create whose parent is gone; that is the stale cache, not an error."""
    import requests

    calls = _folder_fakes(monkeypatch, alive=False, create_error=requests.HTTPError("404"))

    out = drive.ensure_session_folders("tok", "u1", "sid1", roles={"bundle"}, cached=_CACHED)

    assert not [c for c in calls if c[0] == "delete"]
    assert out["sessionFolderId"] == "id-sid1"


def test_ensure_session_folders_raises_a_create_failure_under_a_live_folder(monkeypatch):
    import requests

    _folder_fakes(monkeypatch, alive=True, create_error=requests.HTTPError("500"))

    with pytest.raises(requests.HTTPError):
        drive.ensure_session_folders("tok", "u1", "sid1", roles={"bundle"}, cached=_CACHED)


def test_ensure_session_folders_reuses_the_folder_from_an_earlier_attempt(monkeypatch):
    """A Cloud Tasks retry must not make a second {sid}/ folder."""
    calls = _folder_fakes(monkeypatch, alive=True)

    out = drive.ensure_session_folders(
        "tok", "u1", "sid1", roles={"bundle"}, cached=_CACHED, session_folder_id="old-sid1")

    assert calls == [("exists", "ss")]
    assert out["sessionFolderId"] == "old-sid1"


def test_ensure_session_folders_without_a_cache_searches_for_the_session_folder(monkeypatch):
    """After a walk nothing says whether an earlier attempt made {sid}/, so it
    is found-or-created, never blindly created."""
    calls = _folder_fakes(monkeypatch, alive=True)

    out = drive.ensure_session_folders("tok", "u1", "sid1", roles={"bundle"}, cached={})

    assert [c[1] for c in calls] == ["Research Storage", "user", "u1", "session", "sid1"]
    assert all(c[0] == "find_or_create" for c in calls)
    assert out["sessionFolderId"] == "id-sid1"


async def test_a_retried_provision_passes_the_stored_session_folder(store, client, monkeypatch):
    from app import session_provision

    seen = []

    def ensure(token, uid, sid, roles=None, cached=None, session_folder_id=None):
        seen.append(session_folder_id)
        return {"sessionFolderId": "sf-1", "userFolderId": "uf",
                "sessionsFolderId": "ss", "bundle": "sf-1"}

    monkeypatch.setattr(drive, "ensure_session_folders", ensure)
    r = await client.post("/v1/sessions", json={"specimen": "s", "files": [_file("a")]})
    sid = r.json()["sessionId"]
    # Simulate a retry of a task whose earlier attempt stored the folder but
    # did not open every upload target.
    for f in store._data.get("files", {}).values():
        if f.get("sessionId") == sid:
            f["uploadUrl"] = None
    session_provision.provision_session(sid)

    assert seen == [None, "sf-1"]


def test_enqueue_failure_is_an_error_event(monkeypatch, caplog):
    """The 403 actAs failure sat unnoticed as a WARNING; it must be an error."""
    import logging

    class Boom:
        def queue_path(self, *a):
            return "q"

        def create_task(self, **k):
            raise PermissionError("403 lacks iam.serviceAccounts.actAs")

    monkeypatch.setattr(settings, "TASKS_QUEUE", "q")
    monkeypatch.setattr(settings, "TASKS_TARGET_BASE_URL", "https://run")
    monkeypatch.setattr(settings, "GCP_PROJECT", "p")
    monkeypatch.setattr(tasks, "_tasks_client", lambda: Boom())

    with caplog.at_level(logging.ERROR, logger="semper.tasks"):
        assert tasks.enqueue_provision("s1") is False

    events = [r.getMessage() for r in caplog.records if r.levelno == logging.ERROR]
    assert any('"errorCode":"tasks_enqueue_failed"' in m for m in events)


# --- the task endpoint's identity ------------------------------------------

def test_tasks_disabled_without_full_configuration(monkeypatch):
    """A half-configured queue must fall back to inline rather than silently
    dropping provisioning on the floor."""
    monkeypatch.setattr(settings, "TASKS_QUEUE", "")
    assert settings.tasks_enabled is False
    assert tasks.enqueue_provision("s1") is False

    monkeypatch.setattr(settings, "TASKS_QUEUE", "q")
    monkeypatch.setattr(settings, "TASKS_TARGET_BASE_URL", "")
    assert settings.tasks_enabled is False


def test_task_caller_rejects_a_missing_token(monkeypatch):
    from fastapi import HTTPException

    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    with pytest.raises(HTTPException) as excinfo:
        tasks.tasks_caller(authorization="")
    assert excinfo.value.status_code == 401


@pytest.fixture
def oidc(monkeypatch):
    """Stub Google's ID-token verification, returning whatever claims a test wants."""
    from google.oauth2 import id_token as ga_id_token

    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "TASKS_TARGET_BASE_URL", "https://api.example")
    monkeypatch.setattr(settings, "TASKS_INVOKER_SA", "tasks@project.iam.gserviceaccount.com")

    def set_claims(claims):
        monkeypatch.setattr(
            ga_id_token, "verify_oauth2_token",
            lambda token, request, audience: claims,
        )

    return set_claims


def test_task_caller_accepts_the_configured_invoker(oidc):
    oidc({"email": "tasks@project.iam.gserviceaccount.com", "email_verified": True})
    assert tasks.tasks_caller(authorization="Bearer ok") == {
        "email": "tasks@project.iam.gserviceaccount.com",
    }


def test_task_caller_rejects_a_token_from_another_principal(oidc):
    """The audience check alone is not authentication — any Google account can
    mint an ID token for a public audience, so the identity must match."""
    from fastapi import HTTPException

    oidc({"email": "someone-else@gmail.com", "email_verified": True})
    with pytest.raises(HTTPException) as excinfo:
        tasks.tasks_caller(authorization="Bearer whatever")
    assert excinfo.value.status_code == 403
    assert excinfo.value.detail == "not_task_invoker"


def test_task_caller_rejects_an_unverifiable_token(oidc, monkeypatch):
    from fastapi import HTTPException
    from google.oauth2 import id_token as ga_id_token

    def boom(token, request, audience):
        raise ValueError("bad signature")

    monkeypatch.setattr(ga_id_token, "verify_oauth2_token", boom)
    with pytest.raises(HTTPException) as excinfo:
        tasks.tasks_caller(authorization="Bearer forged")
    assert excinfo.value.status_code == 401


def _task_routes() -> list[str]:
    """Every POST route behind `tasks_caller`, read from the app itself, so a
    new callback is covered by existing."""
    from app.main import app, served_routes

    def calls(dependant):
        for sub in dependant.dependencies:
            yield sub.call
            yield from calls(sub)

    return sorted(r.path for r in served_routes(app)
                  if getattr(r, "dependant", None) and tasks.tasks_caller in set(calls(r.dependant)))


def test_every_task_route_is_found():
    assert _task_routes() == ["/v1/tasks/license-fanout", "/v1/tasks/provision-session",
                              "/v1/tasks/recycle-bin-purge", "/v1/tasks/test-account-expiry"]


@pytest.mark.parametrize("path", _task_routes())
@pytest.mark.parametrize("credential,status,detail", [
    # A Firebase ID token is not signed by Google's OAuth2 certificates.
    ("firebase-user-token", 401, "invalid_task_token"),
    # A Google-signed token for a user's own account: verified, wrong principal.
    ("google-user-token", 403, "not_task_invoker"),
])
async def test_task_route_is_unreachable_with_a_user_token(store, client, monkeypatch, path,
                                                           credential, status, detail):
    """It is not a user route: a perfectly valid app credential opens nothing,
    on any of the three callbacks (readiness TE3-005)."""
    from google.oauth2 import id_token as ga_id_token

    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "TASKS_INVOKER_SA", "tasks-invoker@test.iam.gserviceaccount.com")

    def verify(token, _request, _audience):
        if token == "firebase-user-token":  # noqa: S105 - a fake token
            raise ValueError("Could not verify token signature.")
        return {"email": "someone@gmail.com", "email_verified": True}

    monkeypatch.setattr(ga_id_token, "verify_oauth2_token", verify)
    r = await client.post(path, json={"sessionId": "s1"},
                          headers={"Authorization": f"Bearer {credential}"})
    assert r.status_code == status, r.text
    assert r.json()["detail"] == detail


async def test_upload_targets_are_recorded_in_one_batch(store, queued, client, monkeypatch):
    """Each upload URL used to be written as it was opened, one Firestore
    round trip per file."""
    import fake_firestore

    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file(f"f{i}") for i in range(12)]},
    )).json()["sessionId"]
    commits = []
    real_commit = fake_firestore._Batch.commit
    monkeypatch.setattr(fake_firestore._Batch, "commit",
                        lambda b, **kw: commits.append(len(b._ops)) or real_commit(b, **kw))

    session_provision.provision_session(sid)

    assert commits == [12]
    assert all(f["uploadUrl"] for f in store._data["files"].values())


async def test_targets_opened_before_a_failure_are_kept_for_the_retry(
    store, queued, client, monkeypatch,
):
    """A failed fan-out still records what it opened, so the retry opens a
    session only for the files that have none."""
    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file("a"), _file("b"), _file("c")]},
    )).json()["sessionId"]
    opened = []

    def init(token, folder, name, size):
        if name == "b":
            raise RuntimeError("drive 503")
        opened.append(name)
        return f"https://drive/{name}"

    monkeypatch.setattr(drive, "init_resumable", init)
    monkeypatch.setattr(settings, "TASKS_PROVISION_WORKERS", 1)
    with pytest.raises(RuntimeError):
        session_provision.provision_session(sid)
    urls = {f["name"]: f["uploadUrl"] for f in store._data["files"].values()}
    assert urls == {"a": "https://drive/a", "b": None, "c": None}

    monkeypatch.setattr(drive, "init_resumable", lambda token, folder, name, size: f"https://drive/{name}")
    session_provision.provision_session(sid)
    urls = {f["name"]: f["uploadUrl"] for f in store._data["files"].values()}
    assert urls == {"a": "https://drive/a", "b": "https://drive/b", "c": "https://drive/c"}


def test_task_callbacks_share_one_certificate_transport(oidc, monkeypatch):
    """A new transport per callback was a new connection to Google per task."""
    from google.oauth2 import id_token as ga_id_token

    seen = []
    monkeypatch.setattr(
        ga_id_token, "verify_oauth2_token",
        lambda token, request, audience: seen.append(request) or {
            "email": "tasks@project.iam.gserviceaccount.com"},
    )
    tasks.tasks_caller(authorization="Bearer a")
    tasks.tasks_caller(authorization="Bearer b")
    assert len(seen) == 2 and seen[0] is seen[1]


# --- delete while provisioning (B6) ------------------------------------------

async def test_delete_during_provision_leaves_no_folder(store, queued, client, monkeypatch):
    """A delete that lands while the worker is creating the Drive folder used
    to erase the session doc first; the worker then made the folder with
    nothing left pointing at it. The delete is refused (409, retry later)
    while provisioning runs, so the folder is recorded and the next delete
    removes it with the session."""
    deleted = []
    monkeypatch.setattr(drive, "delete_file", lambda token, fid: deleted.append(fid))
    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file("a")]},
    )).json()["sessionId"]

    r = await client.delete(f"/v1/sessions/{sid}")

    assert r.status_code == 409, r.text
    assert r.json()["detail"] == "session_provisioning"
    assert r.headers["Retry-After"]
    assert sid in store._data["sessions"]

    session_provision.provision_session(sid)
    r = await client.delete(f"/v1/sessions/{sid}")

    assert r.status_code == 200, r.text
    # Binned (ADR-035), with the folder it now records: the purge deletes it.
    assert deleted == []
    assert sid not in store._data["sessions"]
    assert store._data["deleted_sessions"][sid]["driveFolderId"] == "sf"


async def test_a_session_stuck_provisioning_can_still_be_deleted(store, queued, client):
    """A task that never ran must not make an analysis unerasable: past the
    longest a provisioning attempt can run, the delete goes ahead."""
    from datetime import datetime, timedelta, timezone

    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file("a")]},
    )).json()["sessionId"]
    store._data["sessions"][sid]["updatedAt"] = (
        datetime.now(timezone.utc) - session_provision.PROVISIONING_GRACE - timedelta(minutes=1))

    r = await client.delete(f"/v1/sessions/{sid}")

    assert r.status_code == 200, r.text
    assert sid not in store._data["sessions"]


async def test_a_late_retry_holds_off_a_delete_while_it_runs(store, queued, client, monkeypatch):
    """TD-296: a Cloud Tasks retry that starts past the grace after the
    session's last write stamps `updatedAt` first, so a delete that lands
    while it creates the Drive folder is refused (409), not left behind."""
    import asyncio
    import threading
    from datetime import datetime, timedelta, timezone

    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file("a")]},
    )).json()["sessionId"]
    stale = datetime.now(timezone.utc) - session_provision.PROVISIONING_GRACE - timedelta(minutes=1)
    store._data["sessions"][sid]["updatedAt"] = stale
    store._data["sessions"][sid]["createdAt"] = stale

    in_drive, release = threading.Event(), threading.Event()

    def slow_folders(*a, **k):
        in_drive.set()
        release.wait(5)
        return {"sessionFolderId": "sf", "userFolderId": "uf", "bundle": "sf"}

    monkeypatch.setattr(drive, "ensure_session_folders", slow_folders)
    worker = asyncio.create_task(asyncio.to_thread(session_provision.provision_session, sid))
    assert await asyncio.to_thread(in_drive.wait, 5)

    r = await client.delete(f"/v1/sessions/{sid}")
    release.set()
    await worker

    assert r.status_code == 409, r.text
    assert r.json()["detail"] == "session_provisioning"
    assert store._data["sessions"][sid]["driveFolderId"] == "sf"


async def test_a_retry_after_a_delete_creates_nothing(store, queued, client, monkeypatch):
    """The stamp only touches a session still PROVISIONING: a delete that
    went first wins, and the retry finds nothing to provision."""
    monkeypatch.setattr(drive, "ensure_session_folders",
                        lambda *a, **k: pytest.fail("a folder for an erased session"))
    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file("a")]},
    )).json()["sessionId"]
    repo.delete_session(sid)

    assert session_provision.provision_session(sid)["status"] == "gone"
    assert sid not in store._data["sessions"]


def test_the_stamp_leaves_a_session_past_provisioning_alone(store):
    """UPLOADING (or COMPLETED, PROVISION_FAILED) is read back unchanged:
    the stamp is for the delete guard, which only asks about PROVISIONING."""
    store._data["sessions"] = {"s1": {"uid": DEV_UID, "status": "UPLOADING", "updatedAt": "then"}}

    session = repo.start_provisioning("s1")

    assert session["status"] == "UPLOADING" and session["sessionId"] == "s1"
    assert store._data["sessions"]["s1"]["updatedAt"] == "then"
    assert repo.start_provisioning("missing") is None
