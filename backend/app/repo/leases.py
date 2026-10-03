"""Floating-seat leases: checkout, release and the sweep of expired ones.
"""
import logging
from datetime import timedelta

from ..config import settings
from ..licenses import (
    SEATING_FLOATING,
    as_utc,
    normalize_seating,
)

from . import _base
from ._base import (
    db,
    get_license,
    _lease_clear_patch,
    _license_past_grace,
    _now,
    _run_tx,
    _seat_lease_counted,
    _seat_ref,
)
from .user_config import (
    resolve_user_config,
)


log = logging.getLogger("semper.firestore")


#: How many expired leases one checkout reclaims. A pool cannot have more live
#: leases than maxSeats, so this only ever has to clear the backlog of one
#: quiet period; anything left is picked up by the next claim.
_LEASE_SWEEP_LIMIT = 50


def _sweep_expired_leases(lic_ref, now) -> int:
    """Release leases that ran out without anyone calling release.

    `leasesActive` drifts upward every time an app is killed, uninstalled or
    simply goes offline mid-lease, so the counter alone cannot be trusted to
    say whether the pool is full. This reconciles it before a claim reads it.

    A single-field inequality on one subcollection, so no composite index is
    needed. Positional `.where(field, op, value)` deliberately — the fake store
    used by the unit tests implements only that form, not `FieldFilter`.

    Deliberately outside the claim transaction: a transaction may not run a
    query, and sweeping first is safe because releasing a genuinely expired
    lease is correct regardless of who wins the claim that follows.

    Being outside a transaction makes the query's answer a list of
    candidates, not a fact. Two checkouts arriving together both see the same
    expired seats; when this was one batch that cleared them and subtracted
    the count, both batches committed, each seat came off `leasesActive`
    twice, and the pool admitted more than `maxSeats`. Each seat is now
    reclaimed in its own transaction that re-reads it, so only the first
    sweeper to reach a seat counts it.
    """
    expired = list(
        lic_ref.collection("seats")
        .where("leaseExpiresAt", "<=", now)
        .limit(_LEASE_SWEEP_LIMIT)
        .stream()
    )
    reclaimed = sum(1 for doc in expired if _reclaim_expired_lease(lic_ref, doc.reference, now))
    if reclaimed:
        log.info("Reclaimed %d expired lease(s) on license %s", reclaimed, lic_ref.id)
    return reclaimed


def _reclaim_expired_lease(lic_ref, seat_ref, now) -> bool:
    """Clear one lapsed lease and give its slot back, at most once.

    Only a lease that is still present and still expired when the
    transaction reads it is cleared: another sweep may have reclaimed it
    already, or its holder may have renewed it since the query ran. The
    decrement is skipped at zero, so a counter that has already drifted low
    is never pushed negative.
    """
    @_base.firestore.transactional
    def _reclaim(tx) -> bool:
        lic_snap = lic_ref.get(transaction=tx)
        seat_snap = seat_ref.get(transaction=tx)
        if not seat_snap.exists:
            return False
        ends = as_utc((seat_snap.to_dict() or {}).get("leaseExpiresAt"))
        if ends is None or ends > now:
            return False
        tx.update(seat_ref, {
            **_lease_clear_patch(),
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        })
        active = int((lic_snap.to_dict() or {}).get("leasesActive") or 0) if lic_snap.exists else 0
        if active > 0:
            tx.update(lic_ref, {"leasesActive": _base.firestore.Increment(-1)})
        return True

    # Losing every attempt means other requests were writing this seat or the
    # licence at the same moment — most likely another sweep reclaiming the
    # same lease. Leave it: if it is still expired, the next checkout finds it.
    return _run_tx(_reclaim, on_contended=lambda: False)


def checkout_lease(user: dict, device_id: str) -> tuple[str, dict | None]:
    """Claim or extend a floating-seat lease. Returns (error_code, config).

    Re-checkout IS the heartbeat — extending an existing lease takes the same
    path and must not consume a second slot. There is deliberately no separate
    heartbeat route and no audit write here: the app calls this every half
    hour, and both `FILE_DOWNLOAD` and `_LAST_SEEN_THROTTLE` record what
    writing per request on a hot path costs.
    """
    uid = user.get("uid") or ""
    license_id = user.get("licenseId")
    if not license_id:
        return "no_license", None
    lic = get_license(license_id)
    if not lic:
        return "license_not_found", None
    if normalize_seating(lic.get("seating")) != SEATING_FLOATING:
        # An assigned seat is always entitled; there is nothing to check out,
        # and pretending otherwise would let a client invent a lease field.
        return "seating_not_floating", None
    if (lic.get("status") or "active") == "revoked":
        return "license_revoked", None
    if _license_past_grace(lic):
        return "license_expired", None

    lic_ref = db().collection("licenses").document(license_id)
    now = _now()
    _sweep_expired_leases(lic_ref, now)
    expires_at = now + timedelta(hours=settings.LICENSE_LEASE_HOURS)

    seat_ref = _seat_ref(license_id, uid)
    user_ref = db().collection("users").document(uid)

    @_base.firestore.transactional
    def _checkout(tx) -> str:
        lic_snap = lic_ref.get(transaction=tx)
        seat_snap = seat_ref.get(transaction=tx)
        if not seat_snap.exists:
            # Not on the roster. Institution IT adds members; there is no
            # self-service path onto a floating license.
            return "not_eligible"
        seat = seat_snap.to_dict() or {}
        if seat.get("status") in ("revoked", "disabled"):
            return "not_eligible"

        # A lease still in the count — live, or run out and not swept yet —
        # already holds its slot, so taking it up again must not add another.
        renewing = _seat_lease_counted(seat)
        if not renewing:
            max_seats = (lic_snap.to_dict() or {}).get("maxSeats") if lic_snap.exists else None
            active = int((lic_snap.to_dict() or {}).get("leasesActive") or 0)
            if max_seats is not None and active >= int(max_seats):
                return "no_floating_seat"

        tx.update(seat_ref, {
            "leaseExpiresAt": expires_at,
            "leaseDeviceId": device_id,
            "lastHeartbeatAt": now,
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        })
        if not renewing:
            tx.update(lic_ref, {"leasesActive": _base.firestore.Increment(1)})
        tx.update(user_ref, {
            "leaseExpiresAt": expires_at,
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        })
        return ""

    # Fail closed on contention: granting a lease we could not commit is what
    # would overfill the pool. The client retries and wins as soon as there
    # is room.
    err = _run_tx(_checkout, on_contended=lambda: "no_floating_seat")
    if err:
        return err, None
    return "", resolve_user_config({**user, "leaseExpiresAt": expires_at})


def release_lease(user: dict) -> tuple[str, dict | None]:
    """Give a floating slot back. Idempotent — releasing twice frees one slot.

    The user's mirror is cleared in the same commit, so the account resolves
    demo from the next request rather than staying licensed until the lease
    would have expired on its own.
    """
    uid = user.get("uid") or ""
    license_id = user.get("licenseId")
    if not license_id:
        return "no_license", None

    lic_ref = db().collection("licenses").document(license_id)
    seat_ref = _seat_ref(license_id, uid)
    user_ref = db().collection("users").document(uid)

    @_base.firestore.transactional
    def _release(tx) -> str:
        seat_snap = seat_ref.get(transaction=tx)
        if not seat_snap.exists:
            return "not_eligible"
        held = _seat_lease_counted(seat_snap.to_dict() or {})
        tx.update(seat_ref, {
            **_lease_clear_patch(),
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        })
        if held:
            # Only decrement for a lease that is still counted. A second
            # release must not push the pool negative; one after expiry but
            # before the sweep must decrement, or clearing the lease here
            # hides it from the sweep and the slot leaks.
            tx.update(lic_ref, {"leasesActive": _base.firestore.Increment(-1)})
        tx.update(user_ref, {
            "leaseExpiresAt": _base.firestore.DELETE_FIELD,
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        })
        return ""

    # Releasing is idempotent, so a lost race means someone else already did
    # it. Answer from the current state rather than reporting failure.
    err = _run_tx(
        _release,
        on_contended=lambda: "" if seat_ref.get().exists else "not_eligible",
    )
    if err:
        return err, None
    merged = {k: v for k, v in user.items() if k != "leaseExpiresAt"}
    return "", resolve_user_config(merged)
