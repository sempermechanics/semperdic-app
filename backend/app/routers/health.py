import logging
import time

from fastapi import APIRouter, Request

from .. import firestore_repo as repo
from .. import drive, errors
from .. import observability as obs
from .. import rate_limit

log = logging.getLogger("semper")
router = APIRouter()


def _client_key(request: Request) -> str:
    """Best available caller identity for the unauthenticated health limiter.

    `request.client.host` behind API Gateway / the Cloud Run front end is the
    *proxy*, so keying on it alone puts every external caller in one bucket —
    one noisy client would then starve the load balancer's own probes. Trust the
    leftmost X-Forwarded-For entry, which the Google front end sets, and fall
    back to the socket peer when the header is absent (direct/local calls).
    """
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        first = forwarded.split(",", 1)[0].strip()
        if first:
            return first[:64]
    return request.client.host if request.client else "unknown"


@router.get("/healthz")
def healthz(request: Request):
    # Liveness only: process is up. Do not probe dependencies here — a slow
    # Firestore/Drive outage must not restart healthy instances.
    rate_limit.enforce(rate_limit.health_bucket, _client_key(request))
    return {"ok": True}


@router.get("/readyz")
def readyz(request: Request):
    """Readiness: Firestore + Drive must answer within a bounded budget.

    Returns stable 503 detail codes (`firestore_unreachable`, `drive_unhealthy`,
    …) so load balancers and smoke checks can act without parsing messages.
    """
    rate_limit.enforce(rate_limit.health_bucket, _client_key(request))
    started = time.perf_counter()
    try:
        repo.ping()
        drive.ping()
    except obs.DependencyError:
        raise
    except Exception as e:  # noqa: BLE001
        obs.log_event(
            log, logging.ERROR, "readyz_unexpected",
            outcome="error", errorCode=errors.READYZ_FAILED, dependency="unknown",
        )
        raise obs.DependencyError(errors.READYZ_FAILED, "unknown") from e
    latency_ms = round((time.perf_counter() - started) * 1000, 1)
    obs.log_event(
        log, logging.INFO, "readyz_ok",
        outcome="ok", latencyMs=latency_ms, dependency="firestore+drive",
    )
    return {"ok": True, "checks": {"firestore": "ok", "drive": "ok"}}
