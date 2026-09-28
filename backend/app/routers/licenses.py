import math
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Header, HTTPException, Query

from .. import apps, audit, errors, firestore_repo as repo
from .. import rate_limit
from ..deps import attested_or_mfa_user, current_user, rate_limited, request_app
from ..models import LicenseActivate
from ..validation import require_header_identifier

router = APIRouter()


@router.post("/v1/licenses/activate")
def activate_license(
    body: LicenseActivate,
    user=Depends(current_user),
    x_device_id: str = Header(default=""),
    app=Depends(request_app),
):
    """Redeem a Professional (or re-entered) key locked to this email and device
    (the device of the app that asks, ADR-010)."""
    rate_limit.enforce(rate_limit.license_activate_bucket, user["uid"])
    device_id = require_header_identifier(x_device_id, name="device_id", maximum=128)
    code, config = repo.activate_license(
        user["uid"], user.get("email") or "", device_id, body.key, app,
    )
    if code:
        status = {
            errors.LICENSE_NOT_FOUND: 404,
            errors.USER_NOT_FOUND: 404,
            errors.LICENSE_ALREADY_REDEEMED: 409,
            errors.ALREADY_LICENSED: 409,
            errors.LICENSE_SEATS_EXHAUSTED: 409,
            # Lost the race for the seat; a retry succeeds. 503 like
            # device_lock_contended, so it never reads as a full licence.
            errors.CLAIM_CONTENDED: 503,
            # 403, not 410: the key is real and may be renewed in place, so
            # this is "you may not use it", not "it is gone".
            errors.LICENSE_EXPIRED: 403,
        }.get(code, 403)
        raise HTTPException(status, code)
    audit.record(
        user["uid"], device_id, action="LICENSE_ACTIVATE",
        detail={"mode": (config or {}).get("mode")},
    )
    return {"config": config}


#: Lease outcomes that are the pool working as designed rather than a fault.
#: `no_floating_seat` is a 200 elsewhere — see the checkout docstring — but as
#: an explicit checkout it is a refusal the caller asked for and gets 409.
_LEASE_STATUS = {
    errors.NO_LICENSE: 404,
    errors.LICENSE_NOT_FOUND: 404,
    errors.LICENSE_REVOKED: 403,
    errors.LICENSE_EXPIRED: 403,
    errors.NOT_ELIGIBLE: 403,
    errors.SEATING_NOT_FLOATING: 409,
    errors.NO_FLOATING_SEAT: 409,
}


@router.post("/v1/licenses/checkout")
def checkout_lease(
    user=Depends(current_user),
    x_device_id: str = Header(default=""),
):
    """Take or renew a floating seat.

    Re-calling this IS the heartbeat: renewing an existing lease extends it
    without consuming a second slot, which is why there is no separate
    heartbeat route. The response carries `leaseExpiresAt` and
    `leaseHeartbeatMinutes` so the app knows when to call again.

    Deliberately unaudited. A client calls this every half hour per active
    user; `FILE_DOWNLOAD` and `lastSeenAt` both record what an unconditional
    write on a per-request path costs. The roster changes that matter —
    joining and leaving the license — are audited where they happen.

    `409 no_floating_seat` means the pool is full right now. It is not an
    error in the account: the member stays eligible and demo, and the app
    offers to try again rather than treating it as a dead end.
    """
    rate_limit.enforce(rate_limit.institution_bucket, user["uid"])
    device_id = require_header_identifier(x_device_id, name="device_id", maximum=128)
    code, config = repo.checkout_lease(user, device_id)
    if code:
        raise HTTPException(_LEASE_STATUS.get(code, 403), code)
    return {"config": config}


@router.post("/v1/licenses/release")
def release_lease(user=Depends(current_user)):
    """Give a floating seat back so someone else can take it.

    Idempotent — releasing a lease that already lapsed frees nothing and still
    succeeds. Audited, unlike checkout: a release is a discrete act, not a
    heartbeat.
    """
    rate_limit.enforce(rate_limit.institution_bucket, user["uid"])
    code, config = repo.release_lease(user)
    if code:
        raise HTTPException(_LEASE_STATUS.get(code, 403), code)
    audit.record(
        user["uid"], action="INSTITUTION_LEASE_RELEASE",
        target={"type": "lease", "id": f"{user.get('licenseId')}/{user['uid']}"},
    )
    return {"config": config}


@router.post(
    "/v1/licenses/unbind",
    dependencies=[rate_limited(rate_limit.license_activate_bucket)],
)
def unbind_device(
    ctx=Depends(attested_or_mfa_user),
    header_app=Depends(request_app),
    app: str = Query(default=""),
):
    """"Use Semper on a different device" — the holder's own device change.

    Until now only institution IT could unbind a device, which left an
    individual customer, and any member whose IT is slow, writing to support
    for something they can prove they are entitled to do. Since Gap A,
    clearing the lock is the whole operation: the licence or seat goes
    unbound, and the next device to sign in binds it, first writer wins.

    **Clearing is not revoking.** The entitlement, the seat, the lease and
    every analysis stay exactly as they are; only the lock goes empty. Nothing
    has to be typed on the new device.

    Step-up rather than plain USER: this is worth more than a bearer token,
    and it is reachable from a browser. But a second factor proves *who* is
    asking, not *how often*, so it is also the one caller subject to
    SELF_DEVICE_CHANGE_COOLDOWN_DAYS — one person could otherwise re-bind
    daily and pass a single licence round a lab. Staff and IT are not, so a
    support request always works.

    The order on the new device matters and the app should follow it: sign in
    (USER-tier, so `POST /v1/devices/register` works before any licence
    binds), let the first authed request bind the lock, and only then restore.
    File content is device-attested, so restoring first fails on a device the
    user has legitimately just moved to.

    Moves one app's device (ADR-010): the app that asks, by `X-App-Id`, or
    from a browser, which cannot send that header, `?app=materialtesting`.
    Each app has its own cooldown. No app named is Semper.
    """
    if app:
        named = apps.from_name(app)
        if named is None:
            raise HTTPException(400, errors.UNKNOWN_APP)
    else:
        named = header_app
    user = ctx["user"]
    license_id = user.get("licenseId") or ""
    if not license_id:
        raise HTTPException(404, errors.NO_LICENSE)
    err, cleared = repo.clear_device_lock(license_id, user["uid"], actor=repo.ACTOR_SELF,
                                          app=named)
    if err == errors.DEVICE_CHANGE_TOO_SOON:
        # 429, not 403: the answer is "not yet", and the caller is told
        # when. Nothing about their entitlement has changed.
        raise _too_soon((cleared or {}).get("nextChangeAllowedAt") or "")
    if err:
        status = {
            errors.LICENSE_NOT_FOUND: 404,
            errors.SEAT_NOT_FOUND: 404,
        }.get(err, 403)
        raise HTTPException(status, err)
    audit.record(
        user["uid"], action="LICENSE_DEVICE_UNBIND",
        target={"type": (cleared or {}).get("scope") or "license", "id": license_id},
        detail={k: str(v) for k, v in (cleared or {}).items()},
    )
    return {"licenseId": license_id, "app": named, "deviceIdLock": "",
            "previousDeviceId":
                (cleared or {}).get(apps.field("previousDeviceId", named)) or "",
            "nextChangeAllowedAt": (cleared or {}).get("nextChangeAllowedAt") or ""}


def _too_soon(next_allowed: str) -> HTTPException:
    """`429 device_change_too_soon: <ISO instant>`, plus `Retry-After`.

    The instant goes after a colon, as the counts do on
    `session_quota_exceeded`, so a caller matching on the code still matches.
    The refusal used to be the bare code, and the account page could only
    say "recently" to someone waiting up to a month.
    """
    if not next_allowed:
        return HTTPException(429, errors.DEVICE_CHANGE_TOO_SOON)
    detail = f"{errors.DEVICE_CHANGE_TOO_SOON}: {next_allowed}"
    try:
        when = datetime.fromisoformat(next_allowed)
    except ValueError:
        return HTTPException(429, detail)
    if when.tzinfo is None:
        when = when.replace(tzinfo=timezone.utc)
    wait = max(0, math.ceil((when - datetime.now(timezone.utc)).total_seconds()))
    return HTTPException(429, detail, headers={"Retry-After": str(wait)})
