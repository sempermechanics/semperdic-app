"""Cloud Tasks: enqueue session provisioning, and authenticate the callback
(and the Cloud Scheduler jobs that end expired test accounts and purge the
recycle bin, which sign their OIDC tokens as the same invoker).

Why this exists: POST /v1/sessions used to open one Drive resumable session per
file inline. At the 600-file ceiling that is ~1200 sequential round-trips inside
a 60s Cloud Run budget, so a large analysis could not be uploaded at all. The
work now happens in a task; the request just reserves the session.

The callback is authenticated by the OIDC token Cloud Tasks attaches, verified
against the configured invoker service account. It is NOT reachable with a user
ID token or a device signature — it is not a user-facing route.
"""
from __future__ import annotations

import functools
import json
import logging
from datetime import datetime, timedelta, timezone

from fastapi import Header, HTTPException

from . import errors
from . import observability as obs
from .config import settings

log = logging.getLogger("semper.tasks")

PROVISION_PATH = "/v1/tasks/provision-session"
#: The callback that finishes a licence's pending fan-out (ADR-026).
LICENSE_FANOUT_PATH = "/v1/tasks/license-fanout"
#: The daily Cloud Scheduler call that ends test accounts past their
#: `expiresAt` (ADR-027). OIDC like the two above, and off the gateway.
TEST_ACCOUNT_EXPIRY_PATH = "/v1/tasks/test-account-expiry"
#: The daily Cloud Scheduler call that purges recycle-bin entries past their
#: `purgeAt` (ADR-035). OIDC as the invoker, and off the gateway, likewise.
RECYCLE_BIN_PURGE_PATH = "/v1/tasks/recycle-bin-purge"
#: How long after a licence action its fan-out task is due: past the Cloud Run
#: request timeout (300 s), so the action's own call has finished, or died,
#: before the task looks.
FANOUT_TASK_DELAY_S = 360

_client = None


def _tasks_client():
    """One client per process: building it opens a gRPC channel (~100 ms)."""
    global _client
    if _client is None:
        from google.cloud import tasks_v2

        _client = tasks_v2.CloudTasksClient()
    return _client


def enqueue_provision(sid: str) -> bool:
    """Queue provisioning for one session. False means "do it inline instead".

    Returns False rather than raising when the queue is not configured or the
    enqueue fails: a session that cannot be queued must still be provisionable,
    and the caller falls back to the synchronous path.
    """
    if not settings.tasks_enabled:
        return False
    try:
        from google.cloud import tasks_v2

        client = _tasks_client()
        parent = client.queue_path(
            settings.GCP_PROJECT, settings.TASKS_LOCATION, settings.TASKS_QUEUE,
        )
        client.create_task(
            parent=parent,
            task={
                # Deterministic name = Cloud Tasks de-duplicates. A retried
                # create_session for the same session cannot double-provision.
                "name": f"{parent}/tasks/provision-{sid}",
                "http_request": {
                    "http_method": tasks_v2.HttpMethod.POST,
                    "url": settings.TASKS_TARGET_BASE_URL.rstrip("/") + PROVISION_PATH,
                    "headers": {"Content-Type": "application/json"},
                    "body": json.dumps({"sessionId": sid}).encode(),
                    "oidc_token": {
                        "service_account_email": settings.TASKS_INVOKER_SA,
                        "audience": settings.TASKS_TARGET_BASE_URL.rstrip("/"),
                    },
                },
            },
        )
        return True
    except Exception as e:  # noqa: BLE001
        # Includes AlreadyExists on a duplicate task name, which is a success
        # for our purposes — the work is queued either way.
        if type(e).__name__ == "AlreadyExists":
            log.info("provision task for %s already queued", sid)
            return True
        # ERROR, not a warning: the upload still works (inline), but every large
        # manifest is now provisioned on the request path. A missing IAM grant
        # sat here unnoticed for weeks as a WARNING. The exception type goes in
        # the event; its message stays in the plain log line below.
        obs.log_event(log, logging.ERROR, "provision_enqueue_failed", outcome="error",
                      errorCode="tasks_enqueue_failed", dependency="cloud_tasks",
                      errorType=type(e).__name__)
        log.error("Cloud Tasks enqueue failed for %s (%s) — provisioning inline", sid, e)
        return False


def enqueue_license_fanout(license_id: str, fanout_id: str) -> bool:
    """Schedule the task that finishes a licence's pending fan-out, due
    `FANOUT_TASK_DELAY_S` from now. False when the queue is not configured or
    the enqueue failed; the action then relies on its own inline run.

    The name carries the fan-out's id, so a retried action schedules a task
    of its own, and Cloud Tasks drops a duplicate create of the same one."""
    if not settings.tasks_enabled:
        return False
    try:
        from google.cloud import tasks_v2

        client = _tasks_client()
        parent = client.queue_path(
            settings.GCP_PROJECT, settings.TASKS_LOCATION, settings.TASKS_QUEUE,
        )
        base = settings.TASKS_TARGET_BASE_URL.rstrip("/")
        client.create_task(
            parent=parent,
            task={
                "name": f"{parent}/tasks/license-fanout-{fanout_id}",
                "schedule_time": datetime.now(timezone.utc) + timedelta(seconds=FANOUT_TASK_DELAY_S),
                "http_request": {
                    "http_method": tasks_v2.HttpMethod.POST,
                    "url": base + LICENSE_FANOUT_PATH,
                    "headers": {"Content-Type": "application/json"},
                    "body": json.dumps({"licenseId": license_id}).encode(),
                    "oidc_token": {
                        "service_account_email": settings.TASKS_INVOKER_SA,
                        "audience": base,
                    },
                },
            },
        )
        return True
    except Exception as e:  # noqa: BLE001
        if type(e).__name__ == "AlreadyExists":
            return True
        obs.log_event(log, logging.ERROR, "license_fanout_enqueue_failed", outcome="error",
                      errorCode="tasks_enqueue_failed", dependency="cloud_tasks",
                      errorType=type(e).__name__)
        return False


@functools.lru_cache(maxsize=1)
def _oidc_transport():
    """The transport every callback's token check fetches Google's certificates
    through. A new `Request()` per callback opened a new TCP and TLS connection
    to Google on every task."""
    from google.auth.transport import requests as ga_requests

    return ga_requests.Request()


def tasks_caller(authorization: str = Header(default="")) -> dict:
    """Authenticate a Cloud Tasks callback via its OIDC token.

    Distinct from every other dependency in deps.py: the caller is Google, not a
    user, so there is no uid, no device and no access_status. The only thing that
    matters is that the token was minted for our audience and carries the
    configured invoker service account's email.
    """
    if settings.DEV_INSECURE_AUTH:
        return {"email": settings.TASKS_INVOKER_SA or "dev-task-invoker"}
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, errors.MISSING_BEARER)

    from google.oauth2 import id_token as ga_id_token

    audience = settings.TASKS_TARGET_BASE_URL.rstrip("/")
    try:
        claims = ga_id_token.verify_oauth2_token(
            authorization[7:], _oidc_transport(), audience or None,
        )
    except Exception as e:  # noqa: BLE001
        log.warning("task OIDC verification failed: %s", e)
        raise HTTPException(401, errors.INVALID_TASK_TOKEN) from e

    email = (claims.get("email") or "").lower()
    expected = (settings.TASKS_INVOKER_SA or "").lower()
    # Any Google account can mint an ID token for a public audience, so the
    # audience check alone is not authentication — the identity must match.
    if not expected or email != expected:
        log.warning("task token from unexpected principal sha=%s", obs.short_hash(email))  # nosemgrep: python.lang.security.audit.logging.logger-credential-leak.python-logger-credential-disclosure - a short hash of the caller's address, not the token
        raise HTTPException(403, errors.NOT_TASK_INVOKER)
    if not claims.get("email_verified", True):
        raise HTTPException(403, errors.NOT_TASK_INVOKER)
    return {"email": email}
