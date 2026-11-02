"""Staff test accounts for QC and CI (ADR-027).

A test account is an ordinary account with `users/{uid}.testAccount`; every
branch that treats it differently asks `repo.test_account_of`. These tests
cover the staff routes (create, list, extend, password, end), the daily
expiry job, and each place a test account behaves differently from a real
one: several phones per app, no release hold or device mail, a licence not
locked to one phone, sessions out of the demo device count, its own Drive
tree, and `testAccount` on `/v1/config`. A real account beside it keeps
every rule it had.
"""
import logging
import re
import time
from datetime import datetime, timedelta, timezone

import pytest
from fastapi import HTTPException
from firebase_admin import auth as fb_auth

import fake_firestore
import repo_view as repo
from app import apps, audit, deps, drive, errors, google_auth, notify, rate_limit, session_provision
from app.config import settings
from app.models import DeviceReg
from app.observability import DependencyError
from app.services import account as account_service
from app.services import testaccounts as test_accounts
from key_helpers import _ec_pem

DOMAIN = "qa.sempermechanics.com"
QC_EMAIL = f"qc-1@{DOMAIN}"
CI_EMAIL = f"ci@{DOMAIN}"
MT = "semperclassroom"
_FILE = {"name": "Session.zip", "role": "bundle", "bytes": 10, "sha256": "a" * 64}


class FakeAuth:
    """The Firebase Auth project, as far as the Admin SDK calls go: users by
    uid and address, passwords, revoked tokens, deleted users."""

    def __init__(self):
        self.users: dict[str, dict] = {}
        self.revoked: list[str] = []
        self.deleted: list[str] = []
        self.seq = 0

    def create_user(self, **kw):
        if any(u["email"] == kw["email"] for u in self.users.values()):
            raise fb_auth.EmailAlreadyExistsError("taken", None, None)
        self.seq += 1
        uid = f"auth-uid-{self.seq}"
        self.users[uid] = dict(kw)

        class _Record:
            pass

        record = _Record()
        record.uid = uid
        return record

    def get_user_by_email(self, email, app=None):
        for uid, u in self.users.items():
            if u["email"] == email:
                return u
        raise fb_auth.UserNotFoundError("none")

    def update_user(self, uid, **kw):
        self.users[uid].update(kw)

    def revoke_refresh_tokens(self, uid, app=None):
        self.revoked.append(uid)

    def delete_user(self, uid, app=None):
        self.deleted.append(uid)
        self.users.pop(uid, None)


@pytest.fixture
def world(monkeypatch):
    """The fake store and Auth project, test accounts on for `DOMAIN`, the
    audit trail captured and Drive stubbed. DEV_INSECURE_AUTH stays on, so
    the staff routes run as the dev admin."""
    store = fake_firestore.install(monkeypatch)
    store._data["users"] = {}
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(settings, "TEST_ACCOUNT_DOMAINS", frozenset({DOMAIN}))
    fake = FakeAuth()
    for name in ("create_user", "get_user_by_email", "update_user",
                 "revoke_refresh_tokens", "delete_user"):
        monkeypatch.setattr(google_auth.fb_auth, name, getattr(fake, name))
    rows: list[dict] = []
    monkeypatch.setattr(audit, "record",
                        lambda uid=None, device_id=None, **kw: rows.append(
                            {"uid": uid, "deviceId": device_id, **kw}))
    deleted: list[str] = []
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(drive, "delete_file", lambda _t, fid: deleted.append(fid))
    monkeypatch.setattr(drive, "delete_files", lambda _t, ids: len([i for i in ids if i]))
    monkeypatch.setattr(drive, "find_user_folders", lambda _t, _uid, **k: [])
    monkeypatch.setattr(drive, "probe_files", lambda _t, ids: {i: drive.ALIVE for i in ids if i})
    return {"store": store, "auth": fake, "audit": rows, "drive_deleted": deleted}


async def _create(client, **body):
    return await client.post("/v1/admin/test-accounts",
                             json={"email": QC_EMAIL, "purpose": "qc", **body})


def _real_user(store, uid="real-1", email="person@lab.org", **extra):
    store._data["users"][uid] = {"email": email, "emailVerified": True,
                                 "access_status": "APPROVED", "role": "user", **extra}
    return {**store._data["users"][uid], "uid": uid}


def _test_user(store, uid="t-1", email=QC_EMAIL, max_devices=3, expires=None, **extra):
    tag = {"purpose": "qc", "createdByUid": "admin", "createdAt": datetime.now(timezone.utc),
           "expiresAt": expires, "maxDevices": max_devices, "note": ""}
    store._data["users"][uid] = {"email": email, "emailVerified": True,
                                 "access_status": "APPROVED", "role": "user",
                                 "testAccount": tag, **extra}
    return {**store._data["users"][uid], "uid": uid}


def _register(uid, device_id, app="semper"):
    return repo.register_device(uid, DeviceReg(deviceId=device_id, publicKeyPem=_ec_pem()), app)


def _days_from_now(value, days):
    assert abs((value - datetime.now(timezone.utc)) - timedelta(days=days)) < timedelta(minutes=1)


# ------------------------------------------------------------------ the helper


def test_the_helper_reads_the_tag_and_nothing_else():
    assert repo.test_account_of(None) is None
    assert repo.test_account_of({}) is None
    assert repo.test_account_of({"testAccount": True}) is None
    assert repo.test_account_of({"testAccount": {}}) is None
    assert repo.test_account_of({"testAccount": {"purpose": "qc"}}) == {"purpose": "qc"}
    assert repo.max_test_devices({}) == 3
    assert repo.max_test_devices({"maxDevices": "x"}) == 3
    assert repo.max_test_devices({"maxDevices": 0}) == 1
    assert repo.max_test_devices({"maxDevices": 50}) == 10


def test_the_domains_parse_like_the_other_address_lists():
    from app.config import parse_domains

    assert parse_domains("") == frozenset()
    assert parse_domains("QA.Example.com, @ci.example.com;  other.org") == {
        "qa.example.com", "ci.example.com", "other.org"}
    assert parse_domains(" , @ ;") == frozenset()


# ------------------------------------------------------------------ create


async def test_create_makes_an_approved_licensed_login_and_shows_the_password_once(
        client, world, caplog):
    caplog.set_level(logging.DEBUG)
    r = await _create(client, note="Pixel lab bench", maxDevices=4)

    assert r.status_code == 201, r.text
    assert r.headers["cache-control"] == "no-store"
    body = r.json()
    assert set(body) == {"uid", "email", "password", "purpose", "expiresAt", "maxDevices",
                         "licenseId", "licenseIds"}
    # A QC account holds one licence per app by default (ADR-033), Semper's first.
    assert len(body["licenseIds"]) == 2 and body["licenseIds"][0] == body["licenseId"]
    password, uid = body["password"], body["uid"]
    assert re.fullmatch(r"[A-Za-z0-9_-]{20}", password)
    assert body["email"] == QC_EMAIL and body["purpose"] == "qc" and body["maxDevices"] == 4

    auth_user = world["auth"].users[uid]
    assert auth_user == {"email": QC_EMAIL, "password": password, "email_verified": True}
    user = world["store"]._data["users"][uid]
    assert user["access_status"] == "APPROVED" and user["role"] == "user"
    assert user["emailVerified"] is True
    tag = user["testAccount"]
    assert tag["purpose"] == "qc" and tag["maxDevices"] == 4 and tag["createdByUid"] == "dev-user"
    assert tag["note"] == "Pixel lab bench"
    _days_from_now(tag["expiresAt"], 30)
    assert world["store"]._data["auth_links"][uid] == {"uid": uid}

    lic = world["store"]._data["licenses"][body["licenseId"]]
    assert lic["testAccount"] is True and lic["emailLock"] == QC_EMAIL
    assert lic["note"] == "Pixel lab bench"
    assert lic["expiresAt"] == tag["expiresAt"] and lic["graceDays"] == 0
    assert lic["redeemedByUid"] == uid
    assert user["licenseId"] == body["licenseId"] and user["mode"] == "licensed"

    created = [row for row in world["audit"] if row["action"] == "test_account_created"]
    assert len(created) == 1
    assert created[0]["uid"] == "dev-user"
    assert created[0]["target"] == {"type": "user", "id": uid}
    assert created[0]["detail"]["testAccount"] is True
    assert QC_EMAIL not in str(created[0])  # rows outlive erasure: no address
    # The password is in the answer and nowhere else.
    assert password not in str(world["audit"])
    assert password not in caplog.text


async def test_a_qc_account_takes_the_days_it_is_given(client, world):
    r = await _create(client, days=7)
    _days_from_now(datetime.fromisoformat(r.json()["expiresAt"]), 7)


async def test_a_ci_account_without_days_never_ends_and_holds_a_perpetual_licence(client, world):
    r = await client.post("/v1/admin/test-accounts", json={"email": CI_EMAIL, "purpose": "ci"})

    assert r.status_code == 201, r.text
    assert r.json()["expiresAt"] is None
    lic = world["store"]._data["licenses"][r.json()["licenseId"]]
    assert lic["duration"] == "perpetual" and "expiresAt" not in lic
    assert world["store"]._data["users"][r.json()["uid"]]["testAccount"]["expiresAt"] is None


async def test_a_ci_account_may_still_be_given_an_end(client, world):
    r = await client.post("/v1/admin/test-accounts",
                          json={"email": CI_EMAIL, "purpose": "ci", "days": 90})
    _days_from_now(datetime.fromisoformat(r.json()["expiresAt"]), 90)


@pytest.mark.parametrize("body", [
    {"days": 0}, {"days": 91}, {"maxDevices": 0}, {"maxDevices": 11},
    {"note": "x" * 201}, {"note": "bad\x07note"}, {"purpose": "demo"},
    {"email": "no-at-sign"}, {"email": "a b@qa.sempermechanics.com"}, {"extra": 1},
])
async def test_a_bad_body_is_refused(client, world, body):
    r = await _create(client, **body)
    assert r.status_code == 422, r.text
    assert world["auth"].users == {}


async def test_the_feature_is_off_until_domains_are_set(client, world, monkeypatch):
    monkeypatch.setattr(settings, "TEST_ACCOUNT_DOMAINS", frozenset())
    r = await _create(client)
    assert (r.status_code, r.json()["detail"]) == (403, "test_accounts_disabled")
    assert world["auth"].users == {}


async def test_an_address_outside_the_domains_is_refused(client, world):
    for email in ("customer@university.edu", f"x@sub.{DOMAIN}", f"x@{DOMAIN}.evil.com"):
        r = await _create(client, email=email)
        assert (r.status_code, r.json()["detail"]) == (403, "test_accounts_disabled"), email
    assert world["auth"].users == {}


async def test_a_staff_address_is_refused(client, world, monkeypatch):
    monkeypatch.setattr(settings, "ADMIN_EMAILS", {f"boss@{DOMAIN}"})
    r = await _create(client, email=f"Boss@{DOMAIN}")
    assert (r.status_code, r.json()["detail"]) == (409, "email_in_use")
    assert world["auth"].users == {}


async def test_an_address_with_an_account_is_refused(client, world):
    _real_user(world["store"], email=QC_EMAIL)
    r = await _create(client)
    assert (r.status_code, r.json()["detail"]) == (409, "email_in_use")


async def test_an_address_with_an_auth_user_is_refused(client, world):
    world["auth"].users["someone"] = {"email": QC_EMAIL}
    r = await _create(client)
    assert (r.status_code, r.json()["detail"]) == (409, "email_in_use")


async def test_an_address_promised_a_licence_is_refused(client, world):
    repo.create_individual_license(email_lock=QC_EMAIL, created_by_uid="admin")
    r = await _create(client)
    assert (r.status_code, r.json()["detail"]) == (409, "email_in_use")
    assert world["auth"].users == {}


async def test_an_auth_user_made_in_between_is_email_in_use(client, world, monkeypatch):
    """The Auth project is asked first and refuses the create anyway when
    the address was taken in between."""
    monkeypatch.setattr(google_auth, "auth_user_exists", lambda email: False)
    world["auth"].users["late"] = {"email": QC_EMAIL}
    r = await _create(client)
    assert (r.status_code, r.json()["detail"]) == (409, "email_in_use")


async def test_a_failed_mint_removes_the_account_and_its_auth_user(client, world, monkeypatch):
    def boom(**kw):
        raise RuntimeError("mint failed")

    repo.patch(monkeypatch, "create_individual_license", boom)
    with pytest.raises(RuntimeError):
        await _create(client)

    assert world["store"]._data["users"] == {}
    assert world["auth"].deleted == ["auth-uid-1"] and world["auth"].users == {}


async def test_a_licence_left_waiting_is_still_reported(client, world, monkeypatch, caplog):
    real = repo.create_individual_license

    def unattached(**kw):
        out = real(**kw)
        return {**out, "claimedByUid": "", "claimError": "claim_contended"}

    repo.patch(monkeypatch, "create_individual_license", unattached)
    caplog.set_level(logging.WARNING)
    r = await _create(client)
    assert r.status_code == 201
    assert "test_account_licence_not_attached" in caplog.text


# ------------------------------------------------------------------ step-up


@pytest.fixture
def browser_staff(world, monkeypatch):
    """A staff browser session: DEV_INSECURE_AUTH off, an admin address, and
    the token's second factor and sign-in age set per test."""
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "ADMIN_EMAILS", {"staff@sempermechanics.com"})
    claims = {"sub": "staff-1", "email": "staff@sempermechanics.com", "email_verified": True,
              "firebase": {"sign_in_provider": "password"}}
    monkeypatch.setattr(deps, "verify_id_token", lambda _t: dict(claims))
    return claims


async def test_create_takes_the_fresh_staff_step_up(client, world, browser_staff):
    headers = {"Authorization": "Bearer t"}
    body = {"email": QC_EMAIL, "purpose": "qc"}

    no_factor = await client.post("/v1/admin/test-accounts", json=body, headers=headers)
    assert (no_factor.status_code, no_factor.json()["detail"]) == (403, "mfa_required")

    browser_staff["firebase"]["sign_in_second_factor"] = "totp"
    browser_staff["auth_time"] = time.time() - settings.ADMIN_WEB_REVOKE_REAUTH_SECONDS - 30
    stale = await client.post("/v1/admin/test-accounts", json=body, headers=headers)
    assert (stale.status_code, stale.json()["detail"]) == (403, "reauth_required")

    browser_staff["auth_time"] = time.time()
    fresh = await client.post("/v1/admin/test-accounts", json=body, headers=headers)
    assert fresh.status_code == 201, fresh.text
    assert world["store"]._data["users"][fresh.json()["uid"]]["testAccount"]["createdByUid"] == "staff-1"
    assert world["auth"].users == {fresh.json()["uid"]: {
        "email": QC_EMAIL, "password": fresh.json()["password"], "email_verified": True}}


async def test_a_holder_is_not_staff(client, world, browser_staff):
    _real_user(world["store"], uid="u-9", email="person@lab.org")
    browser_staff.update(sub="u-9", email="person@lab.org")
    r = await client.get("/v1/admin/test-accounts", headers={"Authorization": "Bearer t"})
    assert (r.status_code, r.json()["detail"]) == (403, "not_admin")


# ------------------------------------------------------------------ list


async def test_the_list_shows_test_accounts_only(client, world):
    store = world["store"]
    _real_user(store)
    created = (await _create(client, maxDevices=5)).json()
    _test_user(store, "t-old", f"old@{DOMAIN}",
               expires=datetime.now(timezone.utc) - timedelta(hours=1))
    _test_user(store, "t-off", f"off@{DOMAIN}", access_status="SUSPENDED")
    _register(created["uid"], "and-phone-1")
    _register(created["uid"], "and-phone-2")
    _register(created["uid"], "and-phone-mt", MT)

    r = await client.get("/v1/admin/test-accounts")

    assert r.status_code == 200, r.text
    rows = {row["uid"]: row for row in r.json()["accounts"]}
    assert set(rows) == {created["uid"], "t-old", "t-off"}
    row = rows[created["uid"]]
    assert set(row) == {"uid", "email", "purpose", "expiresAt", "maxDevices", "note",
                        "createdAt", "activeDevices", "devices", "status"}
    assert (row["email"], row["purpose"], row["maxDevices"]) == (QC_EMAIL, "qc", 5)
    assert row["activeDevices"] == 3 and row["status"] == "active"
    # Each ACTIVE phone, every app, oldest first, for the console's release.
    assert [(d["deviceId"], d["app"]) for d in row["devices"]] == [
        ("and-phone-1", "semper"), ("and-phone-2", "semper"), ("and-phone-mt", MT)]
    assert set(row["devices"][0]) == {"deviceId", "app", "model", "registeredAt"}
    assert rows["t-old"]["devices"] == [] and rows["t-old"]["activeDevices"] == 0
    assert rows["t-old"]["status"] == "expired"
    assert rows["t-off"]["status"] == "suspended"
    assert r.json()["accounts"][0]["uid"] in {"t-old", "t-off"}  # newest first


# ------------------------------------------------------------------ extend


async def test_extend_moves_the_account_and_its_licence(client, world):
    created = (await _create(client, days=3)).json()

    r = await client.post(f"/v1/admin/test-accounts/{created['uid']}/extend", json={"days": 60})

    assert r.status_code == 200, r.text
    assert r.json()["uid"] == created["uid"] and r.json()["status"] == "active"
    store = world["store"]._data
    expires = store["users"][created["uid"]]["testAccount"]["expiresAt"]
    _days_from_now(expires, 60)
    assert store["licenses"][created["licenseId"]]["expiresAt"] == expires
    # The holder's mirror follows the licence.
    assert store["users"][created["uid"]]["licenseExpiresAt"] == expires
    row = [a for a in world["audit"] if a["action"] == "test_account_extended"]
    assert row and row[0]["detail"]["testAccount"] is True


async def test_extend_gives_a_ci_account_with_no_end_an_end(client, world):
    created = (await client.post("/v1/admin/test-accounts",
                                 json={"email": CI_EMAIL, "purpose": "ci"})).json()
    r = await client.post(f"/v1/admin/test-accounts/{created['uid']}/extend", json={"days": 10})
    assert r.status_code == 200, r.text
    lic = world["store"]._data["licenses"][created["licenseId"]]
    _days_from_now(lic["expiresAt"], 10)
    assert lic["duration"] == "timed" and lic["graceDays"] == 0


async def test_extend_is_capped_at_90_days(client, world):
    created = (await _create(client)).json()
    r = await client.post(f"/v1/admin/test-accounts/{created['uid']}/extend", json={"days": 91})
    assert r.status_code == 422
    assert test_accounts.MAX_DAYS == 90


async def test_the_routes_never_touch_a_real_account(client, world):
    _real_user(world["store"], uid="real-1")
    before = dict(world["store"]._data["users"]["real-1"])
    for call in (client.post("/v1/admin/test-accounts/real-1/extend", json={"days": 5}),
                 client.post("/v1/admin/test-accounts/real-1/password"),
                 client.post("/v1/admin/test-accounts/real-1/devices/and-phone-1/release"),
                 client.delete("/v1/admin/test-accounts/real-1"),
                 client.delete("/v1/admin/test-accounts/nobody")):
        r = await call
        assert (r.status_code, r.json()["detail"]) == (404, "user_not_found")
    assert world["store"]._data["users"]["real-1"] == before
    assert world["auth"].revoked == [] and world["auth"].deleted == []


# ------------------------------------------------------------------ password


async def test_a_new_password_replaces_the_old_and_revokes_tokens(client, world, caplog):
    caplog.set_level(logging.DEBUG)
    created = (await _create(client)).json()

    r = await client.post(f"/v1/admin/test-accounts/{created['uid']}/password")

    assert r.status_code == 200, r.text
    assert r.headers["cache-control"] == "no-store"
    password = r.json()["password"]
    assert set(r.json()) == {"password"}
    assert re.fullmatch(r"[A-Za-z0-9_-]{20}", password) and password != created["password"]
    assert world["auth"].users[created["uid"]]["password"] == password
    assert world["auth"].revoked == [created["uid"]]
    row = [a for a in world["audit"] if a["action"] == "test_account_password_reset"][0]
    assert row["detail"] == {"testAccount": True, "sessionsRevoked": True, "authUsers": 1}
    assert password not in str(world["audit"]) and password not in caplog.text


# ------------------------------------------------------------------ release one phone (TD-314)


def _release(client, uid, device_id):
    return client.post(f"/v1/admin/test-accounts/{uid}/devices/{device_id}/release")


async def test_releasing_a_phone_of_a_full_qc_account_frees_its_place(client, world):
    store = world["store"]._data
    _test_user(world["store"], "t-1", max_devices=2)
    _register("t-1", "and-phone-1")
    _register("t-1", "and-phone-2")
    with pytest.raises(errors.Refusal):
        _register("t-1", "and-phone-3")
    store.setdefault("sessions", {})["s1"] = {"uid": "t-1", "testDeviceId": "and-phone-1",
                                               "testAccount": True, "status": "COMPLETED"}
    sessions_before = dict(store["sessions"]["s1"])

    r = await _release(client, "t-1", "and-phone-1")

    assert r.status_code == 200, r.text
    row = r.json()
    assert row["uid"] == "t-1" and row["activeDevices"] == 1
    assert [d["deviceId"] for d in row["devices"]] == ["and-phone-2"]
    released = store["devices"]["and-phone-1"]
    assert released["status"] == "REVOKED" and released["staffReleased"] is True
    assert released["revokedAt"] is not None
    user = store["users"]["t-1"]
    # The latest phone keeps the binding; no release hold for a test account.
    assert user["activeDeviceId"] == "and-phone-2"
    assert not {"releasedDeviceId", "releasedAt"} & set(user)
    assert store["sessions"]["s1"] == sessions_before
    rows = [a for a in world["audit"] if a["action"] == "test_account_device_released"]
    assert [(a["uid"], a["target"], a["detail"]) for a in rows] == [
        ("dev-user", {"type": "user", "id": "t-1"},
         {"testAccount": True, "deviceId": "and-phone-1", "app": "semper"})]

    _register("t-1", "and-phone-3")
    assert store["devices"]["and-phone-3"]["status"] == "ACTIVE"


async def test_releasing_the_bound_phone_drops_only_that_apps_binding(client, world):
    store = world["store"]._data
    _test_user(world["store"], "t-1")
    _register("t-1", "and-phone-1")
    _register("t-1", "and-phone-mt", MT)

    r = await _release(client, "t-1", "and-phone-1")

    assert r.status_code == 200, r.text
    user = store["users"]["t-1"]
    assert "activeDeviceId" not in user
    assert user["activeDeviceIdSemperClassroom"] == "and-phone-mt"
    assert not {"releasedDeviceId", "releasedAt"} & set(user)
    assert store["devices"]["and-phone-mt"]["status"] == "ACTIVE"


async def test_the_released_phone_cannot_take_its_place_back(client, world, monkeypatch):
    store = world["store"]
    tag = _test_user(store, "t-1", max_devices=2)["testAccount"]
    _register("t-1", "and-phone-1")
    _register("t-1", "and-phone-2")
    assert (await _release(client, "t-1", "and-phone-1")).status_code == 200

    # Room for one, but not for the phone that gave its place up.
    _as(store, monkeypatch, "t-1", testAccount=tag)
    back = await client.post("/v1/devices/register",
                             json={"deviceId": "and-phone-1", "publicKeyPem": _ec_pem()})
    assert (back.status_code, back.json()["detail"]) == (409, "device_released")
    denied = [a for a in world["audit"] if a.get("outcome") == "DENIED"]
    assert [(a["deviceId"], a["action"], a["detail"]) for a in denied] == [
        ("and-phone-1", "DEVICE_REGISTER",
         {"reason": "staff_released", "app": "semper", "testAccount": True})]

    _as(store, monkeypatch, "t-1")
    new = await client.post("/v1/devices/register",
                            json={"deviceId": "and-phone-3", "publicKeyPem": _ec_pem()})
    assert new.status_code == 201, new.text
    _as(store, monkeypatch, "t-1")
    again = await client.post("/v1/devices/register",
                              json={"deviceId": "and-phone-1", "publicKeyPem": _ec_pem()})
    assert again.status_code == 409
    devices = store._data["devices"]
    assert devices["and-phone-3"]["status"] == "ACTIVE"
    assert devices["and-phone-1"]["status"] == "REVOKED"
    assert store._data["users"]["t-1"]["activeDeviceId"] == "and-phone-3"


async def test_another_account_may_still_register_a_released_phone(client, world):
    _test_user(world["store"], "t-1")
    _register("t-1", "and-phone-1")
    assert (await _release(client, "t-1", "and-phone-1")).status_code == 200

    _real_user(world["store"], uid="real-1")
    _register("real-1", "and-phone-1")
    device = world["store"]._data["devices"]["and-phone-1"]
    assert device["uid"] == "real-1" and device["status"] == "ACTIVE"
    assert "staffReleased" not in device


def test_the_stamp_binds_only_a_test_account_and_a_ci_rotation_has_none(world):
    _real_user(world["store"], uid="real-1")
    _register("real-1", "and-phone-1")
    world["store"]._data["devices"]["and-phone-1"].update(status="REVOKED", staffReleased=True)
    _register("real-1", "and-phone-1")  # A real account is unchanged.

    _test_user(world["store"], "t-ci", email=CI_EMAIL, max_devices=1)
    world["store"]._data["users"]["t-ci"]["testAccount"]["purpose"] = "ci"
    _register("t-ci", "and-ci-1")
    assert _register("t-ci", "and-ci-2")["rotated"] == ["and-ci-1"]
    rotated = world["store"]._data["devices"]["and-ci-1"]
    assert not repo.staff_released_from(rotated, "t-ci")
    assert _register("t-ci", "and-ci-1")["rotated"] == ["and-ci-2"]
    assert not repo.staff_released_from(None, "t-ci")


async def test_release_refuses_a_phone_that_is_not_an_active_one_of_the_account(client, world):
    store = world["store"]._data
    _test_user(world["store"], "t-1")
    _test_user(world["store"], "t-2", email=f"qc-2@{DOMAIN}")
    _register("t-1", "and-phone-1")
    _register("t-1", "and-phone-old")
    store["devices"]["and-phone-old"]["status"] = "SUPERSEDED"
    _register("t-2", "and-phone-2")
    before = {k: dict(v) for k, v in store["devices"].items()}
    users_before = {k: dict(v) for k, v in store["users"].items()}

    for device in ("and-phone-2", "and-phone-old", "and-phone-none"):
        r = await _release(client, "t-1", device)
        assert (r.status_code, r.json()["detail"]) == (404, "device_not_found")
    r = await _release(client, "nobody", "and-phone-1")
    assert (r.status_code, r.json()["detail"]) == (404, "user_not_found")

    assert store["devices"] == before and store["users"] == users_before
    assert not [a for a in world["audit"] if a["action"] == "test_account_device_released"]


def test_the_repo_release_rechecks_inside_its_transaction(world):
    _test_user(world["store"], "t-1")
    _register("t-1", "and-phone-1")
    repo.release_test_device("t-1", "and-phone-1")
    with pytest.raises(errors.Refusal) as refused:
        repo.release_test_device("t-1", "and-phone-1")
    assert refused.value.code == errors.DEVICE_NOT_FOUND


async def test_release_takes_the_fresh_staff_step_up(client, world, browser_staff):
    _test_user(world["store"], "t-1")
    _register("t-1", "and-phone-1")
    headers = {"Authorization": "Bearer t"}
    path = "/v1/admin/test-accounts/t-1/devices/and-phone-1/release"

    no_factor = await client.post(path, headers=headers)
    assert (no_factor.status_code, no_factor.json()["detail"]) == (403, "mfa_required")
    browser_staff["firebase"]["sign_in_second_factor"] = "totp"
    browser_staff["auth_time"] = time.time() - settings.ADMIN_WEB_REVOKE_REAUTH_SECONDS - 30
    stale = await client.post(path, headers=headers)
    assert (stale.status_code, stale.json()["detail"]) == (403, "reauth_required")
    assert world["store"]._data["devices"]["and-phone-1"]["status"] == "ACTIVE"

    browser_staff["auth_time"] = time.time()
    fresh = await client.post(path, headers=headers)
    assert fresh.status_code == 200, fresh.text
    row = [a for a in world["audit"] if a["action"] == "test_account_device_released"][0]
    assert row["uid"] == "staff-1"


async def test_a_released_phone_is_not_licensed_by_config_while_a_new_one_is(
        client, world, monkeypatch):
    # A CI account: its licence binds no phone (a QC one runs activations).
    created = (await _create(client, purpose="ci", maxDevices=2)).json()
    uid = created["uid"]
    _register(uid, "and-phone-1")
    _register(uid, "and-phone-2")
    assert (await _release(client, uid, "and-phone-1")).status_code == 200
    # The account's own token, so `current_user` checks the licence lock
    # against the phone's id (the dev user skips it); App Check passes.
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(deps, "verify_id_token", lambda _t: {
        "sub": uid, "email": QC_EMAIL, "email_verified": True,
        "firebase": {"sign_in_provider": "password"}})
    monkeypatch.setattr(deps, "_require_app_check", lambda *a, **k: None)

    async def mode(device):
        r = await client.get("/v1/config", headers={"Authorization": "Bearer t",
                                                    "X-Device-Id": device})
        assert r.status_code == 200, r.text
        return r.json()["mode"]

    # Room for one more, but not for the phone that gave its place up.
    assert await mode("and-phone-1") == "demo"
    assert await mode("and-phone-new") == "licensed"
    assert await mode("and-phone-2") == "licensed"
    # Only a Demo answer for that request: the account stays licensed.
    assert world["store"]._data["users"][uid]["mode"] == "licensed"


# ------------------------------------------------------------------ end


async def test_end_erases_the_account_as_staff(client, world, monkeypatch):
    created = (await _create(client)).json()
    uid = created["uid"]
    store = world["store"]._data
    store["users"][uid]["driveFolderId"] = "drive-user-folder"
    _register(uid, "and-phone-1")
    store.setdefault("sessions", {})["s1"] = {"uid": uid, "status": "COMPLETED"}

    r = await client.delete(f"/v1/admin/test-accounts/{uid}")

    assert r.status_code == 204, r.text
    assert r.content == b""
    assert uid not in store["users"]
    assert store["sessions"] == {} and store.get("devices", {}) == {}
    assert world["drive_deleted"] == ["drive-user-folder"]
    assert world["auth"].revoked == [uid] and world["auth"].deleted == [uid]
    for license_id in created["licenseIds"]:
        assert store["licenses"][license_id]["status"] == "revoked"
    # Not handed back to the address, as an erased holder's licence would be.
    assert QC_EMAIL not in str(store.get("licenseInvites", {}))
    row = [a for a in world["audit"] if a["action"] == "test_account_ended"][0]
    assert row["uid"] == "dev-user" and row["target"] == {"type": "user", "id": uid}
    assert row["detail"]["testAccount"] is True and row["detail"]["authUserDeleted"] is True
    assert row["detail"]["licenseIds"] == created["licenseIds"]
    # The holder's own erasure row is not written: staff ended it.
    assert not [a for a in world["audit"] if a["action"] == "ACCOUNT_DELETE"]


async def test_end_finds_a_licence_still_waiting_at_the_address(client, world):
    """A mint whose attach lost a race leaves the licence promised to the
    address; ending the account revokes it too."""
    user = _test_user(world["store"], "t-9")
    minted = repo.create_individual_license(email_lock="other@x.org", created_by_uid="admin",
                                            test_account=True)
    waiting = repo.create_individual_license(email_lock=QC_EMAIL, created_by_uid="admin",
                                             test_account=True)
    assert repo.licences_of_test_account(repo.get_user("t-9")) == [waiting["license"]["id"]]
    assert minted["license"]["id"] not in repo.licences_of_test_account(user)

    r = await client.delete("/v1/admin/test-accounts/t-9")
    assert r.status_code == 204
    assert world["store"]._data["licenses"][waiting["license"]["id"]]["status"] == "revoked"


async def test_a_real_licence_is_never_revoked_by_an_end(client, world):
    real = repo.create_individual_license(email_lock="buyer@lab.org", created_by_uid="admin")
    _test_user(world["store"], "t-2", licenseId=real["license"]["id"])

    assert repo.licences_of_test_account(repo.get_user("t-2")) == []
    r = await client.delete("/v1/admin/test-accounts/t-2")
    assert r.status_code == 204
    assert world["store"]._data["licenses"][real["license"]["id"]]["status"] != "revoked"


def test_the_holders_own_erasure_is_unchanged(world):
    """`erase_account` still writes ACCOUNT_DELETE with what `erase_data` erased."""
    _real_user(world["store"], uid="real-1")
    detail = account_service.erase_account({"uid": "real-1"}, "dev-1")
    row = world["audit"][-1]
    assert row["action"] == "ACCOUNT_DELETE" and row["uid"] == "real-1"
    assert row["deviceId"] == "dev-1" and row["detail"] == detail


def test_a_test_accounts_drive_fallback_looks_under_its_own_tree(world, monkeypatch):
    looked = []
    monkeypatch.setattr(drive, "find_user_folders",
                        lambda _t, uid, **k: looked.append((uid, k)) or ["found"])
    user = _test_user(world["store"], "t-3")
    account_service.erase_data(user)
    _real_user(world["store"], uid="real-1")
    account_service.erase_data({"uid": "real-1"})
    assert looked == [("t-3", {"test": True}), ("real-1", {})]


# ------------------------------------------------------------------ expiry


async def test_the_expiry_job_ends_only_accounts_past_their_date(client, world):
    store = world["store"]
    past = datetime.now(timezone.utc) - timedelta(minutes=5)
    _test_user(store, "t-due", f"due@{DOMAIN}", expires=past)
    _test_user(store, "t-later", f"later@{DOMAIN}",
               expires=datetime.now(timezone.utc) + timedelta(days=2))
    _test_user(store, "t-ci", CI_EMAIL, expires=None)
    _real_user(store, uid="real-1", licenseExpiresAt=past)

    r = await client.post("/v1/tasks/test-account-expiry")

    assert r.status_code == 200, r.text
    assert r.json() == {"expiredTestAccounts": 1, "ended": ["t-due"], "failed": []}
    assert set(store._data["users"]) == {"t-later", "t-ci", "real-1"}
    row = [a for a in world["audit"] if a["action"] == "test_account_expired"]
    assert len(row) == 1
    assert row[0]["uid"] == "system" and row[0]["target"] == {"type": "user", "id": "t-due"}
    assert row[0]["detail"]["testAccount"] is True


async def test_one_failed_expiry_does_not_stop_the_rest(client, world, monkeypatch, caplog):
    store = world["store"]
    past = datetime.now(timezone.utc) - timedelta(minutes=5)
    _test_user(store, "t-a", f"a@{DOMAIN}", expires=past)
    _test_user(store, "t-b", f"b@{DOMAIN}", expires=past)
    real_erase = account_service.erase_data

    def flaky(user):
        if user["uid"] == "t-a":
            raise RuntimeError("drive down")
        return real_erase(user)

    monkeypatch.setattr(account_service, "erase_data", flaky)
    caplog.set_level(logging.ERROR)

    report = (await client.post("/v1/tasks/test-account-expiry")).json()

    assert report == {"expiredTestAccounts": 2, "ended": ["t-b"], "failed": ["t-a"]}
    assert "test_account_expiry_failed" in caplog.text
    # Still there, suspended, and due again tomorrow.
    assert store._data["users"]["t-a"]["access_status"] == "SUSPENDED"
    assert [u["uid"] for u in repo.expired_test_accounts()] == ["t-a"]


async def test_the_expiry_job_takes_the_task_invoker_only(client, world, monkeypatch):
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    r = await client.post("/v1/tasks/test-account-expiry",
                          headers={"Authorization": "Bearer user-id-token"})
    assert r.status_code == 401
    assert r.json()["detail"] == errors.INVALID_TASK_TOKEN


# ------------------------------------------------------------------ devices


def test_a_test_account_registers_up_to_its_device_cap_per_app(world):
    _test_user(world["store"], "t-1", max_devices=3)
    for device in ("and-phone-1", "and-phone-2", "and-phone-3"):
        _register("t-1", device)

    with pytest.raises(errors.Refusal) as refused:
        _register("t-1", "and-phone-4")
    assert refused.value.code == errors.DEVICE_CONFLICT
    # A phone already on it re-registers (a reinstall), cap or not.
    _register("t-1", "and-phone-2")
    # Each app has its own cap.
    _register("t-1", "and-phone-mt-1", MT)
    devices = world["store"]._data["devices"]
    assert sorted(d for d, v in devices.items() if v["status"] == "ACTIVE") == [
        "and-phone-1", "and-phone-2", "and-phone-3", "and-phone-mt-1"]
    assert world["store"]._data["users"]["t-1"]["activeDeviceId"] == "and-phone-2"


def test_a_full_ci_account_retires_its_oldest_phone_for_a_new_one(world):
    _test_user(world["store"], "t-ci", max_devices=2)
    world["store"]._data["users"]["t-ci"]["testAccount"]["purpose"] = "ci"
    first, second = _register("t-ci", "and-ci-1"), _register("t-ci", "and-ci-2")
    assert first["rotated"] == [] and second["rotated"] == []

    third = _register("t-ci", "and-ci-3")
    assert third["rotated"] == ["and-ci-1"]
    devices = world["store"]._data["devices"]
    assert devices["and-ci-1"]["status"] == "SUPERSEDED"
    assert sorted(d for d, v in devices.items() if v["status"] == "ACTIVE") == ["and-ci-2", "and-ci-3"]
    assert world["store"]._data["users"]["t-ci"]["activeDeviceId"] == "and-ci-3"
    # A phone already on it re-registers without giving one up.
    assert _register("t-ci", "and-ci-2")["rotated"] == []


def test_a_ci_rotation_never_touches_another_app_or_account(world):
    _test_user(world["store"], "t-ci", max_devices=1)
    world["store"]._data["users"]["t-ci"]["testAccount"]["purpose"] = "ci"
    _register("t-ci", "and-ci-mt", MT)
    _test_user(world["store"], "t-qc", email="qc2@qa.example.com", max_devices=1)
    _register("t-qc", "and-qc-1")
    _register("t-ci", "and-ci-1")
    assert _register("t-ci", "and-ci-2")["rotated"] == ["and-ci-1"]
    devices = world["store"]._data["devices"]
    assert devices["and-ci-mt"]["status"] == "ACTIVE" and devices["and-qc-1"]["status"] == "ACTIVE"


async def test_the_register_route_audits_a_ci_rotation(client, world, monkeypatch):
    store = world["store"]
    tag = {**_test_user(store, "t-ci", email=CI_EMAIL, max_devices=1)["testAccount"], "purpose": "ci"}
    _as(store, monkeypatch, "t-ci", testAccount=tag)
    _register("t-ci", "and-ci-old")
    _as(store, monkeypatch, "t-ci")

    r = await client.post("/v1/devices/register",
                          json={"deviceId": "and-ci-new", "publicKeyPem": _ec_pem()})
    assert r.status_code == 201, r.text
    rotated = [row for row in world["audit"] if row.get("action") == "DEVICE_ROTATED"]
    assert [(row["uid"], row["deviceId"], row["detail"]) for row in rotated] == [
        ("t-ci", "and-ci-old", {"app": "semper", "by": "and-ci-new", "testAccount": True})]
    assert store._data["devices"]["and-ci-old"]["status"] == "SUPERSEDED"


def test_a_retired_device_frees_its_place(world):
    _test_user(world["store"], "t-1", max_devices=1)
    _register("t-1", "and-phone-1")
    world["store"]._data["devices"]["and-phone-1"]["status"] = "SUPERSEDED"
    _register("t-1", "and-phone-2")


def test_a_real_account_holds_a_second_phone_too(world):
    """Real accounts hold several phones since ADR-033, as test accounts
    already did; their cap is `MAX_PHONES_PER_APP`, not `maxDevices`."""
    _real_user(world["store"], uid="real-1")
    _register("real-1", "and-phone-1")
    _register("real-1", "and-phone-2")
    devices = world["store"]._data["devices"]
    assert devices["and-phone-1"]["status"] == devices["and-phone-2"]["status"] == "ACTIVE"


def test_another_accounts_phone_is_still_refused(world):
    _real_user(world["store"], uid="real-1")
    _register("real-1", "and-phone-1")
    _test_user(world["store"], "t-1")
    with pytest.raises(errors.Refusal) as refused:
        _register("t-1", "and-phone-1")
    assert refused.value.code == errors.DEVICE_IN_USE


def test_any_active_device_signs_for_test_and_real_accounts(world):
    user = {**_test_user(world["store"], "t-1"), "activeDeviceId": "and-phone-2"}
    dev = {"uid": "t-1", "status": "ACTIVE", "app": "semper"}
    assert deps._device_is_bound(dev, user, "and-phone-1", "semper")
    assert not deps._device_is_bound({**dev, "status": "SUPERSEDED"}, user, "and-phone-1", "semper")
    assert not deps._device_is_bound({**dev, "uid": "other"}, user, "and-phone-1", "semper")

    real = {"uid": "real-1", "activeDeviceId": "and-phone-2"}
    assert deps._device_is_bound({**dev, "uid": "real-1"}, real, "and-phone-1", "semper")
    assert deps._device_is_bound({**dev, "uid": "real-1"}, real, "and-phone-2", "semper")


async def test_verified_device_accepts_a_second_test_phone(world, monkeypatch):
    import base64
    import hashlib

    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from starlette.requests import Request

    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    _test_user(world["store"], "t-1")
    _register("t-1", "and-phone-1")
    repo.register_device("t-1", DeviceReg(deviceId="and-phone-2", publicKeyPem=pem))
    _register("t-1", "and-phone-3")  # the latest: `activeDeviceId`
    repo.patch(monkeypatch, "consume_nonce", lambda *a: True)

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    request = Request({"type": "http", "method": "POST", "path": "/v1/sessions",
                       "headers": [], "query_string": b""}, receive)
    sig = base64.b64encode(priv.sign(b"n1POST/v1/sessions" + hashlib.sha256(b"").digest(),
                                     ec.ECDSA(hashes.SHA256()))).decode()
    user = repo.get_user("t-1")
    assert user["activeDeviceId"] == "and-phone-3"

    ctx = await deps.verified_device(request, user, "and-phone-2", "n1", sig)
    assert ctx.device_id == "and-phone-2"

    world["store"]._data["devices"]["and-phone-2"]["status"] = "SUPERSEDED"
    with pytest.raises(HTTPException) as refused:
        await deps.verified_device(request, user, "and-phone-2", "n1", sig)
    assert refused.value.detail == errors.DEVICE_NOT_ACTIVE


def _as(store, monkeypatch, uid, **user):
    store._data["users"][uid] = {**store._data["users"].get(uid, {}), **user}
    monkeypatch.setattr(deps, "_DEV_USER", {**store._data["users"][uid], "uid": uid})


async def test_registration_has_no_hold_and_no_mail_for_a_test_account(
        client, world, monkeypatch):
    mailed = []
    monkeypatch.setattr(notify, "device_registered", lambda *a, **k: mailed.append(a))
    store = world["store"]
    tag = _test_user(store, "t-1")["testAccount"]
    # The phone a clear released a minute ago, which a real account holds off.
    _as(store, monkeypatch, "t-1", testAccount=tag, releasedDeviceId="and-phone-1",
        releasedAt=datetime.now(timezone.utc))

    r = await client.post("/v1/devices/register",
                          json={"deviceId": "and-phone-1", "publicKeyPem": _ec_pem()})
    assert r.status_code == 201, r.text
    _as(store, monkeypatch, "t-1")
    r = await client.post("/v1/devices/register",
                          json={"deviceId": "and-phone-2", "publicKeyPem": _ec_pem()})
    assert r.status_code == 201, r.text

    assert mailed == []
    rows = [a for a in world["audit"] if a["action"] == "DEVICE_REGISTER"]
    assert [a["detail"]["testAccount"] for a in rows] == [True, True]


async def test_a_real_account_keeps_its_hold_and_its_mail(client, world, monkeypatch):
    mailed = []
    monkeypatch.setattr(notify, "device_registered", lambda *a, **k: mailed.append(a))
    store = world["store"]
    _as(store, monkeypatch, "real-1", email="person@lab.org", emailVerified=True,
        access_status="APPROVED", role="user", releasedDeviceId="and-phone-1",
        releasedAt=datetime.now(timezone.utc))

    held = await client.post("/v1/devices/register",
                             json={"deviceId": "and-phone-1", "publicKeyPem": _ec_pem()})
    assert (held.status_code, held.json()["detail"]) == (409, "device_conflict")
    ok = await client.post("/v1/devices/register",
                           json={"deviceId": "and-phone-2", "publicKeyPem": _ec_pem()})
    assert ok.status_code == 201
    assert len(mailed) == 1
    assert "testAccount" not in [a for a in world["audit"] if a["action"] == "DEVICE_REGISTER"][0][
        "detail"]


# ------------------------------------------------------------------ licence lock


async def test_the_licence_lock_allows_the_device_cap(client, world):
    """A CI account's licence; a QC account's runs activations instead."""
    created = (await _create(client, purpose="ci", maxDevices=2)).json()
    uid = created["uid"]

    def mode(device):
        return repo.revalidate_device_lock(repo.get_user(uid), device)["mode"]

    # Not registered yet, with room: licensed, as an unbound lock would be.
    assert mode("and-phone-new") == "licensed"
    _register(uid, "and-phone-1")
    _register(uid, "and-phone-2")
    assert mode("and-phone-1") == "licensed" and mode("and-phone-2") == "licensed"
    # Full: a third phone runs as Demo, for its own requests only.
    assert mode("and-phone-3") == "demo"
    assert world["store"]._data["users"][uid]["mode"] == "licensed"
    # The other app has its own room; a phone of one app is not the other's.
    assert repo.revalidate_device_lock(repo.get_user(uid), "and-phone-mt", MT)["mode"] == "licensed"
    lic = world["store"]._data["licenses"][created["licenseId"]]
    assert lic["deviceIdLock"] == "" and not lic.get("deviceIdLockSemperClassroom")
    assert not lic.get("deviceIdLockMaterialTesting")


async def test_a_revoked_test_licence_is_still_demo(client, world):
    created = (await _create(client)).json()
    _register(created["uid"], "and-phone-1")
    repo.revoke_license(created["licenseId"], "admin")
    user = repo.get_user(created["uid"])
    assert repo.revalidate_device_lock({**user, "mode": "licensed"}, "and-phone-1")["mode"] == "demo"


# ------------------------------------------------------------------ demo count


def test_the_demo_device_count_ignores_test_sessions(world):
    from app.models import SessionCreate

    body = SessionCreate(specimen="s", files=[_FILE])
    test_user = _test_user(world["store"], "t-1")
    real = _real_user(world["store"], uid="real-1")

    test_session = repo.create_session("s-test", test_user, {"deviceId": "and-phone-1"}, body)
    assert repo.count_device_sessions("and-phone-1") == 0
    assert test_session["testAccount"] is True and test_session["testDeviceId"] == "and-phone-1"
    assert "deviceId" not in test_session
    # It still counts toward the account's own cap.
    assert repo.count_user_sessions("t-1") == 1

    real_session = repo.create_session("s-real", real, {"deviceId": "and-phone-1"}, body)
    assert repo.count_device_sessions("and-phone-1") == 1
    assert "testAccount" not in real_session and real_session["deviceId"] == "and-phone-1"


async def test_a_test_accounts_session_audit_row_is_tagged(client, world, monkeypatch):
    monkeypatch.setattr(drive, "ensure_session_folders",
                        lambda *a, **k: {"sessionFolderId": "sf", "userFolderId": "uf",
                                         "bundle": "sf"})
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    store = world["store"]
    tag = _test_user(store, "t-1")["testAccount"]
    _as(store, monkeypatch, "t-1", testAccount=tag, activeDeviceId="dev-device")
    r = await client.post("/v1/sessions", json={"specimen": "s", "files": [_FILE]})
    assert r.status_code == 200, r.text
    row = [a for a in world["audit"] if a["action"] == "SESSION_CREATE"][0]
    assert row["detail"]["testAccount"] is True


# ------------------------------------------------------------------ Drive


def test_a_test_account_uploads_under_the_test_tree(monkeypatch):
    made = []

    def folder(_token, name, parent):
        made.append(name)
        return f"id-{name}"

    monkeypatch.setattr(drive, "_find_or_create_folder", folder)
    drive.ensure_session_folders("tok", "t-1", "sid", roles=(), test=True)
    assert made[:3] == ["Research Storage", "_test", "t-1"]
    made.clear()
    drive.ensure_session_folders("tok", "real-1", "sid", roles=())
    assert made[:3] == ["Research Storage", "user", "real-1"]


def test_the_test_tree_is_found_for_erasure(monkeypatch):
    looked = []
    monkeypatch.setattr(drive, "_find_folders",
                        lambda _t, name, parent: looked.append(name) or [f"id-{name}"])
    assert drive.find_user_folders("tok", "t-1", test=True) == ["id-t-1"]
    assert looked == ["Research Storage", "_test", "t-1"]


def test_the_test_tree_may_be_missing(monkeypatch, caplog):
    monkeypatch.setattr(drive, "_find_folders",
                        lambda _t, name, parent: [] if name == "_test" else [f"id-{name}"])
    caplog.set_level(logging.WARNING)
    assert drive.find_user_folders("tok", "t-1", test=True) == []
    assert "no '_test' folder" in caplog.text


def test_provisioning_asks_for_the_test_tree_only_for_a_test_account(world, monkeypatch):
    calls = []

    def ensure(token, uid, sid, roles=None, cached=None, session_folder_id=None, **kw):
        calls.append(kw)
        return {"sessionFolderId": "sf", "userFolderId": "uf", "sessionsFolderId": "ss",
                "raw": "raw"}

    monkeypatch.setattr(drive, "ensure_session_folders", ensure)
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/r")
    store = world["store"]
    _test_user(store, "t-1")
    _real_user(store, uid="real-1")
    for uid, sid in (("t-1", "s-t"), ("real-1", "s-r")):
        store._data.setdefault("sessions", {})[sid] = {"uid": uid, "status": "PROVISIONING"}
        store._data.setdefault("files", {})[f"{sid}_raw_a"] = {
            "uid": uid, "sessionId": sid, "role": "raw", "name": "a", "sizeBytes": 1}
        session_provision.provision_session(sid)
    assert calls == [{"test": True}, {}]


# ------------------------------------------------------------------ config


async def test_config_names_a_test_account_and_null_for_a_real_one(client, world, monkeypatch):
    store = world["store"]
    expires = datetime.now(timezone.utc) + timedelta(days=5)
    tag = _test_user(store, "t-1", expires=expires)["testAccount"]
    _as(store, monkeypatch, "t-1", testAccount=tag)
    r = await client.get("/v1/config")
    assert r.json()["testAccount"]["purpose"] == "qc"
    assert datetime.fromisoformat(r.json()["testAccount"]["expiresAt"]) == expires

    _as(store, monkeypatch, "real-1", email="p@lab.org", access_status="APPROVED")
    r = await client.get("/v1/config")
    assert r.json()["testAccount"] is None


# ------------------------------------------------------------------ licence listing


async def test_the_licence_desk_hides_test_licences_unless_asked(client, world):
    created = (await _create(client)).json()
    real = repo.create_individual_license(email_lock="buyer@lab.org", created_by_uid="admin")

    listed = {lic["id"] for lic in (await client.get("/v1/admin/licenses")).json()["licenses"]}
    assert listed == {real["license"]["id"]}
    with_test = (await client.get("/v1/admin/licenses?include_test=true")).json()["licenses"]
    assert {lic["id"]: lic["testAccount"] for lic in with_test} == {
        real["license"]["id"]: False, **{i: True for i in created["licenseIds"]}}

    search = await client.post("/v1/admin/licenses/search", json={"q": QC_EMAIL})
    assert search.json()["licenses"] == []
    search = await client.post("/v1/admin/licenses/search",
                               json={"q": QC_EMAIL, "include_test": True})
    assert sorted(lic["id"] for lic in search.json()["licenses"]) == sorted(created["licenseIds"])


# ------------------------------------------------------------------ Auth Admin


def test_the_auth_calls_refuse_when_the_budget_is_spent(monkeypatch):
    monkeypatch.setattr(rate_limit, "auth_admin_allowed", lambda: False)
    with pytest.raises(DependencyError) as busy:
        google_auth.create_auth_user(QC_EMAIL, "pw")
    assert (busy.value.code, busy.value.status_code) == ("auth_check_busy", 503)


def test_an_auth_failure_is_a_503_that_names_no_password(monkeypatch, caplog):
    def broken(**kw):
        raise ValueError(f"refused {kw['password']}")

    monkeypatch.setattr(google_auth.fb_auth, "create_user", broken)
    caplog.set_level(logging.DEBUG)
    with pytest.raises(DependencyError) as failed:
        google_auth.create_auth_user(QC_EMAIL, "s3cret-password")
    assert failed.value.code == "internal_error"
    assert "auth_admin_failed" in caplog.text and "s3cret-password" not in caplog.text


def test_the_lookup_says_whether_an_address_has_an_auth_user(world):
    assert google_auth.auth_user_exists(QC_EMAIL) is False
    world["auth"].users["u"] = {"email": QC_EMAIL}
    assert google_auth.auth_user_exists(QC_EMAIL) is True


# ------------------------------------------------------------------ QC on activations


async def test_a_qc_accounts_phone_is_demo_until_activated(client, world):
    """ADR-033: a QC account's licences run the activation model, so QC can
    exercise Activate and the device count as a holder would."""
    created = (await _create(client, apps="semper", licenceMaxDevices=2)).json()
    uid = created["uid"]
    lic = world["store"]._data["licenses"][created["licenseId"]]
    assert (lic["app"], lic["maxDevices"]) == ("semper", 2)
    _register(uid, "and-phone-1")

    def mode(device):
        # As a build that taps Activate (`X-Activation: explicit`) checks it.
        return repo.revalidate_device_lock(repo.get_user(uid), device, explicit=True)["mode"]

    assert mode("and-phone-1") == "demo"
    repo.activate_device(uid, "and-phone-1", "semper")
    assert mode("and-phone-1") == "licensed"


async def test_a_demo_qc_account_holds_no_licence(client, world):
    created = (await _create(client, licence="none")).json()
    assert created["licenseIds"] == [] and created["licenseId"] == ""
    assert not world["store"]._data["users"][created["uid"]].get("licenseId")


async def test_one_app_qc_account_leaves_the_other_on_demo(client, world):
    created = (await _create(client, apps="semperclassroom")).json()
    user = repo.get_user(created["uid"])
    assert apps.read_field(user, "licenseId", MT) == created["licenseId"]
    assert not user.get("licenseId")


async def test_a_ci_account_takes_no_licence_fields(client, world):
    r = await _create(client, purpose="ci", apps="semper")
    assert r.status_code == 422
