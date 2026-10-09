"""Per-user product limits: resolve defaults/overrides, config API, admin patch."""
import pytest

import fake_firestore

import repo_view as repo
from app.config import settings


def _limit_env(monkeypatch):
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 25)
    monkeypatch.setattr(settings, "LICENSED_MAX_SESSIONS_PER_USER", 999)
    monkeypatch.setattr(settings, "MAX_FILES_PER_SESSION", 600)
    monkeypatch.setattr(settings, "MAX_FRAMES_PER_ANALYSIS", 150)
    monkeypatch.setattr(settings, "DAT_CODEC_ENCODING_ENABLED", False)


def test_resolve_uses_demo_defaults_when_no_mode(store, monkeypatch):
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({"uid": "u1"})
    assert cfg == {
        "mode": "demo",
        "plan": "demo",
        "licenseKind": "",
        "licenseDuration": "perpetual",
        "licenseExpiresAt": None,
        "licenseGraceEndsAt": None,
        "inGrace": False,
        "licenseSeating": "assigned",
        "leaseExpiresAt": None,
        "leaseHeartbeatMinutes": 30,
        "cloudBackupEnabled": False,
        "shareEnabled": False,
        "maxSessions": 25,
        "maxFilesPerSession": 600,
        "maxFrames": 150,
        "datCodecEncodingEnabled": False,
        "licensePrefix": "",
    }


def test_resolve_demo_ignores_max_sessions_override(store, monkeypatch):
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({"uid": "u1", "maxSessions": 12})
    assert cfg["plan"] == "demo"
    assert cfg["maxSessions"] == 25
    assert cfg["cloudBackupEnabled"] is False


def test_resolve_prefers_positive_user_overrides_on_professional(store, monkeypatch):
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({
        "uid": "u1",
        "plan": "professional",
        "maxSessions": 40,
        "maxFilesPerSession": 800,
        "maxFrames": 100,
        "datCodecEncodingEnabled": True,
    })
    assert cfg == {
        "mode": "licensed",
        "plan": "professional",
        "licenseKind": "",
        "licenseDuration": "perpetual",
        "licenseExpiresAt": None,
        "licenseGraceEndsAt": None,
        "inGrace": False,
        "licenseSeating": "assigned",
        "leaseExpiresAt": None,
        "leaseHeartbeatMinutes": 30,
        "cloudBackupEnabled": True,
        "shareEnabled": True,
        "maxSessions": 40,
        "maxFilesPerSession": 800,
        "maxFrames": 100,
        "datCodecEncodingEnabled": True,
        "licensePrefix": "",
    }


@pytest.mark.parametrize("user, expected", [
    ({"licenseMaxAnalyses": 1}, 25),
    ({"maxSessions": 1}, 25),
    ({"maxSessions": 1, "licenseMaxAnalyses": 40}, 25),
    ({"licenseMaxAnalyses": 40}, 40),
    ({}, 999),
])
def test_licensed_ceiling_is_never_below_demo(store, monkeypatch, user, expected):
    """A licence adds analyses; a stored cap under demo is floored at demo's."""
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({"uid": "u1", "mode": "licensed", **user})
    assert cfg["maxSessions"] == expected


def test_expired_professional_falls_back_to_demo(store, monkeypatch):
    _limit_env(monkeypatch)
    from datetime import datetime, timedelta, timezone
    cfg = repo.resolve_user_config({
        "uid": "u1",
        "plan": "professional",
        "licenseExpiresAt": datetime.now(timezone.utc) - timedelta(days=1),
    })
    assert cfg["plan"] == "demo"
    assert cfg["cloudBackupEnabled"] is False
    assert cfg["maxSessions"] == 25


def test_dat_codec_override_can_disable_when_fleet_default_is_on(store, monkeypatch):
    # The override must be able to go EITHER direction, not just "on" — a
    # per-account kill switch during rollout needs this, not just canarying.
    monkeypatch.setattr(settings, "DAT_CODEC_ENCODING_ENABLED", True)
    cfg = repo.resolve_user_config({"uid": "u1", "datCodecEncodingEnabled": False})
    assert cfg["datCodecEncodingEnabled"] is False


def test_dat_codec_invalid_override_inherits_fleet_default(store, monkeypatch):
    monkeypatch.setattr(settings, "DAT_CODEC_ENCODING_ENABLED", True)
    # A non-bool value on the doc (bad manual edit, legacy data) must not
    # silently truthy/falsy-cast — it inherits the fleet default instead.
    cfg = repo.resolve_user_config({"uid": "u1", "datCodecEncodingEnabled": "yes"})
    assert cfg["datCodecEncodingEnabled"] is True


def test_resolve_ignores_invalid_overrides(store, monkeypatch):
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({
        "uid": "u1",
        "maxSessions": 0,
        "maxFilesPerSession": "nope",
        "maxFrames": -3,
    })
    assert cfg["maxSessions"] == 25
    assert cfg["maxFilesPerSession"] == 600
    assert cfg["maxFrames"] == 150


def test_set_user_config_writes_and_clears(store, monkeypatch):
    _limit_env(monkeypatch)
    store._data["users"] = {
        "u1": {"email": "a@b.com", "access_status": "APPROVED", "plan": "professional"},
    }

    resolved = repo.set_user_config("u1", {"maxSessions": 25})
    assert resolved["maxSessions"] == 25
    assert store._data["users"]["u1"]["maxSessions"] == 25

    cleared = repo.set_user_config("u1", {"maxSessions": None})
    assert cleared["maxSessions"] == 999
    assert "maxSessions" not in store._data["users"]["u1"]


def test_set_user_config_missing_user(store):
    assert repo.set_user_config("missing", {"maxSessions": 10}) is None


def test_set_user_config_casts_bool_field_as_bool_not_int(store, monkeypatch):
    # int(True) == 1 would silently turn this into an int on the stored doc —
    # a naive single-cast implementation would pass a shallower test but store
    # the wrong type, which _bool_override's isinstance(raw, bool) check would
    # then reject on the next read (falling back to the fleet default instead
    # of the override the caller just set).
    monkeypatch.setattr(settings, "DAT_CODEC_ENCODING_ENABLED", False)
    store._data["users"] = {"u1": {"email": "a@b.com", "access_status": "APPROVED"}}

    resolved = repo.set_user_config("u1", {"datCodecEncodingEnabled": True})
    assert resolved["datCodecEncodingEnabled"] is True
    assert store._data["users"]["u1"]["datCodecEncodingEnabled"] is True

    cleared = repo.set_user_config("u1", {"datCodecEncodingEnabled": None})
    assert cleared["datCodecEncodingEnabled"] is False
    assert "datCodecEncodingEnabled" not in store._data["users"]["u1"]


@pytest.mark.asyncio
async def test_config_endpoint_returns_dev_professional(client):
    resp = await client.get("/v1/config")
    assert resp.status_code == 200
    body = resp.json()
    assert body["plan"] == "professional"
    assert body["cloudBackupEnabled"] is True
    assert body["shareEnabled"] is True
    assert body["maxSessions"] == settings.LICENSED_MAX_SESSIONS_PER_USER
    assert body["maxFilesPerSession"] == settings.MAX_FILES_PER_SESSION
    assert body["maxFrames"] == settings.MAX_FRAMES_PER_ANALYSIS
    assert body["datCodecEncodingEnabled"] == settings.DAT_CODEC_ENCODING_ENABLED


async def test_config_endpoint_reports_individual_license_kind(client, monkeypatch):
    from app import deps
    monkeypatch.setattr(
        deps, "_DEV_USER", {**deps._DEV_USER, "licenseKind": "individual"},
    )
    resp = await client.get("/v1/config")
    assert resp.status_code == 200
    assert resp.json()["licenseKind"] == "individual"


def _past(days):
    from datetime import datetime, timedelta, timezone
    return datetime.now(timezone.utc) - timedelta(days=days)


@pytest.mark.parametrize("user, prefix", [
    ({"mode": "licensed"}, "SEMP-AB12"),
    # Past expiry, still in grace: entitled, so named.
    ({"mode": "licensed", "licenseExpiresAt": _past(1), "licenseGraceDays": 14}, "SEMP-AB12"),
    ({"mode": "demo"}, ""),
    ({"mode": "licensed", "licenseExpiresAt": _past(30), "licenseGraceDays": 14}, ""),
    ({"mode": "licensed", "licenseSeating": "floating"}, ""),
    ({"mode": "licensed", "licenseSeating": "floating", "leaseExpiresAt": _past(1)}, ""),
])
def test_config_names_the_licence_only_while_it_entitles(store, monkeypatch, user, prefix):
    """TD-145: the app shows "Licensed as …" whenever `licensePrefix` is set,
    so a Demo key, a lapsed licence or a floating seat with no lease sends none."""
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({"uid": "u1", "licensePrefix": "SEMP-AB12", **user})
    assert cfg["licensePrefix"] == prefix
    assert (cfg["mode"] == "licensed") == bool(prefix)


async def test_config_drops_a_held_licence_prefix_that_me_still_names(client, monkeypatch):
    """A floating member between leases runs as Demo: /v1/config sends no
    prefix, while /v1/me names the held licence under `held`."""
    from app import deps
    monkeypatch.setattr(deps, "_DEV_USER", {
        **deps._DEV_USER, "licenseKind": "institution", "licensePrefix": "SEMP-ABCD",
        "licenseSeating": "floating",
    })
    cfg = (await client.get("/v1/config")).json()
    assert cfg["mode"] == "demo" and cfg["licensePrefix"] == ""
    lic = (await client.get("/v1/me")).json()["license"]
    assert lic["held"] is True and lic["mode"] == "demo" and lic["prefix"] == "SEMP-ABCD"


@pytest.mark.asyncio
async def test_admin_patch_user_config(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {"u1": {"email": "a@b.com", "access_status": "APPROVED"}}

    resp = await client.patch(
        "/v1/admin/users/u1/config",
        json={"mode": "licensed", "maxSessions": 90},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["uid"] == "u1"
    assert body["config"]["mode"] == "licensed"
    # The `plan` mirror is still written for installed builds (§20.5 shim 8).
    assert body["config"]["plan"] == "professional"
    assert body["config"]["maxSessions"] == 90
    assert store._data["users"]["u1"]["maxSessions"] == 90
    assert store._data["users"]["u1"]["mode"] == "licensed"
    assert store._data["users"]["u1"]["plan"] == "professional"


@pytest.mark.asyncio
@pytest.mark.parametrize("body", [
    {"plan": "professional"},
    {"plan": "professional", "maxSessions": 90},
])
async def test_admin_patch_refuses_the_retired_plan_key(client, monkeypatch, body):
    """The pre-rename `plan` patch was retired (TD-45). It is a 422 — never a
    200 that silently left the mode, or anything else sent with it, unchanged."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {"u1": {"email": "a@b.com", "access_status": "APPROVED"}}

    resp = await client.patch("/v1/admin/users/u1/config", json=body)
    assert resp.status_code == 422, resp.text
    assert store._data["users"]["u1"] == {"email": "a@b.com", "access_status": "APPROVED"}


@pytest.mark.asyncio
async def test_admin_patch_empty_rejected(client):
    resp = await client.patch("/v1/admin/users/u1/config", json={})
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_list_sessions_quota_uses_resolved_max(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    # DEV user uid is "dev-user"; plant an override on that doc and stub
    # current_user's resolve path by putting fields on _DEV_USER via deps.
    from app import deps
    monkeypatch.setattr(
        deps,
        "_DEV_USER",
        {**deps._DEV_USER, "maxSessions": 70},
    )
    repo.patch(monkeypatch, "list_user_sessions", lambda uid, limit=50, page_token=None, app=None: ([], None))
    repo.patch(monkeypatch, "count_user_sessions", lambda uid: 0)

    resp = await client.get("/v1/sessions")
    assert resp.status_code == 200
    assert resp.json()["quota"] == {"used": 0, "max": 70}


def test_resolve_reads_the_pre_rename_plan_field(store, monkeypatch):
    """A user document migration 002 has not reached yet still resolves.

    Written before the rename, it carries `plan` and no `mode`. Reading it as
    demo would silently strip a paying account's entitlements.
    """
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({"uid": "u1", "plan": "professional"})
    assert cfg["mode"] == "licensed"
    assert cfg["cloudBackupEnabled"] is True


def test_resolve_prefers_mode_over_a_stale_plan_mirror(store, monkeypatch):
    """`mode` wins when the two disagree — it is the field migration 002 writes."""
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({"uid": "u1", "mode": "demo", "plan": "professional"})
    assert cfg["mode"] == "demo"
    assert cfg["plan"] == "demo"
    assert cfg["cloudBackupEnabled"] is False


def test_config_response_always_carries_both_mode_and_plan(store, monkeypatch):
    """An installed app decodes `plan` and fails closed to Demo without it.

    Dropping the mirror from this response demotes the whole fleet, so both
    keys are asserted together and must always agree.
    """
    _limit_env(monkeypatch)
    for user, mode, plan in [
        ({"uid": "u1"}, "demo", "demo"),
        ({"uid": "u1", "mode": "licensed"}, "licensed", "professional"),
    ]:
        cfg = repo.resolve_user_config(user)
        assert (cfg["mode"], cfg["plan"]) == (mode, plan)
