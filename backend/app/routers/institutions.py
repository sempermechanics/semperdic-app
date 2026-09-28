"""Self-service seat management for institution licenses.

Institution IT drives these routes from the institution console (or curl).
Deliberately separate from `routers/admin.py`: Semper-staff mint and
whole-key revoke stay on the staff step-up path; institution IT gets a
narrower surface scoped to exactly the license(s) that name them.

Routes are served under `/v1/institutions/*` only. The pre-rename
`/v1/campus/*` aliases were retired on 2026-09-26 after 30 days with no
request to them (TD-45; docs/backend/CLOUD_ARCHITECTURE_GCP.md §20.5).

Auth is `current_user` (ID token, APPROVED) plus a *verified* email present
in that specific license's `adminEmails`, **plus** the same browser step-up
every dashboard uses (completed second factor on a recent sign-in). Not
Semper `role=admin` — an institution admin has no authority outside the
licenses that name them, and cross-tenant access (institution A IT reaching
institution B's seats) 404s the same as a license that does not exist —
membership is checked before MFA so a probe learns nothing about the factor.
"""
from fastapi import APIRouter, Depends, Header, HTTPException, Request

from .. import apps, audit, errors, firestore_repo as repo
from .. import rate_limit
from ..deps import current_user, ensure_web_step_up, rate_limited
from ..licenses import KIND_INSTITUTION, normalize_kind
from ..models import InstitutionSeatAdd, InstitutionSeatPatch
from ..validation import DocumentId, Uid

router = APIRouter()


def institution_admin_context(license_id: DocumentId, user: dict = Depends(current_user)) -> dict:
    """Institution IT auth for one institution license (membership only).

    Fails closed at every step: unverified email, a license id that does not
    exist or is not an institution license, or an email absent from that
    license's `adminEmails` all deny (404 for "no such institution license
    reachable by you",
    403 for "wrong email") rather than falling through to broader access. The
    404 on a real-but-foreign license is intentional — it must read
    identically to a license that does not exist, so probing license ids from
    another institution learns nothing.

    Mutating (and console-facing) routes use `institution_admin_stepup`, which
    layers MFA on top of this check.
    """
    if not user.get("emailVerified"):
        raise HTTPException(403, errors.EMAIL_NOT_VERIFIED)
    lic = repo.get_license(license_id)
    if not lic or normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    email = (user.get("email") or "").strip().lower()
    if not repo.is_institution_admin(lic, email):
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    return {"user": user, "license_id": license_id}


async def institution_admin_stepup(
    license_id: DocumentId,
    request: Request,
    user: dict = Depends(current_user),
    x_device_id: str = Header(default=""),
    x_nonce: str = Header(default=""),
    x_signature: str = Header(default=""),
) -> dict:
    """Institution IT + dashboard MFA step-up.

    Membership first (so foreign licences still 404), then the same
    device-or-MFA gate the operator and account consoles use.
    """
    ctx = institution_admin_context(license_id, user)
    await ensure_web_step_up(
        request, user, x_device_id, x_nonce, x_signature,
    )
    return ctx


@router.get("/v1/institutions/licenses")
def list_my_licenses(user=Depends(current_user)):
    """Which institution licences this caller administers.

    Every other route here is addressed by a licence id the caller already
    holds. Sign-in holds an address and nothing else, so without this a member
    of institution IT can only reach their roster by being told the id out of
    band — which is how it worked, and why the console had a text box asking
    for one. This is the read that lets a single sign-in page decide where to
    send somebody.

    Verified email, same as `institution_admin_context` — `adminEmails` names
    addresses, and an address nobody has proved they own must not be able to
    read a customer's roster. An empty list is the ordinary answer for the
    overwhelming majority of accounts and is not an error.
    """
    rate_limit.enforce(rate_limit.institution_bucket, user["uid"])
    if not user.get("emailVerified"):
        raise HTTPException(403, errors.EMAIL_NOT_VERIFIED)
    licenses = repo.list_licenses_administered_by(user.get("email") or "")
    return {"licenses": licenses}


@router.get(
    "/v1/institutions/licenses/{license_id}/seats",
    dependencies=[rate_limited(rate_limit.institution_bucket)],
)
def list_seats(license_id: DocumentId, ctx=Depends(institution_admin_stepup)):
    """Every seat on this license: uid, email, device lock, status. No key
    plaintext — only the license's keyPrefix, same redaction as the
    Semper-staff admin listing.

    `invites` are the addresses promised a place who have not signed in yet.
    They are listed beside the seats because to the person managing the
    roster they are the same list — "who is on this licence" — even though
    only one of the two holds a uid and counts against `maxSeats`.
    """
    return {
        "license": repo.institution_license_summary(license_id),
        "seats": repo.list_institution_seats(license_id),
        "invites": repo.list_institution_invites(license_id),
    }


@router.post(
    "/v1/institutions/licenses/{license_id}/seats",
    dependencies=[rate_limited(rate_limit.institution_bucket)],
)
def add_seat(
    license_id: DocumentId,
    body: InstitutionSeatAdd,
    ctx=Depends(institution_admin_stepup),
):
    """Put someone on this license's roster, by email.

    They do not need an account yet. An address that already has one takes a
    seat immediately; an address that does not becomes a pending **invite**,
    redeemed automatically the first time that person signs in. IT works from
    a list of addresses and cannot make people sign up on cue, so refusing
    them until they had was pushing a scheduling problem onto the wrong
    person.

    Exactly one of `seat` and `invite` comes back. An invite holds no seat and
    consumes no slot — there is no uid to entitle yet, and every check in the
    system is keyed by uid.

    On an assigned license a seat entitles them immediately. On a floating one
    it makes them eligible and consumes no slot; they check out a lease when
    they want to work.
    """
    code, seat, invite = repo.add_institution_member(
        license_id, body.email, invited_by_uid=ctx["user"]["uid"],
    )
    if code:
        status = {
            errors.LICENSE_NOT_FOUND: 404,
            errors.USER_NOT_FOUND: 404,
            errors.LICENSE_SEATS_EXHAUSTED: 409,
            errors.LICENSE_SEAT_DISABLED: 409,
            errors.INVITE_EXISTS: 409,
            errors.MEMBER_ALREADY_LICENSED: 409,
            errors.INVALID_EMAIL: 400,
            # Lost the race for the seat, not out of seats: try again.
            errors.CLAIM_CONTENDED: 503,
        }.get(code, 403)
        raise HTTPException(status, code)
    audit.record(
        ctx["user"]["uid"],
        action="INSTITUTION_SEAT_ADD" if seat else "INSTITUTION_INVITE_ADD",
        target={"type": "seat" if seat else "invite",
                "id": f"{license_id}/{(seat or invite or {}).get('uid') or (invite or {}).get('id')}"},
    )
    return {"licenseId": license_id, "seat": seat, "invite": invite}


@router.delete(
    "/v1/institutions/licenses/{license_id}/invites/{invite_key}",
    dependencies=[rate_limited(rate_limit.institution_bucket)],
)
def revoke_invite(
    license_id: DocumentId,
    invite_key: DocumentId,
    ctx=Depends(institution_admin_stepup),
):
    """Withdraw a promise that has not been kept yet.

    Addressed by the invite id from the seats listing, not by email: an
    address in a request path ends up in access logs and proxy history, and
    the id is what the console already holds.

    Nothing to undo on the account side — an unclaimed invite never entitled
    anyone. Removing a member who *has* signed in is the seat revoke route.
    """
    if not repo.revoke_institution_invite(license_id, invite_key):
        raise HTTPException(404, errors.INVITE_NOT_FOUND)
    audit.record(
        ctx["user"]["uid"], action="INSTITUTION_INVITE_REVOKE",
        target={"type": "invite", "id": f"{license_id}/{invite_key}"},
    )
    return {"licenseId": license_id, "inviteId": invite_key, "revoked": True}


@router.patch(
    "/v1/institutions/licenses/{license_id}/seats/{uid}",
    dependencies=[rate_limited(rate_limit.institution_bucket)],
)
def patch_seat(
    license_id: DocumentId,
    uid: Uid,
    body: InstitutionSeatPatch,
    ctx=Depends(institution_admin_stepup),
):
    """`clearDeviceLock=true` lets a seat holder re-bind to a new device
    without a Semper support ticket. `enabled=false` drops the seat to Demo
    *without* freeing the slot (still counts against maxSeats), releasing any
    floating lease; `enabled=true` restores Professional in place — same
    uid/account, no data migration either direction. A removed seat is 409
    `seat_revoked`: it is re-added, not resumed. At least one field must be set."""
    if body.clearDeviceLock is None and body.enabled is None:
        raise HTTPException(400, errors.EMPTY_PATCH)
    cleared = {}
    if body.clearDeviceLock:
        err, cleared = repo.clear_device_lock(license_id, uid, actor=repo.ACTOR_IT)
        if err:
            raise HTTPException(404, errors.SEAT_NOT_FOUND)
    if body.enabled is not None:
        err = repo.set_seat_enabled(license_id, uid, body.enabled)
        if err:
            raise HTTPException(404 if err == errors.SEAT_NOT_FOUND else 409, err)
    audit.record(
        ctx["user"]["uid"], action="INSTITUTION_SEAT_PATCH",
        target={"type": "seat", "id": f"{license_id}/{uid}"},
        detail={"clearDeviceLock": bool(body.clearDeviceLock), "enabled": body.enabled,
                # The device given up, per app (ADR-010). Its replacement is
                # recorded by LICENSE_DEVICE_BIND when the next device signs
                # in, so the two together say what the change actually was.
                # `releasedDeviceId` is the registered device the account was
                # signed out of, which can differ from the lock's
                # (repo.clear_device_lock).
                **{k: (cleared or {}).get(k) or ""
                   for name in ("previousDeviceId", "releasedDeviceId")
                   for k in apps.spread(name, {})}},
    )
    seats = repo.list_institution_seats(license_id)
    seat = next((s for s in seats if s["uid"] == uid), None)
    if seat is None:
        raise HTTPException(404, errors.SEAT_NOT_FOUND)
    return {"licenseId": license_id, "seat": seat}


@router.delete(
    "/v1/institutions/licenses/{license_id}/seats/{uid}",
    dependencies=[rate_limited(rate_limit.institution_bucket)],
)
def revoke_seat(license_id: DocumentId, uid: Uid, ctx=Depends(institution_admin_stepup)):
    """Single-seat revoke: drops the holder to Demo (in place, no data loss)
    and frees the slot so another domain member can activate. Whole-key revoke
    stays on the Semper-staff POST /v1/admin/licenses/{id}/revoke path."""
    if not repo.revoke_institution_seat(license_id, uid):
        raise HTTPException(404, errors.SEAT_NOT_FOUND)
    audit.record(
        ctx["user"]["uid"], action="INSTITUTION_SEAT_REVOKE",
        target={"type": "seat", "id": f"{license_id}/{uid}"},
    )
    return {"licenseId": license_id, "uid": uid, "revoked": True}
