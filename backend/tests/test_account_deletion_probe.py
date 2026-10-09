"""`GET /v1/me/erasure`: the app's check after an interrupted account deletion.

When the process died between sending `DELETE /v1/me` and its answer, the next
start asks here whether the account went (`CloudErase.probeErasedAccount`)
before it wipes the phone. Erased means this phone's device record is gone or
belongs to someone else. The route reads the ID token alone: the earlier probe
went through `current_user`, which re-created a profile for the erased uid and
mailed support when it was pending (TD-206).
"""
from pathlib import Path

import pytest

import repo_view as repo
from app import deps, errors
from app.config import settings

DEVICE = "d-probe-1"
ROUTE = "/v1/me/erasure"
DEV_UID = deps._DEV_USER["uid"]

_KOTLIN = (
    Path(__file__).resolve().parents[2]
    / "app/src/main/java/com/sempermechanics/semper/data/net/SemperApi.kt"
)


def _device(uid: str, status: str = "ACTIVE") -> dict:
    return {"uid": uid, "status": status, "app": "semper"}


async def _ask(client, device: str | None = DEVICE, **headers):
    if device is not None:
        headers["X-Device-Id"] = device
    return await client.get(ROUTE, headers=headers)


@pytest.mark.skipif(not _KOTLIN.is_file(), reason="app/ not present (backend-only checkout)")
def test_the_app_calls_this_route():
    assert f'"{ROUTE}"' in _KOTLIN.read_text(encoding="utf-8")


async def test_a_living_account_is_not_erased(client, store):
    store._data["devices"] = {DEVICE: _device(DEV_UID)}
    resp = await _ask(client)
    assert resp.status_code == 200
    assert resp.json() == {"erased": False}


async def test_a_retired_device_is_still_the_accounts(client, store):
    # A superseded or revoked device keeps its record; only the erase deletes it,
    # so an admin's reset must not read as an erase (and wipe the phone).
    store._data["devices"] = {DEVICE: _device(DEV_UID, status="SUPERSEDED")}
    assert (await _ask(client)).json() == {"erased": False}


async def test_a_deleted_device_record_means_erased(client, store):
    store._data["devices"] = {}
    assert (await _ask(client)).json() == {"erased": True}


async def test_a_device_now_someone_elses_means_erased(client, store):
    store._data["devices"] = {DEVICE: _device("someone-else")}
    assert (await _ask(client)).json() == {"erased": True}


async def test_asking_creates_no_profile(client, store, monkeypatch):
    # The whole point (TD-206): an erased account must stay erased.
    store._data["users"] = {}
    store._data["devices"] = {}

    def refuse(*a, **k):
        raise AssertionError("the erasure check must not read or create a profile")

    repo.patch(monkeypatch, "get_or_create_user", refuse)
    assert (await _ask(client)).json() == {"erased": True}
    assert store._data["users"] == {}


async def test_a_real_token_is_verified_and_nothing_else(client, store, monkeypatch):
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "APP_CHECK_MODE", "off")
    monkeypatch.setattr(deps, "verify_id_token", lambda token: {"sub": "u-erased"})
    repo.patch(monkeypatch, "get_or_create_user", lambda *a, **k: pytest.fail("profile touched"))
    store._data["devices"] = {DEVICE: _device("u-erased")}

    resp = await _ask(client, Authorization="Bearer tok")
    assert resp.status_code == 200
    assert resp.json() == {"erased": False}


async def test_no_token_is_refused(client, store, monkeypatch):
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    resp = await _ask(client)
    assert resp.status_code == 401
    assert resp.json()["detail"] == errors.MISSING_BEARER


async def test_the_device_header_is_required(client, store):
    resp = await _ask(client, device=None)
    assert resp.status_code == 400
    assert resp.json()["detail"] == "missing_device_id"


async def test_it_is_rate_limited_without_current_user(client, store):
    store._data["devices"] = {DEVICE: _device(DEV_UID)}
    codes = [(await _ask(client)).status_code for _ in range(5)]
    assert codes[:3] == [200, 200, 200]
    assert 429 in codes
