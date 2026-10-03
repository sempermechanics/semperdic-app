"""FastAPI dependencies: user auth (Google ID token) and device assertion."""
import base64
import binascii
import hashlib
import logging
import re
import time
from datetime import datetime, timezone

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from fastapi import Depends, Header, HTTPException, Request
from starlette.concurrency import run_in_threadpool

from . import apps, audit, errors, firestore_repo as repo, rate_limit, statuses
from .config import settings
from .google_auth import verify_app_check_token, verify_id_token
from .validation import require_header_identifier
from . import observability as obs

log = logging.getLogger("semper.auth")

_DEV_USER = {"uid": "dev-user", "email": "dev@local", "role": "admin",
             "access_status": statuses.ACCESS_APPROVED, "activeDeviceId": "dev-device",
             "emailVerified": True, "mode": "licensed", "plan": "professional"}
_DEV_DEVICE = {"deviceId": "dev-device", "uid": "dev-user", "status": statuses.DEVICE_ACTIVE}
#: Claims the dev bypass pretends the token carried. Shaped like a real
#: second-factor sign-in so the console path is exercised in dev rather than
#: skipped by a branch that only exists there.
_DEV_CLAIMS = {"firebase": {"sign_in_second_factor": "phone"}}


def _client_bearer(authorization: str, x_forwarded_authorization: str) -> str:
    """The end-user's bearer token.

    Behind API Gateway / ESPv2 the gateway replaces `Authorization` with its own
    backend service-account token and moves the original client token to
    `X-Forwarded-Authorization`. Direct (non-gateway) calls just use
    `Authorization`. Prefer the forwarded header when present.
    """
    return x_forwarded_authorization or authorization


def _resolve_app(x_app_id: str) -> str:
    """The app a request comes from (`X-App-Id`), or 400 `unknown_app`.

    No header is Semper, as every build before the header was; an id this
    backend does not know is refused rather than read as Semper (`apps.py`).
    """
    app = apps.from_header(x_app_id)
    if app is None:
        raise HTTPException(400, errors.UNKNOWN_APP)
    return app


def request_app(request: Request, x_app_id: str = Header(default="")) -> str:
    """Route dependency: the app whose device binding this request is for
    (ADR-010). `_authenticate` resolves it first; this reads it back, so a
    route and the lock check it rode in on cannot disagree."""
    app = getattr(request.state, "app", None)
    return app if app else _resolve_app(x_app_id)


def _require_app_check(token: str, uid: str) -> None:
    """Attest the *app binary* for callers that identify a device.

    Only callers sending `X-Device-Id` are asked: that is the app, and it is the
    header the abuse this guards against needs. `POST /v1/licenses/checkout`
    takes its device id from that header and doubles as the seat heartbeat, so
    without this a script holding one valid sign-in can occupy an institution's
    whole floating pool under invented device ids. Browsers never send
    `X-Device-Id`, so the consoles are untouched and need no web provider.

    Never raises in `monitor` mode — it logs `app_check_missing` /
    `app_check_invalid` so the miss rate is visible in live traffic before
    anything is refused. See `settings.APP_CHECK_MODE`.
    """
    mode = settings.APP_CHECK_MODE
    if mode == "off":
        return
    enforcing = mode == "enforce"
    if not token:
        obs.log_event(log, logging.WARNING, "app_check_missing",
                      uid=uid, enforcing=enforcing)
        if enforcing:
            audit.record(uid, action="AUTH_DENIED", outcome="DENIED",
                         detail={"stage": "app_check", "reason": "missing"})
            raise HTTPException(403, errors.APP_CHECK_REQUIRED)
        return
    try:
        verify_app_check_token(token)
    except Exception as e:  # noqa: BLE001 - any verification failure is a refusal
        obs.log_event(log, logging.WARNING, "app_check_invalid",
                      uid=uid, enforcing=enforcing, reason=str(e)[:200])
        if enforcing:
            audit.record(uid, action="AUTH_DENIED", outcome="DENIED",
                         detail={"stage": "app_check", "reason": "invalid"})
            raise HTTPException(403, errors.APP_CHECK_REQUIRED) from e


def _authenticate(
    request: Request,
    authorization: str,
    x_forwarded_authorization: str,
    x_device_id: str,
    x_firebase_appcheck: str,
    x_app_id: str = "",
    *,
    require_approved: bool,
) -> dict:
    """Shared body of `current_user` / `any_status_user`.

    `require_approved=False` still refuses SUSPENDED accounts: the only status it
    lets through that `current_user` does not is PENDING, for the one thing a
    not-yet-approved user must be able to do — accept the Terms.
    """
    claims: dict = _DEV_CLAIMS if settings.DEV_INSECURE_AUTH else {}
    app = _resolve_app(x_app_id)
    try:
        request.state.app = app
    except Exception:  # noqa: BLE001
        pass
    if settings.DEV_INSECURE_AUTH:
        user = _DEV_USER
    else:
        bearer = _client_bearer(authorization, x_forwarded_authorization)
        if not bearer.startswith("Bearer "):
            log.warning("no bearer token: authorization=%s x_forwarded=%s",
                        bool(authorization), bool(x_forwarded_authorization))
            raise HTTPException(401, errors.MISSING_BEARER)
        try:
            claims = verify_id_token(bearer[7:])
        except Exception as e:  # noqa: BLE001
            log.warning("id_token verify FAILED (x_forwarded_present=%s): %s",
                        bool(x_forwarded_authorization), e)
            audit.record(action="AUTH_DENIED", outcome="DENIED", detail={"stage": "id_token"})
            raise HTTPException(401, errors.INVALID_TOKEN)
        try:
            require_header_identifier(
                str(claims.get("sub", "")), name="uid", maximum=128
            )
        except HTTPException as exc:
            raise HTTPException(401, errors.INVALID_TOKEN) from exc
        # Before get_or_create_user: a refused caller should not create an
        # account row or move a device lock on the way to being refused.
        if x_device_id:
            _require_app_check(x_firebase_appcheck, str(claims.get("sub", "")))
        try:
            user = repo.get_or_create_user(claims, device_id=x_device_id or None)
        except repo.DeviceInUseError as exc:
            raise HTTPException(409, errors.DEVICE_IN_USE) from exc
        status = user["access_status"]
        if status != statuses.ACCESS_APPROVED and (require_approved or status != statuses.ACCESS_PENDING):
            raise HTTPException(403, errors.NOT_APPROVED)
        # Re-validate the license/seat device lock on every call that carries
        # X-Device-Id — not just at activation time. A revoked key, a disabled
        # or revoked institution seat, or a device that no longer matches the lock
        # drops the account to Demo immediately (fails closed); it never
        # touches the account's stored sessions/files. Checked against the
        # lock of the app the request came from (ADR-010).
        if x_device_id:
            user = repo.revalidate_device_lock(user, x_device_id, app)
    try:
        request.state.uid = user["uid"]
        obs.bind_uid(user["uid"])
    except Exception:  # noqa: BLE001 - logging enrichment must never fail a request
        pass
    # Verified claims, for dependencies that need to know *how* the caller
    # signed in rather than only who they are — today that is the console's
    # second-factor check. Stashed on request.state instead of merged into the
    # user dict: that dict is written back to Firestore by several callers, and
    # a token claim is not a user field.
    try:
        request.state.claims = claims
    except Exception:  # noqa: BLE001
        pass
    return user


def current_user(
    request: Request,
    authorization: str = Header(default=""),
    x_forwarded_authorization: str = Header(default=""),
    x_device_id: str = Header(default=""),
    x_firebase_appcheck: str = Header(default=""),
    x_app_id: str = Header(default=""),
) -> dict:
    """Resolve the caller from a Google ID token; APPROVED accounts only.

    Plain `def` (no awaits) so FastAPI/Starlette runs this in the threadpool —
    `verify_id_token` and Firestore must not block the event loop. Sets
    `request.state.uid` so access logs work on authn-only routes (not only
    device-attested ones).
    """
    return _authenticate(request, authorization, x_forwarded_authorization, x_device_id,
                         x_firebase_appcheck, x_app_id, require_approved=True)


def any_status_user(
    request: Request,
    authorization: str = Header(default=""),
    x_forwarded_authorization: str = Header(default=""),
    x_device_id: str = Header(default=""),
    x_firebase_appcheck: str = Header(default=""),
    x_app_id: str = Header(default=""),
) -> dict:
    """Like `current_user`, but a PENDING account is allowed through.

    The Terms-acceptance gate runs at registration, before an operator has
    approved the account, so the routes that record acceptance and consent
    cannot sit behind the APPROVED check. Nothing else should use this.
    """
    return _authenticate(request, authorization, x_forwarded_authorization, x_device_id,
                         x_firebase_appcheck, x_app_id, require_approved=False)


def rate_limited(bucket: rate_limit.TokenBucket):
    """Route dependency: spend one of the caller's tokens in `bucket`, else 429.

    Declare it in the route decorator's `dependencies=[...]`, which FastAPI
    resolves before the endpoint's own parameters. On a device-signed route
    that puts the limit ahead of `verified_device`, so a 429 leaves the nonce
    unclaimed and the signed request can be sent again as it is — which is
    what the app's `RetryOnTransient` assumes of every 429. Checked inside the
    handler instead, the nonce was already spent, the retry came back 401
    `nonce_invalid_or_replayed`, and the phone gave up on client nonces for
    the rest of its process; a batch erase of more than three analyses left
    some in the cloud every time.

    Keyed on the ID-token uid and spent before the signature is checked: a
    caller holding someone's ID token but not their device key can drain that
    user's per-instance bucket. The same token already reaches every unsigned
    route as them, and the cost is a few seconds of 429s.
    """
    def check(user: dict = Depends(current_user)) -> None:
        rate_limit.enforce(bucket, user["uid"])

    return Depends(check)


def admin_user(user: dict = Depends(current_user)) -> dict:
    """Authenticated caller that is an admin (role=admin or in ADMIN_EMAILS).

    ID-token based (no device signature) so list/read admin screens and curl in
    dev mode work without a registered device. **Mutating** admin routes
    (approve / revoke / config-patch) additionally require `verified_device` so
    a stolen ID token alone cannot change access — see those handlers.
    """
    email = (user.get("email") or "").lower()
    if user.get("role") != "admin" and email not in settings.ADMIN_EMAILS:
        raise HTTPException(403, errors.NOT_ADMIN)
    return user


async def verified_device(
    request: Request,
    user: dict = Depends(current_user),
    x_device_id: str = Header(default=""),
    x_nonce: str = Header(default=""),
    x_signature: str = Header(default=""),
) -> dict:
    """Device-attested caller: active device + single-use nonce + ECDSA signature.

    Keeps `async` only for `await request.body()`. Firestore device/nonce lookups
    run in the threadpool so they do not stall the event loop.
    """
    if settings.DEV_INSECURE_AUTH:
        return {"user": user, "device": _DEV_DEVICE}

    x_device_id = require_header_identifier(
        x_device_id, name="device_id", maximum=128
    )
    x_nonce = require_header_identifier(
        x_nonce, name="nonce", maximum=128
    )
    if not x_signature or len(x_signature) > 512:
        raise HTTPException(400, errors.INVALID_SIGNATURE)

    dev = await run_in_threadpool(repo.get_device, x_device_id)
    if not dev or dev["uid"] != user["uid"] or dev["status"] != statuses.DEVICE_ACTIVE:
        raise HTTPException(409, errors.DEVICE_NOT_ACTIVE)
    client_nonce_ts = parse_client_nonce(x_nonce)
    if client_nonce_ts is None:
        # Server-issued challenge: claimed (deleted) before the signature check,
        # exactly as before — it was bound to this uid+device when issued.
        if not await run_in_threadpool(repo.consume_nonce, x_nonce, user["uid"], x_device_id):
            raise HTTPException(401, errors.NONCE_INVALID_OR_REPLAYED)
    elif not client_nonce_fresh(client_nonce_ts):
        # Stale or from a phone whose clock is off: no write, and the client
        # retries once with a server challenge.
        raise HTTPException(401, errors.NONCE_INVALID_OR_REPLAYED)
    # current_user already re-validated the lock for THIS x_device_id when it
    # was present on the request — but device-attested routes are the ones
    # that actually spend the entitlement (create a session, download a file),
    # so re-check here too rather than trust a value resolved before the
    # signature/nonce were even verified.
    user = await run_in_threadpool(repo.revalidate_device_lock, user, x_device_id,
                                   getattr(request.state, "app", None) or apps.SEMPER)

    body = await request.body()
    # The query string is inside the signature whenever there is one, so a
    # signed request cannot be replayed against the same path with the
    # parameters swapped. Appended only when non-empty, which keeps the message
    # byte-identical for every route today (none take query parameters) — so
    # this cannot desynchronise from a client build that has not shipped yet.
    target = request.url.path
    if request.url.query:
        target = target + "?" + request.url.query
    msg = (x_nonce + request.method + target).encode() + hashlib.sha256(body).digest()
    try:
        pub = load_pem_public_key(dev["publicKeyPem"].encode())
        signature = base64.b64decode(x_signature, validate=True)
        pub.verify(signature, msg, ec.ECDSA(hashes.SHA256()))
    except (binascii.Error, InvalidSignature, ValueError):
        audit.record(user["uid"], x_device_id, action="AUTH_DENIED", outcome="DENIED",
                     detail={"stage": "signature"})
        raise HTTPException(401, errors.BAD_SIGNATURE)
    # A client nonce is claimed only after the signature verified, so a forged
    # request costs no write and cannot burn a nonce the device will use.
    if client_nonce_ts is not None and not await run_in_threadpool(
        repo.claim_client_nonce, x_nonce, user["uid"], x_device_id,
        datetime.fromtimestamp(
            client_nonce_ts + settings.CLIENT_NONCE_WINDOW_SECONDS + 60, tz=timezone.utc,
        ),
    ):
        raise HTTPException(401, errors.NONCE_INVALID_OR_REPLAYED)
    try:
        request.state.device_id = x_device_id
        obs.bind_device(x_device_id)
    except Exception:  # noqa: BLE001
        pass
    return {"user": user, "device": dev}


_CLIENT_NONCE = re.compile(r"t1\.(\d{9,11})\.[A-Za-z0-9_-]{22,86}")


def parse_client_nonce(nonce: str) -> int | None:
    """The Unix time in a client-minted nonce, or None for a server challenge.

    `t1.<seconds>.<random>`, the random part at least 128 bits of base64url.
    Anything else — including a malformed `t1.` value — is treated as a server
    challenge and simply fails the lookup, so there is one rejection path.
    """
    if settings.CLIENT_NONCE_WINDOW_SECONDS <= 0:
        return None
    m = _CLIENT_NONCE.fullmatch(nonce)
    return int(m.group(1)) if m else None


def client_nonce_fresh(ts: int) -> bool:
    return abs(time.time() - ts) <= settings.CLIENT_NONCE_WINDOW_SECONDS


def _second_factor(claims: dict) -> str:
    """The second factor the token records, or "" when there was none.

    Firebase sets `firebase.sign_in_second_factor` only on a token minted after
    an MFA challenge actually completed. Enrolment alone does not set it, so
    this is "they proved a second factor for *this* session", not "they own
    one" — which is the property worth checking.
    """
    firebase = claims.get("firebase")
    if not isinstance(firebase, dict):
        return ""
    return str(firebase.get("sign_in_second_factor") or "")


def _auth_age_seconds(claims: dict) -> float | None:
    """How long ago this session authenticated, or None if the token does not
    say. None is treated as too old: a token that will not state its own age
    cannot satisfy a freshness requirement."""
    raw = claims.get("auth_time")
    try:
        return time.time() - float(raw)
    except (TypeError, ValueError):
        return None


async def attested_or_mfa_admin(
    request: Request,
    user: dict = Depends(admin_user),
    x_device_id: str = Header(default=""),
    x_nonce: str = Header(default=""),
    x_signature: str = Header(default=""),
) -> dict:
    """State-changing Semper-staff caller: an attested device, or a 2FA browser.

    The device path is unchanged and still preferred — if the request carries
    any device header it is held to the full `verified_device` check, so the
    phone admin screen keeps exactly the guarantee it had.

    The browser path exists because a browser cannot produce an ECDSA
    attestation, which is what kept the staff console read-only. It is
    accepted on two conditions, and both are checked here rather than trusted
    from the client:

    * the ID token records a completed **second factor**, so a token stolen
      from a password-only session is refused; and
    * the sign-in behind it is **recent** (ADMIN_WEB_REAUTH_SECONDS), so a
      token that leaks later stops working — the console re-authenticates
      rather than holding authority for the token's full hour.

    This is deliberately weaker than device attestation and is not a drop-in
    equivalent: an attacker who phishes a live MFA session inside the window
    can mint a licence, which the device path made impossible. It is enabled
    because staff need to administer licences from a computer; set
    ADMIN_WEB_MFA_ENABLED=0 to withdraw the browser path entirely.

    Reads are not routed through here. Only the routes that change state are,
    so an operator can browse the console on an ordinary session and is asked
    to re-authenticate at the point of acting.
    """
    return await _attested_or_mfa(
        request, user, x_device_id, x_nonce, x_signature,
        max_age_seconds=settings.ADMIN_WEB_REAUTH_SECONDS,
    )


async def attested_or_mfa_admin_fresh(
    request: Request,
    user: dict = Depends(admin_user),
    x_device_id: str = Header(default=""),
    x_nonce: str = Header(default=""),
    x_signature: str = Header(default=""),
) -> dict:
    """Semper-staff step-up with the tighter revoke window.

    Whole-licence revoke asks for password (or Google re-auth) plus TOTP in
    the console; this dependency refuses a session that is merely "still
    inside the ordinary dashboard window". Device attestation still passes.
    """
    return await _attested_or_mfa(
        request, user, x_device_id, x_nonce, x_signature,
        max_age_seconds=settings.ADMIN_WEB_REVOKE_REAUTH_SECONDS,
    )


async def attested_or_mfa_user(
    request: Request,
    user: dict = Depends(current_user),
    x_device_id: str = Header(default=""),
    x_nonce: str = Header(default=""),
    x_signature: str = Header(default=""),
) -> dict:
    """The same step-up, for an account holder acting on their own licence.

    Identical machinery to `attested_or_mfa_admin` and deliberately so: the
    question — has this caller proved themselves *now*, by an attested device
    or by a second factor on a recent sign-in — does not change with who is
    asking. Only the tier below it does, `current_user` rather than
    `admin_user`, so this authorises nothing beyond what the holder already
    holds.

    It exists because two operations are worth more than an ID token and are
    not staff work: changing which device a licence is bound to, and pulling
    an analysis out through a browser. Both are reachable from a page rather
    than the app, and a browser cannot produce an attestation.
    """
    return await _attested_or_mfa(
        request, user, x_device_id, x_nonce, x_signature,
        max_age_seconds=settings.ADMIN_WEB_REAUTH_SECONDS,
    )


async def ensure_web_step_up(
    request: Request,
    user: dict,
    x_device_id: str = "",
    x_nonce: str = "",
    x_signature: str = "",
    *,
    max_age_seconds: int | None = None,
) -> dict:
    """Shared browser/device step-up used by institution IT and the admin tiers.

    Institution membership is checked by the caller *before* this, so a foreign
    licence still 404s without disclosing that MFA was the next gate.
    """
    return await _attested_or_mfa(
        request, user, x_device_id, x_nonce, x_signature,
        max_age_seconds=(
            settings.ADMIN_WEB_REAUTH_SECONDS
            if max_age_seconds is None
            else max_age_seconds
        ),
    )


async def _attested_or_mfa(
    request: Request,
    user: dict,
    x_device_id: str,
    x_nonce: str,
    x_signature: str,
    *,
    max_age_seconds: int,
) -> dict:
    """The step-up itself, shared by admin, user, and institution tiers.

    A device attestation is preferred and checked in full whenever the request
    carries any device header, so the phone keeps exactly the guarantee it
    had. The browser path — second factor, recent sign-in — is the fallback,
    governed by ADMIN_WEB_MFA_ENABLED and the caller's freshness window: the
    trade being made is the same one either way, and a deployment that
    withdraws the browser path should withdraw all of it.
    """
    if settings.DEV_INSECURE_AUTH:
        return {"user": user, "device": _DEV_DEVICE, "via": "dev"}

    if x_device_id or x_nonce or x_signature:
        ctx = await verified_device(request, user, x_device_id, x_nonce, x_signature)
        return {**ctx, "via": "device"}

    if not settings.ADMIN_WEB_MFA_ENABLED:
        # No device headers and no browser path: this is the attestation-only
        # posture, and the honest answer is that the call needs a device.
        raise HTTPException(400, errors.INVALID_SIGNATURE)

    claims = getattr(request.state, "claims", None) or {}
    factor = _second_factor(claims)
    if not factor:
        audit.record(user["uid"], action="AUTH_DENIED", outcome="DENIED",
                     detail={"stage": "second_factor"})
        raise HTTPException(403, errors.MFA_REQUIRED)

    age = _auth_age_seconds(claims)
    if age is None or age > max_age_seconds:
        audit.record(user["uid"], action="AUTH_DENIED", outcome="DENIED",
                     detail={"stage": "reauth", "ageSeconds": age})
        raise HTTPException(403, errors.REAUTH_REQUIRED)

    try:
        request.state.device_id = ""
    except Exception:  # noqa: BLE001
        pass
    return {"user": user, "device": {}, "via": "mfa", "secondFactor": factor}
