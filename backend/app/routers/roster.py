"""An institution licence's roster: list, add, hold, unbind, remove.

One set of routes, mounted for two tiers. Institution IT reach them at
`/v1/institutions/licenses/{license_id}/…`, scoped to the licences that name
them (`routers/institutions.py`). Semper staff reach the same operations at
`/v1/admin/licenses/{license_id}/…` for any institution licence
(`routers/admin.py`), because support requests reach Semper before they reach
the customer's own IT often enough that staff need an answer. Only the tier
differs:
- who may call, and with which step-up;
- which rate-limit bucket they spend;
- who the device-change actor is (only the holder's own change has a cooldown);
- the prefix on the audit actions.
"""
from dataclasses import dataclass

from fastapi import APIRouter, Depends, HTTPException

from .. import apps, audit, errors, repo
from ..deps import rate_limited
from ..models import InstitutionSeatAdd, InstitutionSeatPatch
from ..rate_limit import TokenBucket
from ..validation import DocumentId, PageToken, Uid
from ._shared import clamp_page_size, page_block


@dataclass(frozen=True)
class RosterAdmin:
    """Whoever is managing this roster, and the licence they reached."""
    user: dict
    license_id: str
    license: dict


@dataclass(frozen=True)
class Tier:
    """How one kind of caller reaches the roster routes."""
    #: Route dependencies resolving a `RosterAdmin`, for reads and for changes.
    reader: object
    writer: object
    bucket: TokenBucket
    #: `repo.ACTOR_IT` or `repo.ACTOR_STAFF`, for a device-lock clear.
    actor: str
    #: Audit action prefix: `INSTITUTION` or `ADMIN`.
    audit: str


#: The largest roster page. A listing used to be the whole roster in one
#: answer, and `maxSeats` allows 100,000.
MAX_PAGE = 1000


def roster_router(tier: Tier) -> APIRouter:
    """The roster routes for `tier`, relative to `…/licenses/{license_id}`."""
    router = APIRouter()
    limited = [rate_limited(tier.bucket)]

    @router.get("/seats", dependencies=limited)
    def list_seats(
        license_id: DocumentId,
        page_size: int = 500,
        page_token: PageToken = "",
        admin: RosterAdmin = Depends(tier.reader),
    ):
        """Every seat on this license — uid, email, device lock, status,
        lease — a page at a time. No key plaintext: only the keyPrefix.

        `invites` are the addresses promised a place who have not signed in
        yet, listed beside the seats because to the person managing the
        roster they are the same list, though only a seat holds a uid and
        counts against `maxSeats`. They come with the first page.
        """
        page_size = clamp_page_size(page_size, MAX_PAGE)
        seats, next_token = repo.page_institution_seats(
            license_id, limit=page_size, page_token=page_token or None,
        )
        return {
            "license": repo.institution_license_summary(license_id, admin.license),
            "seats": seats,
            "invites": [] if page_token else repo.list_institution_invites(license_id),
            "page": page_block(page_size, len(seats), next_token),
        }

    @router.post("/seats", dependencies=limited)
    def add_seat(license_id: DocumentId, body: InstitutionSeatAdd,
                 admin: RosterAdmin = Depends(tier.writer)):
        """Put someone on this license's roster, by email.

        They do not need an account yet. An address that has one takes a seat
        at once; one that does not becomes a pending **invite**, redeemed the
        first time that person signs in. Exactly one of `seat` and `invite`
        comes back: an invite holds no seat and consumes no slot.

        On an assigned license a seat entitles them immediately. On a floating
        one it makes them eligible; they check out a lease when they work.
        """
        seat, invite = repo.add_institution_member(
            license_id, body.email, invited_by_uid=admin.user["uid"],
        )
        audit.record(
            admin.user["uid"],
            action=f"{tier.audit}_SEAT_ADD" if seat else f"{tier.audit}_INVITE_ADD",
            target={"type": "seat" if seat else "invite",
                    "id": f"{license_id}/{(seat or {}).get('uid') or (invite or {}).get('id')}"},
        )
        return {"licenseId": license_id, "seat": seat, "invite": invite}

    @router.delete("/invites/{invite_key}", dependencies=limited)
    def revoke_invite(license_id: DocumentId, invite_key: DocumentId,
                      admin: RosterAdmin = Depends(tier.writer)):
        """Withdraw a promise that has not been kept yet.

        Addressed by the invite id from the seats listing, not by email: an
        address in a request path ends up in access logs and proxy history.
        Nothing to undo on the account side — an unclaimed invite never
        entitled anyone. Removing someone who *has* signed in is the seat
        revoke route.
        """
        if not repo.revoke_institution_invite(license_id, invite_key):
            raise HTTPException(404, errors.INVITE_NOT_FOUND)
        audit.record(
            admin.user["uid"], action=f"{tier.audit}_INVITE_REVOKE",
            target={"type": "invite", "id": f"{license_id}/{invite_key}"},
        )
        return {"licenseId": license_id, "inviteId": invite_key, "revoked": True}

    @router.patch("/seats/{uid}", dependencies=limited)
    def patch_seat(license_id: DocumentId, uid: Uid, body: InstitutionSeatPatch,
                   admin: RosterAdmin = Depends(tier.writer)):
        """`clearDeviceLock=true` lets a seat holder move to a new device
        without anyone typing anything. `enabled=false` drops the seat to Demo
        *without* freeing the slot (it still counts against maxSeats),
        releasing any floating lease; `enabled=true` restores it in place. A
        removed seat is 409 `seat_revoked`: it is re-added, not resumed. At
        least one field must be set."""
        if body.clearDeviceLock is None and body.enabled is None:
            raise HTTPException(400, errors.EMPTY_PATCH)
        cleared = {}
        if body.clearDeviceLock:
            cleared = repo.clear_device_lock(license_id, uid, actor=tier.actor)
        if body.enabled is not None:
            repo.set_seat_enabled(license_id, uid, body.enabled)
        audit.record(
            admin.user["uid"], action=f"{tier.audit}_SEAT_PATCH",
            target={"type": "seat", "id": f"{license_id}/{uid}"},
            detail={"clearDeviceLock": bool(body.clearDeviceLock), "enabled": body.enabled,
                    # The device given up, per app (ADR-010). Its replacement is
                    # recorded by LICENSE_DEVICE_BIND when the next device signs
                    # in, so the two together say what the change was.
                    # `releasedDeviceId` is the registered device the account was
                    # signed out of, which can differ from the lock's.
                    **{k: cleared.get(k) or ""
                       for name in ("previousDeviceId", "releasedDeviceId")
                       for k in apps.spread(name, {})}},
        )
        seat = repo.institution_seat(license_id, uid)
        if seat is None:
            raise HTTPException(404, errors.SEAT_NOT_FOUND)
        return {"licenseId": license_id, "seat": seat}

    @router.delete("/seats/{uid}", dependencies=limited)
    def revoke_seat(license_id: DocumentId, uid: Uid,
                    admin: RosterAdmin = Depends(tier.writer)):
        """Single-seat revoke: drops the holder to Demo (in place, no data
        loss) and frees the slot. Whole-key revoke is the staff
        `POST /v1/admin/licenses/{id}/revoke`."""
        if not repo.revoke_institution_seat(license_id, uid):
            raise HTTPException(404, errors.SEAT_NOT_FOUND)
        audit.record(
            admin.user["uid"], action=f"{tier.audit}_SEAT_REVOKE",
            target={"type": "seat", "id": f"{license_id}/{uid}"},
        )
        return {"licenseId": license_id, "uid": uid, "revoked": True}

    return router
