"""Who the backend takes a caller to be: staff off-boarding, the address a
legacy key is redeemed against, the device id header, and which app's
licence `/v1/me` answers with (review 2026-11-01, L1-1, L1-2, L1-5, L10-4)."""
import pytest

import fake_firestore
import repo_view as repo
from app import audit, deps
from app.config import settings
from license_helpers import _mint_individual, _mint_institution


@pytest.fixture
def secure(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "ADMIN_EMAILS", {"boss@sempermechanics.com"})
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    return store


def _signs_in_as(monkeypatch, email):
    monkeypatch.setattr(deps, "verify_id_token", lambda *_a, **_k: {
        "sub": "staff-1", "email": email, "email_verified": True,
        "firebase": {"sign_in_provider": "google.com"},
    })


# ---------------- L1-1: off-boarded staff ----------------
@pytest.mark.asyncio
async def test_a_stored_admin_role_without_an_admin_address_is_refused_and_demoted(
    secure, client, monkeypatch,
):
    """Taking an address out of ADMIN_EMAILS removed nobody: `admin_user`
    trusted the stored `role`, which was only ever promoted."""
    secure._data["users"] = {"staff-1": {
        "email": "former@sempermechanics.com", "emailVerified": True,
        "role": "admin", "access_status": "APPROVED",
    }}
    _signs_in_as(monkeypatch, "former@sempermechanics.com")

    r = await client.get("/v1/admin/users", headers={"Authorization": "Bearer ok"})

    assert r.status_code == 403
    assert r.json()["detail"] == "not_admin"
    assert secure._data["users"]["staff-1"]["role"] == "user"


@pytest.mark.asyncio
async def test_an_admin_address_still_reads_the_desk(secure, client, monkeypatch):
    secure._data["users"] = {"staff-1": {
        "email": "boss@sempermechanics.com", "emailVerified": True,
        "role": "user", "access_status": "APPROVED",
    }}
    _signs_in_as(monkeypatch, "boss@sempermechanics.com")

    r = await client.get("/v1/admin/users", headers={"Authorization": "Bearer ok"})

    assert r.status_code == 200, r.text
    assert secure._data["users"]["staff-1"]["role"] == "admin"


# ---------------- L1-5: the device id header ----------------
@pytest.mark.asyncio
@pytest.mark.parametrize("device_id", ["a/b", "..", "x" * 2000])
async def test_a_device_id_that_is_not_an_id_is_refused_before_any_lookup(
    secure, client, monkeypatch, device_id,
):
    """It was a Firestore document path in the account lookup and the lock
    check before `verified_device` validated it: a nested path, or a 500."""
    _signs_in_as(monkeypatch, "u@e.com")
    looked_up = []
    repo.patch(monkeypatch, "get_or_create_user",
               lambda *a, **k: looked_up.append(a) or pytest.fail("reached the account"))

    r = await client.get("/v1/me", headers={"Authorization": "Bearer ok", "X-Device-Id": device_id})

    assert r.status_code == 400
    assert r.json()["detail"] == "invalid_device_id"
    assert looked_up == []


# ---------------- L1-2: the legacy key route ----------------
@pytest.mark.asyncio
async def test_the_legacy_key_route_refuses_an_unproven_address(client, store, monkeypatch):
    """`POST /v1/licenses/activate` checked the email lock against the stored
    address, verified or not."""
    store._data["users"] = {"dev-user": {
        "email": "dev@local", "emailVerified": False, "access_status": "APPROVED", "plan": "demo",
    }}
    minted = _mint_individual("dev@local", device_id_lock="dev-device")

    r = await client.post("/v1/licenses/activate", json={"key": minted["key"]},
                          headers={"X-Device-Id": "dev-device"})

    assert r.status_code == 403
    assert r.json()["detail"] == "email_not_verified"
    assert "licenseId" not in store._data["users"]["dev-user"]
    assert not store._data["licenses"][minted["license"]["id"]].get("redeemedByUid")


@pytest.mark.asyncio
async def test_the_legacy_key_route_takes_no_seat_on_an_unproven_domain(client, store, monkeypatch):
    """The institution branch matched `domainLock` against the same stored
    address: anyone who typed a campus address took a seat with the key."""
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "email": "a@university.edu"})
    store._data["users"] = {"dev-user": {
        "email": "a@university.edu", "emailVerified": False, "access_status": "APPROVED",
        "plan": "demo",
    }}
    minted = _mint_institution()
    license_id = minted["license"]["id"]

    r = await client.post("/v1/licenses/activate", json={"key": minted["key"]},
                          headers={"X-Device-Id": "dev-device"})

    assert r.status_code == 403
    assert r.json()["detail"] == "email_not_verified"
    assert not store._data.get(f"licenses/{license_id}/seats")
    assert "licenseId" not in store._data["users"]["dev-user"]


# ---------------- L10-4: `/v1/me` answers for the calling app ----------------
@pytest.mark.asyncio
async def test_me_license_is_the_calling_apps(client, store, monkeypatch):
    """`license` was Semper's whatever app asked, so Semper Classroom compared
    its own `/v1/config` with Semper's licence and fetched config twice."""
    # Licensed for Semper only, on an account split per app (ADR-033).
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "licenseId": "lic-semper",
                                            "licenseApp": "semper"})

    semper = (await client.get("/v1/me")).json()
    classroom = (await client.get("/v1/me", headers={"X-App-Id": "com.sempermechanics.semperclassroom"})).json()

    assert semper["license"]["mode"] == "licensed"
    assert classroom["license"]["mode"] == "demo"
    assert classroom["license"]["mode"] == classroom["licences"]["semperclassroom"]["mode"]
    assert classroom["licences"]["semper"]["mode"] == "licensed"
