"""Claiming a licence: the seat and individual-licence transactions, and the
user-document patches they write.
"""
from .. import apps
from ..licenses import (
    SEATING_FLOATING,
    KIND_INDIVIDUAL,
    KIND_INSTITUTION,
    MODE_DEMO,
    MODE_LICENSED,
    normalize_duration,
    normalize_seating,
)

from . import _base
from ._base import (
    _CONTENDED,
    db,
    _license_mode,
    _mode_patch,
    _run_tx,
    _seat_ref,
)


def _public_claim_error(err: str, fallback: str) -> str:
    """The wire code for a claim failure, contention included.

    Contention fails closed as `fallback` — granting a seat or a licence we
    could not commit is the one outcome that breaks the cap, and a caller who
    lost the race succeeds on their next request.
    """
    return fallback if err == _CONTENDED else err


def _emails_match(left, right) -> bool:
    a = (left or "").strip().lower()
    b = (right or "").strip().lower()
    return bool(a and b and a == b)


def _license_mirror_patch(lic: dict) -> dict:
    """The license terms copied onto the user document at activation.

    `effective_mode` and `license_summary` read only the user dict, which is
    what keeps `resolve_user_config` free of Firestore reads on request paths
    that call it for every session and file. The cost is that these are a
    snapshot: changing the license after activation does not reach anyone who
    already holds a seat. `update_license` fans the new values back out — it is
    the only writer that has to, and it is rare.

    Every field is written on every activation, cleared with DELETE_FIELD when
    the license does not carry it, so re-activating onto a different license
    never leaves a stale term behind.
    """
    expires_at = lic.get("expiresAt")
    grace_days = lic.get("graceDays")
    max_analyses = lic.get("maxAnalyses")
    return {
        "licenseExpiresAt": expires_at if expires_at is not None else _base.firestore.DELETE_FIELD,
        "licenseGraceDays": (
            max(0, int(grace_days)) if isinstance(grace_days, (int, float))
            else _base.firestore.DELETE_FIELD
        ),
        "licenseDuration": normalize_duration(
            lic.get("duration"), has_expiry=expires_at is not None,
        ),
        # Not the lease itself — only whether this license needs one. The lease
        # is written by checkout and cleared by release, and re-stamping it
        # here would hand a seat back to someone who had released it.
        "licenseSeating": normalize_seating(lic.get("seating")),
        "licenseMaxAnalyses": int(max_analyses) if max_analyses else _base.firestore.DELETE_FIELD,
    }


def _claim_terms(user_patch: dict, lic: dict) -> dict:
    """`user_patch` with its licence terms rebuilt from `lic`, in place.

    Callers build the patch from a licence they read before the claim
    transaction began. An `update_license` landing in between — an extension,
    a new analysis cap — would otherwise be stamped over by the old terms on
    the newest member, and its fan-out has already run without them, so the
    member keeps the stale copy until the next edit. `lic` is the snapshot
    the transaction read, which a concurrent edit cannot slip past.

    Only a patch that carries the terms gets them: a caller writing something
    else through the claim is not asking for a mirror. Mutating the caller's
    dict is deliberate — they merge it into the config they answer with, and
    that should describe what was stored.
    """
    if "licenseExpiresAt" in user_patch:
        user_patch.update(_license_mirror_patch(lic))
    return user_patch


def _drop_superseded_demo(uid: str, license_id: str) -> None:
    """Delete the auto-minted Demo key an account has just stopped pointing at.

    `ensure_demo_license` is a compare-and-set, so a request that lost the
    race can no longer stamp Demo *over* a real licence. The opposite order is
    what is left: the loser commits its Demo key first, the winning claim then
    moves the account's pointer, and the Demo record survives
    `status: "redeemed"` with nobody holding it — indistinguishable in
    `GET /v1/admin/licenses` from a live key, one more of them for every raced
    sign-in.

    This runs after a claim commits, deliberately not inside it. Reading
    `users/{uid}` in the claim's transaction looked like the tidy answer, and
    it is wrong: a transaction that reads and then writes one document locks
    it, so several requests arriving at one account together — the shape of
    every app launch, and of the invite delivery path — abort each other
    instead of queueing. Measured against the emulator, six concurrent
    sign-ins starved out completely and the account landed on Demo. A plain
    query and a guarded delete take no locks, and the worst a lost race costs
    here is that the record survives to the next claim: the condition being
    closed is litter in the operator listing, never a wrong entitlement.

    The discriminator is the licence document, never the holder's `mode`
    mirror. `_drop_user_to_demo_if_licensed` leaves a revoked holder demoted
    in place and still pointing at the real, revoked licence, so a mirror test
    would delete revocation records — considerably worse than the leak this
    closes. Only `ensure_demo_license` writes `mode: demo` and
    `createdByUid: "system"` together.
    """
    snap = db().collection("users").document(uid).get()
    if not snap.exists or ((snap.to_dict() or {}).get("licenseId") or "") != license_id:
        # Something has moved the account on again. Whatever it holds now is
        # not this claim's to reason about.
        return
    for doc in db().collection("licenses").where("redeemedByUid", "==", uid).stream():
        if doc.id == license_id:
            continue
        lic = doc.to_dict() or {}
        if _license_mode(lic) == MODE_DEMO and (lic.get("createdByUid") or "") == "system":
            doc.reference.delete()


def claim_seat(license_id: str, uid: str, email: str, device_id: str, user_patch: dict,
               invite_ref=None, *, app: str = apps.SEMPER,
               carried_locks: dict[str, str] | None = None) -> str:
    """Take a seat on the roster, atomically. Returns an error code, or "".

    `device_id` is checked against, and binds, `app`'s lock on the seat
    (ADR-010). `carried_locks` (`{app: device id}`) seeds a new seat's locks
    for the other apps too, for a licence converted to an institution with
    its devices already bound (`repo.upgrade`).

    This was a read-then-`WriteBatch` — atomic for its writes, but carrying no
    reads and no preconditions, so two members activating at once on a pool of
    ten both saw nine free and the count landed at eleven. A batch is not a
    transaction. With a floating pool the count is the actual boundary rather
    than a soft allocation, so the whole claim now reads and writes under one.

    Every read happens before every write, as Firestore requires. The seat
    validation lives inside for the same reason as the count: it is decided
    from data read in the transaction.

    `invite_ref`, when given, is a pending invite being redeemed: it is read
    with the other reads and deleted with the other writes, so the seat and
    the invite settle together. Consuming it in a second write would leave a
    window where IT has revoked the invite but the seat is granted anyway, or
    where the invite is gone and the claim then fails — either way the roster
    and the invite list disagree.

    The licence terms in `user_patch` are replaced with ones built from the
    licence as read here, and the caller's dict is updated in place so what
    it merges into its answer is what was stored — see `_claim_terms`.
    """
    lic_ref = db().collection("licenses").document(license_id)
    seat_ref = _seat_ref(license_id, uid)
    user_ref = db().collection("users").document(uid)

    @_base.firestore.transactional
    def _claim(tx) -> str:
        lic_snap = lic_ref.get(transaction=tx)
        seat_snap = seat_ref.get(transaction=tx)
        invite_snap = invite_ref.get(transaction=tx) if invite_ref is not None else None
        if not lic_snap.exists:
            return "license_not_found"
        if invite_ref is not None and not invite_snap.exists:
            # Revoked between the read that found it and this transaction.
            return "invite_not_found"
        lic = lic_snap.to_dict() or {}
        if (lic.get("status") or "active") == "revoked":
            return "license_revoked"

        seat = seat_snap.to_dict() if seat_snap.exists else None
        if seat and seat.get("status") == "revoked":
            # revoke_institution_seat() already freed this slot. A revoked seat
            # is not a permanent ban — the holder may be re-admitted and takes
            # a fresh slot through the normal maxSeats check below.
            seat = None

        if seat:
            if seat.get("status") == "disabled":
                return "license_seat_disabled"
            lock_field = apps.field("deviceIdLock", app)
            locked_device = seat.get(lock_field) or ""
            if device_id and locked_device and locked_device != device_id:
                return "license_device_mismatch"
            seat_patch = {"updatedAt": _base.firestore.SERVER_TIMESTAMP}
            if device_id:
                # Only when a device actually redeemed. IT adding a member
                # passes no device, and must not wipe the lock of someone who
                # already has one.
                seat_patch[lock_field] = device_id
            tx.update(seat_ref, seat_patch)
        else:
            # `maxSeats` caps the ROSTER on an assigned license, where holding
            # a seat is holding the entitlement. On a floating one it caps
            # concurrent LEASES instead — a fifty-person lab sharing ten slots
            # is the whole point, so the roster is deliberately uncapped and
            # the check moves to checkout_lease.
            max_seats = lic.get("maxSeats")
            seats_used = int(lic.get("seatsUsed") or 0)
            floating = normalize_seating(lic.get("seating")) == SEATING_FLOATING
            if not floating and max_seats is not None and seats_used >= int(max_seats):
                return "license_seats_exhausted"
            locks = {apps.field("deviceIdLock", a): (carried_locks or {}).get(a) or ""
                     for a in apps.ALL}
            if device_id:
                locks[apps.field("deviceIdLock", app)] = device_id
            tx.set(seat_ref, {
                "uid": uid,
                "email": (email or "").strip().lower(),
                **locks,
                "status": "active",
                "createdAt": _base.firestore.SERVER_TIMESTAMP,
                "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            })
            tx.update(lic_ref, {"seatsUsed": _base.firestore.Increment(1)})
        tx.update(user_ref, _claim_terms(user_patch, lic))
        if invite_ref is not None:
            tx.delete(invite_ref)
        return ""

    # Fail closed on contention. Handing out a seat we could not commit is the
    # one outcome that breaks the cap; a caller who lost the race just tries
    # again, and on a pool with room they win immediately.
    err = _run_tx(_claim, on_contended=lambda: _CONTENDED)
    if not err:
        _drop_superseded_demo(uid, license_id)
    return err


def _individual_member_patch(license_id: str, lic: dict) -> dict:
    """The user-document patch that attaches an individual licence.

    The counterpart to `_institution_member_patch`, and shared for the same
    reason: a licence reached by typing its key and one reached by signing in
    at the invited address must entitle the holder identically.
    """
    patch = {
        **_mode_patch(_license_mode(lic)),
        "licenseId": license_id,
        "licenseKind": KIND_INDIVIDUAL,
        "licensePrefix": lic.get("keyPrefix") or "",
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }
    patch.update(_license_mirror_patch(lic))
    return patch


def claim_individual_license(license_id: str, uid: str, email: str,
                             user_patch: dict, invite_ref=None) -> str:
    """Attach an individual licence to `uid`, atomically. Error code, or "".

    The individual counterpart to `claim_seat`, transactional for the same
    reason: `redeemedByUid` names one account, so two requests arriving
    together must not both come away holding the licence. Reads before
    writes, and the invite is consumed in the same transaction, so the promise
    and the grant settle together rather than leaving a window where one
    exists without the other.

    No device lock is written here. Binding happens on the request path — see
    `revalidate_device_lock` — because this runs for a caller who may not have
    presented a device at all.

    The licence terms in `user_patch` are refreshed in place, as in
    `claim_seat`.
    """
    lic_ref = db().collection("licenses").document(license_id)
    user_ref = db().collection("users").document(uid)

    @_base.firestore.transactional
    def _claim(tx) -> str:
        lic_snap = lic_ref.get(transaction=tx)
        invite_snap = invite_ref.get(transaction=tx) if invite_ref is not None else None
        if not lic_snap.exists:
            return "license_not_found"
        if invite_ref is not None and not invite_snap.exists:
            # Withdrawn between the read that found it and this transaction.
            return "invite_not_found"
        lic = lic_snap.to_dict() or {}
        status = lic.get("status") or "unused"
        if status == "revoked":
            return "license_revoked"
        if not _emails_match(lic.get("emailLock"), email):
            return "license_email_mismatch"
        redeemer = lic.get("redeemedByUid")
        if redeemer and redeemer != uid:
            return "license_already_redeemed"
        if status == "unused":
            tx.update(lic_ref, {
                "status": "redeemed",
                "redeemedByUid": uid,
                "redeemedAt": _base.firestore.SERVER_TIMESTAMP,
            })
        tx.update(user_ref, _claim_terms(user_patch, lic))
        if invite_ref is not None:
            tx.delete(invite_ref)
        return ""

    # Fail closed on contention, as claim_seat does: granting a licence we
    # could not commit is the outcome that breaks single-redeemer. The caller
    # retries on their next request, which for the invite path is moments away.
    err = _run_tx(_claim, on_contended=lambda: _CONTENDED)
    if not err:
        _drop_superseded_demo(uid, license_id)
    return err


def _institution_member_patch(license_id: str, lic: dict) -> dict:
    """The user-document patch that puts someone on an institution licence.

    Shared by the two ways onto a roster — IT adding an existing account, and
    a newcomer redeeming an invite at sign-in — so the two cannot drift into
    entitling people differently.
    """
    patch = {
        **_mode_patch(MODE_LICENSED),
        "licenseId": license_id,
        "licenseKind": KIND_INSTITUTION,
        "licensePrefix": lic.get("keyPrefix") or "",
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }
    patch.update(_license_mirror_patch(lic))
    return patch
