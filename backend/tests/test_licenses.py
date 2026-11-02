"""License keys: format, demo issue at verification, activate locks, ops mint."""
from datetime import datetime, timedelta, timezone

import pytest

import fake_firestore

from app import deps
import repo_view as repo
from app.licenses import canonicalize, generate_key, key_hash, key_prefix
from license_helpers import (  # noqa: F401
    _mint_individual,
    _mint_institution,
    _recording_stubs,
    _signed_in,
)
from refusals import attempt


def test_canonicalize_strips_separators():
    assert canonicalize("semp-ab12-cd34") == canonicalize("SEMPAB12CD34")
    assert key_hash("SEMP-AAAA-BBBB-CCCC-DDDD") == key_hash("semp aaaa bbbb cccc dddd")


def test_generate_key_shape():
    key = generate_key()
    parts = key.split("-")
    assert parts[0] == "SEMP"
    assert len(parts) == 5
    assert all(len(p) == 4 for p in parts[1:])
    assert key_prefix(key).startswith("SEMP-")


def test_demo_license_issued_when_approved_verified_and_device_bound(store):
    user = {
        "uid": "u1",
        "email": "A@B.com",
        "emailVerified": True,
        "access_status": "APPROVED",
        "activeDeviceId": "and-12345678",
    }
    store._data["users"] = {"u1": dict(user)}
    out = repo.ensure_demo_license(user, "and-12345678")
    assert out["plan"] == "demo"
    assert out["licenseId"]
    again = repo.ensure_demo_license(out, "and-12345678")
    assert again["licenseId"] == out["licenseId"]
    assert len(store._data["licenses"]) == 1
    lic = next(iter(store._data["licenses"].values()))
    assert lic["emailLock"] == "a@b.com"
    assert lic["deviceIdLock"] == "and-12345678"
    assert lic["status"] == "redeemed"
    assert lic["plan"] == "demo"


def test_demo_license_skipped_until_approved(store):
    user = {
        "uid": "u1",
        "email": "a@b.com",
        "emailVerified": True,
        "access_status": "PENDING",
    }
    store._data["users"] = {"u1": dict(user)}
    out = repo.ensure_demo_license(user, "and-12345678")
    assert "licenseId" not in out
    assert store._data.get("licenses", {}) == {}


def test_demo_license_skipped_without_device(store):
    user = {
        "uid": "u1",
        "email": "a@b.com",
        "emailVerified": True,
        "access_status": "APPROVED",
    }
    store._data["users"] = {"u1": dict(user)}
    out = repo.ensure_demo_license(user, None)
    assert "licenseId" not in out


def test_new_verified_approved_user_with_device_gets_demo_key(store, monkeypatch):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", True)
    u = repo.get_or_create_user(
        {
            "sub": "new-provisional",
            "email": "x@corp.com",
            "email_verified": True,
            "firebase": {"sign_in_provider": "google.com"},
        },
        device_id="and-device99",
    )
    assert u["access_status"] == "APPROVED"
    assert u["licenseId"]
    assert u["plan"] == "demo"


def test_activate_professional_requires_email_and_device_lock(store):
    store._data["users"] = {
        "dev-user": {
            "email": "dev@local", "emailVerified": True, "access_status": "APPROVED", "plan": "demo",
        },
    }
    minted = repo.create_individual_license(
        email_lock="dev@local",
        device_id_lock="dev-device",
        created_by_uid="admin",
        max_analyses=40,
        expires_at=datetime.now(timezone.utc) + timedelta(days=30),
    )
    key = minted["key"]
    err, cfg = attempt(repo.activate_license, "dev-user", "other@x.com", "dev-device", key)
    assert err == "license_email_mismatch"
    err, cfg = attempt(repo.activate_license, "dev-user", "dev@local", "other-device", key)
    assert err == "license_device_mismatch"
    err, cfg = attempt(repo.activate_license, "dev-user", "dev@local", "dev-device", key)
    assert err == ""
    assert cfg["plan"] == "professional"
    assert cfg["cloudBackupEnabled"] is True
    assert cfg["maxSessions"] == 40
    # Same uid, same key is idempotent.
    err2, cfg2 = attempt(repo.activate_license, "dev-user", "dev@local", "dev-device", key)
    assert err2 == ""
    assert cfg2["plan"] == "professional"


def test_activate_unknown_key(store):
    store._data["users"] = {"dev-user": {"email": "dev@local", "emailVerified": True, "access_status": "APPROVED"}}
    err, cfg = attempt(repo.activate_license, "dev-user", "dev@local", "dev-device", "SEMP-ZZZZ-ZZZZ-ZZZZ-ZZZZ")
    assert err == "license_not_found"
    assert cfg is None


def test_revoke_professional_drops_user_to_demo(store):
    store._data["users"] = {
        "u1": {"email": "a@b.com", "emailVerified": True, "access_status": "APPROVED", "plan": "demo"},
    }
    minted = repo.create_individual_license(
        email_lock="a@b.com",
        device_id_lock="and-12345678",
        created_by_uid="admin",
    )
    err, _ = attempt(repo.activate_license, "u1", "a@b.com", "and-12345678", minted["key"])
    assert err == ""
    revoked = repo.revoke_license(minted["license"]["id"], "admin")
    assert revoked["status"] == "revoked"
    assert store._data["users"]["u1"]["plan"] == "demo"


@pytest.mark.asyncio
async def test_demo_records_an_analysis(client, monkeypatch):
    """Recording is open to demo. A demo account's frames and results are
    stored; what it cannot do is read them back (see the content and bundle
    tests). Installed builds that predate licensing retry a 403 from this
    route forever, so the gate must never come back here."""
    store = fake_firestore.install(monkeypatch)
    store._data["users"] = {"dev-user": {"email": "dev@test", "emailVerified": True, "access_status": "APPROVED"}}
    _recording_stubs(monkeypatch)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "mode": "demo", "plan": "demo"})
    resp = await client.post(
        "/v1/sessions",
        json={
            "specimen": "s",
            "files": [{"name": "Session.zip", "role": "bundle", "bytes": 10, "sha256": "a" * 64}],
        },
    )
    assert resp.status_code == 200
    assert resp.json()["sessionId"] in store._data["sessions"]


@pytest.mark.asyncio
async def test_demo_cannot_read_a_stored_file_back(client, monkeypatch):
    """The licensed half: retrieval. The file exists and belongs to the caller;
    a demo account is refused before any Drive read."""
    from app import drive

    store = fake_firestore.install(monkeypatch)
    store._data["files"] = {"f1": {"uid": "dev-user", "driveFileId": "d1", "name": "a.zip"}}
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "mode": "demo", "plan": "demo"})
    resp = await client.get("/v1/files/f1/content")
    assert resp.status_code == 403
    assert resp.json()["detail"].startswith("feature_not_licensed: ")


@pytest.mark.asyncio
async def test_activate_endpoint_happy_path(client, monkeypatch, audited):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {
            "email": "dev@local", "emailVerified": True, "access_status": "APPROVED", "plan": "demo",
        },
    }
    minted = repo.create_individual_license(
        email_lock="dev@local",
        device_id_lock="dev-device",
        created_by_uid="admin",
    )
    resp = await client.post(
        "/v1/licenses/activate",
        json={"key": minted["key"]},
        headers={"X-Device-Id": "dev-device"},
    )
    assert resp.status_code == 200
    assert resp.json()["config"]["plan"] == "professional"
    # The audit trail records the current vocabulary only; `plan` stays a
    # response mirror for old app builds, not something new rows carry (TD-45).
    (row,) = [r for r in audited if r["action"] == "LICENSE_ACTIVATE"]
    assert row["detail"] == {"mode": "licensed"}


@pytest.mark.asyncio
async def test_admin_mint_and_list(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    resp = await client.post(
        "/v1/admin/licenses",
        json={"emailLock": "pro@co.com", "deviceIdLock": "and-12345678"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["key"].startswith("SEMP-")
    assert body["license"]["emailLock"] == "pro@co.com"
    assert body["license"]["deviceIdLock"] == "and-12345678"
    # The stored document, not the dict that was written: the latter carries
    # the SERVER_TIMESTAMP sentinel, which no response can serialise.
    assert isinstance(body["license"]["createdAt"], str)
    listed = await client.get("/v1/admin/licenses")
    assert listed.status_code == 200
    assert listed.json()["page"]["count"] == 1
