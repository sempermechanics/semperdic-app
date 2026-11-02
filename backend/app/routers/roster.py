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

Beside the one-at-a-time routes: a bulk CSV import that runs each row through
the single add, a CSV export, a seat summary, and the licence's own audit log
(`roster_io.py` holds the files and the counting).
"""
import logging
import re
import time
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Response
from google.api_core import exceptions as google_exceptions
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from .. import apps, audit, errors, rate_limit, repo, roster_io
from .. import observability as obs
from ..deps import rate_limited
from ..models import InstitutionSeatAdd, InstitutionSeatPatch
from ..rate_limit import TokenBucket
from ..validation import DocumentId, PageToken, Uid
from ._shared import clamp_page_size, page_block

log = logging.getLogger("roster")


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
    #: The licence's own IT: adds only on its `domainLock`, and an address
    #: another licence holds is answered as an unknown one (ADDENDUM-2).
    institution_it: bool = False


#: The largest roster page. A listing used to be the whole roster in one
#: answer, and `maxSeats` allows 100,000.
MAX_PAGE = 1000

#: How long an import may spend on its rows. The gateway gives a request 60 s;
#: rows not reached by then come back `unprocessed`, and importing the same
#: file again finishes them, since rows already on the roster answer `exists`.
IMPORT_BUDGET_S = 40.0

#: Every outcome an import row can have, in the order the counts list them.
#: `seated` and `invited` are the single add's two answers; `exists` is an
#: address already on the roster (active, on hold or invited), left as it is;
#: `duplicate` repeats an earlier row of the same file; `invalid` is not an
#: address; `refused` is the single add's refusal, its code in `error`;
#: `unprocessed` was not reached or hit a backend fault.
IMPORT_OUTCOMES = ("seated", "invited", "exists", "duplicate", "invalid", "refused", "unprocessed")


class RosterImport(BaseModel):
    """A bulk roster import: the CSV file's text (see `roster_io.parse_import`)."""
    model_config = ConfigDict(extra="forbid")
    # No max_length: an oversized file is `roster_import_too_large` from
    # `parse_import`, a code the console words, not a validator sentence.
    csv: str = Field(min_length=1)


def _attachment(name: str, license_id: str) -> str:
    """A Content-Disposition naming the download after the licence. The id is
    any Firestore id (`DocumentId`), so anything but a plain name character
    is replaced rather than quoted into a header."""
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", license_id)[:64]
    return f'attachment; filename="{name}-{safe}.csv"'


def _csv(body: str, disposition: str, headers: dict | None = None) -> Response:
    return Response(body, media_type="text/csv; charset=utf-8", headers={
        "Content-Disposition": disposition,
        # A roster is personal data; no shared cache keeps a copy.
        "Cache-Control": "no-store",
        **(headers or {}),
    })


def roster_router(tier: Tier) -> APIRouter:
    """The roster routes for `tier`, relative to `…/licenses/{license_id}`."""
    router = APIRouter()
    limited = [rate_limited(tier.bucket)]
    # Bulk work spends the tier's bucket like any roster call, and one of its
    # own: one request here is hundreds of documents.
    importing = [*limited, rate_limited(rate_limit.roster_import_bucket)]
    reporting = [*limited, rate_limited(rate_limit.roster_report_bucket)]

    def add_member(admin: RosterAdmin, license_id: str, email: str):
        """The single add: seat or invite one address, and audit it. Returns
        `(seat, invite)`, or raises `Refusal`. The bulk import runs every row
        through here, so the two cannot disagree about who may be added."""
        seat, invite = repo.add_institution_member(
            license_id, email, invited_by_uid=admin.user["uid"],
            institution_it=tier.institution_it,
        )
        audit.record(
            admin.user["uid"],
            action=f"{tier.audit}_SEAT_ADD" if seat else f"{tier.audit}_INVITE_ADD",
            target={"type": "seat" if seat else "invite",
                    "id": f"{license_id}/{(seat or {}).get('uid') or (invite or {}).get('id')}"},
        )
        return seat, invite

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

        Institution IT may add only addresses on the licence's `domainLock`
        (422 `member_off_domain`), and an address another licence holds or
        promises is answered as one with no account: a pending invite, which
        is not claimed while that other licence holds them. Semper staff may
        add any address and are told `member_already_licensed`.
        """
        seat, invite = add_member(admin, license_id, body.email)
        return {"licenseId": license_id, "seat": seat, "invite": invite}

    @router.post("/seats/import", dependencies=importing)
    def import_seats(license_id: DocumentId, body: RosterImport,
                     admin: RosterAdmin = Depends(tier.writer)):
        """Add up to 500 addresses from a CSV file, each as the single add would.

        One column is read, `email` (or the first column when the file has no
        header row), so an exported roster imports back as it is. A file that
        cannot be read, holds no rows, or holds more than 500 is refused
        whole, before any row is touched. Otherwise every row is answered,
        `{row, email, outcome, error}` in file order, and the rows that can be
        added are, whatever happens to the others.

        Importing the same file twice changes nothing the second time: an
        address with a seat (active or on hold) or an invite on this licence
        answers `exists` and is left alone. That is also how an import that
        ran out of time is finished (`unprocessed` rows). One summary event
        (`…_ROSTER_IMPORT`, the counts) is audited beside each row's own.
        """
        try:
            rows = roster_io.parse_import(body.csv)
        except roster_io.RosterRefused as e:
            too_big = e.code in (errors.ROSTER_IMPORT_TOO_MANY_ROWS, errors.ROSTER_IMPORT_TOO_LARGE)
            raise HTTPException(413 if too_big else 400, e.detail) from e
        on_roster = roster_io.roster_addresses(
            repo.list_institution_seats(license_id), repo.list_institution_invites(license_id),
        )
        first_row: dict[str, int] = {}
        deadline = time.monotonic() + IMPORT_BUDGET_S
        results = [
            _import_row(admin, license_id, row, on_roster, first_row, deadline)
            for row in rows
        ]
        counts = Counter(r["outcome"] for r in results)
        summary = {outcome: counts.get(outcome, 0) for outcome in IMPORT_OUTCOMES}
        audit.record(
            admin.user["uid"], action=f"{tier.audit}_ROSTER_IMPORT",
            target={"type": "license", "id": license_id},
            detail={"rows": len(rows), **summary},
        )
        return {"licenseId": license_id, "rows": len(rows), "counts": summary,
                "results": results}

    def _import_row(admin: RosterAdmin, license_id: str, row: roster_io.ImportRow,
                    on_roster: set[str], first_row: dict[str, int], deadline: float) -> dict:
        """One import row's answer; adds it through `add_member` when it can."""
        def answer(outcome: str, email: str = row.email, error: str = "") -> dict:
            return {"row": row.row, "email": email, "outcome": outcome, "error": error}

        try:
            email = InstitutionSeatAdd(email=row.email).email
        except ValidationError:
            return answer("invalid", error=errors.INVALID_EMAIL)
        if email in first_row:
            return answer("duplicate", email, f"row {first_row[email]}")
        first_row[email] = row.row
        if email in on_roster:
            return answer("exists", email)
        if time.monotonic() > deadline:
            return answer("unprocessed", email, errors.ROSTER_ROW_UNPROCESSED)
        try:
            seat, _invite = add_member(admin, license_id, email)
        except errors.Refusal as e:
            return answer("refused", email, e.code)
        except (google_exceptions.GoogleAPICallError, google_exceptions.RetryError) as e:
            # A Firestore fault on one row is that row's, not the import's:
            # the rows already added stay added and are reported as such.
            obs.log_event(log, logging.WARNING, "roster_import_row_failed",
                          outcome="error", errorCode=type(e).__name__)
            return answer("unprocessed", email, errors.ROSTER_ROW_UNPROCESSED)
        on_roster.add(email)
        return answer("seated" if seat else "invited", email)

    @router.get("/seats/export", dependencies=reporting)
    def export_seats(license_id: DocumentId, admin: RosterAdmin = Depends(tier.reader)):
        """The whole roster as a CSV download: every seat, then every invite.

        `email` is the first column, so the file is also a valid import.
        Audited (`…_ROSTER_EXPORT`): it is the roster's personal data leaving
        the service.
        """
        body = roster_io.roster_csv(
            repo.list_institution_seats(license_id), repo.list_institution_invites(license_id),
        )
        audit.record(
            admin.user["uid"], action=f"{tier.audit}_ROSTER_EXPORT",
            target={"type": "license", "id": license_id},
        )
        return _csv(body, _attachment("roster", license_id))

    @router.get("/summary", dependencies=reporting)
    def seat_summary(license_id: DocumentId, admin: RosterAdmin = Depends(tier.reader)):
        """Seat counts for the console's tiles: assigned, on hold, active in
        the last 7 days, invited, revocation pending, removed, and expiring
        within 30 days. Read from every seat and its holder's account (the
        reconciliation read), so it is one read per seat."""
        report = repo.reconcile_institution_seats(license_id)
        return {
            "licenseId": license_id,
            **roster_io.seat_summary(admin.license, report,
                                     repo.list_institution_invites(license_id),
                                     datetime.now(timezone.utc)),
        }

    @router.get("/audit", dependencies=reporting)
    def license_audit(
        license_id: DocumentId,
        format: Literal["json", "csv"] = "json",
        since: str = "",
        until: str = "",
        page_size: int = 100,
        page_token: PageToken = "",
        admin: RosterAdmin = Depends(tier.reader),
    ):
        """This licence's audit log, newest first: who did what to the
        licence, its seats, its invites and its leases.

        `since` / `until` are ISO dates or instants (UTC unless they say
        otherwise); `until` as a bare date includes that day. Paged by
        `page_size` (1..5000, default 100) and `page_token`. `format=csv` is
        the same page as a download, with the next page's token in
        `X-Next-Page-Token`, and is audited (`…_AUDIT_EXPORT`).

        Scoped by the licence the caller reached, so another institution's
        events are never in it, and a page token naming one restarts the log.
        """
        try:
            start, end = roster_io.audit_range(since, until)
        except roster_io.RosterRefused as e:
            raise HTTPException(400, e.code) from e
        size = clamp_page_size(page_size, repo.MAX_AUDIT_PAGE)
        events, next_token = repo.page_license_audit(
            license_id, limit=size, page_token=page_token or None, since=start, until=end,
        )
        if format == "json":
            return {"licenseId": license_id, "events": events,
                    "page": page_block(size, len(events), next_token)}
        audit.record(
            admin.user["uid"], action=f"{tier.audit}_AUDIT_EXPORT",
            target={"type": "license", "id": license_id},
            detail={"count": len(events), "since": since, "until": until},
        )
        return _csv(roster_io.audit_csv(events), _attachment("audit", license_id),
                    {"X-Next-Page-Token": next_token} if next_token else None)

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
            # The caller, so IT's clear keeps to the domain IT's devices
            # route does (ADR-033: each scope checked in the repository).
            cleared = repo.clear_device_lock(license_id, uid, actor=tier.actor,
                                             caller=admin.user)
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
        if not repo.revoke_institution_seat(license_id, uid, actor=tier.actor):
            raise HTTPException(404, errors.SEAT_NOT_FOUND)
        audit.record(
            admin.user["uid"], action=f"{tier.audit}_SEAT_REVOKE",
            target={"type": "seat", "id": f"{license_id}/{uid}"},
        )
        return {"licenseId": license_id, "uid": uid, "revoked": True}

    return router
