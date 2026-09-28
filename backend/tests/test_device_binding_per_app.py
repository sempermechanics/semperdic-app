"""One phone per app (ADR-010): Semper and Material Testing each bind their own device.

Android scopes `ANDROID_ID` to the signing key, so the two apps on one phone
report two device ids. Before this, the second app was a second device on a
one-device account: refused at registration ("already linked"), and until
phase 0 its requests demoted the account.
"""
import pytest

import fake_firestore

from app import apps, deps, firestore_repo as repo
from key_helpers import _ec_pem
from license_helpers import (  # noqa: F401
    _mint_individual,
    _mint_institution,
    _signed_in,
)

MT = apps.MATERIAL_TESTING
MT_HEADER = {"X-App-Id": "com.indicvision.semper.materialtesting"}


def _licensed(store, uid="solo-1", email="solo@lab.org"):
    """An individual licence held by `uid`, bound to its Semper phone."""
    license_id = _mint_individual(email)["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, uid, email), None)
    repo.revalidate_device_lock(user, "and-semper")
    return license_id


# ------------------------------------------------------------------ the header


def test_the_header_names_the_app():
    assert apps.from_header("") == apps.SEMPER
    assert apps.from_header(None) == apps.SEMPER
    assert apps.from_header("com.indicvision.semper") == apps.SEMPER
    assert apps.from_header(" com.indicvision.semper.materialtesting ") == MT
    # Not Semper: a build nobody registered must not take Semper's slot.
    assert apps.from_header("com.example.other") is None


def test_semper_keeps_the_fields_it_always_had():
    assert apps.field("deviceIdLock", apps.SEMPER) == "deviceIdLock"
    assert apps.field("deviceIdLock", MT) == "deviceIdLockMaterialTesting"
    assert apps.spread("releasedDeviceId", {MT: "x"}) == {
        "releasedDeviceId": "", "releasedDeviceIdMaterialTesting": "x"}


# ------------------------------------------------------------------ the locks


def test_the_second_app_binds_its_own_lock_and_both_stay_licensed(store):
    store._data["users"] = {}
    license_id = _licensed(store)

    mt = repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-mt", MT)

    assert mt["mode"] == "licensed"
    lic = store._data["licenses"][license_id]
    assert lic["deviceIdLock"] == "and-semper"
    assert lic["deviceIdLockMaterialTesting"] == "and-mt"
    semper = repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-semper")
    assert semper["mode"] == "licensed"


def test_each_app_still_holds_one_phone(store):
    """A second phone in the same app is a mismatch, as it always was."""
    store._data["users"] = {}
    _licensed(store)
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-mt", MT)

    other = repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-mt-2", MT)

    assert other["mode"] == "demo"
    assert store._data["users"]["solo-1"]["mode"] == "licensed"


def test_one_apps_device_is_a_mismatch_in_the_other(store):
    """The locks are separate: Semper's device id does not pass Material
    Testing's lock, and the reverse."""
    store._data["users"] = {}
    _licensed(store)
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-mt", MT)
    user = store._data["users"]["solo-1"]

    assert repo.check_device_lock(user, "and-semper", MT) is False
    assert repo.check_device_lock(user, "and-mt") is False
    assert repo.check_device_lock(user, "and-mt", MT) is True


def test_a_seat_binds_one_device_per_app(store):
    store._data["users"] = {
        "u1": {"uid": "u1", "email": "a@university.edu",
               "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    assert repo.activate_license("u1", "a@university.edu", "and-semper", minted["key"])[0] == ""

    err, config = repo.activate_license("u1", "a@university.edu", "and-mt", minted["key"], MT)

    assert err == ""
    assert config["mode"] == "licensed"
    seat = store._data[f"licenses/{license_id}/seats"]["u1"]
    assert seat["deviceIdLock"] == "and-semper"
    assert seat["deviceIdLockMaterialTesting"] == "and-mt"
    # A second phone typing the key in Material Testing is still refused.
    assert repo.activate_license(
        "u1", "a@university.edu", "and-mt-2", minted["key"], MT)[0] == "license_device_mismatch"


def test_a_typed_individual_key_binds_the_asking_apps_lock(store):
    store._data["users"] = {}
    minted = _mint_individual()
    license_id = minted["license"]["id"]
    _signed_in(store, "solo-1", "solo@lab.org")
    assert repo.activate_license("solo-1", "solo@lab.org", "and-semper", minted["key"])[0] == ""

    err, _ = repo.activate_license("solo-1", "solo@lab.org", "and-mt", minted["key"], MT)

    assert err == ""
    assert store._data["licenses"][license_id]["deviceIdLockMaterialTesting"] == "and-mt"


# ------------------------------------------------------------ device changes


def _registered(store, uid, semper="and-semper", mt="and-mt"):
    user = store._data["users"][uid]
    user["activeDeviceId"] = semper
    user["activeDeviceIdMaterialTesting"] = mt
    store._data.setdefault("devices", {})
    store._data["devices"][semper] = {"uid": uid, "status": "ACTIVE", "app": apps.SEMPER}
    store._data["devices"][mt] = {"uid": uid, "status": "ACTIVE", "app": MT}


def test_a_staff_clear_moves_both_apps(store):
    store._data["users"] = {}
    license_id = _licensed(store)
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-mt", MT)
    _registered(store, "solo-1")

    err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert cleared["previousDeviceId"] == "and-semper"
    assert cleared["previousDeviceIdMaterialTesting"] == "and-mt"
    assert cleared["releasedDeviceId"] == "and-semper"
    assert cleared["releasedDeviceIdMaterialTesting"] == "and-mt"
    lic = store._data["licenses"][license_id]
    assert lic["deviceIdLock"] == "" and lic["deviceIdLockMaterialTesting"] == ""
    user = store._data["users"]["solo-1"]
    assert "activeDeviceId" not in user and "activeDeviceIdMaterialTesting" not in user
    assert store._data["devices"]["and-mt"]["status"] == "SUPERSEDED"
    assert repo.released_device_held(user, "and-mt", MT) is True


def test_the_holder_moves_one_app_with_its_own_cooldown(store):
    store._data["users"] = {}
    license_id = _licensed(store)
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-mt", MT)
    _registered(store, "solo-1")

    err, cleared = repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF, app=MT)

    assert err == ""
    assert cleared["app"] == MT
    assert cleared["previousDeviceIdMaterialTesting"] == "and-mt"
    assert cleared["releasedDeviceIdMaterialTesting"] == "and-mt"
    assert cleared["releasedDeviceId"] == ""
    lic = store._data["licenses"][license_id]
    assert lic["deviceIdLock"] == "and-semper", "Semper's phone is untouched"
    assert lic["deviceIdLockMaterialTesting"] == ""
    assert "deviceChangedAtMaterialTesting" in lic and "deviceChangedAt" not in lic
    assert store._data["users"]["solo-1"]["activeDeviceId"] == "and-semper"
    # Moving Material Testing does not make Semper wait, and the reverse.
    assert repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF)[0] == ""
    assert repo.clear_device_lock(
        license_id, "solo-1", actor=repo.ACTOR_SELF, app=MT)[0] == "device_change_too_soon"


# ------------------------------------------------------------------ over HTTP


def _as(store, monkeypatch, uid):
    monkeypatch.setattr(deps, "_DEV_USER", dict(store._data["users"][uid]))


@pytest.mark.asyncio
async def test_material_testing_registers_beside_semper(client, monkeypatch):
    """The sign-in that said "already linked" (2026-09-28)."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    _signed_in(store, "dev-user", "dev@local")
    store._data["users"]["dev-user"]["activeDeviceId"] = "and-semper"
    store._data.setdefault("devices", {})["and-semper"] = {"uid": "dev-user", "status": "ACTIVE"}
    _as(store, monkeypatch, "dev-user")
    # Device ids are at least eight characters (`validation.DeviceId`).
    mt_phone = {"deviceId": "and-mtphone1", "publicKeyPem": _ec_pem()}

    without_header = await client.post("/v1/devices/register", json=mt_phone)
    registered = await client.post("/v1/devices/register", json=mt_phone, headers=MT_HEADER)

    assert without_header.status_code == 409, "read as Semper, whose phone is another"
    assert registered.status_code == 201, registered.text
    user = store._data["users"]["dev-user"]
    assert user["activeDeviceId"] == "and-semper"
    assert user["activeDeviceIdMaterialTesting"] == "and-mtphone1"
    assert store._data["devices"]["and-semper"]["status"] == "ACTIVE"
    assert store._data["devices"]["and-mtphone1"]["app"] == MT


@pytest.mark.asyncio
async def test_an_unknown_app_is_refused(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    resp = await client.get("/v1/me", headers={"X-App-Id": "com.example.other"})
    assert resp.status_code == 400
    assert resp.json()["detail"] == "unknown_app"


@pytest.mark.asyncio
async def test_the_holder_moves_material_testing_from_a_browser(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    license_id = _licensed(store, "dev-user", "dev@local")
    repo.revalidate_device_lock(store._data["users"]["dev-user"], "and-mt", MT)
    _as(store, monkeypatch, "dev-user")

    resp = await client.post("/v1/licenses/unbind?app=materialtesting")
    unknown = await client.post("/v1/licenses/unbind?app=other")

    assert resp.status_code == 200, resp.text
    assert resp.json()["app"] == MT
    assert resp.json()["previousDeviceId"] == "and-mt"
    lic = store._data["licenses"][license_id]
    assert lic["deviceIdLockMaterialTesting"] == ""
    assert lic["deviceIdLock"] == "and-semper"
    assert unknown.status_code == 400


@pytest.mark.asyncio
async def test_the_app_moves_its_own_device_by_header(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    license_id = _licensed(store, "dev-user", "dev@local")
    repo.revalidate_device_lock(store._data["users"]["dev-user"], "and-mt", MT)
    _as(store, monkeypatch, "dev-user")

    resp = await client.post("/v1/licenses/unbind", headers=MT_HEADER)

    assert resp.status_code == 200, resp.text
    assert store._data["licenses"][license_id]["deviceIdLockMaterialTesting"] == ""
    assert store._data["licenses"][license_id]["deviceIdLock"] == "and-semper"
