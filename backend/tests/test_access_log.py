"""The structured access-log middleware runs on every request and emits one
JSON line with latencyMs/outcome (replacing the old plain-text logging)."""
import json


async def test_request_id_header_present(client):
    r = await client.get("/healthz")
    assert r.status_code == 200
    assert r.headers.get("X-Request-Id")


async def test_access_log_line_is_structured_json(client, caplog):
    import logging

    with caplog.at_level(logging.INFO, logger="semper.access"):
        await client.get("/healthz")
    lines = [rec.message for rec in caplog.records if rec.name == "semper.access"]
    assert lines, "no access-log line emitted"
    entry = json.loads(lines[-1])
    assert entry["event"] == "http_access"
    assert entry["method"] == "GET"
    assert entry["path"] == "/healthz"
    assert entry["status"] == 200
    assert entry["outcome"] == "ok"
    assert entry["opClass"] == "health"
    assert entry["routeTemplate"] == "/healthz"
    assert "latencyMs" in entry and "requestId" in entry
    assert "timestamp" in entry
    assert entry["timestamp"].endswith("Z")


async def test_access_log_includes_error_code_on_client_error(client, caplog, monkeypatch):
    import logging

    from app import rate_limit

    monkeypatch.setattr(rate_limit.health_bucket, "allow", lambda key: False)
    with caplog.at_level(logging.INFO, logger="semper.access"):
        await client.get("/healthz")
    lines = [rec.message for rec in caplog.records if rec.name == "semper.access"]
    entry = json.loads(lines[-1])
    assert entry["status"] == 429
    assert entry["errorCode"] == "http_429"


async def test_access_log_names_the_caller_the_auth_dependency_found(client, caplog):
    """The dependencies record the caller on `request.state`; the edge
    middleware reads it back from the same scope for the log line."""
    import logging

    with caplog.at_level(logging.INFO, logger="semper.access"):
        r = await client.get("/v1/me")
    assert r.status_code == 200
    entry = json.loads([rec.message for rec in caplog.records if rec.name == "semper.access"][-1])
    assert entry["uid"] == "dev-user"
    assert entry["opClass"] == "login"


async def test_a_hidden_failure_still_carries_the_edge_headers(client, monkeypatch):
    """On Cloud Run an unexpected exception answers `internal_error`; that
    answer leaves through the same headers as any other."""
    from app.config import settings
    from app.main import app

    monkeypatch.setattr(settings, "ON_CLOUD_RUN", True)

    @app.get("/__test_edge_boom")
    def boom():
        raise RuntimeError("secret internals")

    try:
        r = await client.get("/__test_edge_boom")
    finally:
        app.router.routes = [
            rt for rt in app.router.routes if getattr(rt, "path", None) != "/__test_edge_boom"
        ]
    assert r.status_code == 500
    assert r.json() == {"detail": "internal_error"}
    assert r.headers["x-content-type-options"] == "nosniff"
    assert r.headers["x-request-id"]


def _access_entries(caplog) -> list[dict]:
    return [json.loads(rec.message) for rec in caplog.records if rec.name == "semper.access"]


async def test_access_log_names_the_app_id_each_call_sends(client, caplog, monkeypatch):
    """TD-176: the old `com.indicvision.*` ids can go once the log shows no
    call sends them; a known id is logged as sent."""
    import logging

    import fake_firestore

    fake_firestore.install(monkeypatch)
    sent = ("com.sempermechanics.semper", "com.indicvision.semper", " com.sempermechanics.materialtesting ")
    with caplog.at_level(logging.INFO, logger="semper.access"):
        for app_id in sent:
            r = await client.get("/v1/me", headers={"X-App-Id": app_id})
            assert r.status_code == 200
    logged = [e["appId"] for e in _access_entries(caplog)]
    assert logged == ["com.sempermechanics.semper", "com.indicvision.semper", "com.sempermechanics.materialtesting"]


async def test_access_log_records_an_unknown_app_id_as_unknown(client, caplog, monkeypatch):
    """The refused call is logged too, and the caller's text never is."""
    import logging

    import fake_firestore

    fake_firestore.install(monkeypatch)
    crafted = ("com.example.other", "com.sempermechanics.semper" + "x" * 500, '{"appId":"forged"}')
    with caplog.at_level(logging.INFO, logger="semper.access"):
        for app_id in crafted:
            r = await client.get("/v1/me", headers={"X-App-Id": app_id})
            assert r.status_code == 400
            assert r.json()["detail"] == "unknown_app"
    entries = _access_entries(caplog)
    assert [e["appId"] for e in entries] == ["unknown"] * 3
    assert all(e["status"] == 400 for e in entries)
    assert "forged" not in caplog.text and "com.example" not in caplog.text


async def test_access_log_records_a_call_without_the_header_as_none(client, caplog):
    import logging

    with caplog.at_level(logging.INFO, logger="semper.access"):
        await client.get("/healthz")
    assert _access_entries(caplog)[-1]["appId"] == "none"


def test_a_header_with_line_breaks_is_logged_as_unknown():
    """An HTTP client refuses to send one; the helper still never passes it on."""
    from app import apps

    assert apps.logged_application_id("com.sempermechanics.semper\n{\"event\":\"x\"}") == "unknown"
    assert apps.logged_application_id("com.indicvision.semper\r\n") == "com.indicvision.semper"
    assert apps.logged_application_id("") == "none"
    assert apps.logged_application_id(None) == "none"
