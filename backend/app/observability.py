"""PII-safe structured operational logging and API error types."""
from __future__ import annotations

import hashlib
import json
import logging
import re
from contextvars import ContextVar
from datetime import datetime, timezone
from typing import Any, Iterable

_request_id: ContextVar[str | None] = ContextVar("request_id", default=None)
_uid: ContextVar[str | None] = ContextVar("uid", default=None)
_device_id: ContextVar[str | None] = ContextVar("device_id", default=None)
# What the caller says it is: the `X-App-Id` as the access log records it
# (`apps.logged_application_id`) and the `X-App-Version` versionCode. Bound by
# the edge middleware for the whole request, so every event a request emits —
# activation, checkout, a device-lock drop — can be counted per app and build
# without each call site passing them.
_app_id: ContextVar[str | None] = ContextVar("app_id", default=None)
_app_version: ContextVar[int | None] = ContextVar("app_version", default=None)

_ALLOWED = frozenset({
    "requestId", "uid", "deviceId", "event", "outcome", "errorCode",
    "dependency", "latencyMs", "method", "path", "status", "attempt",
    "maxAttempts", "httpStatus", "count", "stage",
    "opClass", "routeTemplate", "fileCount", "frameCount", "folderMs",
    "appId", "appVersion", "licenseId", "action",
    # `feature_paused`: which kill switch refused. `activation`: how a code
    # was redeemed (`web` from the account page; absent from the app's route).
    "switch", "via",
})

#: The largest versionCode Android allows (`Integer.MAX_VALUE`).
_MAX_VERSION_CODE = 2_147_483_647


def parse_app_version(raw: str | None) -> int | None:
    """An `X-App-Version` header as a versionCode, or None when absent or not
    one. Caller text, so anything but a plain non-negative int in range is
    dropped rather than logged. Nothing is refused on it."""
    raw = (raw or "").strip()
    if not raw.isascii() or not raw.isdigit() or len(raw) > 10:
        return None
    value = int(raw)
    return value if value <= _MAX_VERSION_CODE else None

# Opaque path segments (session / file / user ids) collapse to {id} so log
# groupings stay countable without retaining identifiers in routeTemplate.
_ID_SEGMENT = re.compile(r"^[A-Za-z0-9_-]{8,}$")


def normalize_route_template(path: str) -> str:
    """Return path with opaque id segments replaced by `{id}`."""
    if not path:
        return "/"
    parts = path.split("/")
    out: list[str] = []
    for part in parts:
        if not part:
            out.append(part)
            continue
        if part in {
            "v1", "healthz", "readyz", "me", "config", "devices", "register",
            "challenge", "sessions", "uploads", "files", "content", "complete",
            "admin", "users", "export", "tasks", "provision-session", "bundle",
            "recycle-bin", "restore",
        }:
            out.append(part)
        elif _ID_SEGMENT.match(part):
            out.append("{id}")
        else:
            out.append(part)
    template = "/".join(out)
    return template if template.startswith("/") else f"/{template}"


# Declared route templates, registered by main.py once the routers are
# included. Matching the request against these is what keeps a static segment
# such as `activate` from collapsing to `{id}` (TD-44); the regex above is only
# the fallback for a path no route declares (a 404).
_PARAM = re.compile(r"\{[^/{}]+\}")
_ROUTES: list[tuple[re.Pattern[str], str]] = []


def register_routes(path_formats: Iterable[str]) -> None:
    """Record route templates (`/v1/sessions/{sid}`) for `route_template`.

    Templates with fewer parameters are tried first, so a static segment wins
    over a parameter at the same depth.
    """
    for fmt in path_formats:
        template = _PARAM.sub("{id}", fmt)
        segments = (
            "[^/]+" if _PARAM.fullmatch(seg) else re.escape(seg)
            for seg in fmt.split("/")
        )
        pattern = re.compile("^" + "/".join(segments) + "$")
        if all(t != template for _, t in _ROUTES):
            _ROUTES.append((pattern, template))
    _ROUTES.sort(key=lambda r: r[1].count("{id}"))


def route_template(path: str) -> str:
    """The declared template for [path] with every parameter as `{id}`."""
    for pattern, template in _ROUTES:
        if pattern.match(path):
            return template
    return normalize_route_template(path)


def template_of(route) -> str:
    """A matched route's template with every parameter as `{id}`."""
    return _PARAM.sub("{id}", route.path_format)


def routed_template(scope) -> str | None:
    """The template of the route FastAPI already matched for this request, or
    None when nothing was routed (a 404, a preflight answered at the edge).

    A route under an included router is matched through its effective route
    context, which carries the prefixed `path_format` (FastAPI 0.141 keeps the
    router's own route, unprefixed, in `scope["route"]`). A route whose own
    pattern matches the whole path (one declared on the app) is used as it
    is. Anything else is left to `route_template`, so a FastAPI that moves the
    context costs a re-match, never a wrong template (TD-198).
    """
    fastapi_scope = scope.get("fastapi")
    context = fastapi_scope.get("effective_route_context") if isinstance(fastapi_scope, dict) else None
    if getattr(context, "path_format", None):
        return template_of(context)
    route = scope.get("route")
    regex = getattr(route, "path_regex", None)
    if regex is not None and getattr(route, "path_format", None) and regex.match(scope.get("path") or ""):
        return template_of(route)
    return None


def classify_route(method: str, path: str, template: str | None = None) -> tuple[str, str]:
    """Map method+path to (opClass, routeTemplate) for usage metering.

    `template` is the route the request was matched to (`routed_template`);
    without it the path is matched against the declared routes again.

    `attest` is dedicated to POST /v1/challenge so shared challenge traffic is
    not double-counted under login/backup/restore buckets.
    """
    method_u = (method or "GET").upper()
    template = template or route_template(path or "/")
    raw = path or "/"

    if raw in {"/healthz", "/readyz"} or template in {"/healthz", "/readyz"}:
        return "health", template
    if template == "/v1/challenge" and method_u == "POST":
        return "attest", template
    if template == "/v1/me" and method_u == "DELETE":
        return "account", template
    if template in {"/v1/me/export", "/v1/me/consents", "/v1/me/terms", "/v1/me/erasure"}:
        return "account", template
    if template in {"/v1/me", "/v1/devices/register"}:
        return "login", template
    if template == "/v1/config":
        return "config", template
    if template.startswith("/v1/admin"):
        return "admin", template
    # A licence's own lifecycle on the device: activate, seat checkout and
    # release, unbind, and the phones a licence licenses (ADR-033).
    # Institution desk traffic is its own class.
    if (template.startswith("/v1/licenses/") or template == "/v1/me/claim"
            or template.startswith("/v1/me/devices")):
        return "license", template
    if template.startswith("/v1/institutions/"):
        return "institution", template
    if template == "/v1/tasks/provision-session":
        return "backup", template
    # Finishing a staff revoke or edit's fan-out (ADR-026).
    if template == "/v1/tasks/license-fanout":
        return "admin", template
    # Ending test accounts past their date (ADR-027): staff work, run daily.
    if template == "/v1/tasks/test-account-expiry":
        return "admin", template
    # Purging the recycle bin's due entries (ADR-035): backup housekeeping.
    if template == "/v1/tasks/recycle-bin-purge":
        return "backup", template
    # The recycle bin (ADR-035): listing it is sync, as the backup list is;
    # restoring or erasing an entry is backup work, as deleting one is.
    if template == "/v1/recycle-bin":
        return "sync", template
    if template.startswith("/v1/recycle-bin/"):
        return "backup", template
    if template == "/v1/sessions" and method_u == "POST":
        return "backup", template
    if template.endswith("/uploads") and "/sessions/" in template:
        return "backup", template
    if template.endswith("/complete") and "/files/" in template and method_u == "POST":
        return "backup", template
    if template == "/v1/sessions" and method_u == "GET":
        return "sync", template
    if template.startswith("/v1/sessions/") and method_u == "DELETE":
        return "backup", template  # erase one cloud backup
    # Re-sending a backed-up session's metadata.json (ADR-013).
    if template.endswith("/metadata") and "/sessions/" in template and method_u == "PUT":
        return "backup", template
    if template.endswith("/files") and "/sessions/" in template and method_u == "GET":
        return "restore", template
    if template.endswith("/content") and "/files/" in template and method_u == "GET":
        return "restore", template
    # Pulling the whole analysis out through a browser is the same act as
    # restoring it onto a phone, one request instead of many.
    if template.endswith("/bundle") and "/sessions/" in template and method_u == "GET":
        return "restore", template
    return "other", template


def metrics_counts(metrics: dict | None, *, file_count: int | None = None) -> dict[str, int]:
    """Extract PII-safe integer counts for session-create metering."""
    out: dict[str, int] = {}
    if file_count is not None:
        out["fileCount"] = int(file_count)
    if not metrics:
        return out
    raw = metrics.get("frameCount")
    if isinstance(raw, bool):
        return out
    if isinstance(raw, (int, float)):
        out["frameCount"] = int(raw)
    return out


class DependencyError(RuntimeError):
    """Known upstream failure with a stable, non-sensitive API code."""

    def __init__(self, code: str, dependency: str, status_code: int = 503):
        super().__init__(code)
        self.code = code
        self.dependency = dependency
        self.status_code = status_code


def short_hash(value: str | None) -> str:
    """A stable 12-hex-digit tag for a personal value (an email address), so a
    log line can say "the same address as before" without holding it. Not a
    secret-keyed hash: it only keeps the address out of clear text, and a log
    reader who already knows an address can match it."""
    if not value:
        return ""
    return hashlib.sha256(value.strip().lower().encode("utf-8")).hexdigest()[:12]


def bind_request(request_id: str):
    return _request_id.set(request_id)


def bind_uid(uid: str | None) -> None:
    _uid.set(uid)


def bind_device(device_id: str | None) -> None:
    _device_id.set(device_id)


def bind_client(app_id: str | None, app_version: int | None) -> None:
    _app_id.set(app_id)
    _app_version.set(app_version)


def reset_request(token) -> None:
    _request_id.reset(token)
    _uid.set(None)
    _device_id.set(None)
    _app_id.set(None)
    _app_version.set(None)


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _safe_value(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int, float)):
        return value
    text = str(value)
    # Identifiers used here are validated opaque IDs. Bound length prevents a
    # malformed dependency response from creating an oversized log entry.
    return text[:256]


def event_payload(event: str, **fields: Any) -> dict:
    payload = {
        "timestamp": utc_timestamp(),
        "event": event,
        "requestId": _request_id.get(),
        "uid": _uid.get(),
        "deviceId": _device_id.get(),
        "appId": _app_id.get(),
        "appVersion": _app_version.get(),
    }
    payload.update({key: _safe_value(value) for key, value in fields.items() if key in _ALLOWED})
    return {key: value for key, value in payload.items() if value is not None}


def log_event(logger: logging.Logger, level: int, event: str, **fields: Any) -> None:
    logger.log(level, json.dumps(event_payload(event, **fields), separators=(",", ":")))


def report_exception(
    logger: logging.Logger,
    *,
    error_code: str,
    dependency: str | None = None,
) -> None:
    """Emit a sanitized event Cloud Error Reporting can ingest without a trace.

    The explicit ReportedErrorEvent type enables grouping while deliberately
    omitting exception messages and stack traces from Cloud Logging.
    """
    payload = event_payload(
        "unhandled_exception",
        outcome="error",
        errorCode=error_code,
        dependency=dependency,
    )
    payload.update({
        "@type": "type.googleapis.com/google.devtools.clouderrorreporting.v1beta1.ReportedErrorEvent",
        "serviceContext": {"service": "semper-api"},
        "message": error_code,
    })
    logger.error(json.dumps(payload, separators=(",", ":")))


# Cloud Logging's names for Python's levels; anything else is DEFAULT.
_SEVERITY = {
    "DEBUG": "DEBUG",
    "INFO": "INFO",
    "WARNING": "WARNING",
    "ERROR": "ERROR",
    "CRITICAL": "CRITICAL",
}


class JsonLineFormatter(logging.Formatter):
    """One JSON object per line, which Cloud Run stores as `jsonPayload`.

    A message that is already a JSON object (`log_event`, `report_exception`)
    is written as that object, field order kept, with `severity` added. Any
    other message is wrapped as `{"severity", "message", "logger"}`. A
    traceback, when the record carries one, goes in `stack_trace`, the field
    Error Reporting reads a `jsonPayload` trace from.

    Behind the default `LEVEL:logger:` prefix every line was `textPayload`, so
    no filter on `jsonPayload.*` matched and Error Reporting never saw the
    `@type` of a reported error.
    """

    def format(self, record: logging.LogRecord) -> str:
        message = record.getMessage()
        payload = _json_object(message)
        severity = _SEVERITY.get(record.levelname, "DEFAULT")
        if payload is None:
            payload = {"severity": severity, "message": message, "logger": record.name}
        else:
            payload.setdefault("severity", severity)
        if record.exc_info:
            payload["stack_trace"] = self.formatException(record.exc_info)
        if record.stack_info:
            payload["stack"] = self.formatStack(record.stack_info)
        return json.dumps(payload, separators=(",", ":"), default=str)


def _json_object(message: str) -> dict | None:
    if not message.startswith("{"):
        return None
    try:
        parsed = json.loads(message)
    except ValueError:
        return None
    return parsed if isinstance(parsed, dict) else None


def configure_logging(*, structured: bool) -> None:
    """INFO and up to stderr; one JSON object per line when `structured`.

    Structured on Cloud Run only: locally the plain `LEVEL:logger:` lines read
    better. uvicorn's own loggers (`uvicorn`, `uvicorn.access`) do not
    propagate to the root, so their handlers get the formatter too; each line
    is still written once.
    """
    logging.basicConfig(level=logging.INFO)
    if not structured:
        return
    formatter = JsonLineFormatter()
    for name in ("", "uvicorn", "uvicorn.access"):
        for handler in logging.getLogger(name).handlers:
            handler.setFormatter(formatter)
