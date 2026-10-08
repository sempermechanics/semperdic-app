import threading
from datetime import datetime, timedelta, timezone

import pytest

from app import deps, drive, legal
from app.config import settings
from app.licenses import invite_id
import repo_view as repo


@pytest.mark.asyncio
async def test_me_returns_dev_user(client):
    resp = await client.get("/v1/me")
    assert resp.status_code == 200
    body = resp.json()
    assert body["uid"] == "dev-user"
    assert body["access_status"] == "APPROVED"


# ---------------------------------------------------------------- fixtures

def _seed_two_accounts(store):
    """dev-user (the caller) and u-other, each with a session, a file and a device."""
    store._data["users"] = {
        "dev-user": {"email": "dev@local", "access_status": "APPROVED"},
        "u-other": {"email": "other@lab.org", "access_status": "APPROVED",
                    "driveFolderId": "other-folder"},
    }
    store._data["sessions"] = {
        "s-mine": {"uid": "dev-user", "driveFolderId": "f-mine"},
        "s-theirs": {"uid": "u-other", "driveFolderId": "f-theirs"},
    }
    store._data["files"] = {
        "s-mine_raw_a": {"uid": "dev-user", "sessionId": "s-mine"},
        "s-theirs_raw_a": {"uid": "u-other", "sessionId": "s-theirs"},
    }
    store._data["devices"] = {
        "dev-device": {"uid": "dev-user", "status": "ACTIVE"},
        "d-other": {"uid": "u-other", "status": "ACTIVE"},
    }


class _DriveSpy:
    """Records drive deletes; the fake Drive holds `user_folder` for the caller."""

    def __init__(self, monkeypatch, user_folder="dev-user-folder"):
        self.deleted: list[str] = []
        self.batch: list[list] = []
        self._lock = threading.Lock()
        monkeypatch.setattr(drive, "access_token", lambda: "tok")
        monkeypatch.setattr(drive, "delete_file", self._delete)
        monkeypatch.setattr(drive, "find_user_folder", lambda _t, uid: user_folder)
        real = drive.delete_files

        def delete_files(token, ids, **kw):
            self.batch.append(list(ids))
            return real(token, ids, **kw)

        monkeypatch.setattr(drive, "delete_files", delete_files)

    def _delete(self, _token, fid):
        with self._lock:
            self.deleted.append(fid)


@pytest.fixture
def me_store(store, audited):
    return store


# ------------------------------------------------------------ unauthenticated

@pytest.fixture
def secure(me_store, monkeypatch):
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    return me_store


@pytest.mark.asyncio
async def test_get_me_without_a_bearer_is_401(secure, client):
    r = await client.get("/v1/me")
    assert r.status_code == 401
    assert r.json()["detail"] == "missing_bearer"


@pytest.mark.asyncio
async def test_delete_me_without_a_bearer_is_401_and_erases_nothing(secure, client, monkeypatch):
    _seed_two_accounts(secure)
    spy = _DriveSpy(monkeypatch)
    before = {k: dict(v) for k, v in secure._data.items()}

    r = await client.delete("/v1/me")

    assert r.status_code == 401
    assert r.json()["detail"] == "missing_bearer"
    assert secure._data == before
    assert spy.deleted == []


@pytest.mark.asyncio
async def test_delete_me_with_a_token_but_no_device_signature_erases_nothing(
        secure, client, monkeypatch):
    """A stolen ID token alone must not be able to erase the account."""
    _seed_two_accounts(secure)
    secure._data["users"]["u-other"]["termsAccepted"] = {"version": legal.TERMS_VERSION}
    monkeypatch.setattr(deps, "verify_id_token", lambda _t: {
        "sub": "u-other", "email": "other@lab.org", "email_verified": True})
    spy = _DriveSpy(monkeypatch)

    r = await client.delete("/v1/me", headers={"Authorization": "Bearer t"})

    assert r.status_code == 400
    assert r.json()["detail"] == "missing_device_id"
    assert "u-other" in secure._data["users"]
    assert "s-theirs" in secure._data["sessions"]
    assert spy.deleted == []


@pytest.mark.asyncio
async def test_get_me_answers_for_the_token_holder_only(secure, client, monkeypatch):
    _seed_two_accounts(secure)
    secure._data["users"]["u-other"].update(
        role="user", termsAccepted={"version": "2020-01"},
        improvementConsent={"granted": False})
    secure._data["users"]["dev-user"]["improvementConsent"] = {"granted": True}
    monkeypatch.setattr(deps, "verify_id_token", lambda _t: {
        "sub": "u-other", "email": "other@lab.org", "email_verified": True})

    r = await client.get("/v1/me", headers={"Authorization": "Bearer t"})

    assert r.status_code == 200, r.text
    body = r.json()
    assert body["uid"] == "u-other" and body["email"] == "other@lab.org"
    assert body["role"] == "user"
    assert body["terms"]["accepted_version"] == "2020-01"
    assert body["terms"]["required_version"] == legal.TERMS_VERSION
    assert body["improvement_consent"] is False  # theirs, not dev-user's True
    assert body["license"]["mode"] == "demo" and body["license"]["held"] is False


# ---------------------------------------------------------------- GET /v1/me

@pytest.mark.asyncio
async def test_get_me_licence_block_for_a_floating_seat(me_store, client, monkeypatch):
    lease = datetime.now(timezone.utc) + timedelta(minutes=20)
    expiry = datetime.now(timezone.utc) - timedelta(days=1)
    caller = {**deps._DEV_USER, "licenseKind": "institution", "licensePrefix": "SEMP-ABCD",
              "licenseSeating": "floating", "leaseExpiresAt": lease,
              "licenseExpiresAt": expiry, "licenseGraceDays": 7,
              "termsAccepted": {"version": legal.TERMS_VERSION}}
    monkeypatch.setattr(deps, "_DEV_USER", caller)

    body = (await client.get("/v1/me")).json()

    lic = body["license"]
    assert lic["mode"] == "licensed" and lic["held"] is True
    assert lic["kind"] == "institution" and lic["prefix"] == "SEMP-ABCD"
    assert lic["seating"] == "floating" and lic["inGrace"] is True
    assert lic["duration"] == "timed"
    assert datetime.fromisoformat(lic["leaseExpiresAt"]) == lease
    assert datetime.fromisoformat(lic["graceEndsAt"]) == expiry + timedelta(days=7)
    assert body["terms"]["accepted_version"] == legal.TERMS_VERSION
    assert body["improvement_consent"] is None  # never asked


@pytest.mark.asyncio
async def test_get_me_for_a_floating_member_between_leases_is_demo_but_held(
        me_store, client, monkeypatch):
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "licenseSeating": "floating"})
    lic = (await client.get("/v1/me")).json()["license"]
    assert lic["mode"] == "demo" and lic["held"] is True and lic["leaseExpiresAt"] is None
    assert lic["expiresAt"] is None and lic["duration"] == "perpetual"


@pytest.mark.asyncio
async def test_get_me_reads_nothing_from_firestore(client, monkeypatch):
    """The licence block is pure over the user `current_user` already has."""
    class _NoDb:
        def __getattr__(self, name):
            raise AssertionError(f"GET /v1/me touched Firestore ({name})")

    from app.repo import _base
    monkeypatch.setattr(_base, "_DB", _NoDb())
    assert (await client.get("/v1/me")).status_code == 200


# ------------------------------------------------------------- DELETE /v1/me

@pytest.mark.asyncio
async def test_delete_me_with_a_stored_folder_erases_it_in_one_call(
        me_store, audited, client, monkeypatch):
    _seed_two_accounts(me_store)
    monkeypatch.setitem(deps._DEV_USER, "driveFolderId", "dev-user-folder")
    spy = _DriveSpy(monkeypatch)

    r = await client.delete("/v1/me")

    assert r.status_code == 200, r.text
    body = r.json()
    assert body["deleted"] == "dev-user"
    assert (body["sessions"], body["files"], body["devices"]) == (1, 1, 1)
    assert body["driveFolders"] == 0 and body["userFolderFound"] is True
    assert spy.deleted == ["dev-user-folder"]  # the subtree, not each session
    assert spy.batch == []
    # The caller is gone; the other account is exactly as it was.
    assert "dev-user" not in me_store._data["users"]
    assert set(me_store._data["sessions"]) == {"s-theirs"}
    assert set(me_store._data["files"]) == {"s-theirs_raw_a"}
    assert set(me_store._data["devices"]) == {"d-other"}
    assert me_store._data["users"]["u-other"]["driveFolderId"] == "other-folder"
    assert [a["action"] for a in audited] == ["ACCOUNT_DELETE"]
    assert audited[0]["uid"] == "dev-user" and audited[0]["deviceId"] == "dev-device"


@pytest.mark.asyncio
async def test_delete_me_without_any_drive_folder_still_erases_the_records(
        me_store, audited, client, monkeypatch):
    _seed_two_accounts(me_store)
    me_store._data["sessions"]["s-mine"]["driveFolderId"] = None
    spy = _DriveSpy(monkeypatch, user_folder=None)

    body = (await client.delete("/v1/me")).json()

    assert body["userFolderFound"] is False and body["driveFolders"] == 0
    assert spy.batch == [[None]] and spy.deleted == []
    assert set(me_store._data["sessions"]) == {"s-theirs"}
    assert "dev-user" not in me_store._data["users"]


@pytest.mark.asyncio
async def test_a_drive_failure_aborts_before_any_record_is_erased(me_store, client, monkeypatch):
    """Erasing the metadata first would strand the blobs with nothing pointing at them."""
    _seed_two_accounts(me_store)
    monkeypatch.setitem(deps._DEV_USER, "driveFolderId", "dev-user-folder")
    monkeypatch.setattr(drive, "access_token", lambda: "tok")

    def refuse(_token, _fid):
        raise PermissionError("organizer rights needed")

    monkeypatch.setattr(drive, "delete_file", refuse)
    before = {k: dict(v) for k, v in me_store._data.items()}

    with pytest.raises(PermissionError):
        await client.delete("/v1/me")

    assert me_store._data == before


@pytest.mark.asyncio
async def test_a_second_delete_finds_nothing_left_and_still_answers(me_store, client, monkeypatch):
    _seed_two_accounts(me_store)
    _DriveSpy(monkeypatch, user_folder=None)
    assert (await client.delete("/v1/me")).status_code == 200

    again = await client.delete("/v1/me")

    assert again.status_code == 200
    body = again.json()
    assert (body["sessions"], body["files"], body["devices"]) == (0, 0, 0)
    assert set(me_store._data["sessions"]) == {"s-theirs"}
    assert "u-other" in me_store._data["users"]


@pytest.mark.asyncio
async def test_delete_me_gives_an_individual_licence_back_to_its_address(
        me_store, client, monkeypatch):
    _seed_two_accounts(me_store)
    me_store._data["users"]["dev-user"]["licenseId"] = "lic-solo"
    me_store._data["licenses"] = {"lic-solo": {
        "kind": "individual", "mode": "licensed", "status": "redeemed",
        "redeemedByUid": "dev-user", "redeemedAt": "then", "deviceIdLock": "dev-device",
        "emailLock": "dev@local",
    }}
    _DriveSpy(monkeypatch, user_folder=None)

    assert (await client.delete("/v1/me")).status_code == 200

    lic = me_store._data["licenses"]["lic-solo"]
    assert lic["status"] == "unused"
    assert "redeemedByUid" not in lic and "redeemedAt" not in lic
    assert lic["deviceIdLock"] == ""
    # Re-promised, so a fresh account at that address is licensed again.
    assert me_store._data["licenseInvites"][invite_id("dev@local")]["licenseId"] == "lic-solo"


@pytest.mark.asyncio
async def test_delete_me_frees_the_licence_even_when_its_address_is_promised_elsewhere(
        me_store, client, monkeypatch):
    """The re-invite is refused (the address holds another live promise); the
    licence is still freed and the other promise is left alone."""
    _seed_two_accounts(me_store)
    me_store._data["users"]["dev-user"]["licenseId"] = "lic-solo"
    me_store._data["licenses"] = {
        "lic-solo": {"kind": "individual", "mode": "licensed", "status": "redeemed",
                     "redeemedByUid": "dev-user", "emailLock": "dev@local"},
        "lic-uni": {"kind": "institution", "mode": "licensed", "status": "active"},
    }
    me_store._data["licenseInvites"] = {
        invite_id("dev@local"): {"email": "dev@local", "licenseId": "lic-uni"}}
    _DriveSpy(monkeypatch, user_folder=None)

    assert (await client.delete("/v1/me")).status_code == 200

    assert me_store._data["licenses"]["lic-solo"]["status"] == "unused"
    assert me_store._data["licenseInvites"][invite_id("dev@local")]["licenseId"] == "lic-uni"


@pytest.mark.asyncio
async def test_delete_me_frees_an_institution_seat(me_store, client, monkeypatch):
    _seed_two_accounts(me_store)
    me_store._data["users"]["dev-user"]["licenseId"] = "lic-uni"
    me_store._data["licenses"] = {"lic-uni": {
        "kind": "institution", "mode": "licensed", "status": "active", "seatsUsed": 2}}
    me_store._data["licenses/lic-uni/seats"] = {
        "dev-user": {"uid": "dev-user", "status": "active"},
        "u-other": {"uid": "u-other", "status": "active"},
    }
    _DriveSpy(monkeypatch, user_folder=None)

    assert (await client.delete("/v1/me")).status_code == 200

    seats = me_store._data["licenses/lic-uni/seats"]
    assert seats["dev-user"]["status"] == "revoked"
    assert seats["u-other"]["status"] == "active"
    assert me_store._data["licenses"]["lic-uni"]["seatsUsed"] == 1


@pytest.mark.asyncio
async def test_delete_me_leaves_a_demo_key_and_a_licence_held_by_someone_else(
        me_store, client, monkeypatch):
    _seed_two_accounts(me_store)
    me_store._data["users"]["dev-user"]["licenseId"] = "lic-theirs"
    me_store._data["licenses"] = {"lic-theirs": {
        "kind": "individual", "mode": "licensed", "status": "redeemed",
        "redeemedByUid": "u-other"}}
    _DriveSpy(monkeypatch, user_folder=None)

    assert (await client.delete("/v1/me")).status_code == 200

    assert me_store._data["licenses"]["lic-theirs"]["redeemedByUid"] == "u-other"
    assert me_store._data["licenses"]["lic-theirs"]["status"] == "redeemed"


def test_delete_all_user_data_of_an_unknown_account_is_all_zero(me_store):
    assert repo.delete_all_user_data("ghost") == {"sessions": 0, "files": 0, "devices": 0}
