"""FastAPI dependencies: user auth (Google ID token) and device assertion."""
import base64
import binascii
import hashlib
import logging
import re
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from fastapi import Depends, Header, HTTPException, Request
from starlette.concurrency import run_in_threadpool

from . import apps, audit, errors, repo, rate_limit, statuses
from .config import KILL_SWITCH_NAMES, settings
from .google_auth import verify_app_check_token, verify_id_token
from .validation import require_header_identifier
from . import observability as obs

log = logging.getLogger("semper.auth")


@dataclass(frozen=True)
class Caller:
    """Who an attested or stepped-up request comes from.

    `device` is the registered device a signed request came from, empty for
    a browser that stepped up with a second factor. `via` says how the caller
    proved themselves: "device", "mfa", "dev" (DEV_INSECURE_AUTH), or "" for a
    plain device-signed route.
    """
    user: dict
    device: dict = field(default_factory=dict)
    via: str = ""
    second_factor: str = ""

    @property
    def device_id(self) -> str | None:
        return self.device.get("deviceId") or None

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


def _verified_claims(authorization: str, x_forwarded_authorization: str) -> dict:
    """The claims of the caller's Firebase ID token, or 401: no profile is
    touched. Shared by `_authenticate` and `token_uid`."""
    bearer = _client_bearer(authorization, x_forwarded_authorization)
    if not bearer.startswith("Bearer "):
        log.warning("no bearer token: authorization=%s x_forwarded=%s",  # nosemgrep: python.lang.security.audit.logging.logger-credential-leak.python-logger-credential-disclosure - logs two booleans, never a header value
                    bool(authorization), bool(x_forwarded_authorization))
        raise HTTPException(401, errors.MISSING_BEARER)
    try:
        claims = verify_id_token(bearer[7:])
    except Exception as e:  # noqa: BLE001 - a google-auth message may echo a malformed token: cut out (TE5-105)
        log.warning("id_token verify FAILED (x_forwarded_present=%s): %s: %s",  # nosemgrep: python.lang.security.audit.logging.logger-credential-leak.python-logger-credential-disclosure - the presented token is replaced with <token>
                    bool(x_forwarded_authorization), type(e).__name__, (str(e).replace(bearer[7:], "<token>") if bearer[7:] else str(e))[:300])
        audit.record(action="AUTH_DENIED", outcome="DENIED", detail={"stage": "id_token"})
        raise HTTPException(401, errors.INVALID_TOKEN)
    try:
        require_header_identifier(
            str(claims.get("sub", "")), name="uid", maximum=128
        )
    except HTTPException as exc:
        raise HTTPException(401, errors.INVALID_TOKEN) from exc
    return claims


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
    if x_device_id:  # first: it is a Firestore document path below ("a/b", "..")
        x_device_id = require_header_identifier(x_device_id, name="device_id", maximum=128)
    claims: dict = _DEV_CLAIMS if settings.DEV_INSECURE_AUTH else {}
    app = _resolve_app(x_app_id)
    request.state.app, request.state.app_id = app, x_app_id  # as sent: `apps.token_claim`
    if settings.DEV_INSECURE_AUTH:
        user = _DEV_USER
    else:
        claims = _verified_claims(authorization, x_forwarded_authorization)
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
        # lock of the app the request came from (ADR-010; ADR-033 for Activate).
        if x_device_id:
            explicit = request.headers.get("x-activation", "").strip().lower() == "explicit"
            request.state.activation = shown = {}
            user = repo.revalidate_device_lock(user, x_device_id, app,
                                               explicit=explicit, shown=shown)
    request.state.uid = user["uid"]
    obs.bind_uid(user["uid"])
    # Verified claims, for dependencies that need to know *how* the caller
    # signed in rather than only who they are — today that is the console's
    # second-factor check. Stashed on request.state instead of merged into the
    # user dict: that dict is written back to Firestore by several callers, and
    # a token claim is not a user field.
    request.state.claims = claims
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


def token_uid(
    request: Request,
    authorization: str = Header(default=""),
    x_forwarded_authorization: str = Header(default=""),
    x_device_id: str = Header(default=""),
    x_firebase_appcheck: str = Header(default=""),
) -> str:
    """The caller's uid from a verified ID token, and nothing more.

    `current_user` re-creates a profile for a uid that has none, so it cannot
    ask about an erased account without bringing it back (and, for a pending
    profile, mailing support). This reads the token alone: no profile is read
    or written, no access status is checked, no device lock is revalidated.
    App Check is still asked of a caller that names a device, as there. Only
    `GET /v1/me/erasure` uses it (TD-206); a route that serves account data
    must not.
    """
    if settings.DEV_INSECURE_AUTH:
        uid = _DEV_USER["uid"]
    else:
        uid = str(_verified_claims(authorization, x_forwarded_authorization)["sub"])
        if x_device_id:
            _require_app_check(x_firebase_appcheck, uid)
    request.state.uid = uid
    obs.bind_uid(uid)
    return uid


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


#: How long a paused feature tells the caller to wait before asking again.
#: Long enough that a fleet of upload workers does not hammer a switched-off
#: route, short enough that work resumes soon after the switch is cleared.
FEATURE_PAUSED_RETRY_AFTER_SECONDS = 300

#: How long a 503 for a dependency outage (`obs.DependencyError`, a Firestore
#: outage at the edge) tells the caller to wait. An outage that outlives the
#: Firestore helpers' ~20-40 s retry window is not a one-second blip, but it
#: is not a switch someone has to clear either.
DEPENDENCY_RETRY_AFTER_SECONDS = 30


def unless_paused(*switches: str):
    """Route dependency: 503 `feature_paused` while any of `switches` is in
    `settings.KILL_SWITCHES`, else nothing.

    Declare it in the route decorator's `dependencies=[...]`, after
    `rate_limited(...)`: it then runs after the ID token is verified and the
    account is approved (`current_user`, which it shares with the rate limit),
    and before the endpoint's own parameters, so a paused request reads no
    file, session or device record and touches no Drive object. On a
    device-signed route it also runs before `verified_device`, so the nonce
    stays unclaimed and the app's later retry of the same signed request is
    not a replay, as for a 429 (`rate_limited`).

    The refusal carries `Retry-After` and logs `event="feature_paused"` with
    the switch and the route template, so a dashboard can show what the
    switch is holding back.
    """
    unknown = set(switches).difference(KILL_SWITCH_NAMES)
    if not switches or unknown:
        raise ValueError(f"not kill switches: {sorted(unknown) or 'none named'}")

    async def check(request: Request, user: dict = Depends(current_user)) -> None:
        on = [name for name in switches if name in settings.KILL_SWITCHES]
        if not on:
            return
        route = request.scope.get("route")
        template = getattr(route, "path", "") or obs.route_template(request.url.path)
        obs.log_event(log, logging.WARNING, "feature_paused", uid=user.get("uid"),
                      switch=",".join(on), routeTemplate=template,
                      outcome="refused", errorCode=errors.FEATURE_PAUSED)
        raise HTTPException(
            503, errors.FEATURE_PAUSED,
            headers={"Retry-After": str(FEATURE_PAUSED_RETRY_AFTER_SECONDS)},
        )

    return Depends(check)


def admin_user(request: Request, user: dict = Depends(current_user)) -> dict:
    """Caller whose token proves an ADMIN_EMAILS address (`repo.is_admin_email`),
    asked per request: not the stored address or `role` (a display copy), so
    removing an address revokes it. The dev bypass has no token: its role.
    ID-token based, so read screens work without a registered device;
    **mutating** admin routes also require a step-up (`step_up`), so a stolen
    ID token alone cannot change access.
    """
    claims = getattr(request.state, "claims", None) or {}
    if not (user.get("role") == "admin" if settings.DEV_INSECURE_AUTH
            else repo.is_admin_email(claims)):
        raise HTTPException(403, errors.NOT_ADMIN)
    return user


async def verified_device(
    request: Request,
    user: dict = Depends(current_user),
    x_device_id: str = Header(default=""),
    x_nonce: str = Header(default=""),
    x_signature: str = Header(default=""),
) -> Caller:
    """Device-attested caller: active device + single-use nonce + ECDSA signature.

    Keeps `async` only for `await request.body()`. Firestore device/nonce lookups,
    the body hash and the signature check run in the threadpool so they do not
    stall the event loop.
    """
    if settings.DEV_INSECURE_AUTH:
        return Caller(user, _DEV_DEVICE)

    x_device_id = require_header_identifier(
        x_device_id, name="device_id", maximum=128
    )
    x_nonce = require_header_identifier(
        x_nonce, name="nonce", maximum=128
    )
    if not x_signature or len(x_signature) > 512:
        raise HTTPException(400, errors.INVALID_SIGNATURE)

    dev = await run_in_threadpool(repo.get_device, x_device_id)
    # `_authenticate` resolved the app this request came from (ADR-010).
    app = getattr(request.state, "app", None) or apps.SEMPER
    if not _device_is_bound(dev, user, x_device_id, app):
        raise HTTPException(409, errors.DEVICE_NOT_ACTIVE)
    if not apps.same_app(_device_app(dev), app):
        # One signing key gives two apps one device id, and the record holds
        # the key of whichever registered last. A key registered under the
        # other app must not sign for this one, where the same id may be the
        # bound phone. `bad_signature`, because that is what it is to the
        # phone: the backend holds another key for its id, and the app
        # registers again and resends once (`DeviceKeyRecovery`). No nonce
        # is spent.
        audit.record(user["uid"], x_device_id, action="AUTH_DENIED", outcome="DENIED",
                     detail={"stage": "device_app", "app": app})
        raise HTTPException(401, errors.BAD_SIGNATURE)
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
    # No second lock check here. `current_user` ran `revalidate_device_lock`
    # for this same X-Device-Id, user and app, and handed us its answer: a
    # mismatch or a revoke is already Demo in `user`. Checking again read the
    # licence and seat twice on every signed request and could not answer
    # differently.

    body = await request.body()
    # The query string is inside the signature whenever there is one, so a
    # signed request cannot be replayed against the same path with the
    # parameters swapped. Appended only when non-empty, which keeps the message
    # byte-identical for every route today (none take query parameters) — so
    # this cannot desynchronise from a client build that has not shipped yet.
    target = request.url.path
    if request.url.query:
        target = target + "?" + request.url.query
    # In the threadpool, as the Firestore lookups are (TD-198): the hash of a
    # 4 MiB body is a few ms, and the event loop serves every other request.
    signed = (x_nonce + request.method + target).encode()
    if not await run_in_threadpool(_signature_ok, dev["publicKeyPem"], x_signature, signed, body):
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
    request.state.device_id = x_device_id
    obs.bind_device(x_device_id)
    return Caller(user, dev)


def _signature_ok(public_key_pem: str, signature_b64: str, signed: bytes, body: bytes) -> bool:
    """Whether `signature_b64` is the device key's ECDSA P-256 signature over
    `signed` followed by the SHA-256 of `body`. CPU work, called off the
    event loop."""
    msg = signed + hashlib.sha256(body).digest()
    try:
        pub = load_pem_public_key(public_key_pem.encode())
        signature = base64.b64decode(signature_b64, validate=True)
        pub.verify(signature, msg, ec.ECDSA(hashes.SHA256()))
    except (binascii.Error, InvalidSignature, ValueError):
        return False
    return True


def _device_is_bound(dev: dict | None, user: dict, device_id: str, app: str) -> bool:
    """Whether `dev` is one of `user`'s registered phones: ACTIVE and the
    caller's. An account holds several phones per app (ADR-033, as test
    accounts already did, ADR-027), so any of them signs; whether it is
    licensed is the licence's question (`revalidate_device_lock`), not this
    one. The record's app is checked beside this (`_device_app`). Registration
    is one transaction and every release retires the phone it frees, so an
    ACTIVE record is a phone the account holds."""
    return bool(
        dev
        and dev.get("uid") == user.get("uid")
        and dev.get("status") == statuses.DEVICE_ACTIVE
    )


def _device_app(dev: dict) -> str:
    """The app `dev` was registered under. A record from before devices
    named their app is Semper's, as every request without `X-App-Id` is
    (`apps.canonical`: `materialtesting` is Semper Classroom, ADR-030)."""
    return apps.canonical(dev.get("app"))


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
