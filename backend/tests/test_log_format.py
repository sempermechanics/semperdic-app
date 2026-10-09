"""On Cloud Run every log line is one JSON object, so Cloud Logging stores it
as `jsonPayload` (filters on `jsonPayload.appId`, Error Reporting's `@type`)
rather than as `textPayload` behind a `LEVEL:logger:` prefix."""
import json
import logging

import pytest

from app import observability as obs


def _record(msg, level=logging.INFO, name="semper", args=(), exc_info=None):
    return logging.LogRecord(name, level, __file__, 1, msg, args, exc_info)


def _formatted(record) -> dict:
    return json.loads(obs.JsonLineFormatter().format(record))


def test_a_json_message_keeps_its_fields_in_order_with_severity_added():
    line = '{"timestamp":"t","event":"http_access","status":200}'
    out = _formatted(_record(line))
    assert list(out) == ["timestamp", "event", "status", "severity"]
    assert out == {"timestamp": "t", "event": "http_access", "status": 200, "severity": "INFO"}


def test_a_severity_already_in_the_message_is_kept():
    assert _formatted(_record('{"severity":"NOTICE"}', logging.ERROR))["severity"] == "NOTICE"


@pytest.mark.parametrize("level, severity", [
    (logging.DEBUG, "DEBUG"), (logging.INFO, "INFO"), (logging.WARNING, "WARNING"),
    (logging.ERROR, "ERROR"), (logging.CRITICAL, "CRITICAL"), (25, "DEFAULT"),
])
def test_plain_text_is_wrapped(level, severity):
    out = _formatted(_record("Erased session %s (%d files)", level, "semper.x", ("s1", 3)))
    assert out == {"severity": severity, "message": "Erased session s1 (3 files)", "logger": "semper.x"}


@pytest.mark.parametrize("msg", ["[1,2]", "42", '"text"', "{not json", "null"])
def test_json_that_is_not_an_object_is_plain_text(msg):
    assert _formatted(_record(msg)) == {"severity": "INFO", "message": msg, "logger": "semper"}


def test_an_exception_carries_its_traceback():
    try:
        raise RuntimeError("boom")
    except RuntimeError:
        import sys
        record = _record("firestore ping failed", logging.ERROR, exc_info=sys.exc_info())
    out = _formatted(record)
    assert out["message"] == "firestore ping failed" and out["severity"] == "ERROR"
    assert "Traceback" in out["stack_trace"] and "RuntimeError: boom" in out["stack_trace"]


def test_a_reported_error_keeps_its_type_for_error_reporting():
    logger = logging.getLogger("test.report")
    seen = []
    handler = logging.Handler()
    handler.emit = lambda record: seen.append(_formatted(record))
    logger.addHandler(handler)
    try:
        obs.report_exception(logger, error_code="internal_error")
    finally:
        logger.removeHandler(handler)
    assert seen[0]["@type"].endswith("ReportedErrorEvent")
    assert seen[0]["severity"] == "ERROR" and seen[0]["message"] == "internal_error"


async def test_a_real_access_line_formats_with_app_id_at_the_top(client, caplog):
    with caplog.at_level(logging.INFO, logger="semper.access"):
        await client.get("/healthz", headers={"X-App-Id": "com.sempermechanics.semper"})
    record = [r for r in caplog.records if r.name == "semper.access"][-1]
    out = _formatted(record)
    assert out["event"] == "http_access" and out["severity"] == "INFO"
    assert out["appId"] == "com.sempermechanics.semper"


def test_structured_logging_formats_the_root_and_uvicorn_handlers(monkeypatch):
    handlers = {name: logging.StreamHandler() for name in ("", "uvicorn", "uvicorn.access")}
    for name, handler in handlers.items():
        monkeypatch.setattr(logging.getLogger(name), "handlers", [handler])
    obs.configure_logging(structured=True)
    assert all(isinstance(h.formatter, obs.JsonLineFormatter) for h in handlers.values())


def test_local_logging_stays_plain(monkeypatch):
    handler = logging.StreamHandler()
    monkeypatch.setattr(logging.getLogger(), "handlers", [handler])
    obs.configure_logging(structured=False)
    assert not isinstance(handler.formatter, obs.JsonLineFormatter)
