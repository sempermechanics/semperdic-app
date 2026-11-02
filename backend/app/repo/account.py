"""Per-account records: the Drive user folder, terms and consent, and erasing everything.
"""
import logging

from .. import apps
from .. import observability as obs
from ..licenses import (
    MODE_DEMO,
)

from . import _base
from ._base import (
    ACTOR_STAFF,
    SCHEMA_VERSION,
    _is_institution,
    _is_revoked,
    db,
    _delete_query_until_empty,
    get_license,
    _license_mode,
    _now,
    _get,
    _stream,
    _update_doc,
    _update_refs,
    _delete_doc,
    _seat_ref,
)
from .deactivations import deactivate_holder
from .invites import (
    _write_invite,
)
from .seats import (
    revoke_institution_seat,
)


log = logging.getLogger("semper.firestore")


def remember_user_folder(uid: str, folder_id: str, sessions_folder_id: str | None = None) -> None:
    """Persist the user's Drive subtree id so erasure never has to guess by name.

    `sessions_folder_id` (the user's session/ folder) lets the next upload skip
    the four-level name walk in drive.ensure_session_folders.
    """
    fields = {"driveFolderId": folder_id}
    if sessions_folder_id:
        fields["driveSessionsFolderId"] = sessions_folder_id
    _update_doc(db().collection("users").document(uid), fields)


def get_user(uid: str):
    snap = _get(db().collection("users").document(uid))
    return {**snap.to_dict(), "uid": uid} if snap.exists else None


def record_terms_acceptance(uid: str, version: str, device_id: str | None, source: str) -> dict:
    """Store the clickwrap record: which Terms version this account agreed to.

    Overwrites the previous record — only the latest acceptance matters for the
    gate, and the audit trail keeps the history. Returns the stored record with
    a client-usable timestamp (the SERVER_TIMESTAMP sentinel is not JSON).
    """
    record = {
        "version": version,
        "acceptedAt": _base.firestore.SERVER_TIMESTAMP,
        "deviceId": device_id,
        "source": source,
    }
    _update_doc(db().collection("users").document(uid), {
        "termsAccepted": record, "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    })
    return {**record, "acceptedAt": _now().isoformat()}


def record_improvement_consent(uid: str, granted: bool, version: str, device_id: str | None,
                               source: str) -> dict:
    """Store the separate product-improvement consent (never bundled into the Terms).

    `version` is the Terms/Privacy version the choice was made against, so a
    later policy change can tell an old "yes" from a fresh one.
    """
    record = {
        "granted": bool(granted),
        "version": version,
        "at": _base.firestore.SERVER_TIMESTAMP,
        "deviceId": device_id,
        "source": source,
    }
    _update_doc(db().collection("users").document(uid), {
        "improvementConsent": record, "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    })
    return {**record, "at": _now().isoformat()}


def list_user_devices(uid: str) -> list:
    out = []
    for d in _stream(db().collection("devices").where("uid", "==", uid)):
        v = d.to_dict()
        out.append({
            "deviceId": d.id,
            "status": v.get("status"),
            "model": v.get("model"),
            "osVersion": v.get("osVersion"),
            "appVersion": v.get("appVersion"),
            "registeredAt": str(v.get("registeredAt")),
        })
    return out


def _give_back_license(uid: str) -> None:
    """Return what this account held on a licence, before the account goes.

    Erasing the user document used to leave its hold behind: an institution
    seat went on counting against `seatsUsed` for somebody who no longer
    exists, and an individual licence stayed `redeemed` by a deleted uid, so
    the person could not get it back by signing up again.

    A seat is revoked the usual way, which frees the slot and any lease. An
    individual licence goes back to unused, unbound, and re-promised to its
    address, so a fresh account at that address is licensed on its first
    request, as the original one was. Demo keys are left alone: they are
    minted per account and hold nothing anyone else is waiting for.
    """
    snap = _get(db().collection("users").document(uid))
    if not snap.exists:
        return
    user = snap.to_dict() or {}
    # One licence per app (ADR-033); a licence from before the split sits in
    # Semper's fields alone.
    for license_id in dict.fromkeys(apps.read_field(user, "licenseId", a) or "" for a in apps.ALL):
        if license_id:
            _give_back_one(uid, license_id)


def _give_back_one(uid: str, license_id: str) -> None:
    """`_give_back_license` for one licence the account held."""
    lic = get_license(license_id)
    if not lic or _is_revoked(lic):
        return
    if _is_institution(lic):
        revoke_institution_seat(license_id, uid)
        return
    if (lic.get("redeemedByUid") or "") != uid or _license_mode(lic) == MODE_DEMO:
        return
    # Its phones go with the account: the licence is free for a new one.
    deactivate_holder(license_id, uid, actor=ACTOR_STAFF)
    _update_doc(db().collection("licenses").document(license_id), {
        "status": "unused",
        "redeemedByUid": _base.firestore.DELETE_FIELD,
        "redeemedAt": _base.firestore.DELETE_FIELD,
        **apps.spread("deviceIdLock", {}),
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    })
    email = lic.get("emailLock") or ""
    if email:
        err, _ = _write_invite(license_id, email, "system", app=apps.licence_slot(lic))
        if err:
            # The licence is free and its key still redeems it; only the
            # automatic delivery is missing, as a mint that hits this says.
            log.warning("re-invite after account delete failed license=%s err=%s",
                        license_id, err)


def _seats_of(uid: str, license_id: str) -> tuple[list, bool]:
    """Every seat document held under `uid`, live or in a deleted licence's
    hold, and whether the search reached them all.

    A seat is keyed by its holder's uid under its licence
    (`licenses/{id}/seats/{uid}`, `deleted_licenses/{id}/deleted_seats/{uid}`),
    and an account may have held seats on licences it has since left, so it is
    found by a collection-group query on `uid`. That query needs the
    collection-group single-field index `firestore.indexes.json` declares; a
    database without it refuses the query (FAILED_PRECONDITION), which is
    logged and answered with the current licence's seat alone, so an erasure
    never fails half way for it.
    """
    found = {}
    complete = True
    try:
        # Each group named by a literal at its own call: the rules-test guard
        # (firestore-rules-tests/scan.mjs) and the index-shape test
        # (tests/test_firestore_query_indexes.py) read collection names from
        # the source. "deleted_seats" is `deletion.DELETED_SEATS`.
        groups = (db().collection_group("seats"), db().collection_group("deleted_seats"))
        for group in groups:
            for snap in _stream(group.where("uid", "==", uid)):
                found[snap.reference.path] = snap
    except Exception as e:  # noqa: BLE001 - best effort; the erasure goes on
        complete = False
        obs.log_event(log, logging.ERROR, "erasure_seat_scan_failed", uid=uid,
                      outcome="error", errorCode=type(e).__name__, dependency="firestore")
    if license_id:
        snap = _get(_seat_ref(license_id, uid))
        if snap.exists:
            found.setdefault(snap.reference.path, snap)
    return list(found.values()), complete


def _blank_seat_addresses(uid: str, license_id: str) -> tuple[int, bool]:
    """Blank the address on every seat `uid` held, keeping the seat (TD-243).

    The seat stays: it is what `seatsUsed`, reconciliation and the licence's
    audit export count, and its uid is opaque. A live seat still on the
    roster is revoked the usual way first, which frees its slot and lease, so
    the counts read the same as after any other revoke. `_give_back_license`
    has already done that for the current licence; this finds the rest.
    Returns (seats blanked, whether every seat was searched).
    """
    seats, complete = _seats_of(uid, license_id)
    for snap in seats:
        coll = snap.reference.parent
        if coll.id == "seats" and not _is_revoked(snap.to_dict() or {}):
            revoke_institution_seat(coll.parent.id, uid)
    addressed = [snap.reference for snap in seats if (snap.to_dict() or {}).get("email")]
    _update_refs([(ref, {"email": "", "schemaVersion": SCHEMA_VERSION}) for ref in addressed])
    return len(addressed), complete


def delete_all_user_data(uid: str) -> dict:
    """Erase every Firestore record belonging to a user (GDPR account deletion).

    Sessions + their file docs, the recycle bin's entries (`binnedSessions`,
    ADR-035), the device registrations, the sign-in links
    (`auth_links`, one per Firebase sub that resolves to this account) and the
    user profile itself. Audit records are intentionally kept: they hold no
    analysis content and no address, only the fact that actions (including
    this erasure) occurred. A licence seat or key the account held is given
    back first — see `_give_back_license` — and every seat it held keeps its
    place in the counts but loses its address (`_blank_seat_addresses`).

    `seatScanComplete` is False when the collection-group search for seats
    failed (its index missing); the current licence's seat was still blanked.
    """
    # File docs carry the uid, so the whole account is one query rather than one
    # per session. Deleting session by session meant a query and a batch commit
    # each — sequential round-trips that made erasing a busy account crawl.
    files = _delete_query_until_empty(
        db().collection("files").where("uid", "==", uid)
    )
    sessions = _delete_query_until_empty(
        db().collection("sessions").where("uid", "==", uid)
    )
    # The recycle bin (ADR-035): binned entries and erased markers alike. Their
    # file docs went with the account's files above; their Drive folders went
    # with the user folder, or one by one in the erasure's fallback.
    binned = _delete_query_until_empty(
        db().collection("deleted_sessions").where("uid", "==", uid)
    )
    devices = _delete_query_until_empty(
        db().collection("devices").where("uid", "==", uid)
    )
    snap = _get(db().collection("users").document(uid))
    license_id = ((snap.to_dict() or {}).get("licenseId") or "") if snap.exists else ""
    _give_back_license(uid)
    seats, seat_scan_complete = _blank_seat_addresses(uid, license_id)
    # Every sub linked to this account points at it, its own sub included.
    auth_links = _delete_query_until_empty(
        db().collection("auth_links").where("uid", "==", uid)
    )
    _delete_doc(db().collection("users").document(uid))
    return {"sessions": sessions, "binnedSessions": binned, "files": files, "devices": devices,
            "authLinks": auth_links, "seatsBlanked": seats,
            "seatScanComplete": seat_scan_complete}
