import logging
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from . import errors
from . import observability as obs
from .config import settings
from .routers import (
    account,
    admin,
    devices,
    files,
    health,
    institutions,
    licenses,
    provision_tasks,
    sessions,
)
from .routers._shared import json_dumps  # noqa: F401
from .routers.files import _is_first_byte_request, download_file  # noqa: F401
from .routers.health import _client_key  # noqa: F401
from .session_provision import provision_session, purge_session  # noqa: F401

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("semper")
_access_log = logging.getLogger("semper.access")


def _startup_checks():
    # Required config (token audience, Drive SA, shared drive). A deployed
    # service missing any of these cannot serve real traffic, so fail the start
    # loudly rather than 500 on the first Drive/token call. Locally, warn and
    # continue so partial setups can still be exercised.
    missing = settings.missing_required()
    if missing:
        joined = ", ".join(missing)
        if settings.ON_CLOUD_RUN:
            raise RuntimeError(
                f"Missing required env vars: {joined}. Refusing to start a "
                "deployed service that cannot reach Drive/Firestore. Set them "
                "via --set-env-vars / --set-secrets."
            )
        log.warning("Missing env vars: %s (running locally — continuing)", joined)
    if settings.DEV_INSECURE_AUTH:
        if settings.ON_CLOUD_RUN and not settings.INSECURE_AUTH_ACK:
            raise RuntimeError(
                "DEV_INSECURE_AUTH=1 on a deployed Cloud Run service: user and "
                "device authentication would be bypassed and every caller would "
                "act as an admin. Refusing to start. For a throwaway smoke-test "
                "deployment set INSECURE_AUTH_I_ACCEPT_THE_RISK=1 as well; "
                "otherwise remove DEV_INSECURE_AUTH."
            )
        log.warning("=== DEV_INSECURE_AUTH=1 : auth is BYPASSED. Never use in production. ===")
    if settings.APP_CHECK_MODE not in ("off", "monitor", "enforce"):
        # A misspelt mode must not read as "off". Silently ignoring it would
        # leave an operator believing enforcement is on when nothing is checked,
        # which is the one failure this setting cannot afford.
        raise RuntimeError(
            f"APP_CHECK_MODE={settings.APP_CHECK_MODE!r} is not one of "
            "off / monitor / enforce."
        )


@asynccontextmanager
async def lifespan(app: FastAPI):
    _startup_checks()
    yield


# Interactive docs are served locally (useful) but never from a deployed
# service: /docs, /redoc and /openapi.json publish the full route inventory —
# including every /v1/admin/* path — to anyone who reaches the origin, and they
# are not declared in gateway/openapi.yaml so nothing else gates them.
_docs_enabled = not settings.ON_CLOUD_RUN
app = FastAPI(
    title="Semper API",
    version="1.0",
    lifespan=lifespan,
    docs_url="/docs" if _docs_enabled else None,
    redoc_url="/redoc" if _docs_enabled else None,
    openapi_url="/openapi.json" if _docs_enabled else None,
)


# Browser dashboards only. Bearer tokens, no cookies, so no credentials mode;
# the phone sends no Origin and never hits this. add_middleware stacks
# outward, so the decorators below wrap this one: a preflight answered here
# still passes through security_headers and access_log on the way out.
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CONSOLE_ORIGINS,
    allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
    allow_credentials=False,
    max_age=600,
)


@app.middleware("http")
async def security_headers(request: Request, call_next):
    """Apply browser-safe defaults without claiming HTTP is secure in local dev."""
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Content-Security-Policy"] = "frame-ancestors 'none'"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Permissions-Policy"] = (
        "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
    )
    forwarded_proto = request.headers.get("x-forwarded-proto", "").split(",", 1)[0].strip()
    if settings.ON_CLOUD_RUN and forwarded_proto == "https":
        response.headers["Strict-Transport-Security"] = (
            "max-age=31536000; includeSubDomains"
        )
    return response


@app.middleware("http")
async def access_log(request: Request, call_next):
    """One structured JSON line per request with UTC timestamp, request ID,
    device context, and outcome. Stamps X-Request-Id. Never logs tokens."""
    start = time.perf_counter()
    request_id = uuid.uuid4().hex[:12]
    request.state.uid = None
    request.state.device_id = None
    request.state.request_id = request_id
    ctx_token = obs.bind_request(request_id)
    status = 500
    try:
        try:
            response = await call_next(request)
        except HTTPException:
            raise
        except obs.DependencyError:
            raise
        except Exception:
            obs.report_exception(log, error_code=errors.INTERNAL_ERROR)
            # In production, never leak exception text to clients. Locally and in
            # tests, re-raise so pytest and debuggers still see the real failure.
            if settings.ON_CLOUD_RUN:
                response = JSONResponse(status_code=500, content={"detail": errors.INTERNAL_ERROR})
            else:
                raise
        status = response.status_code
        response.headers["X-Request-Id"] = request_id
        return response
    finally:
        latency_ms = round((time.perf_counter() - start) * 1000, 1)
        outcome = "ok" if status < 400 else ("client_error" if status < 500 else "server_error")
        uid = getattr(request.state, "uid", None)
        device_id = getattr(request.state, "device_id", None)
        obs.bind_uid(uid)
        obs.bind_device(device_id)
        op_class, route_template = obs.classify_route(request.method, request.url.path)
        extra: dict = {}
        counts = getattr(request.state, "usage_counts", None)
        if isinstance(counts, dict):
            extra.update(counts)
        obs.log_event(
            _access_log, logging.INFO, "http_access",
            method=request.method,
            path=request.url.path,
            status=status,
            latencyMs=latency_ms,
            outcome=outcome,
            errorCode=None if status < 400 else f"http_{status}",
            opClass=op_class,
            routeTemplate=route_template,
            **extra,
        )
        obs.reset_request(ctx_token)


@app.exception_handler(obs.DependencyError)
async def dependency_error_handler(request: Request, exc: obs.DependencyError):
    obs.log_event(
        log, logging.ERROR, "dependency_failure",
        outcome="error", errorCode=exc.code, dependency=exc.dependency,
        status=exc.status_code,
    )
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.code})


# Route handlers live in app.routers.* and are included below. They stay plain
# `def`, not `async def`: every Firestore and Drive call is synchronous/blocking,
# so an async handler would stall the event loop. A `def` handler is dispatched
# to Starlette's threadpool. Do not "modernize" these back to async def.

for _router in (
    health.router,
    account.router,
    devices.router,
    licenses.router,
    sessions.router,
    files.router,
    provision_tasks.router,
    admin.router,
    institutions.router,
):
    app.include_router(_router)
    # The access log classifies by declared template, not by guessing which
    # path segments are ids (TD-44).
    obs.register_routes(route.path_format for route in _router.routes)

# Re-exports so existing tests keep `from app.main import …`.
__all__ = [
    "app",
    "json_dumps",
    "_client_key",
    "_is_first_byte_request",
    "download_file",
    "provision_session",
    "purge_session",
]
