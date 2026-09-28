"""App Check for device callers (deps._require_app_check, via current_user).

The Web API key that mints ID tokens ships inside the APK, so an ID token alone
does not prove the caller is our app. App Check does — but only the app sends
`X-Device-Id`, so only the app is asked, and the four browser consoles are left
alone. The three modes are exercised here because `monitor` existing *and not
refusing anything* is the whole point of the rollout.
"""
import pytest
from fastapi import HTTPException
from starlette.requests import Request

from app import deps, errors


def _request() -> Request:
    return Request({"type": "http", "method": "GET", "path": "/v1/me",
                    "headers": [], "query_string": b""}, None)


@pytest.fixture
def real_auth(monkeypatch):
    """current_user with the dev bypass off and Firestore stubbed out."""
    monkeypatch.setattr(deps.settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(deps, "verify_id_token", lambda tok: {"sub": "u1", "email": "a@b.com"})
    user = {"uid": "u1", "email": "a@b.com", "access_status": "APPROVED"}
    monkeypatch.setattr(deps.repo, "get_or_create_user", lambda claims, device_id=None: dict(user))
    monkeypatch.setattr(deps.repo, "revalidate_device_lock", lambda u, did, app=None: u)
    return user


def _call(device_id="d1", app_check=""):
    return deps.current_user(
        request=_request(),
        authorization="Bearer tok",
        x_forwarded_authorization="",
        x_device_id=device_id,
        x_firebase_appcheck=app_check,
        x_app_id="",
    )


# ---------------------------------------------------------------- mode: off

def test_off_ignores_a_missing_token(monkeypatch, real_auth):
    monkeypatch.setattr(deps.settings, "APP_CHECK_MODE", "off")
    assert _call()["uid"] == "u1"


def test_off_does_not_even_verify_a_supplied_token(monkeypatch, real_auth):
    """Off means off: no verification call, so no latency and no surprise 403."""
    monkeypatch.setattr(deps.settings, "APP_CHECK_MODE", "off")
    called = []
    monkeypatch.setattr(deps, "verify_app_check_token", lambda t: called.append(t))
    _call(app_check="anything")
    assert called == []


# ------------------------------------------------------------ mode: monitor

def test_monitor_allows_a_caller_with_no_token(monkeypatch, real_auth):
    monkeypatch.setattr(deps.settings, "APP_CHECK_MODE", "monitor")
    assert _call()["uid"] == "u1"


def test_monitor_allows_a_caller_whose_token_fails(monkeypatch, real_auth):
    monkeypatch.setattr(deps.settings, "APP_CHECK_MODE", "monitor")
    monkeypatch.setattr(deps, "verify_app_check_token",
                        lambda t: (_ for _ in ()).throw(ValueError("bad token")))
    assert _call(app_check="junk")["uid"] == "u1"


# ------------------------------------------------------------ mode: enforce

def test_enforce_refuses_a_missing_token(monkeypatch, real_auth):
    monkeypatch.setattr(deps.settings, "APP_CHECK_MODE", "enforce")
    with pytest.raises(HTTPException) as exc:
        _call()
    assert exc.value.status_code == 403
    assert exc.value.detail == errors.APP_CHECK_REQUIRED


def test_enforce_refuses_an_invalid_token(monkeypatch, real_auth):
    monkeypatch.setattr(deps.settings, "APP_CHECK_MODE", "enforce")
    monkeypatch.setattr(deps, "verify_app_check_token",
                        lambda t: (_ for _ in ()).throw(ValueError("bad token")))
    with pytest.raises(HTTPException) as exc:
        _call(app_check="junk")
    assert exc.value.detail == errors.APP_CHECK_REQUIRED


def test_enforce_accepts_a_valid_token(monkeypatch, real_auth):
    monkeypatch.setattr(deps.settings, "APP_CHECK_MODE", "enforce")
    monkeypatch.setattr(deps, "verify_app_check_token", lambda t: {"app_id": "1:2:android:3"})
    assert _call(app_check="good")["uid"] == "u1"


def test_enforce_refuses_before_the_account_is_touched(monkeypatch, real_auth):
    """A refused caller must not create a user row or move a device lock on the
    way out — the check sits ahead of get_or_create_user for that reason."""
    monkeypatch.setattr(deps.settings, "APP_CHECK_MODE", "enforce")
    touched = []
    monkeypatch.setattr(deps.repo, "get_or_create_user",
                        lambda claims, device_id=None: touched.append(device_id) or {})
    with pytest.raises(HTTPException):
        _call()
    assert touched == []


# ------------------------------------------------- who is asked, and who is not

def test_a_browser_caller_is_never_asked(monkeypatch, real_auth):
    """The consoles send no X-Device-Id, so enforcement must not reach them —
    otherwise turning it on takes the four web consoles down with it."""
    monkeypatch.setattr(deps.settings, "APP_CHECK_MODE", "enforce")
    assert _call(device_id="", app_check="")["uid"] == "u1"


def test_the_dev_bypass_skips_the_check(monkeypatch):
    """DEV_INSECURE_AUTH short-circuits before any token is read at all."""
    monkeypatch.setattr(deps.settings, "DEV_INSECURE_AUTH", True)
    monkeypatch.setattr(deps.settings, "APP_CHECK_MODE", "enforce")
    assert _call()["uid"] == "dev-user"


# ------------------------------------------------------------------- startup

def test_a_misspelt_mode_fails_the_start(monkeypatch):
    """Silently reading as `off` would leave an operator believing enforcement
    is on while nothing is checked."""
    from app import main
    monkeypatch.setattr(deps.settings, "APP_CHECK_MODE", "enforced")
    with pytest.raises(RuntimeError, match="APP_CHECK_MODE"):
        main._startup_checks()
