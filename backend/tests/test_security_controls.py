import json
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app import rate_limit
from app.config import settings

ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.asyncio
async def test_api_security_headers(client, monkeypatch):
    monkeypatch.setattr(rate_limit.health_bucket, "allow", lambda key: True)
    response = await client.get("/healthz")
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["x-frame-options"] == "DENY"
    assert response.headers["content-security-policy"] == "frame-ancestors 'none'"
    assert response.headers["referrer-policy"] == "no-referrer"
    assert "camera=()" in response.headers["permissions-policy"]
    assert "strict-transport-security" not in response.headers


@pytest.mark.asyncio
async def test_hsts_only_on_cloud_run_https(client, monkeypatch):
    monkeypatch.setattr(rate_limit.health_bucket, "allow", lambda key: True)
    monkeypatch.setattr(settings, "ON_CLOUD_RUN", True)
    insecure = await client.get("/healthz", headers={"x-forwarded-proto": "http"})
    assert "strict-transport-security" not in insecure.headers
    secure = await client.get("/healthz", headers={"x-forwarded-proto": "https"})
    hsts = secure.headers["strict-transport-security"]
    assert "max-age=31536000" in hsts
    # Subdomains must be covered too, or a sibling host can be stripped to HTTP.
    assert "includeSubDomains" in hsts


CONSOLE_ORIGIN = "https://app.sempermechanics.com"


@pytest.mark.asyncio
async def test_console_preflight_is_answered_for_a_listed_origin(client):
    # The dashboards send Authorization cross-origin, so the browser asks
    # first. An unanswered preflight is a page that renders and does nothing.
    response = await client.options(
        "/v1/me",
        headers={
            "origin": CONSOLE_ORIGIN,
            "access-control-request-method": "GET",
            "access-control-request-headers": "authorization",
        },
    )
    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == CONSOLE_ORIGIN
    allowed = response.headers["access-control-allow-headers"].lower()
    assert "authorization" in allowed
    assert "content-type" in allowed
    assert "PATCH" in response.headers["access-control-allow-methods"]
    # Bearer tokens only — a credentialed CORS grant would let a cookie ride.
    assert "access-control-allow-credentials" not in response.headers
    # The preflight still leaves through security_headers.
    assert response.headers["x-frame-options"] == "DENY"
    # Answered once per two hours (Chromium's ceiling), not every ten minutes.
    assert response.headers["access-control-max-age"] == "7200"


@pytest.mark.asyncio
async def test_console_preflight_refuses_an_unlisted_origin(client):
    response = await client.options(
        "/v1/me",
        headers={
            "origin": "https://evil.example",
            "access-control-request-method": "GET",
        },
    )
    assert "access-control-allow-origin" not in response.headers


@pytest.mark.asyncio
async def test_simple_request_from_console_origin_keeps_security_headers(
    client, monkeypatch
):
    monkeypatch.setattr(rate_limit.health_bucket, "allow", lambda key: True)
    response = await client.get("/healthz", headers={"origin": CONSOLE_ORIGIN})
    assert response.headers["access-control-allow-origin"] == CONSOLE_ORIGIN
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["content-security-policy"] == "frame-ancestors 'none'"


def test_firestore_indexes_declare_ttl_policies():
    # The Firebase CLI counts each live TTL policy as a field override; one the
    # file omits makes a non-interactive `deploy --only firestore:indexes` stop,
    # and `--force` would delete it. An empty `indexes` list would also switch
    # the field's single-field indexing off, so each one mirrors the default.
    spec = json.loads(
        (ROOT / "backend" / "firestore.indexes.json").read_text(encoding="utf-8")
    )
    overrides = {
        (field["collectionGroup"], field["fieldPath"]): field
        for field in spec["fieldOverrides"]
    }
    assert set(overrides) == {
        ("challenges", "expireAt"),
        ("deleted_licenses", "purgeAt"),
        ("deleted_seats", "purgeAt"),
    }
    for field in overrides.values():
        assert field["ttl"] is True
        modes = sorted(
            (index.get("order") or index.get("arrayConfig"), index["queryScope"])
            for index in field["indexes"]
        )
        assert modes == [
            ("ASCENDING", "COLLECTION"),
            ("CONTAINS", "COLLECTION"),
            ("DESCENDING", "COLLECTION"),
        ]


def test_registry_cleanup_policy_keeps_serving_and_rollback_images():
    # Applied to the live registry by hand (BACKEND_SETUP_GCP.md A7), so a dropped
    # rule or a mistyped prefix would otherwise reach it unchecked. The keep rules
    # are all that stop the 15-day delete: deploy-backend.yml moves `serving` and
    # `rollback-prev`, and Cloud Run needs a revision's image at every cold start.
    rules = {
        rule["name"]: rule
        for rule in json.loads(
            (ROOT / "backend" / "deploy" / "ar-cleanup-policy.json").read_text(encoding="utf-8")
        )
    }
    assert set(rules) == {
        "delete-older-than-15d", "keep-serving", "keep-rollback-tags", "keep-recent-5",
    }
    assert rules["delete-older-than-15d"]["action"] == {"type": "Delete"}
    assert rules["delete-older-than-15d"]["condition"] == {"tagState": "any", "olderThan": "15d"}
    for name, prefixes in (("keep-serving", ["serving", "latest"]),
                           ("keep-rollback-tags", ["rollback"])):
        assert rules[name]["action"] == {"type": "Keep"}
        assert rules[name]["condition"] == {"tagState": "tagged", "tagPrefixes": prefixes}
    assert rules["keep-recent-5"]["action"] == {"type": "Keep"}
    assert rules["keep-recent-5"]["mostRecentVersions"] == {"keepCount": 5}
    workflow = (ROOT / ".github" / "workflows" / "deploy-backend.yml").read_text(encoding="utf-8")
    assert 'pin "$NEW" serving' in workflow
    assert 'pin "$OLD" rollback-prev' in workflow


def test_firestore_rules_are_deny_all_and_deployable():
    rules = (ROOT / "firestore.rules").read_text(encoding="utf-8")
    assert "allow read, write: if false;" in rules
    assert "if true" not in rules

    # The rules deploy through a script that stages them beside a firebase.json
    # of its own: the CLI refuses files outside its project directory, so a
    # `firestore` block pointing at ../firestore.rules could never deploy.
    script = (ROOT / "scripts" / "deploy-firestore.sh").read_text(encoding="utf-8")
    assert '"${ROOT}/firestore.rules"' in script
    assert '"${ROOT}/backend/firestore.indexes.json"' in script
    assert 'PROJECT="${PROJECT:-indicvision-dic-app}"' in script
    assert "allow read, write: if false;" in script

    config = json.loads(
        (ROOT / "firebase-hosting" / "firebase.json").read_text(encoding="utf-8")
    )
    assert "firestore" not in config, "Hosting's firebase.json cannot deploy Firestore"
    assert config["hosting"]["site"] == "indicvision-dic-app-auth"
    keys = {
        header["key"]
        for entry in config["hosting"]["headers"]
        if entry["source"] == "**"
        for header in entry["headers"]
    }
    assert {
        "X-Content-Type-Options",
        "X-Frame-Options",
        "Content-Security-Policy",
        "Referrer-Policy",
        "Permissions-Policy",
        "Strict-Transport-Security",
    } <= keys


@pytest.mark.asyncio
async def test_health_rate_limit_returns_429(client, monkeypatch):
    monkeypatch.setattr(rate_limit.health_bucket, "allow", lambda key: False)
    response = await client.get("/healthz")
    assert response.status_code == 429
    assert response.json()["detail"] == "rate_limited"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("bucket_name", "method", "path", "json_body"),
    [
        (
            "device_register_bucket",
            "post",
            "/v1/devices/register",
            None,
        ),
        (
            "file_complete_bucket",
            "post",
            "/v1/files/file-1/complete",
            {"sessionId": "session-1", "driveFileId": "drive-1", "bytes": 1},
        ),
        (
            "session_verify_bucket",
            "get",
            "/v1/sessions?verify=true",
            None,
        ),
    ],
)
async def test_costly_routes_have_in_process_limit(
    client, monkeypatch, bucket_name, method, path, json_body
):
    monkeypatch.setattr(getattr(rate_limit, bucket_name), "allow", lambda key: False)
    if bucket_name == "device_register_bucket":
        public_key = ec.generate_private_key(ec.SECP256R1()).public_key()
        json_body = {
            "deviceId": "device-123",
            "publicKeyPem": public_key.public_bytes(
                serialization.Encoding.PEM,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            ).decode(),
        }
    response = await client.request(method, path, json=json_body)
    assert response.status_code == 429
    assert response.json()["detail"] == "rate_limited"


@pytest.mark.asyncio
async def test_a_body_over_the_cap_is_refused_before_it_is_read(client, monkeypatch):
    """Every route used to buffer whatever body arrived."""
    from app import main

    monkeypatch.setattr(main, "MAX_BODY_BYTES", 1024)
    declared = await client.post("/v1/sessions", content=b"{" + b" " * 2048 + b"}",
                                 headers={"Content-Type": "application/json"})
    assert declared.status_code == 413
    assert declared.json() == {"detail": "request_too_large"}
    assert declared.headers["x-content-type-options"] == "nosniff"

    async def chunks():
        for _ in range(4):
            yield b" " * 512

    # Sent without a length, it is counted as it arrives.
    streamed = await client.post("/v1/sessions", content=chunks(),
                                 headers={"Content-Type": "application/json"})
    assert streamed.status_code == 413
    assert streamed.json() == {"detail": "request_too_large"}


@pytest.mark.asyncio
async def test_a_body_under_the_cap_is_served(client, monkeypatch):
    from app import main

    monkeypatch.setattr(main, "MAX_BODY_BYTES", 1024)
    r = await client.post("/v1/sessions", json={})
    assert r.status_code == 422
