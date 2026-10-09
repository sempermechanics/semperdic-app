"""The backend's half of the app's account-deletion probe (TD-165).

An account deletion whose `DELETE /v1/me` was sent and never answered (the
process died) is settled at the app's next start by one device-signed
`GET /v1/sessions/account-deletion-probe/uploads`
(`CloudErase.probeErasedAccount`). The app wipes the phone on 409
`device_not_active` or 403 `not_approved` (the erase landed) and leaves it
alone on 404 `session_not_found` (it did not). These pin those answers, so a
change to the route or its dependency cannot turn a wipe into "still there"
or, worse, the other way round, without a test noticing.
"""
from pathlib import Path

import pytest
from fastapi import HTTPException
from pydantic import TypeAdapter

import repo_view as repo
from app import deps, errors
from app.routers import sessions
from app.validation import SessionId
from tests import test_device_auth as device_auth

# The device-auth fixtures: a real keypair and verified_device wired to it.
keypair = device_auth.keypair
wired = device_auth.wired

PROBE_SID = "account-deletion-probe"
PROBE_PATH = f"/v1/sessions/{PROBE_SID}/uploads"
USER = {"uid": "u1", "email": "a@b.com", "access_status": "APPROVED"}

_KOTLIN = (
    Path(__file__).resolve().parents[2]
    / "app/src/main/java/com/sempermechanics/semper/data/cloud/CloudErase.kt"
)


@pytest.mark.skipif(not _KOTLIN.is_file(), reason="app/ not present (backend-only checkout)")
def test_the_app_probes_this_session_id():
    assert f'PROBE_SESSION_ID = "{PROBE_SID}"' in _KOTLIN.read_text(encoding="utf-8")


def test_the_probe_id_is_a_valid_session_id():
    # A 422 here would read as "no answer" on the phone, forever.
    assert TypeAdapter(SessionId).validate_python(PROBE_SID) == PROBE_SID


def test_the_probe_route_is_device_signed():
    route = next(
        r for r in sessions.router.routes
        if r.path == "/v1/sessions/{sid}/uploads" and "GET" in r.methods
    )
    calls = {d.call for d in route.dependant.dependencies}
    assert deps.verified_device in calls


async def test_an_erased_account_answers_device_not_active(wired, monkeypatch):
    # The erase deleted this phone's device record.
    monkeypatch.setattr(deps.repo, "get_device", lambda did: None)
    with pytest.raises(HTTPException) as refused:
        await deps.verified_device(
            request=device_auth._make_request("GET", PROBE_PATH, b""),
            user=USER,
            x_device_id="d1",
            x_nonce="n1",
            x_signature=device_auth._sign(wired, "n1", "GET", PROBE_PATH, b""),
        )
    assert refused.value.status_code == 409
    assert refused.value.detail == errors.DEVICE_NOT_ACTIVE


def test_a_living_account_answers_session_not_found(monkeypatch):
    # No session has the probe's id (sessions are uuid4 hex).
    repo.patch(monkeypatch, "get_session", lambda sid: None)
    with pytest.raises(HTTPException) as refused:
        sessions._owned_session(PROBE_SID, USER)
    assert refused.value.status_code == 404
    assert refused.value.detail == errors.SESSION_NOT_FOUND
