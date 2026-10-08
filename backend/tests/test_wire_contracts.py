"""The wire bodies in `contracts/` are what the backend accepts and returns.

`contracts/*.json` holds one realistic body per request the Android app sends
and per response it reads. The app's `ApiDtosContractTest` decodes and
re-encodes the same files through its DTOs, so a field renamed on either side
fails one of the two suites instead of deploying green and silently decoding a
default.

This side holds:

* each **request** fixture to the pydantic model its route takes: it
  validates, and every key it carries is a field of that model (pydantic
  ignores an unknown key, which is exactly how a renamed field would go
  unnoticed); the ones the scenario can send as they are, it sends;
* each **response** fixture to what the route returns: the scenario below
  drives the real routes over the fake store, and every object in the answer
  has the fixture's keys, no more and no fewer, with the same JSON types
  (null matches anything: the fixture and the scenario may differ in which
  optional values are set).

A response grew a field: add it to the fixture. A field went away or was
renamed: the app's DTO still reads the old name, so fix the client first.
"""
from __future__ import annotations

import json
import typing
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from pydantic import BaseModel

import fake_firestore
import repo_view as repo
from app import audit, deps, drive, models, tasks
from app.routers import account

_CONTRACTS = Path(__file__).resolve().parents[2] / "contracts"

pytestmark = pytest.mark.skipif(not _CONTRACTS.is_dir(), reason="contracts/ not present (backend-only checkout)")

#: Request fixture -> the model its route validates the body with.
REQUESTS: dict[str, type[BaseModel]] = {
    "session_create_request": models.SessionCreate,  # POST /v1/sessions
    "file_complete_request": models.FileComplete,  # POST /v1/files/{id}/complete
    "device_register_request": models.DeviceReg,  # POST /v1/devices/register
    "license_activate_request": models.LicenseActivate,  # POST /v1/licenses/activate
    "terms_accept_request": account.TermsAcceptance,  # POST /v1/me/terms
    "consent_update_request": account.ConsentUpdate,  # PUT /v1/me/consents
}

#: Response fixtures, each produced by the route the scenario names.
RESPONSES = (
    "license_activate_response",  # POST /v1/licenses/activate
    "me_response",  # GET /v1/me
    "config_response",  # GET /v1/config
    "challenge_response",  # POST /v1/challenge
    "session_create_response",  # POST /v1/sessions
    "session_uploads_response",  # GET /v1/sessions/{id}/uploads
    "sessions_response",  # GET /v1/sessions
    "session_files_response",  # GET /v1/sessions/{id}/files
)


def _fixture(name: str):
    return json.loads((_CONTRACTS / f"{name}.json").read_bytes())


def test_every_fixture_is_held_by_this_test():
    on_disk = {p.stem for p in _CONTRACTS.glob("*.json")}
    held = set(REQUESTS) | set(RESPONSES)
    assert on_disk - held == set(), f"contracts/ files no test checks: {sorted(on_disk - held)}"
    assert held - on_disk == set(), f"contracts/ files missing: {sorted(held - on_disk)}"


def test_fixtures_are_lf_and_end_with_a_newline():
    """The Kotlin side reads the same bytes; keep them plain."""
    for path in _CONTRACTS.glob("*.json"):
        raw = path.read_bytes()
        assert b"\r" not in raw and raw.endswith(b"\n"), path.name


# ---------------- requests ----------------
def _nested_model(annotation) -> type[BaseModel] | None:
    """The model inside `List[Model]` / `Optional[Model]` / `Model`, if any."""
    candidates = [annotation, *typing.get_args(annotation)]
    for candidate in candidates:
        if isinstance(candidate, type) and issubclass(candidate, BaseModel):
            return candidate
    return None


def _unknown_keys(model: type[BaseModel], body: dict, path: str = "$") -> list[str]:
    out = []
    for key, value in body.items():
        field = model.model_fields.get(key)
        if field is None:
            out.append(f"{path}.{key}")
            continue
        inner = _nested_model(field.annotation)
        if inner is None:
            continue
        for i, item in enumerate(value if isinstance(value, list) else [value]):
            if isinstance(item, dict):
                out += _unknown_keys(inner, item, f"{path}.{key}[{i}]")
    return out


@pytest.mark.parametrize("name", sorted(REQUESTS))
def test_request_fixture_is_what_the_model_accepts(name):
    model, body = REQUESTS[name], _fixture(name)
    model.model_validate(body)
    unknown = _unknown_keys(model, body)
    assert not unknown, (
        f"contracts/{name}.json sends {unknown}, which {model.__name__} does not declare: "
        "pydantic drops an unknown key, so the backend never sees it"
    )
    required = {k for k, f in model.model_fields.items() if f.is_required()}
    assert required <= set(body), f"{name}: required {sorted(required - set(body))} missing"


def test_the_shape_check_names_an_unknown_nested_key():
    body = _fixture("session_create_request")
    body["files"][0]["size"] = body["files"][0]["bytes"]
    assert _unknown_keys(models.SessionCreate, body) == ["$.files[0].size"]


# ---------------- responses ----------------
def _kind(value) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    return "object"


def shape_diff(fixture, live, path: str = "$") -> list[str]:
    """Where `live` and `fixture` differ in keys or JSON types."""
    if fixture is None or live is None:
        return []
    if _kind(fixture) != _kind(live):
        return [f"{path}: the fixture has {_kind(fixture)}, the backend sends {_kind(live)}"]
    out = []
    if isinstance(fixture, dict):
        for key in sorted(fixture.keys() - live.keys()):
            out.append(f"{path}.{key}: in the fixture, not sent by the backend")
        for key in sorted(live.keys() - fixture.keys()):
            out.append(f"{path}.{key}: sent by the backend, not in the fixture")
        for key in sorted(fixture.keys() & live.keys()):
            out += shape_diff(fixture[key], live[key], f"{path}.{key}")
    elif isinstance(fixture, list) and fixture and live:
        out += [d for item in fixture for d in shape_diff(item, live[0], f"{path}[]")]
        out += [d for item in live[1:] for d in shape_diff(fixture[0], item, f"{path}[]")]
    return sorted(set(out))


def test_shape_diff_reports_keys_and_types_both_ways():
    fixture = {"a": 1, "b": "x", "c": [{"d": True}], "n": None}
    assert shape_diff(fixture, {"a": 2, "b": "y", "c": [{"d": False}], "n": "set"}) == []
    assert shape_diff(fixture, {"a": "2", "b": "y", "c": [{"d": 1, "e": 0}], "n": None}) == [
        "$.a: the fixture has integer, the backend sends string",
        "$.c[].d: the fixture has boolean, the backend sends integer",
        "$.c[].e: sent by the backend, not in the fixture",
    ]
    assert shape_diff(fixture, {"b": "y", "c": [], "n": None}) == ["$.a: in the fixture, not sent by the backend"]


DEVICE = _fixture("device_register_request")["deviceId"]
_NOW = datetime.now(timezone.utc)


@pytest.fixture
def scenario_user(monkeypatch):
    """The dev-auth caller as an institution member on a timed floating seat,
    in grace and holding a lease, so every optional field is set."""
    user = {
        **deps._DEV_USER,
        "role": "user",
        "activeDeviceId": DEVICE,
        "licenseKind": "institution",
        "licensePrefix": "SEMP-AB12",
        "licenseDuration": "timed",
        "licenseExpiresAt": _NOW - timedelta(days=1),
        "licenseGraceDays": 14,
        "licenseSeating": "floating",
        "leaseExpiresAt": _NOW + timedelta(minutes=30),
        "termsAccepted": {"version": "2026-09-15"},
        "improvementConsent": {"granted": False},
    }
    monkeypatch.setattr(deps, "_DEV_USER", user)
    return user


@pytest.fixture
async def live(client, monkeypatch, scenario_user):
    """Drive the routes the app calls, in the order it calls them, and keep
    each answer under its fixture's name."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(
        drive, "ensure_session_folders",
        lambda *a, **k: {"sessionFolderId": "sf", "userFolderId": "uf", "bundle": "sf", "metadata": "sf"},
    )
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    monkeypatch.setattr(tasks, "enqueue_provision", lambda sid: True)
    store._data["users"] = {"dev-user": {"email": "dev@local", "access_status": "APPROVED", "plan": "demo"}}
    out: dict = {}

    async def call(name, method, path, status=200, **kw):
        resp = await client.request(method, path, **kw)
        assert resp.status_code == status, f"{name}: {method} {path} -> {resp.status_code} {resp.text}"
        out[name] = resp.json()
        return out[name]

    device = {"X-Device-Id": DEVICE}
    await call("device_register", "POST", "/v1/devices/register", 201,
               json=_fixture("device_register_request"))
    minted = repo.create_individual_license(email_lock="dev@local", device_id_lock=DEVICE, created_by_uid="admin")
    await call("license_activate_response", "POST", "/v1/licenses/activate",
               json={"key": minted["key"]}, headers=device)
    await call("me_response", "GET", "/v1/me")
    await call("config_response", "GET", "/v1/config")
    await call("consent_update", "PUT", "/v1/me/consents", json=_fixture("consent_update_request"))
    await call("challenge_response", "POST", "/v1/challenge", headers=device)

    request = _fixture("session_create_request")
    created = await call("session_create_response", "POST", "/v1/sessions", json=request)
    sid = created["sessionId"]
    bundle, metadata = (u["fileId"] for u in created["uploads"])

    complete = {**_fixture("file_complete_request"), "sessionId": sid}
    meta = {"size": complete["bytes"], "md5": complete["md5"], "parents": ["sf"]}
    monkeypatch.setattr(drive, "get_file_meta", lambda token, file_id: meta)
    await call("file_complete", "POST", f"/v1/files/{bundle}/complete", json=complete)
    await call("session_uploads_response", "GET", f"/v1/sessions/{sid}/uploads")

    size = request["files"][1]["bytes"]
    monkeypatch.setattr(drive, "get_file_meta", lambda token, file_id: {**meta, "size": size})
    await call("file_complete", "POST", f"/v1/files/{metadata}/complete",
               json={**complete, "driveFileId": "1MetaDataFileIdOnDrive0123456789", "bytes": size})
    await call("sessions_response", "GET", "/v1/sessions")
    await call("session_files_response", "GET", f"/v1/sessions/{sid}/files")
    return out


@pytest.mark.parametrize("name", RESPONSES)
def test_response_fixture_has_the_shape_the_route_returns(live, name):
    diff = shape_diff(_fixture(name), live[name])
    assert not diff, f"contracts/{name}.json and the live response differ:\n  " + "\n  ".join(diff)


def test_the_scenario_reaches_the_states_the_fixtures_show(live):
    """The fixtures show a finished backup and a licensed floating seat in
    grace; a scenario that stopped short would compare against nulls."""
    assert live["sessions_response"]["sessions"][0]["status"] == "COMPLETED"
    assert live["session_files_response"]["files"][0]["status"] == "COMPLETED"
    assert [u["role"] for u in live["session_uploads_response"]["uploads"]] == ["metadata"]
    config = live["config_response"]
    assert (config["mode"], config["licenseSeating"], config["inGrace"]) == ("licensed", "floating", True)
    assert config["leaseExpiresAt"] and config["licenseExpiresAt"] and config["licenseGraceEndsAt"]
    assert live["me_response"]["terms"]["accepted_version"] == "2026-09-15"


def test_timestamps_in_the_fixtures_are_written_as_the_backend_writes_them(live):
    """The app parses these strings; the fixture's must be the backend's format."""
    config, fixture = live["config_response"], _fixture("config_response")
    for key in ("licenseExpiresAt", "licenseGraceEndsAt", "leaseExpiresAt"):
        assert datetime.fromisoformat(fixture[key]).utcoffset() == timedelta(0)
        assert fixture[key][19:] == config[key][-len(fixture[key][19:]):], (key, fixture[key], config[key])
