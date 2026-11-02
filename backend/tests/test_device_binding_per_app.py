"""One phone per app (ADR-010): Semper and Semper Classroom each bind their own device.

Android scopes `ANDROID_ID` to the signing key, so the two apps on one phone
report two device ids. Before this, the second app was a second device on a
one-device account: refused at registration ("already linked"), and until
phase 0 its requests demoted the account.
"""
import pytest

import fake_firestore

from app import apps, deps
import repo_view as repo
from key_helpers import _ec_pem
from license_helpers import (  # noqa: F401
    _mint_individual,
    _mint_institution,
    _signed_in,
)
from refusals import attempt

MT = apps.SEMPER_CLASSROOM
MT_HEADER = {"X-App-Id": "com.sempermechanics.semperclassroom"}


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
    assert apps.from_header("com.sempermechanics.semper") == apps.SEMPER
    assert apps.from_header(" com.sempermechanics.semperclassroom ") == MT
    # Semper Classroom's id names Semper Classroom (ADR-030).
    assert apps.from_header(" com.sempermechanics.materialtesting ") == MT
    # The ids from before the move to com.sempermechanics name the same apps.
    assert apps.from_header("com.indicvision.semper") == apps.SEMPER
    assert apps.from_header(" com.indicvision.semper.materialtesting ") == MT
    # Not Semper: a build nobody registered must not take Semper's slot.
    assert apps.from_header("com.example.other") is None


def test_semper_keeps_the_fields_it_always_had():
    assert apps.field("deviceIdLock", apps.SEMPER) == "deviceIdLock"
    assert apps.field("deviceIdLock", MT) == "deviceIdLockSemperClassroom"
    assert apps.spread("releasedDeviceId", {MT: "x"}) == {
        "releasedDeviceId": "", "releasedDeviceIdSemperClassroom": "x",
        "releasedDeviceIdMaterialTesting": "x"}


# ------------------------------------------------------------------ the locks


def test_the_second_app_binds_its_own_lock_and_both_stay_licensed(store):
    store._data["users"] = {}
    license_id = _licensed(store)

    mt = repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-mt", MT)

    assert mt["mode"] == "licensed"
    lic = store._data["licenses"][license_id]
    assert lic["deviceIdLock"] == "and-semper"
    assert lic["deviceIdLockSemperClassroom"] == "and-mt"
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
    """The locks are separate: Semper's device id does not pass Semper
    Classroom's lock, and the reverse."""
    store._data["users"] = {}
    _licensed(store)
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-mt", MT)
    user = store._data["users"]["solo-1"]

    assert repo.revalidate_device_lock(user, "and-semper", MT)["mode"] == "demo"
    assert repo.revalidate_device_lock(user, "and-mt")["mode"] == "demo"
    assert repo.revalidate_device_lock(user, "and-mt", MT)["mode"] == "licensed"


def test_a_seat_binds_one_device_per_app(store):
    store._data["users"] = {
        "u1": {"uid": "u1", "email": "a@university.edu", "emailVerified": True,
               "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    assert attempt(repo.activate_license, "u1", "a@university.edu", "and-semper", minted["key"])[0] == ""

    err, config = attempt(repo.activate_license, "u1", "a@university.edu", "and-mt", minted["key"], MT)

    assert err == ""
    assert config["mode"] == "licensed"
    seat = store._data[f"licenses/{license_id}/seats"]["u1"]
    assert seat["deviceIdLock"] == "and-semper"
    assert seat["deviceIdLockSemperClassroom"] == "and-mt"
    # A second phone typing the key in Semper Classroom is still refused.
    assert attempt(repo.activate_license,
        "u1", "a@university.edu", "and-mt-2", minted["key"], MT)[0] == "license_device_mismatch"


def test_a_typed_individual_key_binds_the_asking_apps_lock(store):
    store._data["users"] = {}
    minted = _mint_individual()
    license_id = minted["license"]["id"]
    _signed_in(store, "solo-1", "solo@lab.org")
    assert attempt(repo.activate_license, "solo-1", "solo@lab.org", "and-semper", minted["key"])[0] == ""

    err, _ = attempt(repo.activate_license, "solo-1", "solo@lab.org", "and-mt", minted["key"], MT)

    assert err == ""
    assert store._data["licenses"][license_id]["deviceIdLockSemperClassroom"] == "and-mt"


# ------------------------------------------------------------ device changes


def _registered(store, uid, semper="and-semper", mt="and-mt"):
    """Both phones registered as this backend registers them: Semper Classroom's
    binding under both field names, its device tagged with the stored name."""
    user = store._data["users"][uid]
    user["activeDeviceId"] = semper
    user.update(apps.written_fields("activeDeviceId", MT, mt))
    store._data.setdefault("devices", {})
    store._data["devices"][semper] = {"uid": uid, "status": "ACTIVE", "app": apps.SEMPER}
    store._data["devices"][mt] = {"uid": uid, "status": "ACTIVE", "app": apps.stored_name(MT)}


def test_a_staff_clear_moves_both_apps(store):
    store._data["users"] = {}
    license_id = _licensed(store)
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-mt", MT)
    _registered(store, "solo-1")

    err, cleared = attempt(repo.clear_device_lock, license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert cleared["previousDeviceId"] == "and-semper"
    assert cleared["previousDeviceIdSemperClassroom"] == "and-mt"
    assert cleared["releasedDeviceId"] == "and-semper"
    assert cleared["releasedDeviceIdSemperClassroom"] == "and-mt"
    lic = store._data["licenses"][license_id]
    assert lic["deviceIdLock"] == "" and lic["deviceIdLockSemperClassroom"] == ""
    user = store._data["users"]["solo-1"]
    assert not {"activeDeviceId", "activeDeviceIdSemperClassroom",
                "activeDeviceIdMaterialTesting"} & set(user)
    assert store._data["devices"]["and-mt"]["status"] == "SUPERSEDED"
    assert repo.released_device_held(user, "and-mt", MT) is True


def test_the_holder_moves_one_app_alone(store):
    """The holder's own clear releases the app that asked and nothing else.
    No stamp, no wait (ADR-033): either app may move again at once."""
    store._data["users"] = {}
    license_id = _licensed(store)
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-mt", MT)
    _registered(store, "solo-1")

    err, cleared = attempt(repo.clear_device_lock, license_id, "solo-1", actor=repo.ACTOR_SELF, app=MT)

    assert err == ""
    assert cleared["app"] == MT
    assert cleared["previousDeviceIdSemperClassroom"] == "and-mt"
    assert cleared["releasedDeviceIdSemperClassroom"] == "and-mt"
    assert cleared["releasedDeviceId"] == ""
    lic = store._data["licenses"][license_id]
    assert lic["deviceIdLock"] == "and-semper", "Semper's phone is untouched"
    assert lic["deviceIdLockSemperClassroom"] == ""
    assert not {"deviceChangedAt", "deviceChangedAtSemperClassroom",
                "deviceChangedAtMaterialTesting"} & set(lic)
    assert store._data["users"]["solo-1"]["activeDeviceId"] == "and-semper"
    # Either app may move again straight away; the device count is the limit.
    assert attempt(repo.clear_device_lock, license_id, "solo-1", actor=repo.ACTOR_SELF)[0] == ""
    assert attempt(repo.clear_device_lock,
        license_id, "solo-1", actor=repo.ACTOR_SELF, app=MT)[0] == ""
    assert lic["deviceIdLock"] == "" and lic["deviceIdLockSemperClassroom"] == ""


# ------------------------------------------------------------------ over HTTP


def _as(store, monkeypatch, uid):
    monkeypatch.setattr(deps, "_DEV_USER", dict(store._data["users"][uid]))


@pytest.mark.asyncio
async def test_semper_classroom_registers_beside_semper(client, monkeypatch):
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

    registered = await client.post("/v1/devices/register", json=mt_phone, headers=MT_HEADER)

    assert registered.status_code == 201, registered.text
    user = store._data["users"]["dev-user"]
    assert user["activeDeviceId"] == "and-semper"
    assert user["activeDeviceIdSemperClassroom"] == "and-mtphone1"
    assert user["activeDeviceIdMaterialTesting"] == "and-mtphone1"
    assert store._data["devices"]["and-semper"]["status"] == "ACTIVE"
    # Stored by the old name, which a backend from before the rename reads too.
    assert store._data["devices"]["and-mtphone1"]["app"] == apps.LEGACY_MATERIAL_TESTING


@pytest.mark.asyncio
async def test_an_unknown_app_is_refused(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    resp = await client.get("/v1/me", headers={"X-App-Id": "com.example.other"})
    assert resp.status_code == 400
    assert resp.json()["detail"] == "unknown_app"


@pytest.mark.asyncio
async def test_the_holder_moves_semper_classroom_from_a_browser(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    license_id = _licensed(store, "dev-user", "dev@local")
    repo.revalidate_device_lock(store._data["users"]["dev-user"], "and-mt", MT)
    _as(store, monkeypatch, "dev-user")

    resp = await client.post("/v1/licenses/unbind?app=semperclassroom")
    unknown = await client.post("/v1/licenses/unbind?app=other")

    assert resp.status_code == 200, resp.text
    assert resp.json()["app"] == MT
    assert resp.json()["previousDeviceId"] == "and-mt"
    lic = store._data["licenses"][license_id]
    assert lic["deviceIdLockSemperClassroom"] == ""
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
    assert store._data["licenses"][license_id]["deviceIdLockSemperClassroom"] == ""
    assert store._data["licenses"][license_id]["deviceIdLock"] == "and-semper"


# ------------------------------------------- the released phone's demo count


def _sessions_from(store, uid, device, n=2):
    store._data.setdefault("sessions", {})
    for i in range(n):
        store._data["sessions"][f"{uid}-{device}-{i}"] = {
            "uid": uid, "deviceId": device, "status": "COMPLETED"}


def test_a_staff_clear_stops_the_old_phone_counting_the_holders_analyses(store):
    """TD-276: the phone may go to someone else, whose demo it would hold."""
    store._data["users"] = {}
    license_id = _licensed(store)
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-mt", MT)
    _registered(store, "solo-1")
    _sessions_from(store, "solo-1", "and-semper")
    _sessions_from(store, "someone-else", "and-semper", n=1)

    err, cleared = attempt(repo.clear_device_lock, license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert cleared["sessionsForgotten"] == 2
    assert repo.count_device_sessions("and-semper") == 1, "another account's stay counted"
    assert repo.count_user_sessions("solo-1") == 2, "still the account's"


def test_the_holders_own_clear_keeps_the_phone_counting(store):
    """Else a second account on the same phone could shed the first one's count."""
    store._data["users"] = {}
    license_id = _licensed(store)
    _registered(store, "solo-1")
    _sessions_from(store, "solo-1", "and-semper")

    err, cleared = attempt(repo.clear_device_lock, license_id, "solo-1", actor=repo.ACTOR_SELF)

    assert err == ""
    assert "sessionsForgotten" not in cleared
    assert repo.count_device_sessions("and-semper") == 2
