"""Revocation (A3.4 / A5.5): the browser step-up can ask Firebase whether the
token was revoked, a staff suspension revokes the user's refresh tokens, and
an account erase deletes the Firebase user. The Admin-SDK calls are
best-effort until the runtime account holds Firebase Auth admin."""
import json
import logging
import time

import pytest
from firebase_admin import auth as fb_auth

import fake_firestore
import repo_view as repo
from app import config, deps, drive, google_auth, rate_limit, step_up
from app.config import settings


# ---------------- google_auth ----------------
def test_verify_id_token_passes_check_revoked_through(monkeypatch):
    seen = []
    monkeypatch.setattr(google_auth.fb_auth, "verify_id_token",
                        lambda token, check_revoked=False: seen.append((token, check_revoked)) or {})
    google_auth.verify_id_token("t1")
    google_auth.verify_id_token("t2", check_revoked=True)
    assert seen == [("t1", False), ("t2", True)]


def test_revoke_sessions_is_true_when_firebase_accepts(monkeypatch):
    seen = []
    monkeypatch.setattr(google_auth.fb_auth, "revoke_refresh_tokens", seen.append)
    assert google_auth.revoke_sessions("u1") is True
    assert seen == ["u1"]


def test_revoke_sessions_failure_is_logged_and_false(monkeypatch, caplog):
    def denied(uid):
        raise PermissionError("caller lacks firebaseauth.users.update")

    monkeypatch.setattr(google_auth.fb_auth, "revoke_refresh_tokens", denied)
    assert google_auth.revoke_sessions("u1") is False
    assert "revoke_refresh_tokens failed uid=u1: PermissionError" in caplog.text


def test_delete_auth_user_counts_an_absent_user_as_deleted(monkeypatch):
    def gone(uid):
        raise fb_auth.UserNotFoundError("no user record")

    monkeypatch.setattr(google_auth.fb_auth, "delete_user", gone)
    assert google_auth.delete_auth_user("u1") is True


def test_delete_auth_user_failure_is_logged_and_false(monkeypatch, caplog):
    def denied(uid):
        raise PermissionError("caller lacks firebaseauth.users.delete")

    monkeypatch.setattr(google_auth.fb_auth, "delete_user", denied)
    assert google_auth.delete_auth_user("u1") is False
    assert "delete_user failed uid=u1: PermissionError" in caplog.text


def test_delete_auth_user_is_true_when_firebase_accepts(monkeypatch):
    seen = []
    monkeypatch.setattr(google_auth.fb_auth, "delete_user", seen.append)
    assert google_auth.delete_auth_user("u1") is True
    assert seen == ["u1"]


def test_the_setting_is_off_by_default():
    assert settings.CHECK_REVOKED_ON_STEP_UP is False


# ---------------- the browser step-up ----------------
@pytest.fixture
def stepping_admin(monkeypatch, audited):
    """A staff admin in a browser with a fresh second factor, and a record of
    every verify_id_token call (token, check_revoked)."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "ADMIN_EMAILS", {"admin@sempermechanics.com"})
    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    calls: list[tuple[str, bool]] = []
    outcome: dict = {"revoked_check": None}

    def verify(token, *, check_revoked=False):
        calls.append((token, check_revoked))
        if check_revoked and outcome["revoked_check"] is not None:
            raise outcome["revoked_check"]
        return {"sub": "admin-1", "email": "admin@sempermechanics.com", "email_verified": True,
                "firebase": {"sign_in_second_factor": "phone"}, "auth_time": time.time() - 60}

    monkeypatch.setattr(deps, "verify_id_token", verify)
    monkeypatch.setattr(step_up, "verify_id_token", verify)
    repo.patch(monkeypatch, "get_or_create_user", lambda c, device_id=None: {
        "uid": "admin-1", "email": "admin@sempermechanics.com",
        "role": "admin", "access_status": "APPROVED",
    })
    store._data["users"] = {
        "admin-1": {"email": "admin@sempermechanics.com", "role": "admin", "access_status": "APPROVED"},
        "target": {"email": "t@e.com", "role": "user", "access_status": "PENDING"},
    }
    return {"store": store, "calls": calls, "outcome": outcome, "audited": audited}


async def test_off_the_step_up_does_not_ask_about_revocation(stepping_admin, client, monkeypatch):
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", False)
    r = await client.post("/v1/admin/users/target/approve", headers={"Authorization": "Bearer tok"})
    assert r.status_code == 200, r.text
    assert stepping_admin["calls"] == [("tok", False)]


async def test_on_a_live_token_passes_after_one_revocation_check(stepping_admin, client, monkeypatch):
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    r = await client.post("/v1/admin/users/target/approve",
                          headers={"X-Forwarded-Authorization": "Bearer fwd",
                                   "Authorization": "Bearer gateway-sa"})
    assert r.status_code == 200, r.text
    # The client's own token, the one behind the gateway, is the one checked.
    assert stepping_admin["calls"] == [("fwd", False), ("fwd", True)]
    assert stepping_admin["store"]._data["users"]["target"]["access_status"] == "APPROVED"


@pytest.mark.parametrize("error", [
    fb_auth.RevokedIdTokenError("The Firebase ID token has been revoked."),
    fb_auth.UserDisabledError("The user record is disabled."),
])
async def test_on_a_revoked_or_disabled_user_is_refused(stepping_admin, client, monkeypatch, error):
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    stepping_admin["outcome"]["revoked_check"] = error
    r = await client.post("/v1/admin/users/target/approve", headers={"Authorization": "Bearer tok"})
    assert r.status_code == 401
    assert r.json()["detail"] == "invalid_token"
    assert stepping_admin["store"]._data["users"]["target"]["access_status"] == "PENDING"
    denied = [a for a in stepping_admin["audited"] if a.get("action") == "AUTH_DENIED"]
    assert denied == [{"uid": "admin-1", "deviceId": None, "action": "AUTH_DENIED",
                       "outcome": "DENIED", "detail": {"stage": "revoked"}}]


async def test_on_a_check_that_cannot_answer_fails_closed(stepping_admin, client, monkeypatch, caplog):
    """No Firebase Auth admin grant (or Auth down): refused, and logged as
    the check failing rather than as a revoked user."""
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    stepping_admin["outcome"]["revoked_check"] = PermissionError("403 caller lacks permission")
    r = await client.post("/v1/admin/users/target/approve", headers={"Authorization": "Bearer tok"})
    assert r.status_code == 401
    assert r.json()["detail"] == "invalid_token"
    assert stepping_admin["store"]._data["users"]["target"]["access_status"] == "PENDING"
    assert [a["detail"] for a in stepping_admin["audited"] if a.get("action") == "AUTH_DENIED"] == [
        {"stage": "revocation_check"}]
    assert '"event":"revocation_check_failed"' in caplog.text
    assert '"errorCode":"PermissionError"' in caplog.text


async def test_on_the_device_signed_path_is_not_asked(stepping_admin, client, monkeypatch):
    """A phone's signed request is held to its device record, not to this.
    It fails here on the device (not registered), with no revocation call."""
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    r = await client.post("/v1/admin/users/target/approve", headers={
        "Authorization": "Bearer tok", "X-Device-Id": "adev", "X-Nonce": "n" * 16,
        "X-Signature": "c2ln",
    })
    assert r.status_code == 409
    assert all(not checked for _, checked in stepping_admin["calls"])


async def test_on_a_missing_second_factor_is_refused_before_any_revocation_call(
    stepping_admin, client, monkeypatch,
):
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    monkeypatch.setattr(deps, "verify_id_token", lambda token, *, check_revoked=False: (
        stepping_admin["calls"].append((token, check_revoked)) or {
            "sub": "admin-1", "email": "admin@sempermechanics.com"}))
    r = await client.post("/v1/admin/users/target/approve", headers={"Authorization": "Bearer tok"})
    assert r.status_code == 403
    assert stepping_admin["calls"] == [("tok", False)]


# ---------------- staff suspension ----------------
@pytest.fixture
def admin_store(store, audited):
    store._data["users"] = {"u1": {"email": "a@b.com", "access_status": "APPROVED", "role": "user"}}
    return store


async def test_revoking_a_user_revokes_their_sessions(admin_store, audited, client, monkeypatch):
    seen = []
    monkeypatch.setattr(google_auth.fb_auth, "revoke_refresh_tokens", seen.append)
    r = await client.post("/v1/admin/users/u1/revoke")
    assert r.status_code == 200
    assert seen == ["u1"]
    row = next(a for a in audited if a["action"] == "ADMIN_REVOKE")
    assert row["detail"] == {"sessionsRevoked": True, "authUsers": 1, "authUsersRevoked": 1}
    assert row["target"] == {"type": "user", "id": "u1"}


async def test_revoking_a_user_revokes_every_linked_sign_in(admin_store, audited, client,
                                                            monkeypatch):
    """A second Firebase sub adopted into the account signs in to it too, so
    suspending the account revokes that sub's refresh tokens as well."""
    admin_store._data["users"]["u1"]["linkedAuthUids"] = ["sub-b"]
    admin_store._data["auth_links"] = {"u1": {"uid": "u1"}, "sub-b": {"uid": "u1"},
                                       "sub-c": {"uid": "u1"}, "x": {"uid": "u2"}}
    seen = []
    monkeypatch.setattr(google_auth.fb_auth, "revoke_refresh_tokens", seen.append)

    r = await client.post("/v1/admin/users/u1/revoke")

    assert r.status_code == 200
    assert seen == ["u1", "sub-b", "sub-c"]
    row = next(a for a in audited if a["action"] == "ADMIN_REVOKE")
    assert row["detail"] == {"sessionsRevoked": True, "authUsers": 3, "authUsersRevoked": 3}


async def test_a_failed_session_revoke_still_suspends(admin_store, audited, client, monkeypatch):
    def denied(uid):
        raise PermissionError("no firebaseauth.admin")

    monkeypatch.setattr(google_auth.fb_auth, "revoke_refresh_tokens", denied)
    r = await client.post("/v1/admin/users/u1/revoke")
    assert r.status_code == 200
    assert r.json() == {"uid": "u1", "access_status": "SUSPENDED"}
    assert admin_store._data["users"]["u1"]["access_status"] == "SUSPENDED"
    assert next(a for a in audited if a["action"] == "ADMIN_REVOKE")["detail"] == {
        "sessionsRevoked": False, "authUsers": 1, "authUsersRevoked": 0}


async def test_revoking_a_missing_user_revokes_nothing(admin_store, client, monkeypatch):
    seen = []
    monkeypatch.setattr(google_auth.fb_auth, "revoke_refresh_tokens", seen.append)
    assert (await client.post("/v1/admin/users/nobody/revoke")).status_code == 404
    assert seen == []


# ---------------- account erase ----------------
@pytest.fixture
def erasable(store, audited, monkeypatch):
    store._data["users"] = {"dev-user": {"email": "dev@local", "access_status": "APPROVED",
                                         "driveFolderId": "uf"}}
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "driveFolderId": "uf"})
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(drive, "delete_file", lambda token, fid: None)
    return store


async def test_erasing_the_account_deletes_the_firebase_user_last(erasable, audited, client, monkeypatch):
    order = []
    monkeypatch.setattr(google_auth.fb_auth, "delete_user", lambda uid: order.append(("auth", uid)))
    real = repo.delete_all_user_data
    repo.patch(monkeypatch, "delete_all_user_data",
               lambda uid: order.append(("data", uid)) or real(uid))
    r = await client.delete("/v1/me")
    assert r.status_code == 200, r.text
    assert order == [("data", "dev-user"), ("auth", "dev-user")]
    assert r.json()["authUserDeleted"] is True
    row = next(a for a in audited if a["action"] == "ACCOUNT_DELETE")
    assert row["detail"]["authUserDeleted"] is True


async def test_a_failed_auth_delete_still_erases_and_says_so(erasable, audited, client, monkeypatch):
    def denied(uid):
        raise PermissionError("no firebaseauth.admin")

    monkeypatch.setattr(google_auth.fb_auth, "delete_user", denied)
    r = await client.delete("/v1/me")
    assert r.status_code == 200
    assert r.json()["authUserDeleted"] is False
    assert "dev-user" not in erasable._data.get("users", {})
    assert next(a for a in audited if a["action"] == "ACCOUNT_DELETE")["detail"][
        "authUserDeleted"] is False


# ---------------- the Auth Admin budget (TD-239) ----------------
@pytest.fixture
def spent(monkeypatch):
    """This instance's Firebase Auth Admin budget, already spent."""
    monkeypatch.setattr(rate_limit, "auth_admin_bucket",
                        rate_limit.TokenBucket(rate_per_sec=0.5, burst=0.0))


def _events(caplog, name):
    return [json.loads(r.message) for r in caplog.records
            if r.message.startswith("{") and json.loads(r.message).get("event") == name]


def test_the_budget_defaults_and_reads_its_env(monkeypatch):
    assert (settings.AUTH_ADMIN_CALLS_PER_SECOND, settings.AUTH_ADMIN_BURST) == (10, 20)
    monkeypatch.setenv("AUTH_ADMIN_CALLS_PER_SECOND", "3")
    monkeypatch.setenv("AUTH_ADMIN_BURST", "7")
    import importlib

    try:
        fresh = importlib.reload(config).Settings()
        assert (fresh.AUTH_ADMIN_CALLS_PER_SECOND, fresh.AUTH_ADMIN_BURST) == (3, 7)
        # A 0 or less is not "off": it would refuse every call for good.
        monkeypatch.setenv("AUTH_ADMIN_CALLS_PER_SECOND", "0")
        monkeypatch.setenv("AUTH_ADMIN_BURST", "-5")
        fresh = importlib.reload(config).Settings()
        assert (fresh.AUTH_ADMIN_CALLS_PER_SECOND, fresh.AUTH_ADMIN_BURST) == (1, 1)
    finally:
        monkeypatch.delenv("AUTH_ADMIN_CALLS_PER_SECOND")
        monkeypatch.delenv("AUTH_ADMIN_BURST")
        importlib.reload(config)


def test_the_budget_runs_out_after_its_burst(monkeypatch):
    monkeypatch.setattr(rate_limit, "auth_admin_bucket",
                        rate_limit.TokenBucket(rate_per_sec=0.001, burst=2.0))
    assert [rate_limit.auth_admin_allowed() for _ in range(3)] == [True, True, False]


@pytest.mark.parametrize(("call", "sdk"), [
    (google_auth.revoke_sessions, "revoke_refresh_tokens"),
    (google_auth.delete_auth_user, "delete_user"),
])
def test_over_budget_an_admin_call_is_not_made_and_answers_false(spent, monkeypatch, caplog,
                                                                 call, sdk):
    seen = []
    monkeypatch.setattr(google_auth.fb_auth, sdk, seen.append)
    with caplog.at_level(logging.WARNING, logger="semper.auth"):
        assert call("u1") is False
    assert seen == []
    [event] = _events(caplog, "auth_admin_rate_limited")
    assert (event["uid"], event["action"], event["dependency"]) == (
        "u1", call.__name__, "firebase_auth")


async def test_over_budget_a_suspension_still_lands_and_says_so(spent, admin_store, audited,
                                                                client):
    r = await client.post("/v1/admin/users/u1/revoke")
    assert r.status_code == 200
    assert admin_store._data["users"]["u1"]["access_status"] == "SUSPENDED"
    assert next(a for a in audited if a["action"] == "ADMIN_REVOKE")["detail"] == {
        "sessionsRevoked": False, "authUsers": 1, "authUsersRevoked": 0}


async def test_over_budget_the_step_up_check_is_503_not_invalid_token(
    spent, stepping_admin, client, monkeypatch, caplog,
):
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    with caplog.at_level(logging.WARNING, logger="semper"):
        r = await client.post("/v1/admin/users/target/approve",
                              headers={"Authorization": "Bearer tok"})
    assert r.status_code == 503
    assert r.json()["detail"] == "auth_check_busy"
    assert int(r.headers["Retry-After"]) >= 1
    # The Auth project was not asked, and nothing was refused as a bad token.
    assert stepping_admin["calls"] == [("tok", False)]
    assert not [a for a in stepping_admin["audited"] if a.get("action") == "AUTH_DENIED"]
    assert stepping_admin["store"]._data["users"]["target"]["access_status"] == "PENDING"
    [event] = _events(caplog, "auth_admin_rate_limited")
    assert (event["stage"], event["errorCode"], event["uid"]) == (
        "revocation_check", "auth_check_busy", "admin-1")


async def test_the_step_up_check_spends_the_shared_budget(stepping_admin, client, monkeypatch):
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    monkeypatch.setattr(rate_limit, "auth_admin_bucket",
                        rate_limit.TokenBucket(rate_per_sec=0.001, burst=1.0))
    first = await client.post("/v1/admin/users/target/approve",
                              headers={"Authorization": "Bearer tok"})
    assert first.status_code == 200, first.text
    # The check spent the only token, so a revoke now is skipped too.
    assert google_auth.revoke_sessions("target") is False


async def test_a_quota_error_from_the_check_is_still_a_failed_check(
    stepping_admin, client, monkeypatch, caplog,
):
    """Under budget here, but the Auth project's own quota refuses: the
    fail-closed rule of `_require_not_revoked` applies, 401, logged as the
    check failing (TD-239's acceptance)."""
    from google.api_core import exceptions as google_exceptions

    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    stepping_admin["outcome"]["revoked_check"] = google_exceptions.ResourceExhausted("quota")
    r = await client.post("/v1/admin/users/target/approve", headers={"Authorization": "Bearer tok"})
    assert r.status_code == 401
    assert r.json()["detail"] == "invalid_token"
    assert '"event":"revocation_check_failed"' in caplog.text
    assert '"errorCode":"ResourceExhausted"' in caplog.text

