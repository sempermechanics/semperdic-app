import logging

from fastapi import APIRouter, Depends, HTTPException

from .. import apps, audit, errors, google_auth, repo, statuses
from .. import observability as obs
from .. import rate_limit
from ..config import settings
from ..deps import admin_user, rate_limited
from ..step_up import attested_or_mfa_admin, attested_or_mfa_admin_fresh
from ..licenses import KIND_INSTITUTION, normalize_kind
from ..models import (
    AdminDeviceRelease,
    AdminLicenseConvert,
    AdminLicenseCreate,
    AdminLicenseSearch,
    AdminLicenseUpdate,
    UserConfigPatch,
)
from ..services import licenses as license_service
from ..validation import AccessStatus, DocumentId, LicenceSearch, PageToken, Uid
from ._shared import clamp_page_size, page_block
from .roster import RosterAdmin, Tier, roster_router

log = logging.getLogger("semper.admin")
router = APIRouter()


def _audited_patch(patch: dict) -> dict:
    """A licence edit as ADMIN_LICENSE_EXTEND records it: each term as text,
    except `adminEmails`, which is hashed (`admin_email_detail`), and `note`,
    free text staff may type a name or an address into: only that it changed
    and its length (TD-270). The row outlives erasure and reaches the
    licence's audit export."""
    out = {k: str(v) for k, v in patch.items() if k not in ("adminEmails", "note")}
    if "adminEmails" in patch:
        out.update(license_service.admin_email_detail(patch["adminEmails"]))
    if "note" in patch:
        out.update({"noteChanged": "true", "noteLength": str(len(patch["note"] or ""))})
    return out


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
def admin_approve_user(uid: Uid, ctx=Depends(attested_or_mfa_admin)):
    admin = ctx.user
    if not repo.set_user_status(uid, statuses.ACCESS_APPROVED):
        raise HTTPException(404, errors.USER_NOT_FOUND)
    audit.record(admin["uid"], action="ADMIN_APPROVE", target={"type": "user", "id": uid})
    return {"uid": uid, "access_status": statuses.ACCESS_APPROVED}


@router.post("/v1/admin/users/{uid}/revoke", dependencies=[rate_limited(rate_limit.admin_bucket)])
def admin_revoke_user(uid: Uid, ctx=Depends(attested_or_mfa_admin)):
    """Suspend an account, then revoke its Firebase refresh tokens.

    The suspension is the control: every route behind `current_user` refuses
    a SUSPENDED account from the next request. Revoking the refresh tokens
    (A5.5) only stops new ID tokens being minted from existing sign-ins, and
    is best-effort until the runtime account holds Firebase Auth admin on the
    Auth project, so the audit row says whether it happened
    (`sessionsRevoked`, with `authUsers` and `authUsersRevoked`: every Auth
    user that signs in to the account is revoked, linked subs included) and
    the request never fails on it.
    """
    admin = ctx.user
    if not repo.set_user_status(uid, statuses.ACCESS_SUSPENDED):
        raise HTTPException(404, errors.USER_NOT_FOUND)
    # Every Auth user that signs in to the account, linked subs included.
    auth_uids = repo.sign_in_uids(uid)
    revoked = sum(1 for sub in auth_uids if google_auth.revoke_sessions(sub))
    audit.record(admin["uid"], action="ADMIN_REVOKE", target={"type": "user", "id": uid},
                 detail={"sessionsRevoked": revoked == len(auth_uids),
                         "authUsers": len(auth_uids), "authUsersRevoked": revoked})
    return {"uid": uid, "access_status": statuses.ACCESS_SUSPENDED}


@router.post("/v1/admin/device-releases", dependencies=[rate_limited(rate_limit.admin_bucket)])
def admin_release_account_device(body: AdminDeviceRelease,
                                 ctx=Depends(attested_or_mfa_admin)):
    """Free an account's registered phone so a new one can register (TD-126).

    For a Demo account, on request: it has no licence lock to clear, and the app
    tells a phone refused at registration to ask an admin. By email, since that
    is what the request arrives with. An account on a live licence is 409
    `license_device_clear_required`: **New device** on its licence moves both
    bindings. `releasedDeviceId` is "" when nothing was registered.

    Releases every app's phone (ADR-010); `releasedDeviceIdSemperClassroom`
    names the Semper Classroom one (mirrored as `releasedDeviceIdMaterialTesting`
    for a console from before ADR-030).
    """
    admin = ctx.user
    user = repo.find_user_by_email(body.email)
    if user is None:
        raise HTTPException(404, errors.USER_NOT_FOUND)
    released = repo.release_account_device(user["uid"])
    # The phone may go to someone else: its demo count stops holding this
    # account's analyses, which still count toward the account's (TD-276).
    forgotten = repo.forget_device_sessions(user["uid"], released.values())
    audit.record(
        admin["uid"], action="ADMIN_DEVICE_RELEASE",
        target={"type": "user", "id": user["uid"]},
        detail={**apps.spread("releasedDeviceId", released), "sessionsForgotten": forgotten},
    )
    return {"uid": user["uid"], "email": user.get("email") or "",
            **apps.spread("releasedDeviceId", released)}


@router.patch("/v1/admin/users/{uid}/config", dependencies=[rate_limited(rate_limit.admin_bucket)])
def admin_patch_user_config(uid: Uid, body: UserConfigPatch,
                            ctx=Depends(attested_or_mfa_admin)):
    """Set or clear per-user product-limit overrides on the Firestore user doc."""
    # model_dump(exclude_unset=True) keeps omitted fields out; explicit nulls
    # remain so set_user_config can DELETE_FIELD them.
    admin = ctx.user
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
    include_test: bool = False,
    admin=Depends(admin_user),
):
    """Licences, newest first. System Demo keys are left out unless
    `include_demo`, and test-account licences unless `include_test`
    (ADR-027); revoked licences are left out when `include_revoked` is
    false. `q` searches by exact address, domain or key prefix instead of
    paging (see `repo.list_licenses`).

    `q` here is deprecated (TD-244): it puts an address in the URL, which
    request logs keep. `POST /v1/admin/licenses/search` takes it in a body.
    It is still served for one release, with a warning that names the caller
    and never the value."""
    if q:
        obs.log_event(log, logging.WARNING, "deprecated_query_search",
                      uid=admin["uid"], path="/v1/admin/licenses")
    return _licence_listing(admin, limit=limit, page_token=page_token,
                            include_demo=include_demo, include_revoked=include_revoked, q=q,
                            include_test=include_test)


@router.post("/v1/admin/licenses/search")
def admin_search_licenses(body: AdminLicenseSearch, admin=Depends(admin_user)):
    """The licence search with its terms in the body, so an address never
    reaches a URL (TD-244). A read: the ordinary admin token, the admin
    bucket and the answer of `GET /v1/admin/licenses`, whose query fields
    the body repeats."""
    return _licence_listing(admin, limit=body.limit, page_token=body.page_token,
                            include_demo=body.include_demo,
                            include_revoked=body.include_revoked, q=body.q,
                            include_test=body.include_test)


def _licence_listing(admin: dict, *, limit: int, page_token: str, include_demo: bool,
                     include_revoked: bool, q: str, include_test: bool = False) -> dict:
    """The licence table or a search over it, for the GET and the POST."""
    rate_limit.enforce(rate_limit.admin_bucket, admin["uid"])
    limit = clamp_page_size(limit, 200)
    licenses, next_token = repo.list_licenses(
        limit=limit, page_token=page_token or None,
        include_demo=include_demo, include_revoked=include_revoked, q=q.strip(),
        include_test=include_test,
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
    return license_service.mint_license(body, ctx.user["uid"])


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
    admin = ctx.user
    patch = body.model_dump(exclude_none=True)
    clear_lock = patch.pop("clearDeviceLock", False)
    # Every check is made before the lock is touched, so a refused edit never
    # leaves half the request applied. `update_license` checks again against
    # what it reads, for an edit that lands in between.
    if clear_lock and patch:
        current = repo.get_license(license_id)
        err = repo.license_edit_error(current, patch) if current else ""
        if err:
            raise errors.Refusal(err)
    if clear_lock:
        cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)
        audit.record(
            admin["uid"], action="ADMIN_DEVICE_LOCK_CLEAR",
            target={"type": "license", "id": license_id},
            detail={k: str(v) for k, v in cleared.items()},
        )
    # An empty patch is a device change on its own; `update_license` answers
    # with the licence as it stands and writes nothing, which is what that is.
    updated = repo.update_license(license_id, patch, admin["uid"])
    if updated is None:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    if patch:
        audit.record(
            admin["uid"], action="ADMIN_LICENSE_EXTEND",
            target={"type": "license", "id": license_id},
            detail=_audited_patch(patch),
        )
    return updated


@router.delete(
    "/v1/admin/licenses/{license_id}",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def admin_delete_license(
    license_id: DocumentId,
    ctx=Depends(attested_or_mfa_admin_fresh),
):
    """Delete a licence into a 30-day hold (`repo/deletion.py`). The same
    fresh step-up as a whole-licence revoke, which this runs first: holders
    drop to Demo and their data is untouched. The licence and its seats are
    copied to `deleted_licenses` with `purgeAt`, which a TTL policy removes;
    until then `POST /v1/admin/deleted-licenses/{id}/restore` brings it back.
    A system Demo key is refused (409 `demo_key_not_deletable`).
    """
    admin = ctx.user
    row = repo.delete_license(license_id, admin["uid"])
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
    page_token: PageToken = "",
    admin=Depends(admin_user),
):
    """Licences deleted within the hold, most recent first, each with the
    date it is purged.

    Cursor-paginated: `limit` (1..200, default 50) and optional `page_token`.
    Response includes `page.nextPageToken` / `page.hasMore`.
    """
    rate_limit.enforce(rate_limit.admin_bucket, admin["uid"])
    limit = clamp_page_size(limit, 200)
    licenses, next_token = repo.list_deleted_licenses(
        limit=limit, page_token=page_token or None,
    )
    return {"licenses": licenses, "page": page_block(limit, len(licenses), next_token)}


@router.post(
    "/v1/admin/deleted-licenses/{license_id}/restore",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def admin_restore_license(
    license_id: DocumentId,
    ctx=Depends(attested_or_mfa_admin),
):
    """Bring a deleted licence back within its hold. Holders are re-attached
    unless they have taken another licence since (one licence per person)."""
    admin = ctx.user
    lic = repo.restore_license(license_id, admin["uid"])
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
):
    """Device-attested, Semper-staff only. Replace an individual licence with
    an institution licence carrying its terms (`repo/upgrade.py`).

    The holder is seated on the new licence with their device lock, so
    nothing changes for them but the roster they are on; someone not signed
    in yet has their invite moved. The individual licence is then revoked
    with `supersededBy` set. The new key is returned once, as a mint does.
    """
    admin = ctx.user
    out = repo.convert_to_institution(
        license_id,
        domain_lock=body.domainLock,
        admin_emails=body.adminEmails,
        max_seats=body.maxSeats,
        seating=body.seating,
        admin_uid=admin["uid"],
    )
    audit.record(
        admin["uid"], action="ADMIN_LICENSE_CONVERT",
        target={"type": "license", "id": license_id},
        detail={"to": out["license"]["id"], "domainLock": body.domainLock,
                **license_service.admin_email_detail(body.adminEmails), "maxSeats": body.maxSeats,
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
    admin = ctx.user
    cleared = repo.clear_device_lock(license_id, uid, actor=repo.ACTOR_STAFF)
    audit.record(
        admin["uid"], action="ADMIN_DEVICE_LOCK_CLEAR",
        target={"type": "seat", "id": f"{license_id}/{uid}"},
        detail={k: str(v) for k, v in cleared.items()},
    )
    return {"licenseId": license_id, "uid": uid, "deviceIdLock": "",
            **{k: cleared.get(k) or "" for k in apps.spread("previousDeviceId", {})}}


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
        "events": repo.list_license_device_history(license_id, limit=limit),
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
    return repo.reconcile_institution_seats(license_id)


@router.post(
    "/v1/admin/licenses/{license_id}/revoke",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def admin_revoke_license(
    license_id: DocumentId,
    ctx=Depends(attested_or_mfa_admin_fresh),
):
    """Whole-licence revoke. Browser callers need a *fresh* password/Google
    re-auth plus TOTP (ADMIN_WEB_REVOKE_REAUTH_SECONDS), tighter than ordinary
    dashboard mutations — the console forces step-up before this call.
    """
    admin = ctx.user
    revoked = repo.revoke_license(license_id, admin["uid"])
    if revoked is None:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    audit.record(
        admin["uid"], action="ADMIN_LICENSE_REVOKE",
        target={"type": "license", "id": license_id},
    )
    return revoked


# ---------------- institution rosters, at the staff tier ----------------
# The same roster routes IT has (`routers/roster.py`), for any institution
# licence. Staff are not in a customer's `adminEmails`, and a support request
# must not depend on whether they are. Reads take the ordinary admin token,
# like the licence listing; changes take the staff step-up, like every other
# staff write.


def _staff_roster(license_id: str, user: dict) -> RosterAdmin:
    lic = repo.get_license(license_id)
    if not lic or normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    return RosterAdmin(user, license_id, lic)


def staff_roster_reader(license_id: DocumentId, admin=Depends(admin_user)) -> RosterAdmin:
    return _staff_roster(license_id, admin)


def staff_roster_writer(license_id: DocumentId,
                        ctx=Depends(attested_or_mfa_admin)) -> RosterAdmin:
    return _staff_roster(license_id, ctx.user)


router.include_router(
    roster_router(Tier(
        reader=staff_roster_reader, writer=staff_roster_writer,
        bucket=rate_limit.admin_bucket, actor=repo.ACTOR_STAFF, audit="ADMIN",
    )),
    prefix="/v1/admin/licenses/{license_id}",
)
