"""Cloud Tasks: enqueue session provisioning, and authenticate the callback.

Why this exists: POST /v1/sessions used to open one Drive resumable session per
file inline. At the 600-file ceiling that is ~1200 sequential round-trips inside
a 60s Cloud Run budget, so a large analysis could not be uploaded at all. The
work now happens in a task; the request just reserves the session.

The callback is authenticated by the OIDC token Cloud Tasks attaches, verified
against the configured invoker service account. It is NOT reachable with a user
ID token or a device signature — it is not a user-facing route.
"""
from __future__ import annotations

import json
import logging

from fastapi import Header, HTTPException

from . import errors
from . import observability as obs
from .config import settings

log = logging.getLogger("semper.tasks")

PROVISION_PATH = "/v1/tasks/provision-session"

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

    from google.auth.transport import requests as ga_requests
    from google.oauth2 import id_token as ga_id_token

    audience = settings.TASKS_TARGET_BASE_URL.rstrip("/")
    try:
        claims = ga_id_token.verify_oauth2_token(
            authorization[7:], ga_requests.Request(), audience or None,
        )
    except Exception as e:  # noqa: BLE001
        log.warning("task OIDC verification failed: %s", e)
        raise HTTPException(401, errors.INVALID_TASK_TOKEN) from e

    email = (claims.get("email") or "").lower()
    expected = (settings.TASKS_INVOKER_SA or "").lower()
    # Any Google account can mint an ID token for a public audience, so the
    # audience check alone is not authentication — the identity must match.
    if not expected or email != expected:
        log.warning("task token from unexpected principal %r", email)
        raise HTTPException(403, errors.NOT_TASK_INVOKER)
    if not claims.get("email_verified", True):
        raise HTTPException(403, errors.NOT_TASK_INVOKER)
    return {"email": email}
