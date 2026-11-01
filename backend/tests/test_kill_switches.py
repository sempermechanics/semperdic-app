"""Kill switches are enforced, not only published (A6.4).

`KILL_SWITCHES` names features an operator has switched off fleet-wide. The
app reads the list from `/v1/config`, but a build that predates the list, or
ignores it, would carry on; so the backend refuses the routes behind each
switch too: 503 `feature_paused` with Retry-After, after authentication and
before any Firestore or Drive work (`deps.unless_paused`).
"""
import json
import logging

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

import fake_firestore
from app import deps, drive, errors, tasks
import repo_view as repo

_SHA = "a" * 64
_MD5 = "b" * 32

_CREATE = ("POST", "/v1/sessions", "/v1/sessions",
           {"specimen": "s", "files": [{"name": "a.zip", "role": "bundle", "bytes": 10,
                                        "sha256": _SHA}]})
_COMPLETE = ("POST", "/v1/files/f-1/complete", "/v1/files/{file_id}/complete",
             {"sessionId": "s-1", "driveFileId": "d-1", "bytes": 10, "md5": _MD5})
_CONTENT = ("GET", "/v1/files/f-1/content", "/v1/files/{file_id}/content", None)
_BUNDLE = ("GET", "/v1/sessions/s-1/bundle", "/v1/sessions/{sid}/bundle", None)
# The resume path hands out Drive resumable URIs; the metadata replace writes
# into Drive. Both are upload work (readiness TE4-004).
_UPLOADS = ("GET", "/v1/sessions/s-1/uploads", "/v1/sessions/{sid}/uploads", None)
_METADATA = ("PUT", "/v1/sessions/s-1/metadata", "/v1/sessions/{sid}/metadata",
             {"specimen": "s"})
# Restoring from the recycle bin takes a place under the quota, as an upload
# does, so the same two switches hold it (ADR-035).
_BIN_RESTORE = ("POST", "/v1/recycle-bin/s-1/restore", "/v1/recycle-bin/{sid}/restore", None)

#: Every switch the backend enforces, by the routes it refuses.
PAUSED = [
    ("upload", _CREATE), ("upload", _COMPLETE),
    ("upload", _UPLOADS), ("upload", _METADATA),
    ("backup", _CREATE), ("backup", _COMPLETE),
    ("backup", _UPLOADS), ("backup", _METADATA),
    ("restore", _CONTENT), ("restore", _BUNDLE),
    ("upload", _BIN_RESTORE), ("backup", _BIN_RESTORE),
]
ROUTES = [_CREATE, _COMPLETE, _UPLOADS, _METADATA, _CONTENT, _BUNDLE, _BIN_RESTORE]


def _events(caplog, event: str) -> list[dict]:
    return [json.loads(r.message) for r in caplog.records
            if r.message.startswith("{") and json.loads(r.message).get("event") == event]


@pytest.fixture
def untouched(store, monkeypatch):
    """Fails the test if the request reached Drive; Firestore is checked by
    the caller through `fake_firestore.CALLS`."""
    def no_drive(*_a, **_k):
        raise AssertionError("a paused route reached Drive")

    for name in ("access_token", "get_file_meta", "open_download", "ensure_session_folders"):
        monkeypatch.setattr(drive, name, no_drive)
    return store


@pytest.fixture
def recording(store, audited):
    """The dev account as POST /v1/sessions needs it to record an analysis."""
    store._data["users"] = {"dev-user": {"email": "dev@local", "access_status": "APPROVED"}}
    return store


def _switches(monkeypatch, *names):
    monkeypatch.setattr(deps.settings, "KILL_SWITCHES", list(names))


async def _send(client, method, path, body, headers=None):
    return await client.request(method, path, json=body, headers=headers or {})


@pytest.mark.parametrize("switch,route", PAUSED, ids=lambda v: v if isinstance(v, str) else v[1])
async def test_a_switch_refuses_its_routes_before_any_work(untouched, client, monkeypatch,
                                                           caplog, switch, route):
    method, path, template, body = route
    _switches(monkeypatch, switch)
    fake_firestore.CALLS.clear()

    with caplog.at_level(logging.WARNING, logger="semper.auth"):
        r = await _send(client, method, path, body)

    assert r.status_code == 503, r.text
    assert r.json()["detail"] == errors.FEATURE_PAUSED
    assert r.headers["Retry-After"] == "300"
    assert fake_firestore.CALLS == [], "a paused route read or wrote Firestore"
    [event] = _events(caplog, "feature_paused")
    assert event["switch"] == switch
    assert event["routeTemplate"] == template
    assert (event["outcome"], event["errorCode"]) == ("refused", "feature_paused")
    assert event["uid"] == "dev-user"


async def test_upload_and_backup_together_are_named_together(untouched, client, monkeypatch,
                                                             caplog):
    _switches(monkeypatch, "backup", "upload", "restore")
    with caplog.at_level(logging.WARNING, logger="semper.auth"):
        r = await _send(client, *_COMPLETE[:2], _COMPLETE[3])
    assert r.status_code == 503
    assert _events(caplog, "feature_paused")[0]["switch"] == "upload,backup"


@pytest.mark.parametrize("switch,route", [
    ("restore", _CREATE), ("restore", _COMPLETE), ("share", _CREATE), ("share", _COMPLETE),
    ("upload", _CONTENT), ("backup", _CONTENT), ("upload", _BUNDLE), ("share", _BUNDLE),
    ("restore", _UPLOADS), ("share", _UPLOADS), ("restore", _METADATA), ("share", _METADATA),
    ("restore", _BIN_RESTORE), ("share", _BIN_RESTORE),
], ids=lambda v: v if isinstance(v, str) else v[1])
async def test_another_switch_leaves_the_route_alone(recording, client, monkeypatch, switch,
                                                     route):
    """The empty store answers each route's ordinary not-found, or creates."""
    method, path, _template, body = route
    _switches(monkeypatch, switch)
    _drive_stubs(monkeypatch)
    r = await _send(client, method, path, body)
    assert r.status_code != 503, r.text
    assert r.json().get("detail") != errors.FEATURE_PAUSED


def _drive_stubs(monkeypatch):
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(
        drive, "ensure_session_folders",
        lambda *a, **k: {"sessionFolderId": "sf", "userFolderId": "uf", "bundle": "sf"},
    )
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    monkeypatch.setattr(tasks, "enqueue_provision", lambda sid: False)


@pytest.mark.parametrize("route,status,detail", [
    (_CREATE, 200, None),
    (_COMPLETE, 404, "file_not_found"),
    (_CONTENT, 404, "file_not_found"),
    (_BUNDLE, 404, "session_not_found"),
    (_UPLOADS, 404, "session_not_found"),
    (_METADATA, 404, "session_not_found"),
    (_BIN_RESTORE, 404, "session_not_found"),
], ids=lambda v: v[1] if isinstance(v, tuple) else str(v))
async def test_no_switch_answers_as_before(recording, client, monkeypatch, route, status,
                                           detail):
    method, path, _template, body = route
    _switches(monkeypatch)
    _drive_stubs(monkeypatch)
    r = await _send(client, method, path, body)
    assert r.status_code == status, r.text
    if detail:
        assert r.json()["detail"] == detail


def test_only_known_switches_can_guard_a_route():
    with pytest.raises(ValueError):
        deps.unless_paused("analytics")
    with pytest.raises(ValueError):
        deps.unless_paused()


# ---------------- order: after authentication, before the signature ----------
@pytest.fixture
def real_auth(store, monkeypatch):
    monkeypatch.setattr(deps.settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(deps, "verify_id_token", lambda _t, **_k: {"sub": "u-1"})
    profile = {"uid": "u-1", "email": "u@lab.org", "role": "user",
               "access_status": "APPROVED", "activeDeviceId": "dev-1"}
    repo.patch(monkeypatch, "get_or_create_user", lambda claims, device_id=None: dict(profile))
    return store


async def test_a_paused_route_still_asks_for_a_token_first(real_auth, client, monkeypatch):
    _switches(monkeypatch, "restore")
    r = await client.get("/v1/files/f-1/content")
    assert r.status_code == 401
    assert r.json()["detail"] == "missing_bearer"


async def test_a_paused_signed_request_keeps_its_nonce(real_auth, client, monkeypatch):
    """Refused before `verified_device`, as a 429 is: the device record is not
    read and the nonce is not claimed, so the app can send the same signed
    request again once the switch is off."""
    def must_not(*_a, **_k):
        raise AssertionError("signature checked on a paused route")

    repo.patch(monkeypatch, "get_device", must_not)
    repo.patch(monkeypatch, "consume_nonce", must_not)
    repo.patch(monkeypatch, "claim_client_nonce", must_not)
    _switches(monkeypatch, "upload")
    pem = ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    real_auth._data["devices"] = {"dev-1": {"uid": "u-1", "status": "ACTIVE", "publicKeyPem": pem}}

    r = await client.post("/v1/sessions", json=_CREATE[3], headers={
        "Authorization": "Bearer ok", "X-Device-Id": "dev-1", "X-Nonce": "n-1",
        "X-Signature": "c2ln"})

    assert r.status_code == 503, r.text
    assert r.json()["detail"] == "feature_paused"


async def test_config_still_publishes_the_switches(store, client, monkeypatch):
    """The app learns of a pause from /v1/config and hides the feature; the
    refusal above is for builds that do not."""
    _switches(monkeypatch, "restore", "share")
    r = await client.get("/v1/config")
    assert r.status_code == 200
    assert r.json()["killSwitches"] == ["restore", "share"]
