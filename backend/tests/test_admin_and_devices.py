"""Admin approve/revoke, list_users limit clamp, device registration conflicts,
and which registered devices may step up."""
import base64
import hashlib
import time as _time

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

import fake_firestore
from app import audit
import repo_view as repo
from app import deps
from app.config import settings
from key_helpers import _ec_pem


@pytest.fixture
def store(store, monkeypatch):
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    return store


# ---------------- admin approve / revoke ----------------
async def test_admin_approve_and_revoke(store, client):
    store._data["users"] = {
        "u1": {"email": "a@b.com", "access_status": "PENDING", "role": "user"},
    }

    r = await client.post("/v1/admin/users/u1/approve")
    assert r.status_code == 200
    assert r.json() == {"uid": "u1", "access_status": "APPROVED"}
    assert store._data["users"]["u1"]["access_status"] == "APPROVED"

    r = await client.post("/v1/admin/users/u1/revoke")
    assert r.status_code == 200
    assert r.json() == {"uid": "u1", "access_status": "SUSPENDED"}
    assert store._data["users"]["u1"]["access_status"] == "SUSPENDED"


async def test_admin_approve_missing_user_404(store, client):
    r = await client.post("/v1/admin/users/missing/approve")
    assert r.status_code == 404


async def test_admin_list_users_clamps_limit(store, client, monkeypatch):
    store._data["users"] = {
        f"u{i}": {"email": f"u{i}@t.com", "access_status": "APPROVED", "role": "user"}
        for i in range(5)
    }
    seen = {}

    def spy(status="", limit=50, page_token=None):
        seen["limit"] = limit
        seen["page_token"] = page_token
        users = [{"uid": uid, "email": u.get("email"), "displayName": None,
                  "role": u.get("role"), "access_status": u.get("access_status"),
                  "activeDeviceId": None}
                 for uid, u in list(store._data["users"].items())[:limit]]
        return users, None

    repo.patch(monkeypatch, "list_users", spy)

    r = await client.get("/v1/admin/users?limit=9999")
    assert r.status_code == 200
    assert seen["limit"] == 200

    r = await client.get("/v1/admin/users?limit=0")
    assert r.status_code == 200
    assert seen["limit"] == 1

    r = await client.get("/v1/admin/users?limit=3")
    assert r.status_code == 200
    assert seen["limit"] == 3
    assert len(r.json()["users"]) == 3
    assert r.json()["page"]["hasMore"] is False


async def test_admin_list_users_cursor(store, client):
    store._data["users"] = {
        f"u{i:02d}": {"email": f"u{i}@t.com", "access_status": "APPROVED", "role": "user"}
        for i in range(5)
    }
    first = await client.get("/v1/admin/users?limit=2")
    assert first.status_code == 200
    body = first.json()
    assert len(body["users"]) == 2
    assert body["page"]["hasMore"] is True
    token = body["page"]["nextPageToken"]
    second = await client.get(f"/v1/admin/users?limit=2&page_token={token}")
    assert second.status_code == 200
    assert len(second.json()["users"]) == 2
    assert {u["uid"] for u in first.json()["users"]}.isdisjoint(
        {u["uid"] for u in second.json()["users"]}
    )


# ---------------- register_device conflicts ----------------
async def test_a_second_phone_registers_beside_the_first(store, client, monkeypatch):
    """An account holds several phones per app (ADR-033): the second one
    signs in and becomes the latest; the first stays registered."""
    monkeypatch.setattr(
        deps, "_DEV_USER",
        {**deps._DEV_USER, "activeDeviceId": "already-bound-device"},
    )
    store._data["users"] = {"dev-user": {"email": "dev@local", "access_status": "APPROVED",
                                         "activeDeviceId": "already-bound-device"}}
    store._data["devices"] = {"already-bound-device": {"uid": "dev-user", "status": "ACTIVE"}}
    r = await client.post(
        "/v1/devices/register",
        json={"deviceId": "and-newdevice", "publicKeyPem": _ec_pem()},
    )
    assert r.status_code == 201, r.text
    assert r.json() == {"deviceId": "and-newdevice", "healed": False}
    assert store._data["users"]["dev-user"]["activeDeviceId"] == "and-newdevice"
    assert store._data["devices"]["already-bound-device"]["status"] == "ACTIVE"


async def test_register_device_in_use_by_other_account(store, client, monkeypatch):
    monkeypatch.setattr(
        deps, "_DEV_USER",
        {**deps._DEV_USER, "activeDeviceId": None},
    )
    store._data["users"] = {"dev-user": {"email": "dev@local", "access_status": "APPROVED"}}
    store._data["devices"] = {
        "and-shared1": {"uid": "other-user", "status": "ACTIVE", "publicKeyPem": "x"},
    }
    r = await client.post(
        "/v1/devices/register",
        json={"deviceId": "and-shared1", "publicKeyPem": _ec_pem()},
    )
    assert r.status_code == 409
    assert r.json()["detail"] == "device_in_use"


async def test_register_device_heals_same_device(store, client, monkeypatch):
    pem = _ec_pem()
    monkeypatch.setattr(
        deps, "_DEV_USER",
        {**deps._DEV_USER, "activeDeviceId": "and-mine12"},
    )
    store._data["users"] = {
        "dev-user": {"email": "dev@local", "access_status": "APPROVED", "activeDeviceId": "and-mine12"},
    }
    store._data["devices"] = {
        "and-mine12": {"uid": "dev-user", "status": "ACTIVE", "publicKeyPem": "old"},
    }
    r = await client.post(
        "/v1/devices/register",
        json={"deviceId": "and-mine12", "publicKeyPem": pem},
    )
    assert r.status_code == 201
    body = r.json()
    assert body["deviceId"] == "and-mine12"
    assert body["healed"] is True
    assert store._data["devices"]["and-mine12"]["publicKeyPem"] == pem


# ---------------- device trust: who may step up with a phone (B1) ----------------
# A registered device used to satisfy every step-up tier. Registration needs
# only an ID token, so a stolen staff token plus the `X-App-Id` of an app the
# account had no phone for registered the thief's key and signed admin
# mutations without ever meeting the second factor. The device now carries
# `stepUpEligible` (the registering token proved a second factor), and only
# such a device steps up for staff or institution IT.
MT_APP = "com.sempermechanics.semperclassroom"


@pytest.fixture
def secure(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "ADMIN_EMAILS", {"staff@sempermechanics.com"})
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    store._data["users"] = {
        "admin-1": {"email": "staff@sempermechanics.com", "role": "admin",
                    "access_status": "APPROVED", "emailVerified": True,
                    "activeDeviceId": "staff-phone"},
        "target": {"email": "t@e.com", "role": "user", "access_status": "PENDING"},
    }
    return store


def _token(monkeypatch, claims: dict):
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda *_a, **_k: {"sub": "admin-1", "email": "staff@sempermechanics.com",
                           "email_verified": True, **claims},
    )


def _key():
    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    return priv, pem


async def _register(client, device_id: str, pem: str, app_id: str = ""):
    headers = {"Authorization": "Bearer ok"}
    if app_id:
        headers["X-App-Id"] = app_id
    return await client.post("/v1/devices/register", headers=headers,
                             json={"deviceId": device_id, "publicKeyPem": pem})


async def _signed_approve(client, priv, device_id: str, app_id: str = ""):
    path = "/v1/admin/users/target/approve"
    nonce = f"t1.{int(_time.time())}.{'A' * 30}"
    msg = (nonce + "POST" + path).encode() + hashlib.sha256(b"").digest()
    sig = base64.b64encode(priv.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()
    headers = {"Authorization": "Bearer ok", "X-Device-Id": device_id,
               "X-Nonce": nonce, "X-Signature": sig}
    if app_id:
        headers["X-App-Id"] = app_id
    return await client.post(path, headers=headers)


async def test_device_registered_on_bare_token_cannot_step_up_admin(secure, client, monkeypatch):
    """The attack itself: a password-only (stolen) staff token registers a
    key under the app the account has no phone for, then signs an admin
    mutation with it."""
    _token(monkeypatch, {})
    priv, pem = _key()
    r = await _register(client, "thief-phone", pem, app_id=MT_APP)
    assert r.status_code == 201, r.text
    assert secure._data["devices"]["thief-phone"]["stepUpEligible"] is False

    r = await _signed_approve(client, priv, "thief-phone", app_id=MT_APP)

    assert r.status_code == 403, r.text
    assert r.json()["detail"] == "mfa_required"
    assert secure._data["users"]["target"]["access_status"] == "PENDING"


async def test_device_registered_with_a_second_factor_steps_up_admin(secure, client, monkeypatch):
    _token(monkeypatch, {"firebase": {"sign_in_second_factor": "totp"}})
    priv, pem = _key()
    r = await _register(client, "staff-phone", pem)
    assert r.status_code == 201, r.text
    assert secure._data["devices"]["staff-phone"]["stepUpEligible"] is True

    r = await _signed_approve(client, priv, "staff-phone")

    assert r.status_code == 200, r.text
    assert secure._data["users"]["target"]["access_status"] == "APPROVED"


async def test_re_registering_without_a_second_factor_drops_eligibility(secure, client, monkeypatch):
    """A heal replaces the key, so it is judged afresh: a bare token that
    re-registers the staff phone's id with its own key gets no step-up."""
    secure._data["devices"] = {"staff-phone": {
        "uid": "admin-1", "app": "semper", "status": "ACTIVE",
        "publicKeyPem": "old", "stepUpEligible": True}}
    _token(monkeypatch, {})
    priv, pem = _key()
    assert (await _register(client, "staff-phone", pem)).status_code == 201

    r = await _signed_approve(client, priv, "staff-phone")

    assert r.status_code == 403
    assert r.json()["detail"] == "mfa_required"


async def test_a_device_signs_only_for_the_app_it_registered_under(secure, client, monkeypatch):
    """Two apps of one account may report one device id (one signing key).
    A key registered under Semper Classroom must not then sign as Semper,
    where the same id is the bound phone: the device record names its app,
    here by its name from before ADR-030.
    `bad_signature`, so a real phone in that spot registers again and
    resends (`DeviceKeyRecovery`), as it did when the key simply differed."""
    _token(monkeypatch, {"firebase": {"sign_in_second_factor": "totp"}})
    priv, pem = _key()
    secure._data["devices"] = {"staff-phone": {
        "uid": "admin-1", "app": "materialtesting", "status": "ACTIVE",
        "publicKeyPem": pem, "stepUpEligible": True}}

    r = await _signed_approve(client, priv, "staff-phone")

    assert r.status_code == 401, r.text
    assert r.json()["detail"] == "bad_signature"
    assert secure._data["users"]["target"]["access_status"] == "PENDING"


async def test_a_retired_device_cannot_sign(secure, client, monkeypatch):
    """Any of the account's ACTIVE phones signs (ADR-033); a retired one
    (released, rotated out, superseded) cannot, whatever its key."""
    _token(monkeypatch, {"firebase": {"sign_in_second_factor": "totp"}})
    priv, pem = _key()
    secure._data["devices"] = {"stale-phone": {
        "uid": "admin-1", "app": "semper", "status": "SUPERSEDED",
        "publicKeyPem": pem, "stepUpEligible": True}}

    r = await _signed_approve(client, priv, "stale-phone")

    assert r.status_code == 409, r.text
    assert r.json()["detail"] == "device_not_active"
    assert secure._data["users"]["target"]["access_status"] == "PENDING"


async def test_the_holder_tier_still_takes_a_phone_registered_without_mfa(secure, client, monkeypatch):
    """The user tier is deliberately unchanged: an ordinary account has no
    second factor, and its own phone is how it moves its licence or pulls a
    bundle. The phone's signature already reaches every device-attested
    route, so asking more of the step-up here would protect nothing."""
    secure._data["users"]["admin-1"].update(role="user", email="holder@lab.org")
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda *_a, **_k: {"sub": "admin-1", "email": "holder@lab.org", "email_verified": True},
    )
    priv, pem = _key()
    assert (await _register(client, "staff-phone", pem)).status_code == 201
    path = "/v1/licenses/unbind"
    nonce = f"t1.{int(_time.time())}.{'B' * 30}"
    msg = (nonce + "POST" + path).encode() + hashlib.sha256(b"").digest()
    sig = base64.b64encode(priv.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()

    r = await client.post(path, headers={"Authorization": "Bearer ok", "X-Device-Id": "staff-phone",
                                         "X-Nonce": nonce, "X-Signature": sig})

    assert r.status_code == 200, r.text
    assert r.json()["previousDeviceId"] == "staff-phone"
