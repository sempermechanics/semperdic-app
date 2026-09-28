from fastapi import APIRouter, Depends, HTTPException

from .. import apps, audit, errors, firestore_repo as repo, statuses
from .. import rate_limit
from ..config import settings
from ..deps import admin_user, attested_or_mfa_admin, attested_or_mfa_admin_fresh, rate_limited
from ..licenses import KIND_INDIVIDUAL, KIND_INSTITUTION
from ..models import (
    AdminDeviceRelease,
    AdminLicenseConvert,
    AdminLicenseCreate,
    AdminLicenseUpdate,
    UserConfigPatch,
)
from ..validation import AccessStatus, DocumentId, LicenceSearch, PageToken, Uid
from ._shared import clamp_page_size, page_block

router = APIRouter()


@router.get("/v1/admin/users")
def admin_list_users(
    status: AccessStatus = "",
    limit: int = 50,
    page_token: PageToken = "",
    admin=Depends(admin_user),
):
    """List users, optionally filtered by access_status (e.g. ?status=PENDING).

    Cursor-paginated: `limit` (1..200, default 50) and optional `page_token`.
    Response includes `nextPageToken` / `hasMore`.
    """
    rate_limit.enforce(rate_limit.admin_bucket, admin["uid"])
    limit = clamp_page_size(limit, 200)
    users, next_token = repo.list_users(
        status, limit=limit, page_token=page_token or None,
    )
    return {
        "users": users,
        "page": page_block(limit, len(users), next_token),
    }


@router.post("/v1/admin/users/{uid}/approve", dependencies=[rate_limited(rate_limit.admin_bucket)])
def admin_approve_user(uid: Uid, ctx=Depends(attested_or_mfa_admin), admin=Depends(admin_user)):
    if not repo.set_user_status(uid, statuses.ACCESS_APPROVED):
        raise HTTPException(404, errors.USER_NOT_FOUND)
    audit.record(admin["uid"], action="ADMIN_APPROVE", target={"type": "user", "id": uid})
    return {"uid": uid, "access_status": statuses.ACCESS_APPROVED}


@router.post("/v1/admin/users/{uid}/revoke", dependencies=[rate_limited(rate_limit.admin_bucket)])
def admin_revoke_user(uid: Uid, ctx=Depends(attested_or_mfa_admin), admin=Depends(admin_user)):
    if not repo.set_user_status(uid, statuses.ACCESS_SUSPENDED):
        raise HTTPException(404, errors.USER_NOT_FOUND)
    audit.record(admin["uid"], action="ADMIN_REVOKE", target={"type": "user", "id": uid})
    return {"uid": uid, "access_status": statuses.ACCESS_SUSPENDED}


@router.post("/v1/admin/device-releases", dependencies=[rate_limited(rate_limit.admin_bucket)])
def admin_release_account_device(body: AdminDeviceRelease,
                                 ctx=Depends(attested_or_mfa_admin), admin=Depends(admin_user)):
    """Free an account's registered phone so a new one can register (TD-126).

    For a Demo account, on request: it has no licence lock to clear, and the app
    tells a phone refused at registration to ask an admin. By email, since that
    is what the request arrives with. An account on a live licence is 409
    `license_device_clear_required`: **New device** on its licence moves both
    bindings. `releasedDeviceId` is "" when nothing was registered.

    Releases every app's phone (ADR-010); `releasedDeviceIdMaterialTesting`
    names the Material Testing one.
    """
    user = repo.find_user_by_email(body.email)
    if user is None:
        raise HTTPException(404, errors.USER_NOT_FOUND)
    err, released = repo.release_account_device(user["uid"])
    if err:
        raise HTTPException(404 if err == errors.USER_NOT_FOUND else 409, err)
    audit.record(
        admin["uid"], action="ADMIN_DEVICE_RELEASE",
        target={"type": "user", "id": user["uid"]},
        detail=apps.spread("releasedDeviceId", released),
    )
    return {"uid": user["uid"], "email": user.get("email") or "",
            **apps.spread("releasedDeviceId", released)}


@router.patch("/v1/admin/users/{uid}/config", dependencies=[rate_limited(rate_limit.admin_bucket)])
def admin_patch_user_config(uid: Uid, body: UserConfigPatch,
                            ctx=Depends(attested_or_mfa_admin), admin=Depends(admin_user)):
    """Set or clear per-user product-limit overrides on the Firestore user doc."""
    # model_dump(exclude_unset=True) keeps omitted fields out; explicit nulls
    # remain so set_user_config can DELETE_FIELD them.
    patch = body.model_dump(exclude_unset=True)
    if not patch:
        raise HTTPException(400, errors.EMPTY_PATCH)
    resolved = repo.set_user_config(uid, patch)
    if resolved is None:
        raise HTTPException(404, errors.USER_NOT_FOUND)
    audit.record(admin["uid"], action="ADMIN_CONFIG", target={"type": "user", "id": uid},
                 detail={"patch": patch, "resolved": resolved})
    return {"uid": uid, "config": resolved}


@router.get("/v1/admin/licenses")
def admin_list_licenses(
    limit: int = 50,
    page_token: PageToken = "",
    include_demo: bool = False,
    include_revoked: bool = True,
    q: LicenceSearch = "",
    admin=Depends(admin_user),
):
    """Licences, newest first. System Demo keys are left out unless
    `include_demo`; revoked licences are left out when `include_revoked` is
    false. `q` searches by exact address, domain or key prefix instead of
    paging (see `repo.list_licenses`)."""
    rate_limit.enforce(rate_limit.admin_bucket, admin["uid"])
    limit = clamp_page_size(limit, 200)
    licenses, next_token = repo.list_licenses(
        limit=limit, page_token=page_token or None,
        include_demo=include_demo, include_revoked=include_revoked, q=q.strip(),
    )
    return {
        "licenses": licenses,
        # What every demo-mode key gives its holder, whatever the key stores.
        # The desk shows it on demo rows instead of their `maxAnalyses`.
        "demoMaxAnalyses": settings.DEMO_MAX_ANALYSES,
        "page": page_block(limit, len(licenses), next_token),
    }


@router.post("/v1/admin/licenses", dependencies=[rate_limited(rate_limit.admin_bucket)])
def admin_create_license(
    body: AdminLicenseCreate,
    ctx=Depends(attested_or_mfa_admin),
    admin=Depends(admin_user),
):
    """Mint a licensed key.

    `kind=individual` (default) locks one email; the device lock is optional
    and normally left empty, binding to the first device that signs in as that
    address. Minting also records a pending invite, which is how the licence
    reaches the customer — they sign in and it attaches. The plaintext key is
    returned once and is the support-recovery path, not the delivery one.

    `kind=institution` mints an institution key instead — no email/device lock
    at mint time; membership is decided per-activation by `domainLock`, and
    `adminEmails` names the IT contacts who self-serve seat management via
    /v1/institutions/licenses/{id}/seats (see routers/institutions.py).

    Only Semper staff (this device-attested admin path) may mint or whole-key
    revoke; institution IT never reaches this route.
    """
    if body.kind == KIND_INSTITUTION:
        minted = repo.create_institution_license(
            domain_lock=body.domainLock,
            admin_emails=body.adminEmails,
            created_by_uid=admin["uid"],
            max_seats=body.maxSeats,
            seating=body.seating,
            expires_at=body.expiresAt,
            grace_days=body.graceDays,
            support_until=body.supportUntil,
            max_analyses=body.maxAnalyses,
            note=body.note,
        )
        audit.record(
            admin["uid"], action="ADMIN_LICENSE_MINT",
            target={"type": "license", "id": minted["license"]["id"]},
            detail={"kind": KIND_INSTITUTION, "domainLock": body.domainLock,
                    "adminEmails": body.adminEmails, "maxSeats": body.maxSeats,
                    "seating": body.seating},
        )
        return minted
    # One licence per person. Refused before anything is written: the mint
    # used to go ahead and only report that the licence had not reached them.
    # The id is the licence they hold, for the desk to open — renewal is
    # Extend on that one.
    held = repo.licence_held_by(body.emailLock)
    if held:
        raise HTTPException(409, f"{errors.EMAIL_ALREADY_LICENSED}: {held}")
    minted = repo.create_individual_license(
        email_lock=body.emailLock,
        device_id_lock=body.deviceIdLock,
        created_by_uid=admin["uid"],
        expires_at=body.expiresAt,
        grace_days=body.graceDays,
        support_until=body.supportUntil,
        max_analyses=body.maxAnalyses,
        note=body.note,
    )
    audit.record(
        admin["uid"], action="ADMIN_LICENSE_MINT",
        target={"type": "license", "id": minted["license"]["id"]},
        detail={"kind": KIND_INDIVIDUAL, "emailLock": body.emailLock,
                "deviceIdLock": body.deviceIdLock,
                "inviteError": minted.get("inviteError") or "",
                # Set when the address already had an account, which the
                # mint attached to directly (see _attach_to_existing_holder).
                "claimedByUid": minted.get("claimedByUid") or "",
                "claimError": minted.get("claimError") or ""},
    )
    return minted


@router.get("/v1/admin/licenses/{license_id}")
def admin_get_license(
    license_id: DocumentId,
    admin=Depends(admin_user),
):
    """One licence, as a row of the list. The desk refreshes the row a
    change touched with this instead of reloading the whole table."""
    rate_limit.enforce(rate_limit.admin_bucket, admin["uid"])
    lic = repo.get_license_public(license_id)
    if lic is None:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    return lic


@router.patch(
    "/v1/admin/licenses/{license_id}",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def admin_update_license(
    license_id: DocumentId,
    body: AdminLicenseUpdate,
    ctx=Depends(attested_or_mfa_admin),
    admin=Depends(admin_user),
):
    """Device-attested, Semper-staff only. Change a license's terms in place.

    Renewal lives here: extending `expiresAt` re-entitles everyone already on
    the license without issuing a new key or asking anyone to re-activate. The
    new terms are pushed to the individual redeemer, or to every non-revoked
    institution seat, before this returns.

    Terms only — `kind`, the email/domain locks and the key itself are fixed
    at mint. `clearDeviceLock=true` is the one thing here that is not a term:
    it unbinds an individual licence from the phone it is on so the customer
    can move to a new one. Nothing is revoked and nothing has to be typed —
    the next device to sign in binds. Use the seat route below for an
    institution member.

    `maxAnalyses` on a demo-mode key is refused (422 `cap_on_demo_key`): a
    demo holder gets `DEMO_MAX_ANALYSES` whatever the key stores, so the edit
    would answer 200 and change nothing. `clearMaxAnalyses` is still allowed.

    Upgrades and downgrades in place: `perpetual`, `allowShorten` with an
    earlier `expiresAt`, and on an institution licence `seating`, `maxSeats`
    and `adminEmails` (`license_edit_error` has every refusal). Unknown
    fields are a 422, not silently dropped.
    """
    patch = body.model_dump(exclude_none=True)
    clear_lock = patch.pop("clearDeviceLock", False)
    # Every check is made before the lock is touched, so a refused edit never
    # leaves half the request applied. `update_license` checks again against
    # what it reads, for an edit that lands in between.
    if clear_lock and patch:
        current = repo.get_license(license_id)
        err = repo.license_edit_error(current, patch) if current else ""
        if err:
            raise HTTPException(422, err)
    cleared = {}
    if clear_lock:
        err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)
        if err:
            raise HTTPException(404, err)
        audit.record(
            admin["uid"], action="ADMIN_DEVICE_LOCK_CLEAR",
            target={"type": "license", "id": license_id},
            detail={k: str(v) for k, v in (cleared or {}).items()},
        )
    # An empty patch is a device change on its own; `update_license` answers
    # with the licence as it stands and writes nothing, which is what that is.
    try:
        updated = repo.update_license(license_id, patch, admin["uid"])
    except repo.LicenseTermsRejected as exc:
        raise HTTPException(422, exc.code) from exc
    if updated is None:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    if patch:
        audit.record(
            admin["uid"], action="ADMIN_LICENSE_EXTEND",
            target={"type": "license", "id": license_id},
            detail={k: str(v) for k, v in patch.items()},
        )
    return updated


@router.delete(
    "/v1/admin/licenses/{license_id}",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def admin_delete_license(
    license_id: DocumentId,
    ctx=Depends(attested_or_mfa_admin_fresh),
    admin=Depends(admin_user),
):
    """Delete a licence into a 30-day hold (`repo/deletion.py`). The same
    fresh step-up as a whole-licence revoke, which this runs first: holders
    drop to Demo and their data is untouched. The licence and its seats are
    copied to `deleted_licenses` with `purgeAt`, which a TTL policy removes;
    until then `POST /v1/admin/deleted-licenses/{id}/restore` brings it back.
    A system Demo key is refused (409 `demo_key_not_deletable`).
    """
    code, row = repo.delete_license(license_id, admin["uid"])
    if code:
        raise HTTPException(404 if code == errors.LICENSE_NOT_FOUND else 409, code)
    audit.record(
        admin["uid"], action="ADMIN_LICENSE_DELETE",
        target={"type": "license", "id": license_id},
        detail={"priorStatus": row["priorStatus"], "kind": row["kind"],
                "keyPrefix": row["keyPrefix"], "purgeAt": str(row["purgeAt"])},
    )
    return row


@router.get("/v1/admin/deleted-licenses")
def admin_list_deleted_licenses(
    limit: int = 50,
    admin=Depends(admin_user),
):
    """Licences deleted within the hold, most recent first, each with the
    date it is purged."""
    rate_limit.enforce(rate_limit.admin_bucket, admin["uid"])
    return {"licenses": repo.list_deleted_licenses(limit=clamp_page_size(limit, 200))}


@router.post(
    "/v1/admin/deleted-licenses/{license_id}/restore",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def admin_restore_license(
    license_id: DocumentId,
    ctx=Depends(attested_or_mfa_admin),
    admin=Depends(admin_user),
):
    """Bring a deleted licence back within its hold. Holders are re-attached
    unless they have taken another licence since (one licence per person)."""
    code, lic = repo.restore_license(license_id, admin["uid"])
    if code:
        status = {
            errors.DELETED_LICENSE_NOT_FOUND: 404,
            errors.DELETED_LICENSE_PURGED: 410,
            errors.LICENSE_EXISTS: 409,
        }.get(code, 409)
        raise HTTPException(status, code)
    audit.record(
        admin["uid"], action="ADMIN_LICENSE_RESTORE",
        target={"type": "license", "id": license_id},
        detail={"status": lic["status"], "seatsUsed": lic.get("seatsUsed")},
    )
    return lic


@router.post(
    "/v1/admin/licenses/{license_id}/convert",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def admin_convert_license(
    license_id: DocumentId,
    body: AdminLicenseConvert,
    ctx=Depends(attested_or_mfa_admin),
    admin=Depends(admin_user),
):
    """Device-attested, Semper-staff only. Replace an individual licence with
    an institution licence carrying its terms (`repo/upgrade.py`).

    The holder is seated on the new licence with their device lock, so
    nothing changes for them but the roster they are on; someone not signed
    in yet has their invite moved. The individual licence is then revoked
    with `supersededBy` set. The new key is returned once, as a mint does.
    """
    code, out = repo.convert_to_institution(
        license_id,
        domain_lock=body.domainLock,
        admin_emails=body.adminEmails,
        max_seats=body.maxSeats,
        seating=body.seating,
        admin_uid=admin["uid"],
    )
    if code:
        status = {
            errors.LICENSE_NOT_FOUND: 404,
            errors.LICENSE_REVOKED: 409,
            errors.LICENSE_NOT_CONVERTIBLE: 409,
            errors.CONVERT_DOMAIN_MISMATCH: 422,
            errors.CLAIM_CONTENDED: 503,
        }.get(code, 409)
        raise HTTPException(status, code)
    audit.record(
        admin["uid"], action="ADMIN_LICENSE_CONVERT",
        target={"type": "license", "id": license_id},
        detail={"to": out["license"]["id"], "domainLock": body.domainLock,
                "adminEmails": body.adminEmails, "maxSeats": body.maxSeats,
                "seating": body.seating, "claimedByUid": out["claimedByUid"]},
    )
    return out


@router.patch(
    "/v1/admin/licenses/{license_id}/seats/{uid}/device",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def admin_clear_seat_device_lock(
    license_id: DocumentId,
    uid: Uid,
    ctx=Depends(attested_or_mfa_admin),
    admin=Depends(admin_user),
):
    """Semper staff unbinding one institution seat from its device.

    The same operation IT already has at
    `PATCH /v1/institutions/licenses/{id}/seats/{uid}` with
    `clearDeviceLock`, at a different tier. Staff are not in a customer's
    `adminEmails`, so that route 404s for them, and support requests reach
    Semper before they reach the customer's own IT often enough that having
    no answer was the wrong posture.

    Clearing is not revoking: the seat, its lease and the member's data are
    untouched, and the next device that signs in binds.
    """
    err, cleared = repo.clear_device_lock(license_id, uid, actor=repo.ACTOR_STAFF)
    if err:
        raise HTTPException(404, err)
    audit.record(
        admin["uid"], action="ADMIN_DEVICE_LOCK_CLEAR",
        target={"type": "seat", "id": f"{license_id}/{uid}"},
        detail={k: str(v) for k, v in (cleared or {}).items()},
    )
    return {"licenseId": license_id, "uid": uid, "deviceIdLock": "",
            **{k: (cleared or {}).get(k) or ""
               for k in apps.spread("previousDeviceId", {})}}


@router.get("/v1/admin/licenses/{license_id}/device-history")
def admin_license_device_history(
    license_id: DocumentId,
    limit: int = 50,
    admin=Depends(admin_user),
):
    """Recent device bind / clear / unbind events for one licence.

    Read-only: ordinary admin token is enough (same as listing licences). The
    console uses this after a "New device" clear so support can see the move.
    """
    rate_limit.enforce(rate_limit.admin_bucket, admin["uid"])
    if repo.get_license(license_id) is None:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    return {
        "licenseId": license_id,
        "events": audit.list_license_device_history(license_id, limit=limit),
    }


@router.get("/v1/admin/licenses/{license_id}/reconcile")
def admin_reconcile_license_seats(
    license_id: DocumentId,
    admin=Depends(admin_user),
):
    """The second seat count: what this licence actually entitles right now.

    The institution console shows `seatsUsed`, which moves the moment IT
    revokes a seat — so it reports intent, and intent is all it can report.
    This compares each seat against its holder's account and says which
    revocations have landed, which have not, and why. The number worth acting
    on is `counts.revokedStillRunning`.

    A plain admin read, like `GET /v1/admin/licenses`: it changes nothing, so
    it does not take the second-factor tier the write routes do. It costs one
    user read per seat, which is why it is on demand and not folded into the
    licence listing.
    """
    rate_limit.enforce(rate_limit.admin_bucket, admin["uid"])
    err, report = repo.reconcile_institution_seats(license_id)
    if err == errors.LICENSE_NOT_FOUND:
        raise HTTPException(404, err)
    if err:
        raise HTTPException(400, err)
    return report


@router.post(
    "/v1/admin/licenses/{license_id}/revoke",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def admin_revoke_license(
    license_id: DocumentId,
    ctx=Depends(attested_or_mfa_admin_fresh),
    admin=Depends(admin_user),
):
    """Whole-licence revoke. Browser callers need a *fresh* password/Google
    re-auth plus TOTP (ADMIN_WEB_REVOKE_REAUTH_SECONDS), tighter than ordinary
    dashboard mutations — the console forces step-up before this call.
    """
    revoked = repo.revoke_license(license_id, admin["uid"])
    if revoked is None:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    audit.record(
        admin["uid"], action="ADMIN_LICENSE_REVOKE",
        target={"type": "license", "id": license_id},
    )
    return revoked
