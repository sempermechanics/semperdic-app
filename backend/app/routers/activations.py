"""A licence's phones: Activate, list and Deactivate (ADR-033).

A licence that names its app licenses only the phones activated on it, up to
its `maxDevices`; any other phone of the holder is Demo. Three tiers reach a
licence's phones, each on routes of its own and each scoped again in
`repo.deactivations`, so a route cannot widen what its caller may touch:

- **The holder** (`/v1/me/devices*`): Activate the phone that signs the
  request; list the account's phones for one app; Deactivate one of their
  own (step-up, so the web account page can do it too).
- **Institution IT** (`/v1/institutions/licenses/{id}/devices*`): the
  licence's phones of members on its `domainLock`, and Deactivate. IT does
  not activate a phone for a member.
- **Staff** (`/v1/admin/licenses/{id}/devices*`): any licence's phones,
  Deactivate, and Add: activate a phone the holder has registered, within
  the count or past it with `override`.

Nothing here touches an analysis: a deactivated phone keeps its files.
"""
from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from typing import Optional

from pydantic import BaseModel, ConfigDict

from .. import apps, errors, rate_limit, repo
from ..models import DeviceActivate
from ..deps import admin_user, current_user, rate_limited, request_app, verified_device
from ..step_up import attested_or_mfa_admin, attested_or_mfa_user
from ..validation import DeviceId, DocumentId
from ._shared import named_app
from .account import config_body
from .institutions import institution_admin_stepup
from .roster import RosterAdmin

router = APIRouter()


class StaffActivation(BaseModel):
    """Staff adding a phone. `override` goes past the licence's count, for a
    support case the count should not block; it is audited as such."""
    model_config = ConfigDict(extra="forbid")
    override: bool = False


@router.post(
    "/v1/me/devices/activate",
    dependencies=[rate_limited(rate_limit.license_activate_bucket)],
)
def activate_this_phone(
    request: Request,
    body: Optional[DeviceActivate] = None,
    ctx=Depends(verified_device),
    app=Depends(request_app),
    x_device_id: str = Header(default=""),
):
    """Activate: license the phone that signs this request, for the app it
    runs (`X-App-Id`), on the licence the account holds for that app.

    Signed by the phone, so only the phone itself activates itself; staff add
    one from the operator console instead. `409 device_limit_reached: n/m`
    when every slot is taken, `409 seating_floating` on a floating licence
    (check out a seat instead), `404 no_license` with none. A phone already
    active answers as a success.

    `{"upgrade": true}` activates it on the new licence issued to the
    account instead, which moves the account there and revokes the old one
    (`repo.apply_upgrade`); `404 no_pending_upgrade` with none waiting, or
    when the old licence was revoked rather than replaced. Any refusal the
    activation would give is found before the account moves, with the same
    code, so the holder stays on the old licence.

    The answer carries the config the phone holds from now on, so the app
    unlocks without a restart or a second call.
    """
    if body is not None and body.upgrade:
        pending = apps.licence_view(ctx.user, app).get("pendingLicenseId") or ""
        if not pending:
            raise HTTPException(404, errors.NO_PENDING_UPGRADE)
        done = repo.apply_upgrade(pending, ctx.user["uid"], app, device_id=x_device_id)["activation"]
    else:
        done = repo.activate_device(ctx.user["uid"], x_device_id, app)
    user = repo.get_user(ctx.user["uid"]) or ctx.user
    # The check on the way in found this phone not yet active; read afresh.
    request.state.activation = {}
    return {"activation": done, "config": config_body(request, user, app, x_device_id)}


@router.get("/v1/me/devices")
def my_devices(
    user=Depends(current_user),
    header_app=Depends(request_app),
    app: str = Query(default=""),
    x_device_id: str = Header(default=""),
):
    """The caller's phones for one app: each registered phone, whether the
    licence licenses it, and the licence's count. The app's phones screen
    and the web account page (`?app=`, since a browser sends no `X-App-Id`).
    Only the caller's own phones, never another member's in a pool."""
    return repo.account_devices(user, named_app(app, header_app), x_device_id)


@router.delete(
    "/v1/me/devices/{device_id}",
    dependencies=[rate_limited(rate_limit.license_activate_bucket)],
)
def deactivate_my_phone(
    device_id: DeviceId,
    ctx=Depends(attested_or_mfa_user),
    header_app=Depends(request_app),
    app: str = Query(default=""),
):
    """Deactivate one of the caller's own phones on their licence for the
    app, freeing its slot at once. Step-up, like a device change: it is
    reachable from a browser. A phone that is not the caller's, or not
    active, is `404 activation_not_found`."""
    named = named_app(app, header_app)
    license_id = apps.licence_view(ctx.user, named).get("licenseId") or ""
    if not license_id:
        raise HTTPException(404, errors.ACTIVATION_NOT_FOUND)
    return repo.deactivate_device(license_id, device_id, actor=repo.ACTOR_SELF, caller=ctx.user)


@router.get(
    "/v1/institutions/licenses/{license_id}/devices",
    dependencies=[rate_limited(rate_limit.institution_bucket)],
)
def institution_devices(admin: RosterAdmin = Depends(institution_admin_stepup)):
    """The licence's active phones of members on its `domainLock`, with the
    pool's count."""
    return repo.licence_devices(admin.license_id, admin.license,
                                domain=admin.license.get("domainLock") or "")


@router.delete(
    "/v1/institutions/licenses/{license_id}/devices/{device_id}",
    dependencies=[rate_limited(rate_limit.institution_bucket)],
)
def institution_deactivate(device_id: DeviceId,
                           admin: RosterAdmin = Depends(institution_admin_stepup)):
    """Deactivate a member's phone, which also signs it out of their account
    (the release hold applies). Members off the licence's domain answer
    `404 activation_not_found`, as a phone that is not there does."""
    return repo.deactivate_device(admin.license_id, device_id, actor=repo.ACTOR_IT,
                                  caller=admin.user)


@router.get(
    "/v1/admin/licenses/{license_id}/devices",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def licence_devices(license_id: DocumentId, _admin=Depends(admin_user)):
    """Any licence's active phones, with its count."""
    lic = repo.get_license(license_id)
    if not lic:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    return repo.licence_devices(license_id, lic)


@router.delete(
    "/v1/admin/licenses/{license_id}/devices/{device_id}",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def staff_deactivate(license_id: DocumentId, device_id: DeviceId,
                     ctx=Depends(attested_or_mfa_admin)):
    """Deactivate any phone on any licence; it is signed out of its account."""
    return repo.deactivate_device(license_id, device_id, actor=repo.ACTOR_STAFF, caller=ctx.user)


@router.post(
    "/v1/admin/licenses/{license_id}/devices/{device_id}",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def staff_activate(license_id: DocumentId, device_id: DeviceId, body: StaffActivation,
                   ctx=Depends(attested_or_mfa_admin)):
    """Add a phone: activate one the holder has registered, for the app it
    registered under, on this licence. For a holder whose build cannot tap
    Activate, or a support case. Within `maxDevices` unless `override`."""
    device = repo.get_device(device_id)
    if not device or not device.get("uid"):
        raise HTTPException(404, errors.DEVICE_NOT_FOUND)
    return repo.activate_device(
        device["uid"], device_id, apps.canonical(device.get("app")),
        actor=repo.ACTOR_STAFF, by_uid=ctx.user["uid"], override=body.override,
        license_id=license_id)
