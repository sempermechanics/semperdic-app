"""A backup's `localSessionId` names a directory on every phone that restores it (L5-1).

The app restores a backup into the row, and the folder, its `localSessionId`
names. `..` made the phone's files dir the session dir, which a failed restore
then deleted, on every phone of the account through "Download from my other
phones". `POST /v1/sessions` now takes only the characters the app's own ids
use (a UUID prefix, `restored-…`), or none.
"""
import pytest

from app import audit, drive
from app.models import SessionCreate

DEV_UID = "dev-user"  # deps._DEV_USER in DEV_INSECURE_AUTH mode
_SHA = "a" * 64
_FILES = [{"name": "Session.zip", "role": "bundle", "bytes": 10, "sha256": _SHA}]


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


@pytest.mark.parametrize("local_id", ["..", ".", "a/../b", "a/b", "a\\b", "index.json", "a b", "é"])
async def test_a_local_id_that_could_name_another_directory_is_refused(store, client, local_id):
    r = await client.post("/v1/sessions", json={"specimen": "s.png", "localSessionId": local_id, "files": _FILES})

    assert r.status_code == 422
    assert store._data.get("sessions", {}) == {}


@pytest.mark.parametrize("local_id", ["f0f0406f-8c2", "restored-abcdefghijkl", "local_1"])
async def test_the_ids_the_app_mints_still_create(store, client, local_id):
    r = await client.post("/v1/sessions", json={"specimen": "s.png", "localSessionId": local_id, "files": _FILES})

    assert r.status_code == 200
    assert store._data["sessions"][r.json()["sessionId"]]["localSessionId"] == local_id


async def test_an_older_app_that_sends_no_local_id_still_creates(store, client):
    r = await client.post("/v1/sessions", json={"specimen": "s.png", "files": _FILES})

    assert r.status_code == 200


def test_the_bound_is_128_characters():
    assert SessionCreate(specimen="s", localSessionId="x" * 128, files=_FILES).localSessionId == "x" * 128
    with pytest.raises(ValueError):
        SessionCreate(specimen="s", localSessionId="x" * 129, files=_FILES)
