from fastapi import APIRouter, Depends, Header, HTTPException, Query

from .. import apps, audit, errors, repo
from .. import rate_limit
from ..deps import attested_or_mfa_user, current_user, rate_limited, request_app
from ..models import LicenseActivate
from ..validation import require_header_identifier
from ._shared import named_app

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
    config = repo.activate_license(
        user["uid"], user.get("email") or "", device_id, body.key, app,
    )
    audit.record(
        user["uid"], device_id, action="LICENSE_ACTIVATE",
        detail={"mode": (config or {}).get("mode")},
    )
    return {"config": config}


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
    return {"config": repo.checkout_lease(user, device_id)}


@router.post("/v1/licenses/release")
def release_lease(user=Depends(current_user)):
    """Give a floating seat back so someone else can take it.

    Idempotent — releasing a lease that already lapsed frees nothing and still
    succeeds. Audited, unlike checkout: a release is a discrete act, not a
    heartbeat.
    """
    rate_limit.enforce(rate_limit.institution_bucket, user["uid"])
    config = repo.release_lease(user)
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
    named = named_app(app, header_app)
    user = ctx.user
    license_id = user.get("licenseId") or ""
    if not license_id:
        raise HTTPException(404, errors.NO_LICENSE)
    # Too soon is 429 with the instant and a Retry-After (`errors.STATUS`): the
    # answer is "not yet", and nothing about the entitlement has changed.
    cleared = repo.clear_device_lock(license_id, user["uid"], actor=repo.ACTOR_SELF, app=named)
    audit.record(
        user["uid"], action="LICENSE_DEVICE_UNBIND",
        target={"type": cleared.get("scope") or "license", "id": license_id},
        detail={k: str(v) for k, v in cleared.items()},
    )
    return {"licenseId": license_id, "app": named, "deviceIdLock": "",
            "previousDeviceId": cleared.get(apps.field("previousDeviceId", named)) or "",
            "nextChangeAllowedAt": cleared.get("nextChangeAllowedAt") or ""}
