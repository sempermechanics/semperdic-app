"""One key per device id, and who may replace it.

Android gives every app signed with one key the same `ANDROID_ID`, so a
retired `com.indicvision.*` build still installed beside its successor
reports the same device id. Its registration replaced the successor's key on
`devices/{id}`, and every call the successor signed failed `bad_signature`
(2026-10-10). Retired ids may no longer register; the app puts its key back
by registering again (`DeviceKeyRecovery`), which must be a plain key
overwrite.
"""
import pytest

from app import apps, deps
import repo_view as repo
from key_helpers import _ec_pem
from license_helpers import _mint_individual, _signed_in

NEW_SEMPER = {"X-App-Id": "com.sempermechanics.semper"}


def _bound(store, uid="dev-user", email="dev@local", device="and-semper"):
    """`uid` licensed and registered on `device` in Semper, with key A."""
    store._data["users"] = {}
    license_id = _mint_individual(email)["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, uid, email), None)
    repo.revalidate_device_lock(user, device)
    store._data["users"][uid]["activeDeviceId"] = device
    store._data.setdefault("devices", {})[device] = {
        "uid": uid, "app": apps.SEMPER, "status": "ACTIVE", "publicKeyPem": "key-A",
    }
    return license_id


def _as(store, monkeypatch, uid="dev-user"):
    monkeypatch.setattr(deps, "_DEV_USER", dict(store._data["users"][uid]))


def test_the_retired_ids_are_the_old_ones_only():
    assert apps.is_retired("com.indicvision.semper")
    assert apps.is_retired(" com.indicvision.semper.materialtesting ")
    assert not apps.is_retired("com.sempermechanics.semper")
    assert not apps.is_retired("com.sempermechanics.materialtesting")
    assert not apps.is_retired("")
    assert not apps.is_retired(None)
    # Still the same apps everywhere else (sessions, locks, the access log).
    assert all(apps.from_header(app_id) for app_id in apps.RETIRED)


@pytest.mark.asyncio
@pytest.mark.parametrize("app_id", sorted(apps.RETIRED))
async def test_a_retired_app_may_not_replace_the_key(client, store, audited, monkeypatch, app_id):
    license_id = _bound(store)
    _as(store, monkeypatch)
    registered = []
    repo.patch(monkeypatch, "register_device", lambda *a, **k: registered.append(a))

    resp = await client.post(
        "/v1/devices/register",
        json={"deviceId": "and-semper", "publicKeyPem": _ec_pem()},
        headers={"X-App-Id": app_id},
    )

    assert resp.status_code == 410, resp.text
    assert resp.json()["detail"] == "app_retired"
    assert registered == []
    assert store._data["devices"]["and-semper"]["publicKeyPem"] == "key-A"
    assert store._data["licenses"][license_id]["deviceIdLock"] == "and-semper"
    assert audited[-1]["action"] == "DEVICE_REGISTER"
    assert audited[-1]["outcome"] == "DENIED"
    assert audited[-1]["detail"]["reason"] == "app_retired"


@pytest.mark.asyncio
async def test_a_retired_app_keeps_its_other_routes(client, store, monkeypatch):
    """Only registration is refused: the access log still has to show when no
    build sends an old id (TD-176), and its reads cannot hurt the new app."""
    _bound(store)
    _as(store, monkeypatch)

    resp = await client.get("/v1/me", headers={"X-App-Id": "com.indicvision.semper"})

    assert resp.status_code == 200, resp.text


@pytest.mark.asyncio
async def test_registering_the_same_device_again_only_rewrites_its_key(client, store, audited, monkeypatch):
    """What the app's recovery from `bad_signature` sends: the same device id,
    the same account. No device retired, no lock or licence moved."""
    license_id = _bound(store)
    store._data["devices"]["and-other"] = {"uid": "dev-user", "status": "SUPERSEDED"}
    lic_before = dict(store._data["licenses"][license_id])
    _as(store, monkeypatch)
    key_b = _ec_pem()
    audited.clear()  # the set-up bound the licence lock

    resp = await client.post(
        "/v1/devices/register",
        json={"deviceId": "and-semper", "publicKeyPem": key_b, "model": "Pixel 6"},
        headers=NEW_SEMPER,
    )

    assert resp.status_code == 201, resp.text
    assert resp.json() == {"deviceId": "and-semper", "healed": True}
    device = store._data["devices"]["and-semper"]
    assert device["publicKeyPem"] == key_b
    assert device["status"] == "ACTIVE"
    assert device["uid"] == "dev-user"
    assert device["app"] == apps.SEMPER
    assert store._data["devices"]["and-other"]["status"] == "SUPERSEDED"
    user = store._data["users"]["dev-user"]
    assert user["activeDeviceId"] == "and-semper"
    assert "releasedDeviceId" not in user
    assert store._data["licenses"][license_id] == lic_before
    assert [row["action"] for row in audited] == ["DEVICE_REBIND"]


@pytest.mark.asyncio
async def test_another_apps_registration_on_the_shared_id_is_put_back(client, store, audited, monkeypatch):
    """The slot both apps share (TD-208): Material Testing on the same signing
    key registers the same device id and takes the key; Semper registering
    again takes it back, and each app's binding stays where it was."""
    _bound(store)
    _as(store, monkeypatch)
    mt_key, semper_key = _ec_pem(), _ec_pem()

    mt = await client.post(
        "/v1/devices/register",
        json={"deviceId": "and-semper", "publicKeyPem": mt_key},
        headers={"X-App-Id": "com.sempermechanics.materialtesting"},
    )
    assert mt.status_code == 201, mt.text
    assert store._data["devices"]["and-semper"]["publicKeyPem"] == mt_key

    _as(store, monkeypatch)
    back = await client.post(
        "/v1/devices/register",
        json={"deviceId": "and-semper", "publicKeyPem": semper_key},
        headers=NEW_SEMPER,
    )

    assert back.status_code == 201, back.text
    assert store._data["devices"]["and-semper"]["publicKeyPem"] == semper_key
    assert store._data["devices"]["and-semper"]["status"] == "ACTIVE"
    user = store._data["users"]["dev-user"]
    assert user["activeDeviceId"] == "and-semper"
    assert user["activeDeviceIdMaterialTesting"] == "and-semper"
