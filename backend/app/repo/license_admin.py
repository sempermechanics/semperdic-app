"""Licence administration by Semper staff: listing, renewal fan-out, whole-key revoke.
"""
import logging
import uuid
from concurrent.futures import ThreadPoolExecutor

from .. import apps, errors, tasks
from .. import observability as obs
from ..errors import Refusal
from ..config import settings
from ..licenses import (
    DURATION_PERPETUAL,
    DURATION_TIMED,
    LIVE_STATUSES,
    MODE_DEMO,
    MODE_LICENSED,
    SEATING_FLOATING,
    STATUS_REVOKED,
    as_utc,
    key_prefix,
    normalize_email,
    normalize_seating,
    seat_cap_below_roster,
)

from . import _base
from ._base import (
    SCHEMA_VERSION,
    _apply_patch,
    _is_institution,
    _is_revoked,
    _cursor_page,
    _run_tx,
    db,
    _get_all,
    _lease_clear_patch,
    _license_mode,
    _mode_patch,
    _now,
    _seat_lease_counted,
    _update_refs,
    _get,
    _stream,
    _update_doc,
)
from .claims import (
    _license_mirror_patch,
)
from .invites import (
    _delete_license_invites,
)
from .mint import (
    _license_public,
)


log = logging.getLogger("semper.licences")

#: Most licences a search answers with. A search is an exact match on an
#: address, a domain or a key prefix, so more than this is not a search.
SEARCH_LIMIT = 50


def list_licenses(limit: int = 50, page_token: str | None = None, *,
                  include_demo: bool = False, include_revoked: bool = True,
                  q: str = "", include_test: bool = False) -> tuple[list, str | None]:
    """The staff desk's licence table, newest first.

    It used to be the whole collection in document-id order. The id is the
    key's hash, so the order was random, and every account's system Demo key
    was in it: the licences anyone sold were spread thinly across page after
    page, and the desk hid Demo rows only after fetching them. Demo keys and
    revoked licences are now left out by the query, not the browser.

    `q` is a search instead of a page: licences whose `emailLock` or
    `domainLock` is `q`, or whose key starts with it, with the same filters.

    A staff test account's licence (`testAccount`, ADR-027) is left out
    unless `include_test`. Filtered from the page rather than by the query:
    Firestore cannot ask for "no such field" without dropping every licence
    that lacks it, which is every real one. A page may then hold fewer rows
    than `limit` with more to come; `nextPageToken` still says so.
    """
    col = db().collection("licenses")
    if q:
        rows = _search_licenses(col, q, include_demo, include_revoked)
        return _without_test(rows, include_test), None
    query = col
    if not include_demo:
        query = query.where("mode", "==", MODE_LICENSED)
    if not include_revoked:
        query = query.where("status", "in", list(LIVE_STATUSES))
    docs, next_token = _cursor_page(col, query, limit, page_token,
                                    order_field="createdAt", descending=True)
    rows = [_license_public(d.id, d.to_dict() or {}) for d in docs]
    return _without_test(rows, include_test), next_token


def _without_test(rows: list, include_test: bool) -> list:
    """`rows` without test-account licences, unless they are asked for."""
    return rows if include_test else [r for r in rows if not r["testAccount"]]


def _search_licenses(col, q: str, include_demo: bool, include_revoked: bool) -> list:
    """Exact-match lookups on the fields support is asked about.

    Single-field equality queries, so no composite index; the filters and the
    order are applied here, over at most `3 * SEARCH_LIMIT` rows. The lookups
    run at once, each on its own thread (TD-198): the search costs the
    slowest one's round trip, not the sum. A failed lookup fails the search.
    """
    text = q.strip()
    lookups = [("emailLock", normalize_email(text)), ("domainLock", text.lower())]
    if text.upper().startswith("SEMP"):
        lookups.append(("keyPrefix", key_prefix(text)))
    queries = [col.where(field, "==", value).limit(SEARCH_LIMIT) for field, value in lookups if value]
    with ThreadPoolExecutor(max_workers=max(1, len(queries)),
                            thread_name_prefix="licence-search") as pool:
        # map keeps the lookups' order, so the merge below is the serial one.
        results = list(pool.map(_stream, queries))
    found = {}
    for docs in results:
        for doc in docs:
            found[doc.id] = doc.to_dict() or {}
    rows = [
        (doc_id, lic) for doc_id, lic in found.items()
        if (include_demo or _license_mode(lic) != MODE_DEMO)
        and (include_revoked or not _is_revoked(lic))
    ]
    # Newest first, like the table; a licence with no `createdAt` goes last.
    rows.sort(key=lambda row: str(row[1].get("createdAt") or ""), reverse=True)
    return [_license_public(doc_id, lic) for doc_id, lic in rows[:SEARCH_LIMIT]]


def get_license_public(license_id: str) -> dict | None:
    """One licence as the desk shows it, for refreshing a single row."""
    snap = _get(db().collection("licenses").document(license_id))
    return _license_public(license_id, snap.to_dict() or {}) if snap.exists else None


def revoke_license(license_id: str, admin_uid: str) -> dict | None:
    """Whole-key revoke. Every redeemer (individual redeemer, or every
    institution seat holder) drops to Demo and every occupied seat is freed.

    Leases go with the seats. They used to survive the revoke: each seat kept
    its `leaseExpiresAt`, the licence kept its `leasesActive`, and the console
    showed a revoked pool with seats in use until every lease ran out.

    The licence is written `revoked` together with a pending fan-out
    (ADR-026), and a Cloud Task scheduled just before that write finishes the
    fan-out if this call dies part-way. Running it again is still a repair:
    the holders are re-checked, but a seat or licence already revoked keeps
    the `revokedAt` it had. Re-stamping it moved IT's earlier seat revokes to
    now, and reconciliation dates a revoke from that field. Holders are read
    in one call and written in batches, not one round trip each."""
    ref = db().collection("licenses").document(license_id)
    snap = _get(ref)
    if not snap.exists:
        return None
    lic = snap.to_dict() or {}
    patch = {} if _is_revoked(lic) else {
        "status": STATUS_REVOKED,
        "revokedAt": _base.firestore.SERVER_TIMESTAMP,
        "revokedByUid": admin_uid,
    }
    _write_with_fanout(ref, license_id, patch, {FANOUT_REVOKE})
    _finish_fanout(ref, license_id)
    if _is_institution(lic):
        # Answer with the counts just written. The desk merges this reply
        # into its row, and the pre-revoke snapshot showed a revoked licence
        # with every seat still taken until the list reloaded.
        lic = {**lic, "seatsUsed": 0, "leasesActive": 0}
    return _license_public(license_id, {**lic, "status": STATUS_REVOKED})


def _revoke_fanout(ref, license_id: str, lic: dict) -> None:
    """Everything a whole-licence revoke writes besides the licence's status.
    Idempotent: a seat already revoked is left as it is."""
    if _is_institution(lic):
        seats = _stream(ref.collection("seats"))
        # Every seat's holder, revoked seats included: a seat revoke whose
        # demotion never landed (reconcile's `still_licensed`) is repaired here.
        _drop_users_to_demo_if_licensed(
            [(s.to_dict() or {}).get("uid") or s.id for s in seats], license_id,
        )
        seat_patch = {
            "status": STATUS_REVOKED,
            "revokedAt": _base.firestore.SERVER_TIMESTAMP,
            **_lease_clear_patch(),
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            "schemaVersion": SCHEMA_VERSION,
        }
        _update_refs([
            (s.reference, seat_patch) for s in seats
            if (s.to_dict() or {}).get("status") != STATUS_REVOKED
        ])
        _update_doc(ref, {"seatsUsed": 0, "leasesActive": 0})
        _delete_license_invites(license_id)
    else:
        redeemer = lic.get("redeemedByUid")
        if redeemer and _license_mode(lic) == MODE_LICENSED:
            _drop_user_to_demo_if_licensed(redeemer, license_id)
        _delete_license_invites(license_id, lic.get("emailLock") or "",
                                app=apps.licence_slot(lic))
    if lic.get("supersedes"):
        _withdraw_pending_upgrade(lic["supersedes"], license_id)
    pending = lic.get("upgradePendingId") or ""
    if pending and not lic.get("supersededBy"):
        _cancel_pending_upgrade(license_id, pending, lic.get("revokedByUid") or "")


def _cancel_pending_upgrade(old_id: str, new_id: str, actor_uid: str) -> None:
    """A licence with an upgrade waiting is revoked (ADR-033): the upgrade
    goes with it, or the holder's Activate would license them again on the
    successor. An apply writes `supersededBy` before its revoke, so this
    never runs for the upgrade being applied.

    An individual successor nobody has claimed is revoked too: it was minted
    for this holder only, as this licence's replacement, and left live it
    would be a working key locked to the address the revoke meant to stop.
    Its own revoke withdraws both pointers. An institution successor has its
    own administrators, and one already claimed has a holder, so either only
    stops being offered; staff revoke it from the desk if that is meant."""
    new_snap = _get(db().collection("licenses").document(new_id))
    new = (new_snap.to_dict() or {}) if new_snap.exists else {}
    unclaimed = bool(new) and not _is_revoked(new) and not _is_institution(new) \
        and not new.get("redeemedByUid")
    obs.log_event(log, logging.INFO, "upgrade_withdrawn", licenseId=old_id,
                  outcome="revoked" if unclaimed else "withdrawn")
    if unclaimed:
        revoke_license(new_id, actor_uid)
    else:
        _withdraw_pending_upgrade(old_id, new_id)


def _withdraw_pending_upgrade(old_id: str, new_id: str) -> None:
    """A licence issued as an upgrade is revoked (ADR-033): its holder stops
    being offered it, and the old licence may be upgraded again. Nothing
    changes once the upgrade was applied, since both pointers are gone."""
    old_ref = db().collection("licenses").document(old_id)
    old_snap = _get(old_ref)
    old = (old_snap.to_dict() or {}) if old_snap.exists else {}
    if old.get("upgradePendingId") == new_id:
        _update_doc(old_ref, {"upgradePendingId": _base.firestore.DELETE_FIELD})
    holder = old.get("redeemedByUid") or ""
    if not holder:
        return
    user_ref = db().collection("users").document(holder)
    snap = _get(user_ref)
    user = (snap.to_dict() or {}) if snap.exists else {}
    for app in apps.ALL:
        if apps.read_field(user, "pendingLicenseId", app) == new_id:
            _update_doc(user_ref, apps.written_fields(
                "pendingLicenseId", app, _base.firestore.DELETE_FIELD))


# ---------------- pending fan-outs (ADR-026, TD-294) ----------------

#: The licence fields that record a fan-out still to run: an id per action,
#: what is left to do, and when it was recorded.
FANOUT_ID, FANOUT_OPS, FANOUT_AT = "fanoutId", "fanoutOps", "fanoutAt"
#: Drop every holder to Demo and revoke every seat (`_revoke_fanout`).
FANOUT_REVOKE = "revoke"
#: Re-stamp every holder's copy of the terms (`_license_mirror_patch`).
FANOUT_TERMS = "terms"
#: Clear every lease, on the seats and the holders, while not floating.
FANOUT_LEASES = "leases"
#: How many times a fan-out overtaken by a newer action runs again.
FANOUT_PASSES = 3


def _write_with_fanout(ref, license_id: str, patch: dict, ops: set[str]) -> None:
    """Write `patch` to the licence with `ops` recorded as pending, in one
    write, after scheduling the task that finishes them.

    The task goes first: a crash after it and before the write leaves a task
    that finds nothing to do, while the reverse order could leave a marker no
    task will ever read. `ArrayUnion` keeps the ops an earlier action left
    pending, merged by Firestore in the same write."""
    fanout_id = uuid.uuid4().hex
    # False is logged by the enqueue. The fan-out still runs inline after
    # this; only a crash in this same call would then leave it to a repeat.
    tasks.enqueue_license_fanout(license_id, fanout_id)
    _update_doc(ref, {
        **patch,
        FANOUT_ID: fanout_id,
        FANOUT_OPS: _base.firestore.ArrayUnion(sorted(ops)),
        FANOUT_AT: _base.firestore.SERVER_TIMESTAMP,
    })


def _clear_fanout(ref, fanout_id: str | None) -> bool:
    """Clear the pending marker if it is still `fanout_id`. False when another
    action has written a newer one (or finished and cleared it) since."""
    @_base.firestore.transactional
    def _clear(tx) -> bool:
        snap = _get(ref, tx)
        if not snap.exists:
            return True
        if (snap.to_dict() or {}).get(FANOUT_ID) != fanout_id:
            return False
        if fanout_id is not None:
            tx.update(ref, {
                FANOUT_ID: _base.firestore.DELETE_FIELD,
                FANOUT_OPS: _base.firestore.DELETE_FIELD,
                FANOUT_AT: _base.firestore.DELETE_FIELD,
            })
        return True

    return _run_tx(_clear, on_contended=lambda: False)


def _run_ops(ref, license_id: str, lic: dict, ops) -> None:
    if FANOUT_REVOKE in ops:
        _revoke_fanout(ref, license_id, lic)
    clear_leases = (FANOUT_LEASES in ops and _is_institution(lic)
                    and normalize_seating(lic.get("seating")) != SEATING_FLOATING)
    if clear_leases:
        _clear_seat_leases(ref)
    if FANOUT_TERMS in ops or clear_leases:
        extra = ({"leaseExpiresAt": _base.firestore.DELETE_FIELD,
                  "leaseDeviceId": _base.firestore.DELETE_FIELD} if clear_leases else {})
        _refresh_license_mirrors(_license_holder_uids(ref, lic), license_id,
                                 {**_license_mirror_patch(lic), **extra})


def _finish_fanout(ref, license_id: str) -> None:
    """Run whatever fan-out the licence has pending, from the licence as it now
    stands, and clear the marker.

    A marker that changed while this ran means another action wrote the
    licence meanwhile, and the copies written here may be older than it: run
    again, the ops just run included, up to `FANOUT_PASSES` times. A marker
    still there after that belongs to the newer action, whose own run (or
    task) clears it."""
    ran: set[str] = set()
    for _ in range(FANOUT_PASSES):
        snap = _get(ref)
        if not snap.exists:
            return
        lic = snap.to_dict() or {}
        ops = set(lic.get(FANOUT_OPS) or ()) | ran
        _run_ops(ref, license_id, lic, ops)
        ran |= ops
        if _clear_fanout(ref, lic.get(FANOUT_ID)):
            return


def finish_license_fanout(license_id: str) -> list[str]:
    """The Cloud Task's half (`POST /v1/tasks/license-fanout`): finish a fan-out
    the action that recorded it did not. Returns the ops that were pending,
    [] when there were none (the ordinary case: the call finished its own)."""
    ref = db().collection("licenses").document(license_id)
    snap = _get(ref)
    pending = sorted((snap.to_dict() or {}).get(FANOUT_OPS) or ()) if snap.exists else []
    if not pending:
        return []
    obs.log_event(log, logging.WARNING, "license_fanout_finished_late",
                  outcome="repaired", ops=",".join(pending))
    _finish_fanout(ref, license_id)
    return pending


def expiry_change_error(lic: dict, expires_at, *, allow_shorten: bool = False) -> str:
    """Why this new `expiresAt` may not be applied to `lic`, or "".

    The edit route is the desk's Extend button, and every holder follows the
    licence the moment it is written. A date already past ended the licence
    for all of them; one earlier than the current expiry shortened it; and
    on a perpetual licence it turned an unending licence into a timed one
    with no grace, because perpetual licences are minted without
    `graceDays`. Each of those was then reported on the desk as "extended".
    Ending a licence now is `revoke`.

    `allow_shorten` is the desk saying, with a typed confirmation, that the
    shorter term or the end date on a perpetual licence is meant: a downgrade
    agreed with the customer. A date already past is refused even then.
    """
    new = as_utc(expires_at)
    if new is None:
        return ""
    if new <= _now():
        return errors.EXPIRY_IN_PAST
    if allow_shorten:
        return ""
    current = as_utc(lic.get("expiresAt"))
    if current is None:
        return errors.LICENSE_PERPETUAL
    if new < current:
        return errors.EXPIRY_BEFORE_CURRENT
    return ""


def analysis_cap_error(lic: dict) -> str:
    """Why a new `maxAnalyses` may not be set on `lic`, or "".

    A demo holder's allowance is `DEMO_MAX_ANALYSES` and nothing on the key
    moves it (`resolve_user_config` reads the cap only for a licensed
    account). A cap written to a demo key was stored, mirrored onto the
    holder and answered 200, and changed nothing the holder could see.
    Clearing one is still allowed: it only removes a number that never
    applied.
    """
    if _license_mode(lic) == MODE_DEMO:
        return errors.CAP_ON_DEMO_KEY
    return ""


_ROSTER_FIELDS = ("maxSeats", "seating", "adminEmails")


def license_edit_error(lic: dict, patch: dict) -> str:
    """Why this edit may not be applied to `lic`, or "". Every refusal an
    edit can meet, decided before anything is written.

    `patch` is the request as sent: `allowShorten` rides along with the
    fields it qualifies.
    """
    institution = _is_institution(lic)
    if not institution and any(patch.get(f) is not None for f in _ROSTER_FIELDS):
        return errors.INSTITUTION_ONLY
    if patch.get("maxAnalyses") is not None:
        err = analysis_cap_error(lic)
        if err:
            return err
    err = expiry_change_error(lic, patch.get("expiresAt"),
                              allow_shorten=bool(patch.get("allowShorten")))
    if err:
        return err
    if patch.get("maxDevices") is not None:
        # Checked against the count as read; an Activate landing in between
        # is held to the new count by its own transaction.
        if not lic.get("app"):
            return errors.MAX_DEVICES_NEEDS_APP
        if int(patch["maxDevices"]) < int(lic.get("devicesActive") or 0):
            return errors.MAX_DEVICES_BELOW_ACTIVE
    if institution and (patch.get("maxSeats") is not None or patch.get("seating")):
        after = {**lic, **{f: patch[f] for f in ("maxSeats", "seating") if patch.get(f) is not None}}
        if normalize_seating(after.get("seating")) == SEATING_FLOATING and after.get("maxSeats") is None:
            return errors.FLOATING_NEEDS_MAX_SEATS
        # Switching to assigned makes `maxSeats` cap the roster, so it has to
        # hold everyone already on it — the same rule as lowering it.
        if seat_cap_below_roster(after, after.get("maxSeats")):
            return errors.MAX_SEATS_BELOW_USED
    return ""


def update_license(license_id: str, patch: dict, admin_uid: str) -> dict | None:
    """Change a license's terms and push them to everyone already holding it.

    Renewal is the reason this exists. `_license_mirror_patch` snapshots the
    terms onto each user at activation so the hot read path needs no Firestore
    lookup; the cost is that editing the license alone reaches nobody. So this
    writes the document and then fans the new mirror out — to the individual
    redeemer, or to every seat on an institution license.

    The fan-out is bounded by `seatsUsed` and renewal is rare, which is what
    makes this the right side of the trade against a per-request read. Revoked
    seats are skipped: they hold no entitlement to refresh, and touching them
    would quietly resurrect a revoked member on the next resolve.

    `perpetual` drops the expiry and grace. An expiry on a perpetual licence
    (with `allowShorten`) makes it timed, with the fleet default grace unless
    one is sent. A seating switch zeroes `leasesActive`; leaving floating also
    clears every lease, on the seats and on the holders, since an assigned
    seat needs none.

    Returns the updated public license, or None if there is no such license.
    Raises `Refusal`, before writing anything, for anything
    `license_edit_error` refuses.
    """
    ref = db().collection("licenses").document(license_id)
    snap = _get(ref)
    if not snap.exists:
        return None
    lic = snap.to_dict() or {}

    update = {k: v for k, v in patch.items() if v is not None}
    err = license_edit_error(lic, update)
    if err:
        raise Refusal(err)
    allow_shorten = update.pop("allowShorten", False)
    clear_cap = bool(update.pop("clearMaxAnalyses", False))
    if clear_cap:
        update["maxAnalyses"] = _base.firestore.DELETE_FIELD
    if update.pop("perpetual", False):
        update.update({
            "expiresAt": _base.firestore.DELETE_FIELD,
            "graceDays": _base.firestore.DELETE_FIELD,
            "duration": DURATION_PERPETUAL,
        })
    elif "expiresAt" in update:
        update["duration"] = DURATION_TIMED
        if allow_shorten and lic.get("graceDays") is None and "graceDays" not in update:
            # A perpetual licence was minted without a grace; one given an
            # end date gets the fleet default, as a timed mint does.
            update["graceDays"] = settings.LICENSE_GRACE_DAYS_DEFAULT
    old_seating = normalize_seating(lic.get("seating"))
    if "seating" in update:
        update["seating"] = normalize_seating(update["seating"])
        if update["seating"] == old_seating:
            del update["seating"]
        else:
            update["leasesActive"] = 0
    if not update:
        return _license_public(license_id, lic)
    if "graceDays" in update and update["graceDays"] is not _base.firestore.DELETE_FIELD:
        update["graceDays"] = max(0, int(update["graceDays"]))
    update["updatedAt"] = _base.firestore.SERVER_TIMESTAMP
    update["updatedByUid"] = admin_uid

    # The sentinels are for Firestore; the mirror and the response read the
    # licence as it now stands.
    merged = _apply_patch(lic, update)
    ops = set()
    if old_seating == SEATING_FLOATING and "seating" in update:
        ops.add(FANOUT_LEASES)
    # Only the terms mirrored onto holders need to reach them. A note, a
    # support date or a seat cap changes nothing on any user document, and
    # used to rewrite every one of them anyway.
    if _license_mirror_patch(merged) != _license_mirror_patch(lic):
        ops.add(FANOUT_TERMS)
    if not ops:
        _update_doc(ref, update)
        return _license_public(license_id, merged)
    # Recorded with the terms, finished here, and by the task if this dies
    # part-way (ADR-026).
    _write_with_fanout(ref, license_id, update, ops)
    _finish_fanout(ref, license_id)
    return _license_public(license_id, merged)


def _clear_seat_leases(ref) -> None:
    """Drop every lease on a licence that no longer uses them."""
    _update_refs([
        (doc.reference, {**_lease_clear_patch(), "updatedAt": _base.firestore.SERVER_TIMESTAMP,
                         "schemaVersion": SCHEMA_VERSION})
        for doc in _stream(ref.collection("seats"))
        if _seat_lease_counted(doc.to_dict() or {})
    ])


def _license_holder_uids(ref, lic: dict) -> list[str]:
    """Everyone currently entitled by this license."""
    if not _is_institution(lic):
        redeemer = lic.get("redeemedByUid")
        return [redeemer] if redeemer else []
    out = []
    for seat_doc in _stream(ref.collection("seats")):
        seat = seat_doc.to_dict() or {}
        if _is_revoked(seat):
            continue
        out.append(seat.get("uid") or seat_doc.id)
    return out


def _users_on_license(uids: list[str], license_id: str) -> list:
    """`(reference, app)` for those of `uids` whose account still points at
    this licence, `app` being whose mirror fields hold it (ADR-033).

    One `get_all` for the lot. The guard is the one every writer here uses: a
    holder who has since moved to another licence must not have this one's
    terms, or its revoke, written over theirs. Each write goes through
    `apps.licence_patch` with that app, so a Semper Classroom licence's revoke
    leaves the holder's Semper licence alone.
    """
    users = db().collection("users")
    refs = [users.document(uid) for uid in dict.fromkeys(u for u in uids if u)]
    out = []
    for snap in _get_all(refs):
        slot = apps.slot_holding(snap.to_dict() or {}, license_id) if snap.exists else None
        if slot:
            out.append((snap.reference, slot))
    return out


def _refresh_license_mirrors(uids: list[str], license_id: str, mirror: dict) -> None:
    """Re-stamp every holder's copy of the license terms, in batches."""
    patch = {**mirror, "updatedAt": _base.firestore.SERVER_TIMESTAMP}
    _update_refs([(ref, apps.licence_patch(patch, slot))
                  for ref, slot in _users_on_license(uids, license_id)])


def _demo_drop_patch() -> dict:
    return {
        **_mode_patch(MODE_DEMO),
        "leaseExpiresAt": _base.firestore.DELETE_FIELD,
        "leaseDeviceId": _base.firestore.DELETE_FIELD,
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        # The instant from which "has this account been back since?" is
        # asked. `_touch_user` throttles lastSeenAt to the hour, which
        # would leave reconciliation reading a landed revoke as unlanded
        # for that long; the checkpoint makes the account's next request
        # bypass the throttle exactly once. Written inside the update the
        # demotion already performs, so it costs no extra write here.
        "seenCheckpointAt": _base.firestore.SERVER_TIMESTAMP,
    }


def _drop_users_to_demo_if_licensed(uids: list[str], license_id: str) -> None:
    """`_drop_user_to_demo_if_licensed` for a whole roster: one read call,
    batched writes."""
    patch = _demo_drop_patch()
    _update_refs([(ref, apps.licence_patch(patch, slot))
                  for ref, slot in _users_on_license(uids, license_id)])


def _drop_user_to_demo_if_licensed(uid: str, license_id: str) -> None:
    """Drop a user to Demo only if they are still pointed at this exact
    license — activation is in-place (same uid/doc, no data migration), and
    downgrade must never delete or hide existing sessions/files, only stop
    new analysis creation once the account is back over the Demo cap.

    The holder's copy of a floating lease goes too. Every caller has just
    taken the seat's lease away (revoke, hold, whole-licence revoke), and the
    copy left behind read as a seat still in use on the account page."""
    user_ref = db().collection("users").document(uid)
    user_snap = _get(user_ref)
    slot = apps.slot_holding(user_snap.to_dict() or {}, license_id) if user_snap.exists else None
    if slot:
        _update_doc(user_ref, apps.licence_patch(_demo_drop_patch(), slot))
