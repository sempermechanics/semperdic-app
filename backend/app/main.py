import logging
import math
import time
import uuid
from datetime import datetime, timezone
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.datastructures import Headers, MutableHeaders

from . import apps, errors
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
# the phone sends no Origin and never hits this.
# max_age: a browser may reuse a preflight answer for this long. Every
# dashboard call carries Authorization, so each new path or method costs an
# OPTIONS round trip before the real request; at 600 s a desk left open
# re-asked every ten minutes. 7200 s is Chromium's ceiling (Firefox allows
# more), so a larger number would buy nothing. A deploy that narrows the
# origins still takes effect at once: the real response carries no
# Access-Control-Allow-Origin for a dropped origin, whatever the browser cached.
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CONSOLE_ORIGINS,
    allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
    allow_credentials=False,
    max_age=7200,
)


# Every response carries these. HSTS only on Cloud Run behind HTTPS, so that
# local HTTP is never told it is secure.
_SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Content-Security-Policy": "frame-ancestors 'none'",
    "Referrer-Policy": "no-referrer",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
}
_HSTS = "max-age=31536000; includeSubDomains"

# The largest body any route accepts. The largest valid one is a session
# manifest at its 5,000-file ceiling with 256-character names, about 2 MB.
# Every route used to buffer whatever arrived — `verified_device` reads the
# body to hash it before the route's model sees it — so a large junk body cost
# memory in proportion to its size.
MAX_BODY_BYTES = 4 * 1024 * 1024


class EdgeMiddleware:
    """Security headers, `X-Request-Id`, and one structured access-log line
    per request. Never logs tokens.

    A plain ASGI middleware. The two `BaseHTTPMiddleware` layers it replaces
    each ran the rest of the app in a task of its own and passed every
    response body through a memory stream, a 300 s download included.
    `request.state` is `scope["state"]`, so what the dependencies record there
    (`uid`, `device_id`, `usage_counts`) is read back here for the log line.
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        start = time.perf_counter()
        request_id = uuid.uuid4().hex[:12]
        state = scope.setdefault("state", {})
        state.update(uid=None, device_id=None, request_id=request_id)
        ctx_token = obs.bind_request(request_id)
        added = {**_SECURITY_HEADERS, "X-Request-Id": request_id}
        forwarded_proto = Headers(scope=scope).get("x-forwarded-proto", "").split(",", 1)[0].strip()
        if settings.ON_CLOUD_RUN and forwarded_proto == "https":
            added["Strict-Transport-Security"] = _HSTS
        status = 500
        started = False

        received = 0

        async def capped_receive():
            # A body sent without a length is counted as it arrives. An
            # HTTPException, because FastAPI turns anything else raised while
            # it reads a body into a 400.
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > MAX_BODY_BYTES:
                    raise HTTPException(413, errors.REQUEST_TOO_LARGE)
            return message

        async def send_with_headers(message):
            nonlocal status, started
            if message["type"] == "http.response.start":
                started = True
                status = message["status"]
                headers = MutableHeaders(scope=message)
                for name, value in added.items():
                    headers[name] = value
            await send(message)

        declared = Headers(scope=scope).get("content-length", "")
        try:
            try:
                if declared.isdigit() and int(declared) > MAX_BODY_BYTES:
                    # Refused unread.
                    refusal = JSONResponse(status_code=413, content={"detail": errors.REQUEST_TOO_LARGE})
                    await refusal(scope, receive, send_with_headers)
                else:
                    await self.app(scope, capped_receive, send_with_headers)
            except Exception:
                obs.report_exception(log, error_code=errors.INTERNAL_ERROR)
                # In production, never leak exception text to clients. Locally
                # and in tests, re-raise so pytest and debuggers still see the
                # real failure. A response already under way cannot be replaced.
                if not settings.ON_CLOUD_RUN or started:
                    raise
                error = JSONResponse(status_code=500, content={"detail": errors.INTERNAL_ERROR})
                await error(scope, receive, send_with_headers)
        finally:
            self._log(scope, state, status, start)
            obs.reset_request(ctx_token)

    @staticmethod
    def _log(scope, state: dict, status: int, start: float) -> None:
        latency_ms = round((time.perf_counter() - start) * 1000, 1)
        outcome = "ok" if status < 400 else ("client_error" if status < 500 else "server_error")
        obs.bind_uid(state.get("uid"))
        obs.bind_device(state.get("device_id"))
        method, path = scope["method"], scope["path"]
        op_class, route_template = obs.classify_route(method, path)
        counts = state.get("usage_counts")
        obs.log_event(
            _access_log, logging.INFO, "http_access",
            method=method,
            path=path,
            status=status,
            latencyMs=latency_ms,
            outcome=outcome,
            errorCode=None if status < 400 else f"http_{status}",
            opClass=op_class,
            routeTemplate=route_template,
            appId=apps.logged_application_id(Headers(scope=scope).get("x-app-id")),
            **(counts if isinstance(counts, dict) else {}),
        )


# Outermost, so a CORS preflight answered by the layer above still leaves
# with the security headers and its access-log line.
app.add_middleware(EdgeMiddleware)


@app.exception_handler(obs.DependencyError)
async def dependency_error_handler(request: Request, exc: obs.DependencyError):
    obs.log_event(
        log, logging.ERROR, "dependency_failure",
        outcome="error", errorCode=exc.code, dependency=exc.dependency,
        status=exc.status_code,
    )
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.code})


@app.exception_handler(errors.Refusal)
async def refusal_handler(request: Request, exc: errors.Refusal):
    """Every domain refusal, answered from `errors.STATUS` — no route maps
    codes to statuses itself."""
    headers = None
    if exc.retry_at is not None:
        wait = (exc.retry_at - datetime.now(timezone.utc)).total_seconds()
        headers = {"Retry-After": str(max(0, math.ceil(wait)))}
    return JSONResponse(status_code=exc.status, content={"detail": exc.detail}, headers=headers)


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



def served_routes(application: FastAPI):
    """Every route the app serves, with its full path, methods, endpoint and
    dependencies — nested routers included. FastAPI keeps an included router
    as one `_IncludedRouter` entry in `app.routes` and resolves its routes,
    prefixes and all (the roster under institutions and under admin), only
    through `effective_route_contexts()`. The app's own routes (the
    interactive docs) are not part of the API and are left out."""
    for route in application.routes:
        contexts = getattr(route, "effective_route_contexts", None)
        if contexts is not None:
            yield from contexts()


# The access log classifies by declared template, not by guessing which path
# segments are ids (TD-44).
obs.register_routes(route.path_format for route in served_routes(app))
