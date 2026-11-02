import logging

import requests
from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .. import apps, audit, entitlement_token, errors, repo, legal
from .. import observability as obs
from .. import rate_limit
from ..config import settings
from ..deps import (
    any_status_user,
    current_user,
    rate_limited,
    request_app,
    token_uid,
    verified_device,
)
from ..services import account as account_service
from ..validation import require_header_identifier

log = logging.getLogger("semper")
router = APIRouter()


def _terms_block(user: dict) -> dict:
    """What the app compares to decide whether to show the acceptance screen."""
    accepted = user.get("termsAccepted") or {}
    return {
        "required_version": legal.TERMS_VERSION,
        "accepted_version": accepted.get("version"),
        "terms_url": legal.TERMS_URL,
        "privacy_url": legal.PRIVACY_URL,
    }


def _test_account(user: dict) -> dict | None:
    """`{purpose, expiresAt}` for a staff test account (ADR-027), so the app
    can tag its analytics and say when the login ends; null for every real
    account."""
    tag = repo.test_account_of(user)
    if tag is None:
        return None
    return {"purpose": tag.get("purpose") or "", "expiresAt": tag.get("expiresAt")}


def _improvement_consent(user: dict) -> bool | None:
    """None until the user has answered; the app treats None as "not asked"."""
    consent = user.get("improvementConsent")
    return None if not consent else bool(consent.get("granted"))


@router.get("/v1/me")
def me(request: Request, user=Depends(current_user)):
    """Who the caller is, and what their license currently grants.

    The `license` block answers "am I entitled, and for how much longer" in the
    same round trip as identity, so the app can warn about an approaching
    expiry instead of only discovering it when `mode` silently flips to demo.
    It costs no extra Firestore read — `current_user` already returns the whole
    user document, and the summary is pure over it.

    `/v1/config` remains the source of truth for limits and feature flags; this
    is deliberately the smaller answer.

    `license` is the calling app's (`X-App-Id`, resolved by `current_user`),
    the licence the same app's `/v1/config` answers for, so the two can be
    compared. A browser sends no `X-App-Id` and so reads Semper's, as before.
    """
    summary = repo.license_summary(user, getattr(request.state, "app", None) or apps.SEMPER)
    return {
        "uid": user["uid"],
        "email": user.get("email"),
        "role": user.get("role"),
        "access_status": user["access_status"],
        # Each app's own licence (ADR-033), for the web account page, which
        # shows both. `license` is the calling app's: Semper's for a browser.
        "licences": {app: {**_licence_block(repo.license_summary(user, app)),
                           "pendingLicence": repo.pending_upgrade(user, app)}
                     for app in apps.ALL},
        "license": {
            "mode": summary["mode"],
            "kind": summary["licenseKind"],
            "prefix": summary["prefix"],
            "duration": summary["duration"],
            # Both null when perpetual. inGrace is past expiry but still fully
            # entitled — a warning, not a restriction.
            "expiresAt": summary["expiresAt"],
            "graceEndsAt": summary["graceEndsAt"],
            "inGrace": summary["inGrace"],
            # Seating and the lease are here so a page that renders "you hold
            # a floating seat until 14:20" needs this call alone. `/v1/config`
            # carries them too, but it is the larger answer and a browser
            # asking "what am I?" should not have to fetch limits to find out.
            "seating": summary["seating"],
            "leaseExpiresAt": summary["leaseExpiresAt"],
            "held": summary["held"],
        },
        "terms": _terms_block(user),
        "improvement_consent": _improvement_consent(user),
    }


def _licence_block(summary: dict) -> dict:
    """One app's licence for `/v1/me`'s `licences`: the `license` block's
    fields, by the same names."""
    return {"mode": summary["mode"], "kind": summary["licenseKind"],
            "prefix": summary["prefix"], "expiresAt": summary["expiresAt"],
            "inGrace": summary["inGrace"], "seating": summary["seating"],
            "held": summary["held"]}


class TermsAcceptance(BaseModel):
    version: str = Field(min_length=1, max_length=32)


class ConsentUpdate(BaseModel):
    improvement: bool


def _consent_source(x_device_id: str) -> str:
    return "app" if x_device_id else "console"


@router.post("/v1/me/terms")
def accept_terms(
    body: TermsAcceptance,
    user=Depends(any_status_user),
    x_device_id: str = Header(default=""),
):
    """Record clickwrap acceptance of the current Terms of Service.

    PENDING accounts may call this — the gate runs at registration, before
    approval. The version is checked against the server's, not trusted from the
    client: an app that ships an older document must not be able to record
    agreement to terms the user never saw.
    """
    if body.version != legal.TERMS_VERSION:
        raise HTTPException(409, errors.TERMS_VERSION_MISMATCH)
    uid = user["uid"]
    record = repo.record_terms_acceptance(
        uid, body.version, x_device_id or None, _consent_source(x_device_id),
    )
    audit.record(uid, x_device_id or None, action="TERMS_ACCEPTED",
                 target={"type": "user", "id": uid}, detail={"version": body.version})
    return {"terms": {**_terms_block({"termsAccepted": record}),
                      "accepted_at": record["acceptedAt"]}}


@router.put("/v1/me/consents")
def set_consents(
    body: ConsentUpdate,
    user=Depends(any_status_user),
    x_device_id: str = Header(default=""),
):
    """Grant or withdraw the optional product-improvement consent.

    Kept apart from Terms acceptance on purpose: consent to secondary use of the
    user's content has to be a separate, freely revocable choice, not a
    condition of the service.
    """
    uid = user["uid"]
    record = repo.record_improvement_consent(
        uid, body.improvement, legal.TERMS_VERSION, x_device_id or None,
        _consent_source(x_device_id),
    )
    audit.record(uid, x_device_id or None, action="CONSENT_CHANGED",
                 target={"type": "user", "id": uid},
                 detail={"improvement": record["granted"], "version": record["version"]})
    return {"improvement_consent": record["granted"], "updated_at": record["at"]}


@router.get("/v1/config")
def app_config(
    request: Request,
    user=Depends(current_user),
    app=Depends(request_app),
    x_device_id: str = Header(default=""),
):
    """Resolved plan, entitlements, and product limits for the caller.

    Plus the fleet's release gate and kill switches (A6.1 / A6.4), which the
    app applies itself — nothing here refuses an old build — and, when this
    service holds a signing key and the caller is a registered device,
    `entitlement`: the same mode and features as a signed token the app can
    rely on offline (`entitlement_token`). Absent, never an error, otherwise.
    The caller's `X-App-Version` is logged with the request, not checked.
    """
    return config_body(request, user, app, x_device_id)


def config_body(request: Request, user: dict, app: str, x_device_id: str) -> dict:
    """`/v1/config`'s answer, shared with Activate, which answers with the
    config the phone holds from then on (`routers/activations.py`).

    `activation` is this phone's place in the licence's device count
    (ADR-033): what the licence check found on the way in, or read here for a
    call that carried no device."""
    config = repo.resolve_user_config(user, app)
    min_supported, recommended = settings.release_gate(app)
    body = {
        **config,
        # The calling app's own pair: each app numbers its builds separately.
        "minSupportedVersionCode": min_supported,
        "recommendedVersionCode": recommended,
        "killSwitches": list(settings.KILL_SWITCHES),
        "testAccount": _test_account(user),
        "activation": (getattr(request.state, "activation", None)
                       or repo.activation_summary(user, app, x_device_id)),
        # A new licence issued to this account for the app, waiting for
        # Activate (ADR-033); null when none waits.
        "pendingLicence": repo.pending_upgrade(user, app),
    }
    # The token names the app as the calling build pins it (`apps.token_claim`):
    # a Material Testing build from before the rename (ADR-030) refuses one
    # that says `semperclassroom`. `current_user` kept the header as sent.
    claim = apps.token_claim(getattr(request.state, "app_id", "")) or app
    token = _entitlement(user, config, claim, x_device_id)
    if token:
        body["entitlement"] = token
    return body


def _entitlement(user: dict, config: dict, app: str, x_device_id: str) -> str | None:
    """The signed entitlement for this caller, or None. Best-effort: the
    token is an addition to /v1/config, so a malformed device id, a device
    lookup or a signing failure omits it and is logged, never refused."""
    if entitlement_token.signing_key() is None or not x_device_id:
        return None
    try:
        device_id = require_header_identifier(x_device_id, name="device_id", maximum=128)
        return entitlement_token.issue(
            user, config, app=app, device_id=device_id, device=repo.get_device(device_id),
        )
    except Exception as e:  # noqa: BLE001 - an optional field must not fail the call
        obs.log_event(log, logging.WARNING, "entitlement_not_issued",
                      errorCode=type(e).__name__)
        return None


@router.get("/v1/me/export", dependencies=[rate_limited(rate_limit.export_bucket)])
def export_account(ctx=Depends(verified_device)):
    """GDPR data portability (Art. 20): everything we hold about the caller, as JSON.

    Structured and machine-readable: the profile, registered devices, and every
    analysis with its full file manifest. The binary artifacts themselves stay
    downloadable via /v1/files/{id}/content (each file's `fileId` is included),
    which is also what the app's Restore screen uses.

    Device-signed (like DELETE /v1/me): a full-account export is high-consequence
    enough that a stolen ID token alone must not be able to trigger it.
    """
    uid = ctx.user["uid"]
    header = account_service.export_header(uid)
    return StreamingResponse(
        account_service.export_stream(uid, header),
        media_type="application/json",
        headers={
            "Content-Disposition": 'attachment; filename="semper-account-export.json"',
            # A full-account dump of personal data must not sit in a shared cache.
            "Cache-Control": "no-store",
        },
    )


@router.get("/v1/me/erasure")
def erasure_status(uid: str = Depends(token_uid), x_device_id: str = Header(default="")):
    """Whether the account this phone was registered to has been erased.

    For an account deletion whose `DELETE /v1/me` was sent and never answered
    (the app's process died): the next start asks here before it wipes the
    phone. Erased is "this phone's device record is gone, or someone else's":
    the erase deletes the account's device records, and nothing else ever
    does (a superseded or revoked device keeps its record with another
    status), so an admin's device reset does not read as an erase.

    Token-only (`token_uid`): any route behind `current_user` re-creates a
    profile for the erased uid, and mails support when it is pending, which is
    what the app's earlier device-signed probe did (TD-206). The answer names
    only the caller's own device, so it tells a token holder nothing about
    anyone else. Rate-limited in the handler: `rate_limited` goes through
    `current_user`.
    """
    rate_limit.enforce(rate_limit.erase_bucket, uid)
    device_id = require_header_identifier(x_device_id, name="device_id", maximum=128)
    device = repo.get_device(device_id)
    return {"erased": device is None or device.get("uid") != uid}


@router.delete("/v1/me", dependencies=[rate_limited(rate_limit.erase_bucket)])
def delete_account(ctx=Depends(verified_device)):
    """Erase the account and ALL of its data (GDPR right to erasure).

    Deletes the user's entire Drive subtree in one shot (every analysis, plus
    anything orphaned by a failed sync), then every Firestore record: sessions,
    file pointers, device registrations and the user profile.

    Last, the Firebase Auth users themselves are deleted: the account's own
    and every one linked to it (`repo.sign_in_uids`). Best-effort: it needs
    Firebase Auth admin on the Auth project, and the audit row says whether it
    happened (`authUserDeleted` when every one went, with `authUsers` and
    `authUsersDeleted`). Once it is gone the sign-in cannot mint
    another token, and signing in again with the same address creates a new
    Firebase user with a new uid, so nothing erased here is reattached to it.

    Only the append-only audit trail survives — it records that actions
    (including this erasure) happened, and holds no analysis content. The caller
    must sign out afterwards: while the Auth user survives (the delete failed),
    any further authenticated call would create a fresh, empty profile.
    """
    uid = ctx.user["uid"]
    try:
        detail = account_service.erase_account(ctx.user, ctx.device_id)
    except requests.RequestException as e:
        # Drive refused or was unreachable: the erase stopped before any
        # record went, so this is a 502 to retry, not an unhandled 500
        # (readiness TE3-007).
        raise HTTPException(502, errors.DRIVE_WRITE_FAILED) from e
    return {"deleted": uid, **detail}
