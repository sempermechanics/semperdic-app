"""Each app lists and restores its own cloud backups (ADR-014).

Semper and Material Testing share accounts and this backend. Before the tag,
each app's backups card and restore list showed the other's sessions, and
Semper restoring a Material Testing backup silently dropped its test data.
A session is now tagged on create with the app that asked (`X-App-Id`), an
untagged one reads as Semper's, `GET /v1/sessions` lists the asking app's
unless `?app=` says otherwise, and the restore manifest answers only the app
that made the backup.
"""
import sys
from pathlib import Path

import pytest

import fake_firestore
from app import apps, audit, drive, firestore_repo as repo, statuses

DEV_UID = "dev-user"  # deps._DEV_USER in DEV_INSECURE_AUTH mode
MT = apps.MATERIAL_TESTING
MT_HEADER = {"X-App-Id": "com.indicvision.semper.materialtesting"}
SEMPER_HEADER = {"X-App-Id": "com.sempermechanics.semper"}
_SHA = "a" * 64

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
import tag_session_apps  # noqa: E402


def _session(app=None, uid=DEV_UID, **over):
    doc = {"uid": uid, "status": statuses.SESSION_COMPLETED, "localSessionId": "",
           "fileCount": 1, "completedCount": 1, "driveFolderId": None}
    if app is not None:
        doc["app"] = app
    return {**doc, **over}


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
    return store


@pytest.fixture
def mixed(store):
    """Tagged Semper, tagged Material Testing and untagged (Semper) sessions,
    plus someone else's Material Testing one."""
    store._data["sessions"] = {
        "a-semper": _session(apps.SEMPER),
        "b-mt": _session(MT),
        "c-untagged": _session(),
        "d-mt": _session(MT),
        "e-other-user": _session(MT, uid="someone-else"),
    }
    return store


def _ids(body):
    return [s["sessionId"] for s in body["sessions"]]


# ------------------------------------------------------------------ create


async def _create(client, headers=None, local="loc-1"):
    return await client.post(
        "/v1/sessions", headers=headers or {},
        json={"specimen": "s", "localSessionId": local,
              "files": [{"name": "Session.zip", "role": "bundle", "bytes": 10, "sha256": _SHA}]},
    )


async def test_a_session_is_tagged_with_the_app_that_created_it(store, client):
    semper = await _create(client, local="loc-semper")
    mt = await _create(client, MT_HEADER, local="loc-mt")

    assert semper.status_code == 200 and mt.status_code == 200
    sessions = store._data["sessions"]
    assert sessions[semper.json()["sessionId"]]["app"] == apps.SEMPER
    assert sessions[mt.json()["sessionId"]]["app"] == MT


async def test_the_tag_is_not_a_body_field(store, client):
    """The server derives the app; a body claiming the other app is ignored."""
    r = await client.post(
        "/v1/sessions",
        json={"specimen": "s", "app": MT,
              "files": [{"name": "Session.zip", "role": "bundle", "bytes": 10, "sha256": _SHA}]},
    )
    assert r.status_code == 200
    assert store._data["sessions"][r.json()["sessionId"]]["app"] == apps.SEMPER


async def test_the_create_audit_names_the_app(store, client, audited):
    await _create(client, MT_HEADER)
    created, = [row for row in audited if row["action"] == "SESSION_CREATE"]
    assert created["detail"]["app"] == MT


def test_an_untagged_session_is_semper():
    assert repo.session_app({}) == apps.SEMPER
    assert repo.session_app(None) == apps.SEMPER
    assert repo.session_app({"app": ""}) == apps.SEMPER
    assert repo.session_app({"app": MT}) == MT


# ------------------------------------------------------------------ listing


async def test_no_header_lists_semper_and_untagged(mixed, client):
    r = await client.get("/v1/sessions")
    assert r.status_code == 200
    assert _ids(r.json()) == ["a-semper", "c-untagged"]
    assert [s["app"] for s in r.json()["sessions"]] == [apps.SEMPER, apps.SEMPER]


async def test_material_testing_lists_only_its_own(mixed, client):
    r = await client.get("/v1/sessions", headers=MT_HEADER)
    assert _ids(r.json()) == ["b-mt", "d-mt"]
    assert {s["app"] for s in r.json()["sessions"]} == {MT}


async def test_an_explicit_app_overrides_the_header(mixed, client):
    r = await client.get("/v1/sessions?app=materialtesting", headers=SEMPER_HEADER)
    assert _ids(r.json()) == ["b-mt", "d-mt"]
    r = await client.get("/v1/sessions?app=semper", headers=MT_HEADER)
    assert _ids(r.json()) == ["a-semper", "c-untagged"]


async def test_app_all_lists_the_whole_account_with_each_app(mixed, client):
    r = await client.get("/v1/sessions?app=all", headers=MT_HEADER)
    assert {s["sessionId"]: s["app"] for s in r.json()["sessions"]} == {
        "a-semper": apps.SEMPER, "b-mt": MT, "c-untagged": apps.SEMPER, "d-mt": MT,
    }


async def test_an_unknown_app_is_refused(mixed, client):
    r = await client.get("/v1/sessions?app=bogus")
    assert r.status_code == 400
    assert r.json()["detail"] == "unknown_app"


async def test_an_unknown_header_is_refused(mixed, client):
    r = await client.get("/v1/sessions", headers={"X-App-Id": "com.example.other"})
    assert r.status_code == 400
    assert r.json()["detail"] == "unknown_app"


async def test_the_quota_stays_account_wide(mixed, client):
    """The cap is the account's: every app's sessions count, whichever app asks."""
    for headers in ({}, MT_HEADER):
        r = await client.get("/v1/sessions", headers=headers)
        assert r.json()["quota"]["used"] == 4


@pytest.mark.parametrize("headers, want", [
    ({}, [f"s{i:02d}" for i in range(0, 30, 3)]),
    (MT_HEADER, [f"s{i:02d}" for i in range(30) if i % 3]),
])
async def test_paging_across_interleaved_apps_fills_and_ends(store, client, headers, want):
    """Pages fill past the other app's sessions, and the token chain ends."""
    store._data["sessions"] = {
        f"s{i:02d}": _session(None if i % 3 == 0 else MT) for i in range(30)
    }
    got, token, pages = [], "", 0
    while True:
        url = "/v1/sessions?page_size=4" + (f"&page_token={token}" if token else "")
        body = (await client.get(url, headers=headers)).json()
        pages += 1
        assert pages <= 30, "the token chain never ended"
        got += _ids(body)
        token = body["page"]["nextPageToken"]
        if not token:
            assert body["page"]["hasMore"] is False
            break
        assert body["page"]["count"] == 4, "a page with more to come was not filled"
    assert got == want


def test_no_token_when_the_last_match_ends_the_account(store):
    store._data["sessions"] = {"a": _session(MT), "b": _session(MT), "c": _session()}
    page, token = repo.list_user_sessions(DEV_UID, limit=2, app=MT)
    assert [s["sessionId"] for s in page] == ["a", "b"]
    # "c" follows, so a token is issued; the next page is empty and final.
    assert token == "b"
    assert repo.list_user_sessions(DEV_UID, limit=2, page_token=token, app=MT) == ([], None)

    store._data["sessions"] = {"a": _session(MT), "b": _session(MT)}
    assert repo.list_user_sessions(DEV_UID, limit=2, app=MT)[1] is None


def test_the_export_iterator_is_unfiltered(mixed):
    """`/v1/me/export` and erasure walk every app's sessions."""
    ids = [s["sessionId"] for s in repo.iter_all_user_sessions(DEV_UID, page_size=1)]
    assert ids == ["a-semper", "b-mt", "c-untagged", "d-mt"]


# ------------------------------------------------------------------ per-session routes


async def test_the_restore_manifest_answers_only_the_app_that_backed_up(mixed, client):
    assert (await client.get("/v1/sessions/b-mt/files", headers=MT_HEADER)).status_code == 200
    assert (await client.get("/v1/sessions/c-untagged/files")).status_code == 200

    for sid, headers in (("b-mt", {}), ("a-semper", MT_HEADER), ("c-untagged", MT_HEADER)):
        r = await client.get(f"/v1/sessions/{sid}/files", headers=headers)
        assert r.status_code == 404, sid
        assert r.json()["detail"] == "session_not_found"


@pytest.fixture
def uploaded(mixed, monkeypatch):
    """`a-semper` with a completed bundle and metadata in Drive."""
    for sid, role, name in (("a-semper", "bundle", "Session.zip"),
                            ("a-semper", "metadata", "metadata.json")):
        mixed._data.setdefault("files", {})[f"{sid}_{role}_{name}"] = {
            "sessionId": sid, "uid": DEV_UID, "role": role, "name": name,
            "sizeBytes": 4, "sha256": "aa", "status": statuses.FILE_COMPLETED,
            "driveFileId": f"drive-{role}",
        }
    mixed._data["sessions"]["a-semper"]["localSessionId"] = "loc-a"

    class _Download:
        def iter_chunks(self, chunk_size=0):
            yield b"data"

    monkeypatch.setattr(drive, "open_download", lambda *a, **k: _Download())
    monkeypatch.setattr(drive, "replace_content",
                        lambda token, fid, data, mime="application/json": {"size": len(data), "md5": "b" * 32})
    monkeypatch.setattr(drive, "delete_file", lambda *a, **k: None)
    return mixed


async def test_the_bundle_metadata_and_delete_stay_account_wide(uploaded, client):
    """A browser cannot send `X-App-Id`, and a delete or metadata write of
    one's own session is not a restore: none of these is gated by app."""
    bundle = await client.get("/v1/sessions/a-semper/bundle", headers=MT_HEADER)
    assert bundle.status_code == 200

    meta = await client.put("/v1/sessions/a-semper/metadata", headers=MT_HEADER,
                            json={"schema": "indic.session.metadata/3", "localSessionId": "loc-a"})
    assert meta.status_code == 200

    deleted = await client.delete("/v1/sessions/a-semper", headers=MT_HEADER)
    assert deleted.status_code == 200
    assert "a-semper" not in uploaded._data["sessions"]


# ------------------------------------------------------------------ backfill


@pytest.fixture
def backfill():
    store = fake_firestore.FakeClient()
    store._data["devices"] = {
        "dev-semper": {"uid": "u1", "app": apps.SEMPER},
        "dev-mt": {"uid": "u1", "app": MT},
        "dev-old": {"uid": "u1"},  # registered before ADR-010 wrote `app`
    }
    store._data["sessions"] = {
        "s1": {"uid": "u1", "deviceId": "dev-semper"},
        "s2": {"uid": "u1", "deviceId": "dev-mt"},
        "s3": {"uid": "u1", "deviceId": "dev-old"},
        "s4": {"uid": "u1", "deviceId": "dev-gone"},
        "s5": {"uid": "u1"},
        "s6": {"uid": "u1", "deviceId": "dev-mt", "app": apps.SEMPER},  # already tagged
        "s7": {"uid": "u1", "deviceId": "dev-mt"},
    }
    return store


def test_backfill_dry_run_counts_and_writes_nothing(backfill):
    before = {k: dict(v) for k, v in backfill._data["sessions"].items()}
    counts = tag_session_apps.tag_sessions(backfill, apply=False)
    assert backfill._data["sessions"] == before
    assert counts["scanned"] == 7
    assert counts["already_tagged"] == 1
    assert counts[MT] == 2
    assert counts[apps.SEMPER] == 4
    assert counts["no_device"] == 2


def test_backfill_attributes_by_the_sessions_device(backfill):
    tag_session_apps.tag_sessions(backfill, apply=True)
    tags = {sid: s.get("app") for sid, s in backfill._data["sessions"].items()}
    assert tags == {
        "s1": apps.SEMPER, "s2": MT, "s3": apps.SEMPER, "s4": apps.SEMPER,
        "s5": apps.SEMPER, "s6": apps.SEMPER, "s7": MT,
    }


def test_backfill_is_idempotent(backfill, monkeypatch):
    tag_session_apps.tag_sessions(backfill, apply=True)
    monkeypatch.setattr(tag_session_apps, "PAGE", 2)  # walks several pages too
    counts = tag_session_apps.tag_sessions(backfill, apply=True)
    assert counts["scanned"] == 7
    assert counts["already_tagged"] == 7
    assert counts[MT] == counts[apps.SEMPER] == 0
