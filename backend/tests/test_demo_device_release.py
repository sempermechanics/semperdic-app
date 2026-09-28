"""A Demo account changing phone: staff release it on request (TD-126, decided 2026-09-26).

A Demo account has no licence lock to clear, so before this its first phone was
its only phone. Staff release the registered phone by the account's email; the
release is the one a lock clear makes, hold included.
"""
import pytest

import fake_firestore

from app import deps, firestore_repo as repo
from key_helpers import _ec_pem
from license_helpers import _mint_individual, _signed_in


def _demo_on(store, uid="demo-1", email="demo@lab.org", device_id="old-phone"):
    """A Demo account registered on `device_id`, the way the app leaves it."""
    store._data.setdefault("users", {})
    _signed_in(store, uid, email)
    store._data["users"][uid].update({"mode": "demo", "activeDeviceId": device_id})
    store._data.setdefault("devices", {})[device_id] = {"uid": uid, "status": "ACTIVE"}
    return uid


def test_staff_release_frees_a_demo_accounts_phone(store):
    uid = _demo_on(store)

    err, released = repo.release_account_device(uid)

    assert (err, released) == ("", {"semper": "old-phone"})
    user = store._data["users"][uid]
    assert "activeDeviceId" not in user
    assert user["releasedDeviceId"] == "old-phone"
    assert "updatedAt" in user
    assert store._data["devices"]["old-phone"]["status"] == "SUPERSEDED"
    assert repo.released_device_held(user, "old-phone") is True


def test_a_licensed_account_is_sent_to_new_device(store):
    """Its lock would still name the old phone and demote the new one."""
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    repo.revalidate_device_lock(user, "old-phone")
    store._data["users"]["solo-1"]["activeDeviceId"] = "old-phone"
    assert store._data["users"]["solo-1"]["licenseId"] == license_id

    err, released = repo.release_account_device("solo-1")

    assert (err, released) == ("license_device_clear_required", {})
    assert store._data["users"]["solo-1"]["activeDeviceId"] == "old-phone"


def test_a_holder_left_on_demo_by_a_revoked_licence_can_be_released(store):
    """A lock clear skips them (TD-127); the staff release is their way to move."""
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    repo.revalidate_device_lock(user, "old-phone")
    store._data["users"]["solo-1"]["activeDeviceId"] = "old-phone"
    repo.revoke_license(license_id, "staff-1")
    assert store._data["users"]["solo-1"]["licenseId"] == license_id

    assert repo.release_account_device("solo-1") == ("", {"semper": "old-phone"})


def test_nothing_registered_is_not_an_error(store):
    uid = _demo_on(store)
    store._data["users"][uid].pop("activeDeviceId")

    assert repo.release_account_device(uid) == ("", {})
    assert "releasedDeviceId" not in store._data["users"][uid]


def test_an_unknown_account(store):
    store._data["users"] = {}
    assert repo.release_account_device("nobody") == ("user_not_found", {})


def test_a_release_frees_the_phone_in_both_apps(store):
    """One phone per app (ADR-010): the holder is changing phones, and both
    apps are on the phone they are changing."""
    uid = _demo_on(store)
    store._data["users"][uid]["activeDeviceIdMaterialTesting"] = "old-phone-mt"
    store._data["devices"]["old-phone-mt"] = {"uid": uid, "status": "ACTIVE"}

    err, released = repo.release_account_device(uid)

    assert (err, released) == ("", {"semper": "old-phone", "materialtesting": "old-phone-mt"})
    user = store._data["users"][uid]
    assert "activeDeviceId" not in user and "activeDeviceIdMaterialTesting" not in user
    assert user["releasedDeviceIdMaterialTesting"] == "old-phone-mt"
    assert store._data["devices"]["old-phone-mt"]["status"] == "SUPERSEDED"
    assert repo.released_device_held(user, "old-phone-mt", "materialtesting") is True
    # Each app's hold is its own: the Semper id is not held off Material Testing.
    assert repo.released_device_held(user, "old-phone", "materialtesting") is False


@pytest.mark.asyncio
async def test_released_over_http_then_the_new_phone_registers(client, monkeypatch, audited):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    staff = dict(deps._DEV_USER)
    _demo_on(store, uid="demo-1", email="demo@lab.org")

    def as_holder():
        monkeypatch.setattr(deps, "_DEV_USER", dict(store._data["users"]["demo-1"]))

    as_holder()
    new_phone = {"deviceId": "and-newphone1", "publicKeyPem": _ec_pem()}
    assert (await client.post("/v1/devices/register", json=new_phone)).status_code == 409

    monkeypatch.setattr(deps, "_DEV_USER", staff)
    resp = await client.post("/v1/admin/device-releases", json={"email": "Demo@Lab.org"})

    assert resp.status_code == 200, resp.text
    assert resp.json() == {"uid": "demo-1", "email": "demo@lab.org",
                           "releasedDeviceId": "old-phone",
                           "releasedDeviceIdMaterialTesting": ""}
    released = [r for r in audited if r["action"] == "ADMIN_DEVICE_RELEASE"]
    assert released[0]["target"] == {"type": "user", "id": "demo-1"}
    assert released[0]["detail"] == {"releasedDeviceId": "old-phone",
                                     "releasedDeviceIdMaterialTesting": ""}

    as_holder()
    old_phone = {"deviceId": "old-phone", "publicKeyPem": _ec_pem()}
    assert (await client.post("/v1/devices/register", json=old_phone)).status_code == 409
    registered = await client.post("/v1/devices/register", json=new_phone)
    assert registered.status_code == 201, registered.text
    assert store._data["users"]["demo-1"]["activeDeviceId"] == "and-newphone1"


@pytest.mark.asyncio
async def test_release_answers(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    store._data["users"] = {}

    missing = await client.post("/v1/admin/device-releases", json={"email": "nobody@lab.org"})
    malformed = await client.post("/v1/admin/device-releases", json={"email": "not-an-email"})

    assert missing.status_code == 404
    assert "user_not_found" in missing.text
    assert malformed.status_code == 422
