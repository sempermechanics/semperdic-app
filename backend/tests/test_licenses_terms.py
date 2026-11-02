"""Licence terms: the campus->institution rename (retired input, stored-document reads), duration and grace, renewal by PATCH."""
from datetime import datetime, timedelta, timezone

import pytest

import fake_firestore

from app import deps, errors
import repo_view as repo
from app.config import settings
from license_helpers import (  # noqa: F401
    _mint_individual,
    _mint_institution,
    _recording_stubs,
    _signed_in,
)
from refusals import attempt


# ====================================================== rename compatibility
# The campus→institution / plan→mode rename. The wire-level shims for ops and
# IT tooling (the /v1/campus/* aliases, kind="campus" at mint) were retired on
# 2026-09-26 (TD-45); these pin that the old input is now refused. Stored
# documents still read either spelling — that shim waits on migration 002.

@pytest.mark.asyncio
async def test_retired_campus_route_is_gone_and_the_current_one_serves(client, monkeypatch):
    """/v1/campus/* no longer routes; /v1/institutions/* serves the same roster."""
    from app import deps

    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "email": "it@university.edu"})
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {"email": "it@university.edu", "emailVerified": True, "access_status": "APPROVED", "plan": "demo"},
        "student": {"email": "a@university.edu", "emailVerified": True, "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("student", "a@university.edu", "dev-1", minted["key"])

    legacy = await client.get(f"/v1/campus/licenses/{license_id}/seats")
    current = await client.get(f"/v1/institutions/licenses/{license_id}/seats")
    assert legacy.status_code == 404, legacy.text
    assert current.status_code == 200, current.text
    assert [s["uid"] for s in current.json()["seats"]] == ["student"]


@pytest.mark.asyncio
async def test_admin_mint_refuses_the_retired_campus_kind(client, monkeypatch):
    """kind="campus" is a 422 now, and nothing is minted."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    resp = await client.post(
        "/v1/admin/licenses",
        json={
            "kind": "campus",
            "domainLock": "university.edu",
            "adminEmails": ["it@university.edu"],
        },
    )
    assert resp.status_code == 422, resp.text
    assert not store._data.get("licenses")


def test_activation_resolves_a_pre_migration_license_document(store):
    """A license minted before the rename carries kind=campus and plan, not mode.

    Migration 002 rewrites these, but the dual-read window means an unmigrated
    document must still activate — otherwise every institution seat breaks
    between the code deploy and the migration run.
    """
    store._data["users"] = {
        "u1": {"email": "student@university.edu", "emailVerified": True, "access_status": "APPROVED", "plan": "demo"},
    }
    key = "SEMP-AAAA-BBBB-CCCC-DDDD"
    store._data["licenses"] = {
        repo.key_hash(key): {
            "kind": "campus",              # pre-rename value
            "plan": "professional",        # pre-rename field
            "status": "active",
            "keyPrefix": "SEMP-AAAA",
            "domainLock": "university.edu",
            "adminEmails": ["it@university.edu"],
            "seatsUsed": 0,
        },
    }
    err, cfg = attempt(repo.activate_license, "u1", "student@university.edu", "dev-1", key)
    assert err == ""
    assert cfg["mode"] == "licensed"
    assert cfg["plan"] == "professional"
    assert cfg["licenseKind"] == "institution"


# ============================================================ duration & grace
# A timed license stops at expiresAt + graceDays. Entitlements are UNCHANGED
# during grace — it exists so a renewal in flight does not interrupt work.

def _timed_user(days_from_now: float, grace_days: int = 14) -> dict:
    return {
        "uid": "u1",
        "mode": "licensed",
        "licenseDuration": "timed",
        "licenseGraceDays": grace_days,
        "licenseExpiresAt": datetime.now(timezone.utc) + timedelta(days=days_from_now),
    }


def test_grace_keeps_full_entitlements_after_expiry(store):
    cfg = repo.resolve_user_config(_timed_user(-2))
    assert cfg["mode"] == "licensed"
    assert cfg["inGrace"] is True
    # The point of grace: nothing is withdrawn, only flagged.
    assert cfg["cloudBackupEnabled"] is True
    assert cfg["shareEnabled"] is True


def test_before_expiry_is_licensed_and_not_in_grace(store):
    cfg = repo.resolve_user_config(_timed_user(5))
    assert (cfg["mode"], cfg["inGrace"]) == ("licensed", False)


def test_past_grace_drops_to_demo(store):
    cfg = repo.resolve_user_config(_timed_user(-20))
    assert cfg["mode"] == "demo"
    assert cfg["inGrace"] is False
    assert cfg["cloudBackupEnabled"] is False


def test_grace_boundary_is_the_last_moment_of_grace(store, monkeypatch):
    """Entitlement ends AT graceEndsAt, not a day either side of it."""
    expiry = datetime(2026, 6, 1, tzinfo=timezone.utc)
    user = {
        "uid": "u1", "mode": "licensed", "licenseDuration": "timed",
        "licenseGraceDays": 10, "licenseExpiresAt": expiry,
    }
    ends = expiry + timedelta(days=10)
    for now, expected in [
        (ends - timedelta(seconds=1), "licensed"),
        (ends, "demo"),
        (ends + timedelta(seconds=1), "demo"),
    ]:
        repo.patch(monkeypatch, "_now", lambda now=now: now)
        assert repo.effective_mode(user) == expected, now


def test_grace_boundary_at_the_production_default(store, monkeypatch):
    """The 14-day default a mint writes: licensed at 13 d 23 h past expiry, demo at 14 d + 1 min.

    The test above pins the boundary semantics at 10 days; this pins the value
    every timed licence actually carries (readiness TE4-002).
    """
    assert settings.LICENSE_GRACE_DAYS_DEFAULT == 14
    expiry = datetime(2026, 6, 1, tzinfo=timezone.utc)
    user = {
        "uid": "u1", "mode": "licensed", "licenseDuration": "timed",
        "licenseGraceDays": settings.LICENSE_GRACE_DAYS_DEFAULT, "licenseExpiresAt": expiry,
    }
    for after_expiry, mode, in_grace in [
        (timedelta(days=13, hours=23), "licensed", True),
        (timedelta(days=14, minutes=1), "demo", False),
    ]:
        now = expiry + after_expiry
        repo.patch(monkeypatch, "_now", lambda now=now: now)
        cfg = repo.resolve_user_config(user)
        assert (cfg["mode"], cfg["inGrace"]) == (mode, in_grace), after_expiry
        assert cfg["cloudBackupEnabled"] is (mode == "licensed"), after_expiry


def test_zero_grace_is_a_hard_cliff(store):
    cfg = repo.resolve_user_config(_timed_user(-0.001, grace_days=0))
    assert cfg["mode"] == "demo"


def test_perpetual_never_expires(store):
    cfg = repo.resolve_user_config({"uid": "u1", "mode": "licensed"})
    assert cfg["mode"] == "licensed"
    assert cfg["licenseDuration"] == "perpetual"
    assert cfg["licenseExpiresAt"] is None
    assert cfg["licenseGraceEndsAt"] is None


def test_a_license_predating_grace_gets_none_retroactively(store):
    """Deploying a grace default must not reinstate already-expired accounts.

    A user document written before graceDays existed carries no such field. If
    that read as the fleet default, everyone who expired inside the window
    would silently come back to licensed on deploy.
    """
    cfg = repo.resolve_user_config({
        "uid": "u1",
        "plan": "professional",  # pre-rename too, as such a document would be
        "licenseExpiresAt": datetime.now(timezone.utc) - timedelta(days=1),
    })
    assert cfg["mode"] == "demo"
    assert cfg["inGrace"] is False


def test_expired_institution_license_drops_the_seat_holder(store):
    """The only expiry test before this covered an individual license."""
    store._data["users"] = {
        "u1": {"email": "student@university.edu", "emailVerified": True, "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_institution_license(
        domain_lock="university.edu",
        admin_emails=["it@university.edu"],
        created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(seconds=1),
    )
    err, cfg = attempt(repo.activate_license, "u1", "student@university.edu", "dev-1", minted["key"])
    assert err == ""
    assert cfg["mode"] == "licensed"

    seat_holder = store._data["users"]["u1"]
    past = datetime.now(timezone.utc) - timedelta(days=365)
    assert repo.effective_mode({**seat_holder, "licenseExpiresAt": past}) == "demo"


def test_activating_an_expired_key_is_refused_not_silently_demoted(store):
    """It used to "succeed": the past expiry was mirrored on, the account
    resolved demo, and the caller got err="" with a demo config."""
    store._data["users"] = {
        "u1": {"email": "a@b.com", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_individual_license(
        email_lock="a@b.com",
        device_id_lock="dev-1",
        created_by_uid="admin",
        # Past the default grace window, not merely past expiry — a key inside
        # grace is still entitled and must still activate.
        expires_at=datetime.now(timezone.utc)
        - timedelta(days=settings.LICENSE_GRACE_DAYS_DEFAULT + 1),
    )
    # Verified after the mint (which then left an invite), before the key.
    store._data["users"]["u1"]["emailVerified"] = True
    err, cfg = attempt(repo.activate_license, "u1", "a@b.com", "dev-1", minted["key"])
    assert err == "license_expired"
    assert cfg is None
    # And nothing was written onto the user.
    assert "licenseId" not in store._data["users"]["u1"]


def test_mint_records_duration_and_grace(store):
    timed = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(days=30),
    )
    stored = store._data["licenses"][timed["license"]["id"]]
    assert stored["duration"] == "timed"
    # Stamped from the fleet default at mint, not inherited at read time.
    assert stored["graceDays"] == settings.LICENSE_GRACE_DAYS_DEFAULT

    perpetual = repo.create_individual_license(
        email_lock="c@d.com", device_id_lock="dev-2", created_by_uid="admin",
    )
    assert store._data["licenses"][perpetual["license"]["id"]]["duration"] == "perpetual"


def test_activating_inside_grace_still_works(store):
    """Grace is entitlement, not a warning state — a key can still be redeemed
    during it, and the redeemer lands in grace rather than being refused."""
    store._data["users"] = {
        "u1": {"email": "a@b.com", "emailVerified": True, "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_individual_license(
        email_lock="a@b.com",
        device_id_lock="dev-1",
        created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) - timedelta(days=1),
    )
    err, cfg = attempt(repo.activate_license, "u1", "a@b.com", "dev-1", minted["key"])
    assert err == ""
    assert cfg["mode"] == "licensed"
    assert cfg["inGrace"] is True


# ====================================================== renewal (PATCH terms)
# Extending expiresAt has to reach people who already activated. The terms are
# mirrored onto each user at activation so the read path needs no Firestore
# lookup, which means editing the license alone reaches nobody.

def test_extending_an_individual_license_re_entitles_the_redeemer(store):
    store._data["users"] = {
        "u1": {"email": "a@b.com", "emailVerified": True, "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(days=1),
    )
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@b.com", "dev-1", minted["key"])

    far = datetime.now(timezone.utc) + timedelta(days=400)
    updated = repo.update_license(license_id, {"expiresAt": far}, "admin")
    assert updated["expiresAt"] == far
    # The mirror moved with it — without the fan-out this would still be +1d.
    assert store._data["users"]["u1"]["licenseExpiresAt"] == far


def test_extending_an_institution_license_re_entitles_every_seat(store):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "emailVerified": True, "access_status": "APPROVED", "mode": "demo"},
        "u2": {"email": "b@university.edu", "emailVerified": True, "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_institution_license(
        domain_lock="university.edu", admin_emails=["it@university.edu"],
        created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(days=1),
    )
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    repo.activate_license("u2", "b@university.edu", "dev-2", minted["key"])

    far = datetime.now(timezone.utc) + timedelta(days=400)
    repo.update_license(license_id, {"expiresAt": far}, "admin")
    assert store._data["users"]["u1"]["licenseExpiresAt"] == far
    assert store._data["users"]["u2"]["licenseExpiresAt"] == far


def test_extending_skips_a_revoked_seat(store):
    """A revoked seat holds no entitlement. Re-stamping its mirror would
    resurrect the member on the next resolve."""
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "emailVerified": True, "access_status": "APPROVED", "mode": "demo"},
        "u2": {"email": "b@university.edu", "emailVerified": True, "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_institution_license(
        domain_lock="university.edu", admin_emails=["it@university.edu"],
        created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(days=1),
    )
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    repo.activate_license("u2", "b@university.edu", "dev-2", minted["key"])
    repo.revoke_institution_seat(license_id, "u2")

    far = datetime.now(timezone.utc) + timedelta(days=400)
    repo.update_license(license_id, {"expiresAt": far}, "admin")
    assert store._data["users"]["u1"]["licenseExpiresAt"] == far
    assert store._data["users"]["u2"]["mode"] == "demo"


def test_extending_does_not_touch_someone_on_a_different_license(store):
    store._data["users"] = {
        "u1": {"email": "a@b.com", "emailVerified": True, "access_status": "APPROVED", "mode": "demo"},
    }
    first = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(days=1),
    )
    repo.activate_license("u1", "a@b.com", "dev-1", first["key"])
    # The first lapses and u1 moves to a second key (one licence per person:
    # only a lapsed one lets them); the first no longer speaks for them.
    store._data["licenses"][first["license"]["id"]].update(
        {"expiresAt": datetime.now(timezone.utc) - timedelta(days=1), "graceDays": 0})
    second = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
    )
    repo.activate_license("u1", "a@b.com", "dev-1", second["key"])

    far = datetime.now(timezone.utc) + timedelta(days=400)
    repo.update_license(first["license"]["id"], {"expiresAt": far}, "admin")
    # The second license is perpetual, so activating it cleared the mirror.
    # The fan-out must leave it cleared rather than stamping the first
    # license's new expiry onto someone who has moved off it.
    assert store._data["users"]["u1"].get("licenseExpiresAt") is None


def test_extending_a_perpetual_license_is_refused(store):
    """An expiry on a perpetual licence is not an extension: it turned an
    unending licence into a timed one, with no grace, for everyone on it."""
    minted = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
    )
    license_id = minted["license"]["id"]
    far = datetime.now(timezone.utc) + timedelta(days=30)
    with pytest.raises(errors.Refusal) as raised:
        repo.update_license(license_id, {"expiresAt": far}, "admin")
    assert raised.value.code == "license_perpetual"
    assert store._data["licenses"][license_id]["duration"] == "perpetual"
    assert store._data["licenses"][license_id].get("expiresAt") is None


def test_update_unknown_license_is_none(store):
    assert repo.update_license("nope", {"graceDays": 5}, "admin") is None


@pytest.mark.asyncio
async def test_admin_extend_over_http(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "u1": {"email": "a@b.com", "emailVerified": True, "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(days=1),
    )
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@b.com", "dev-1", minted["key"])

    resp = await client.patch(
        f"/v1/admin/licenses/{license_id}",
        json={"expiresAt": "2030-01-01T00:00:00Z", "graceDays": 30},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["duration"] == "timed"
    assert store._data["users"]["u1"]["licenseGraceDays"] == 30


@pytest.mark.asyncio
async def test_admin_extend_rejects_an_empty_patch(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    resp = await client.patch("/v1/admin/licenses/whatever", json={})
    assert resp.status_code == 422


# ====================================================== analysis cap
# A licence may not give fewer analyses than demo. The operator's cap box was
# the only number on an individual licence, so "1" read as "one licence".

def test_clearing_the_analysis_cap_reaches_the_holder(store, monkeypatch):
    """A cap stored before the floor existed can be dropped, and the holder
    goes back to the licensed default without re-activating."""
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 25)
    monkeypatch.setattr(settings, "LICENSED_MAX_SESSIONS_PER_USER", 999)
    store._data["users"] = {
        "u1": {"email": "a@b.com", "emailVerified": True, "access_status": "APPROVED", "mode": "demo"},
    }
    # The repo mints what it is given; the 1 is the legacy data the API now refuses.
    minted = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
        max_analyses=1,
    )
    license_id = minted["license"]["id"]
    err, cfg = attempt(repo.activate_license, "u1", "a@b.com", "dev-1", minted["key"])
    assert err == ""
    assert store._data["users"]["u1"]["licenseMaxAnalyses"] == 1
    assert cfg["maxSessions"] == 25  # floored, never "0 / 1"

    updated = repo.update_license(license_id, {"clearMaxAnalyses": True}, "admin")
    assert updated["maxAnalyses"] is None
    assert "maxAnalyses" not in store._data["licenses"][license_id]
    assert "licenseMaxAnalyses" not in store._data["users"]["u1"]
    assert repo.resolve_user_config(store._data["users"]["u1"])["maxSessions"] == 999


@pytest.mark.asyncio
@pytest.mark.parametrize("body", [
    {"emailLock": "a@b.com", "maxAnalyses": 1},
    {"kind": "institution", "domainLock": "university.edu",
     "adminEmails": ["it@university.edu"], "maxAnalyses": 24},
])
async def test_admin_mint_refuses_a_cap_below_demo(client, monkeypatch, body):
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 25)
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    resp = await client.post("/v1/admin/licenses", json=body)
    assert resp.status_code == 422, resp.text
    assert "maxAnalyses" in resp.text
    assert not store._data.get("licenses")


@pytest.mark.asyncio
@pytest.mark.parametrize("body", [
    {"maxAnalyses": 1},
    {"maxAnalyses": 40, "clearMaxAnalyses": True},
])
async def test_admin_update_refuses_a_bad_cap(client, monkeypatch, body):
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 25)
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    minted = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
    )
    resp = await client.patch(f"/v1/admin/licenses/{minted['license']['id']}", json=body)
    assert resp.status_code == 422, resp.text


@pytest.mark.asyncio
async def test_admin_clear_cap_over_http(client, monkeypatch):
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 25)
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    minted = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
        max_analyses=40,
    )
    license_id = minted["license"]["id"]
    resp = await client.patch(
        f"/v1/admin/licenses/{license_id}", json={"clearMaxAnalyses": True},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["maxAnalyses"] is None
    assert "maxAnalyses" not in store._data["licenses"][license_id]


# ====================================================== cap on a demo key
# A demo holder gets DEMO_MAX_ANALYSES whatever the key stores, so a cap
# written to a system-minted demo key answered 200 and changed nothing: the
# app kept showing "N of 25" (SEMP-8AKN, 2026-09-25).

def _demo_holder(store, uid="u1", email="a@b.com", device="dev-1"):
    """A signed-in account with the demo key `ensure_demo_license` mints."""
    user = repo.ensure_demo_license(_signed_in(store, uid, email), device)
    license_id = user["licenseId"]
    lic = store._data["licenses"][license_id]
    assert (lic["mode"], lic["createdByUid"], lic["status"]) == ("demo", "system", "redeemed")
    return license_id


def test_a_cap_on_a_demo_key_is_refused_before_anything_is_written(store, monkeypatch):
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 25)
    store._data["users"] = {}
    license_id = _demo_holder(store)
    before_lic = dict(store._data["licenses"][license_id])
    before_user = dict(store._data["users"]["u1"])

    with pytest.raises(errors.Refusal) as exc:
        repo.update_license(license_id, {"maxAnalyses": 100}, "admin")

    assert exc.value.code == "cap_on_demo_key"
    assert store._data["licenses"][license_id] == before_lic
    assert store._data["users"]["u1"] == before_user
    assert repo.resolve_user_config(store._data["users"]["u1"])["maxSessions"] == 25


@pytest.mark.asyncio
async def test_admin_cap_on_a_demo_key_is_a_422_that_leaves_the_device_lock(
    store, client, monkeypatch,
):
    """Refused in the route, before `clearDeviceLock` in the same request runs."""
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 25)
    store._data["users"] = {}
    license_id = _demo_holder(store)

    resp = await client.patch(
        f"/v1/admin/licenses/{license_id}",
        json={"maxAnalyses": 100, "clearDeviceLock": True},
    )

    assert resp.status_code == 422, resp.text
    assert resp.json()["detail"] == "cap_on_demo_key"
    lic = store._data["licenses"][license_id]
    assert "maxAnalyses" not in lic
    assert lic["deviceIdLock"] == "dev-1"
    assert "licenseMaxAnalyses" not in store._data["users"]["u1"]


@pytest.mark.asyncio
async def test_admin_can_clear_a_cap_already_stored_on_a_demo_key(store, client, monkeypatch):
    """The state SEMP-8AKN was left in: a cap on the key and its holder that
    never applied. Clearing it is allowed; it removes a number, nothing else."""
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 25)
    store._data["users"] = {}
    license_id = _demo_holder(store)
    store._data["licenses"][license_id]["maxAnalyses"] = 100
    store._data["users"]["u1"]["licenseMaxAnalyses"] = 100

    resp = await client.patch(
        f"/v1/admin/licenses/{license_id}", json={"clearMaxAnalyses": True},
    )

    assert resp.status_code == 200, resp.text
    assert resp.json()["maxAnalyses"] is None
    assert resp.json()["mode"] == "demo"
    assert "maxAnalyses" not in store._data["licenses"][license_id]
    assert "licenseMaxAnalyses" not in store._data["users"]["u1"]
    assert repo.resolve_user_config(store._data["users"]["u1"])["maxSessions"] == 25


@pytest.mark.asyncio
async def test_admin_cap_on_a_licensed_key_still_reaches_the_holder(store, client, monkeypatch):
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 25)
    store._data["users"] = {
        "u1": {"email": "a@b.com", "emailVerified": True, "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
    )
    license_id = minted["license"]["id"]
    err, _ = attempt(repo.activate_license, "u1", "a@b.com", "dev-1", minted["key"])
    assert err == ""

    resp = await client.patch(f"/v1/admin/licenses/{license_id}", json={"maxAnalyses": 100})

    assert resp.status_code == 200, resp.text
    assert resp.json()["maxAnalyses"] == 100
    assert repo.resolve_user_config(store._data["users"]["u1"])["maxSessions"] == 100


@pytest.mark.asyncio
async def test_admin_licence_list_carries_the_demo_allowance(store, client, monkeypatch):
    """The desk shows this on demo rows instead of whatever the key stores."""
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 25)
    resp = await client.get("/v1/admin/licenses")
    assert resp.status_code == 200, resp.text
    assert resp.json()["demoMaxAnalyses"] == 25


@pytest.mark.asyncio
async def test_me_reports_the_license_summary(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    expiry = datetime.now(timezone.utc) - timedelta(days=1)
    monkeypatch.setattr(deps, "_DEV_USER", {
        **deps._DEV_USER,
        "licenseExpiresAt": expiry, "licenseGraceDays": 14,
        "licenseDuration": "timed", "licenseKind": "institution",
    })
    body = (await client.get("/v1/me")).json()
    assert body["license"]["mode"] == "licensed"
    assert body["license"]["inGrace"] is True
    assert body["license"]["duration"] == "timed"
    assert body["license"]["kind"] == "institution"
    assert body["license"]["held"] is True
    # Identity is unchanged — this is additive.
    assert body["uid"] == "dev-user"
    assert body["access_status"] == "APPROVED"


@pytest.mark.parametrize(("stored", "held"), [("licensed", True), ("demo", False)])
def test_the_summary_says_whether_a_real_licence_is_attached(stored, held):
    """A Demo key and a revoked licence both read demo, as does an expired
    one; only the stored mode tells the account page which has a licence."""
    expired = datetime.now(timezone.utc) - timedelta(days=30)
    user = {"mode": stored, "licenseKind": "individual", "licenseExpiresAt": expired}
    summary = repo.license_summary(user)
    assert summary["mode"] == "demo"
    assert summary["held"] is held
