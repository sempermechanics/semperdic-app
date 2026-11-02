"""Deleting a licence, and holding it for 30 days in case that was a mistake.

Revoke keeps the licence as the audit record, so the desk fills with them. A
delete takes the licence and its seats out of `licenses` altogether and
copies them to `deleted_licenses/{id}` (seats under `deleted_seats`), each
stamped with `purgeAt` thirty days on. A Firestore TTL policy on `purgeAt`
removes the copy after that (docs/backend/BACKEND_SETUP_CONSOLE.md); until
then the desk lists it and can restore it. The audit log row
(`ADMIN_LICENSE_DELETE`) outlives the purge.

Deleting is revoking first: holders drop to Demo exactly as a revoke drops
them, and their sessions and files are untouched. Their account then stops
pointing at a licence that no longer exists, so the next request gives it a
Demo key of its own, as for a new account. Restoring puts each holder back
unless they have taken another licence since — one licence per person holds
across a restore too.

Nothing is removed until the copy is written. System Demo keys are not
deleted here: there is one per account, and the account would only be
issued another.

A licence's phones (`licenses/{id}/activations`, ADR-033) stay where they
are through the hold, as a revoke leaves them: nothing reads them while the
licence is gone. A restore keeps the phones of the holders it puts back and
deactivates the rest, so a seat or a licence that comes back without its
holder frees its phone slots (`_settle_activations`).
"""
from datetime import timedelta

from .. import apps, errors
from ..errors import Refusal
from ..licenses import (
    MODE_DEMO,
    STATUS_REVOKED,
    as_utc,
    normalize_email,
)

from . import _base
from ._base import (
    ACTOR_STAFF,
    SCHEMA_VERSION,
    _is_institution,
    _is_revoked,
    _BATCH_LIMIT,
    db,
    _delete_refs,
    _license_mode,
    _load_user,
    _now,
    _update_refs,
    _get,
    _stream,
    _commit,
    _cursor_page,
    _set_doc,
    _delete_doc,
)
from .activations import (
    ACTIVATIONS,
    ACTIVE,
    DEACTIVATED,
    runs_activations,
)
from .claims import (
    _drop_superseded_demos,
    _member_patch,
)
from .holders import (
    licence_held_by,
)
from .invites import (
    _write_invite,
)
from .mint import (
    _license_public,
)
from .license_admin import (
    get_license_public,
    revoke_license,
    _demo_drop_patch,
    _users_on_license,
)


DELETED = "deleted_licenses"
DELETED_SEATS = "deleted_seats"
#: How long a deleted licence can be restored. The TTL policy on `purgeAt`
#: removes it some time after this — Firestore deletes expired documents
#: within about a day, not at the instant — so restore also checks the date.
HOLD_DAYS = 30
#: What a tombstone adds to the licence it holds; stripped on restore.
_TOMBSTONE_FIELDS = ("priorStatus", "deletedAt", "deletedByUid", "purgeAt")
_LEASE_FIELDS = ("leaseExpiresAt", "leaseDeviceId", "lastHeartbeatAt")
#: The licence a user document carries: the pointer and the mirrored terms.
_USER_LICENCE_FIELDS = (
    "licenseId", "licenseKind", "licensePrefix", "licenseExpiresAt", "licenseGraceDays",
    "licenseDuration", "licenseSeating", "licenseMaxAnalyses",
)


def _set_refs(writes: list) -> None:
    """Applies every `(reference, data)` as a set, chunked like `_update_refs`."""
    for start in range(0, len(writes), _BATCH_LIMIT):
        batch = db().batch()
        for ref, data in writes[start:start + _BATCH_LIMIT]:
            batch.set(ref, data)
        _commit(batch)


def _settle_activations(ref, kept: set[str], admin_uid: str) -> dict[str, int]:
    """Deactivate the active phones on the licence at `ref` whose holder is
    not in `kept`, and answer `{uid: phones still active}` for the rest.

    For a restore: a seat that comes back revoked, or an individual licence
    that comes back unused, has no holder for its phones, and they held the
    pool's or the licence's slots against `maxDevices` (review L2-2). Only
    the activation is marked, as staff (`ACTOR_STAFF`, the admin who
    restored): the phone's registration is not released, because its owner
    has moved on to another licence and may use it there."""
    phones: dict[str, int] = {}
    gone = []
    now = _now()
    for snap in _stream(ref.collection(ACTIVATIONS).where("status", "==", ACTIVE)):
        uid = (snap.to_dict() or {}).get("uid") or ""
        if uid and uid in kept:
            phones[uid] = phones.get(uid, 0) + 1
        else:
            gone.append((snap.reference, {"status": DEACTIVATED, "deactivatedAt": now,
                                          "deactivatedBy": ACTOR_STAFF,
                                          "deactivatedByUid": admin_uid}))
    _update_refs(gone)
    return phones


def _deleted_public(license_id: str, tomb: dict) -> dict:
    return {
        **_license_public(license_id, tomb),
        "priorStatus": tomb.get("priorStatus") or "",
        "deletedAt": tomb.get("deletedAt"),
        "deletedByUid": tomb.get("deletedByUid") or "",
        "purgeAt": tomb.get("purgeAt"),
    }


def delete_license(license_id: str, admin_uid: str) -> dict:
    """Delete a licence into the 30-day hold. Returns (error, deleted row)."""
    ref = db().collection("licenses").document(license_id)
    snap = _get(ref)
    if not snap.exists:
        raise Refusal(errors.LICENSE_NOT_FOUND)
    lic = snap.to_dict() or {}
    if _license_mode(lic) == MODE_DEMO and (lic.get("createdByUid") or "") == "system":
        raise Refusal(errors.DEMO_KEY_NOT_DELETABLE)
    seats = _stream(ref.collection("seats"))
    purge_at = _now() + timedelta(days=HOLD_DAYS)

    # The copy first, as the licence stood before the revoke below, so a
    # restore can put back what was live.
    tomb_ref = db().collection(DELETED).document(license_id)
    tomb = {
        **lic,
        "priorStatus": lic.get("status") or "",
        "deletedAt": _base.firestore.SERVER_TIMESTAMP,
        "deletedByUid": admin_uid,
        "purgeAt": purge_at,
    }
    _set_doc(tomb_ref, tomb)
    _set_refs([
        (tomb_ref.collection(DELETED_SEATS).document(s.id), {**(s.to_dict() or {}), "purgeAt": purge_at})
        for s in seats
    ])

    revoke_license(license_id, admin_uid)
    holders = [lic.get("redeemedByUid") or ""] + [(s.to_dict() or {}).get("uid") or s.id for s in seats]
    detach = {**_demo_drop_patch(), **{f: _base.firestore.DELETE_FIELD for f in _USER_LICENCE_FIELDS}}
    _update_refs([(user_ref, apps.licence_patch(detach, slot))
                  for user_ref, slot in _users_on_license(holders, license_id)])

    _delete_refs([s.reference for s in seats])
    _delete_doc(ref)
    return _deleted_public(license_id, {**tomb, "deletedAt": _now()})


def list_deleted_licenses(limit: int = 50,
                          page_token: str | None = None) -> tuple[list[dict], str | None]:
    """Licences deleted and still held, most recently deleted first, a page at
    a time. Returns (page, next_page_token_or_None)."""
    col = db().collection(DELETED)
    docs, next_token = _cursor_page(col, col, limit, page_token,
                                    order_field="deletedAt", descending=True)
    return [_deleted_public(d.id, d.to_dict() or {}) for d in docs], next_token


def restore_license(license_id: str, admin_uid: str) -> dict:
    """Bring a deleted licence back within its hold. Returns (error, licence).

    A licence that was live comes back live, with each holder re-attached
    unless they hold another live licence now; a seat whose holder has moved
    on comes back revoked, and an individual licence whose holder has moved
    on comes back unused. Leases do not come back: a floating member checks
    out again. A licence that was already revoked comes back revoked.

    Phones (ADR-033): a holder put back keeps the phones they had active, a
    seat on hold keeps its member's as a hold does, and every other phone is
    deactivated; `devicesActive` is then counted from what is left, on the
    licence and on each seat (`_settle_activations`).
    """
    tomb_ref = db().collection(DELETED).document(license_id)
    snap = _get(tomb_ref)
    if not snap.exists:
        raise Refusal(errors.DELETED_LICENSE_NOT_FOUND)
    tomb = snap.to_dict() or {}
    purge_at = as_utc(tomb.get("purgeAt"))
    if purge_at is not None and purge_at <= _now():
        raise Refusal(errors.DELETED_LICENSE_PURGED)
    ref = db().collection("licenses").document(license_id)
    if _get(ref).exists:
        raise Refusal(errors.LICENSE_EXISTS)

    lic = {k: v for k, v in tomb.items() if k not in _TOMBSTONE_FIELDS}
    live = (tomb.get("priorStatus") or "") != STATUS_REVOKED
    lic["status"] = tomb.get("priorStatus") or lic.get("status") or ""
    lic["restoredAt"] = _base.firestore.SERVER_TIMESTAMP
    lic["restoredByUid"] = admin_uid
    institution = _is_institution(lic)
    seat_docs = _stream(tomb_ref.collection(DELETED_SEATS))

    back: list[str] = []
    #: Holders whose phones stay activated: everyone the licence keeps.
    kept: set[str] = set()
    seat_writes = []

    def _free(uid: str) -> bool:
        user = _load_user(uid) if uid else None
        return bool(user) and not licence_held_by(user.get("email") or "", user=user,
                                                  app=lic.get("app"), exclude_id=license_id)

    if institution:
        on_roster = 0
        for doc in seat_docs:
            seat = {k: v for k, v in (doc.to_dict() or {}).items()
                    if k != "purgeAt" and k not in _LEASE_FIELDS}
            uid = seat.get("uid") or doc.id
            if live and not _is_revoked(seat):
                if _free(uid):
                    on_roster += 1
                    kept.add(uid)
                    if seat.get("status") != "disabled":
                        back.append(uid)
                else:
                    seat.update({"status": STATUS_REVOKED, "revokedAt": _now()})
            seat_writes.append((ref.collection("seats").document(doc.id),
                                {**seat, "schemaVersion": SCHEMA_VERSION}))
        lic["seatsUsed"] = on_roster
        lic["leasesActive"] = 0
    elif live:
        redeemer = lic.get("redeemedByUid") or ""
        if redeemer and _free(redeemer):
            back.append(redeemer)
            kept.add(redeemer)
        elif redeemer:
            lic["status"] = "unused"
            lic.pop("redeemedByUid", None)
            lic.pop("redeemedAt", None)
            # The lock (or, on a licence that runs activations, its mirror)
            # named the phone of a holder who has gone.
            lic.update(apps.spread("deviceIdLock", {}))

    if live and runs_activations(lic):
        phones = _settle_activations(ref, kept, admin_uid)
        lic["devicesActive"] = sum(phones.values())
        for seat_ref, seat in seat_writes:
            uid = seat.get("uid") or seat_ref.id
            seat["devicesActive"] = phones.get(uid, 0)
            if uid not in kept:
                seat.update(apps.spread("deviceIdLock", {}))

    _set_doc(ref, lic)
    _set_refs(seat_writes)
    patch = _member_patch(license_id, lic)
    users = db().collection("users")
    _update_refs([(users.document(uid), patch) for uid in back])
    # The Demo keys the accounts were given while this licence was gone.
    _drop_superseded_demos(back, license_id)
    address = normalize_email(lic.get("emailLock"))
    if live and not institution and lic["status"] == "unused" and address \
            and not licence_held_by(address, app=lic.get("app"), exclude_id=license_id):
        # Still waiting for its first sign-in: promise it to the address again.
        _write_invite(license_id, address, admin_uid, app=apps.licence_slot(lic))

    _delete_refs([d.reference for d in seat_docs])
    _delete_doc(tomb_ref)
    return get_license_public(license_id)
