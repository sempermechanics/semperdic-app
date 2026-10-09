"""PII-safe structured operational logging and API error types."""
from __future__ import annotations

import json
import logging
import re
from contextvars import ContextVar
from datetime import datetime, timezone
from typing import Any, Iterable

_request_id: ContextVar[str | None] = ContextVar("request_id", default=None)
_uid: ContextVar[str | None] = ContextVar("uid", default=None)
_device_id: ContextVar[str | None] = ContextVar("device_id", default=None)

_ALLOWED = frozenset({
    "requestId", "uid", "deviceId", "event", "outcome", "errorCode",
    "dependency", "latencyMs", "method", "path", "status", "attempt",
    "maxAttempts", "httpStatus", "count", "stage",
    "opClass", "routeTemplate", "fileCount", "frameCount", "folderMs",
})

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


def classify_route(method: str, path: str) -> tuple[str, str]:
    """Map method+path to (opClass, routeTemplate) for usage metering.

    `attest` is dedicated to POST /v1/challenge so shared challenge traffic is
    not double-counted under login/backup/restore buckets.
    """
    method_u = (method or "GET").upper()
    template = route_template(path or "/")
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
    # release, unbind. Institution desk traffic is its own class.
    if template.startswith("/v1/licenses/"):
        return "license", template
    if template.startswith("/v1/institutions/"):
        return "institution", template
    if template == "/v1/tasks/provision-session":
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


def bind_request(request_id: str):
    return _request_id.set(request_id)


def bind_uid(uid: str | None) -> None:
    _uid.set(uid)


def bind_device(device_id: str | None) -> None:
    _device_id.set(device_id)


def reset_request(token) -> None:
    _request_id.reset(token)
    _uid.set(None)
    _device_id.set(None)


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
