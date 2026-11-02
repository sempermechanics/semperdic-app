"""Institution seat administration: clearing a device lock, holding and revoking a seat.
"""

from .. import apps, errors, statuses
from ..errors import Refusal
from ..licenses import (
    MODE_LICENSED,
    STATUS_ACTIVE,
)

from . import _base
from ._base import (  # ACTOR_IT is re-exported through `repo`
    ACTOR_IT,  # noqa: F401
    ACTOR_SELF,
    ACTOR_STAFF,
    SCHEMA_VERSION,
    _is_institution,
    _is_revoked,
    db,
    _lease_clear_patch,
    _license_mode,
    _mode_patch,
    _run_tx,
    _seat_lease_counted,
    _seat_ref,
    _get,
    _update_doc,
)
from .deactivations import deactivate_holder
from .devices import (
    _release_devices,
    forget_device_sessions,
)
from .license_admin import (
    _demo_drop_patch,
    _drop_user_to_demo_if_licensed,
)




def _live_holder(license_id: str, lic: dict, ref, scope: str, uid: str):
    """The holder's `(user_ref, user)` if a device-lock clear may act on them, else None.

    None for a revoked licence, for a seat that is revoked or on hold, and for an
    account that has since moved to a different licence. The release once
    checked only the last condition: **New device** on a held seat let its
    member, by then on Demo, register a different phone, a change Demo accounts
    do not otherwise get (TD-127).
    """
    if _is_revoked(lic):
        return None
    if scope == "seat":
        seat = _get(ref)
        if not seat.exists or (seat.to_dict() or {}).get("status") != STATUS_ACTIVE:
            return None
        holder = uid
    else:
        holder = lic.get("redeemedByUid") or ""
    if not holder:
        return None
    user_ref = db().collection("users").document(holder)
    snap = _get(user_ref)
    if not snap.exists:
        return None
    user = snap.to_dict() or {}
    if apps.slot_holding(user, license_id) is None:
        return None
    return user_ref, user


def _settle_holder(license_id: str, lic: dict, ref, scope: str, uid: str,
                   locks: dict[str, str]) -> dict[str, str]:
    """Finish a device change on the holder's account. `{app: released device id}`.

    `locks` is the lock each cleared app held, keyed by app (ADR-010); every
    step below is taken per app, against that app's own fields, and the
    answer names every app in `locks`, with "" where nothing was released.

    Clearing the lock is only one of the three bindings a device change moves:

    - **Mode.** Until 2026-09-28 a device change was usually preceded by the
      holder trying the new device, and `revalidate_device_lock` stored that
      mismatch as `mode: demo` on the user document. It now serves Demo to the
      mismatching device's requests only, but accounts demoted before then
      still carry it, and `revalidate_device_lock` returns early for an
      account that reads as demo, so it would never reach the bind branch and
      the holder would sit on Demo holding a live licence. The mode is
      re-stamped here. Nothing is resurrected: `effective_mode` still
      re-applies expiry, grace and the floating-lease check, so a licence that
      has run out stays demo either way.
    - **Registered device.** The phone the cleared `lock` named (with no
      lock, the latest registered one) is retired (`_retire_device`) and
      signed out, and the account's `activeDeviceId` dropped where it names
      that phone. Only when it is one of the account's registered phones: a
      lock on a phone that never registered (bound before
      `revalidate_device_lock` bound only the registered phone) releases
      nothing. An account holds several phones (ADR-033), so its other
      registered phones stay; the latest of them binds the empty lock on its
      next request.
    - **Release hold.** The released id is stamped (`releasedDeviceId`,
      `releasedAt`) so registration can hold it off for
      `DEVICE_RELEASE_HOLD_HOURS`: the old phone's upload worker re-registers as
      soon as it reads `device_not_active`, and would otherwise take the account
      straight back (`repo.devices.released_device_held`).

    One read of the user and one batch, so the mode and the release land
    together. Nothing is written where `_live_holder` says no: a revoked
    licence, a seat that is revoked or on hold, an account that has moved to a
    different licence.

    Deliberately outside the caller's transaction: reading `users/{uid}` and
    then writing it inside one takes a lock on that document, which is what
    starved out concurrent claims before `_drop_superseded_demo` moved the
    same guarded read out of `claim_seat`.
    """
    none = {app: "" for app in locks}
    live = _live_holder(license_id, lic, ref, scope, uid)
    if live is None:
        return none
    user_ref, user = live
    patch = {
        **_mode_patch(_license_mode(lic)),
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }
    releasing = {}
    for app, lock in locks.items():
        phone = lock or apps.read_field(user, "activeDeviceId", app) or ""
        dev = (_get(db().collection("devices").document(phone)).to_dict() or {}) if phone else {}
        if dev.get("uid") == user_ref.id and dev.get("status") == statuses.DEVICE_ACTIVE:
            releasing[app] = phone
    _release_devices(user_ref, releasing, patch, user)
    return {**none, **releasing}


def _restamp_mode(license_id: str, lic: dict, ref, scope: str, uid: str) -> None:
    """The mode half of `_settle_holder`, for a licence that runs activations.

    A holder demoted by a device mismatch before 2026-09-28 still stores
    `mode: demo` against a live licence, and so does the twin the split
    copied it to (`split_licences_per_app.py`, `_mirror`). Activate then
    refuses them (`activations._licence_for`: `no_license`) and the request
    check returns early on demo, so nothing repaired them after the split
    (review L2-4). A device clear is the remedy it was before: the licence's
    mode is written to the slot that holds it, where `_live_holder` allows.
    `effective_mode` still applies expiry, grace and the floating lease."""
    live = _live_holder(license_id, lic, ref, scope, uid)
    if live is None:
        return
    user_ref, user = live
    slot = apps.slot_holding(user, license_id)
    if slot is None or apps.read_field(user, "mode", slot) == _license_mode(lic):
        return
    _update_doc(user_ref, apps.licence_patch({
        **_mode_patch(_license_mode(lic)),
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }, slot))


def clear_device_lock(license_id: str, uid: str = "", *,
                      actor: str = ACTOR_STAFF,
                      app: str = apps.SEMPER,
                      caller: dict | None = None) -> dict:
    """Unbind a licence or a seat from the device it is on. Returns the audit
    detail, or raises `Refusal`.

    One primitive with three callers — Semper staff, institution IT, and the
    holder — because there is one operation. Since Gap A, clearing the lock is
    the *whole* device change: an empty lock reads as `_Lock.UNBOUND`, and
    `revalidate_device_lock` binds it to whatever signs in next, first writer
    wins. Nothing is re-activated and nothing is typed.

    **Clearing is not revoking.** Entitlement, seat, lease and data are all
    untouched; the lock goes empty and the holder's device binding is released,
    so the new phone can register. A holder demoted in place by the mismatch
    they hit on the new device gets their mode back. Both are
    `_settle_holder`; without it, clearing would be half a device change.

    `uid` selects the seat on an institution licence. An individual licence
    holds its lock on the licence document itself, so `uid` is ignored there.

    **Which apps.** Each app has its own lock (ADR-010). A staff or IT clear
    releases every app, because the holder's phone changed and both apps are
    on it. The holder's own clear releases `app` alone, the app that asked.

    A staff or IT clear also stops the released phone's demo count holding
    the holder's analyses (`forget_device_sessions`, TD-276); the holder's
    own clear does not, or a second account on the same phone could shed
    the first one's count.

    No cooldown (ADR-033): the holder may clear their own lock as often as
    they like. The licence's device count is what limits how many phones hold
    it, and every clear is audited.

    **Activations.** A licence that names its app has no lock to clear: its
    phones are its activations. A clear there deactivates every phone the
    holder has active on it (`repo.activations.deactivate_holder`), with the
    same release a staff or IT deactivation does, and the lock mirror empties
    with them. `caller`, when given, is held to the scope a deactivation of
    that phone checks (`repo.deactivations`): IT reaches only members on the
    licence's `domainLock`, as it does by the devices route, and a phone
    outside it refuses the clear with `activation_not_found`. Each such
    clear then re-stamps a live holder's stored mode, as the lock's does
    (`_restamp_mode`).

    The answer is the audit detail, including the device
    that was given up — the other half of the record `revalidate_device_lock`
    writes when the replacement binds. `previousDeviceId` is the lock's device
    and `releasedDeviceId` the registered one the account was signed out of;
    a registered phone the lock did not name is kept (`_settle_holder`), and either can be empty (a lock that never bound, an
    account with nothing registered, or a holder `_settle_holder` skips).
    On a licence that runs activations, `deactivated` lists the phones
    deactivated.
    Both device ids come once per app (`apps.spread`): `previousDeviceId` and
    `releasedDeviceId` are Semper's, `previousDeviceIdSemperClassroom` and so
    on the other app's (mirrored under the old `...MaterialTesting` names,
    ADR-030), "" for an app the clear did not touch.
    """
    lic_snap = _get(db().collection("licenses").document(license_id))
    if not lic_snap.exists:
        raise Refusal(errors.LICENSE_NOT_FOUND)
    lic = lic_snap.to_dict() or {}
    if _is_institution(lic):
        if not uid:
            raise Refusal(errors.SEAT_NOT_FOUND)
        ref, scope = _seat_ref(license_id, uid), "seat"
    else:
        ref, scope = db().collection("licenses").document(license_id), "license"

    detail = {"scope": scope, "licenseId": license_id, "uid": uid, "actor": actor}
    if actor == ACTOR_SELF:
        detail["app"] = app
    not_found = errors.SEAT_NOT_FOUND if scope == "seat" else errors.LICENSE_NOT_FOUND

    if lic.get("app"):
        holder = uid if scope == "seat" else lic.get("redeemedByUid") or ""
        if scope == "seat" and not _get(ref).exists:
            raise Refusal(not_found)
        gone = deactivate_holder(license_id, holder, actor=actor, caller=caller) if holder else []
        _restamp_mode(license_id, lic, ref, scope, uid)
        first = gone[0] if gone else ""
        named = apps.canonical(lic.get("app"))
        return {**detail, **apps.spread("previousDeviceId", {named: first}),
                **apps.spread("releasedDeviceId", {named: first if actor != ACTOR_SELF else ""}),
                "deactivated": gone}

    if actor != ACTOR_SELF:
        # No invariant to protect: two clears landing together produce the
        # same empty lock. A plain read and update keeps the support path out
        # of the transaction machinery entirely.
        snap = _get(ref)
        if not snap.exists:
            raise Refusal(not_found)
        doc = snap.to_dict() or {}
        previous = {a: apps.read_field(doc, "deviceIdLock", a) or "" for a in apps.ALL}
        _update_doc(ref, {
            **apps.spread("deviceIdLock", {}),
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            "schemaVersion": SCHEMA_VERSION,
        })
        released = _settle_holder(license_id, lic, ref, scope, uid, previous)
        # The phone may go to someone else: its demo count stops holding the
        # holder's analyses (TD-276). Staff and IT only; see the docstring.
        holder = uid if scope == "seat" else lic.get("redeemedByUid") or ""
        forgotten = forget_device_sessions(holder, released.values()) if holder else 0
        return {**detail, **apps.spread("previousDeviceId", previous),
                **apps.spread("releasedDeviceId", released), "sessionsForgotten": forgotten}

    snap = _get(ref)
    if not snap.exists:
        raise Refusal(not_found)
    doc = snap.to_dict() or {}
    previous = apps.read_field(doc, "deviceIdLock", app) or ""
    _update_doc(ref, {
        **apps.written_fields("deviceIdLock", app, ""),
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    })
    released = _settle_holder(license_id, lic, ref, scope, uid, {app: previous})
    return {**detail, **apps.spread("previousDeviceId", {app: previous}),
            **apps.spread("releasedDeviceId", released)}


def set_seat_enabled(license_id: str, uid: str, enabled: bool) -> None:
    """Hold or resume a seat, or raise `Refusal`.

    Hold drops the holder to Demo but does NOT free the slot — the seat still
    counts against maxSeats so IT can resume it without a fresh activation. A
    floating lease is released, though: a seat on hold cannot check out, so a
    lease left on it would only fill the pool until it expired. Resume
    restores the licensed mode in place, no data migration.

    A revoked seat, or a seat on a revoked licence, is refused either way.
    Resuming one used to set it active again without taking a slot back —
    licensed, on the roster, and not counted in `seatsUsed` — and holding one
    made a freed slot look occupied.

    The holder, while still pointed at this licence, moves in the same commit
    as the seat (TD-294). It was a separate write after it, so a call that
    died in between left a held seat's holder licensed until IT pressed the
    button again.
    """
    lic_ref = db().collection("licenses").document(license_id)
    ref = _seat_ref(license_id, uid)
    user_ref = db().collection("users").document(uid)

    @_base.firestore.transactional
    def _set(tx) -> str:
        snap = _get(ref, tx)
        lic_snap = _get(lic_ref, tx)
        if not snap.exists:
            return errors.SEAT_NOT_FOUND
        seat = snap.to_dict() or {}
        if _is_revoked(seat):
            return errors.SEAT_REVOKED
        if lic_snap.exists and _is_revoked(lic_snap.to_dict() or {}):
            return errors.LICENSE_REVOKED
        # Every read before the first write, as a transaction requires.
        user_snap = _get(user_ref, tx)
        patch = {
            "status": "active" if enabled else "disabled",
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            "schemaVersion": SCHEMA_VERSION,
        }
        release = not enabled and _seat_lease_counted(seat)
        if release:
            patch.update(_lease_clear_patch())
        tx.update(ref, patch)
        if release and lic_snap.exists:
            tx.update(lic_ref, {"leasesActive": _base.firestore.Increment(-1)})
        slot = apps.slot_holding(user_snap.to_dict() if user_snap.exists else None, license_id)
        if slot:
            tx.update(user_ref, apps.licence_patch(_demo_drop_patch() if not enabled else {
                **_mode_patch(MODE_LICENSED),
                "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            }, slot))
        return ""

    # Losing means a revoke, checkout or another hold landed on this seat
    # (or a write on its holder) first. Nothing was written; IT tries again
    # against the new state.
    err = _run_tx(_set, on_contended=lambda: errors.SEAT_BUSY)
    if err:
        raise Refusal(err)


def revoke_institution_seat(license_id: str, uid: str, *, actor: str = ACTOR_STAFF) -> bool:
    """Single-seat revoke: drops the holder to Demo and frees the slot
    (decrements seatsUsed) so another roster member can take it. On a licence
    that names its app, the member's phones are deactivated too, as `actor`,
    so they free the pool's phone slots as well (ADR-033).

    Transactional for the same reason `claim_seat` is, and against the mirror
    image of its race: two concurrent revokes of one seat both read a status
    that is not yet "revoked", both decrement, and the pool undercounts by one
    forever. A counted lease is dropped in the same commit — a revoked seat
    must not keep occupying a floating slot, and one that ran out unswept is
    still in the count (see `_seat_lease_counted`).
    """
    lic_ref = db().collection("licenses").document(license_id)
    seat_ref = _seat_ref(license_id, uid)

    @_base.firestore.transactional
    def _revoke(tx) -> bool:
        seat_snap = _get(seat_ref, tx)
        if not seat_snap.exists:
            return False
        seat = seat_snap.to_dict() or {}
        if _is_revoked(seat):
            return True  # idempotent: already revoked, counters already settled
        lic_snap = _get(lic_ref, tx)
        seats_used = int((lic_snap.to_dict() or {}).get("seatsUsed") or 0) if lic_snap.exists else 0
        held_lease = _seat_lease_counted(seat)

        tx.update(seat_ref, {
            "status": "revoked",
            # Stamped separately from `updatedAt` because reconciliation asks
            # "has the holder been back since the revoke?", and `updatedAt`
            # moves for any later write to the seat (a staff device clear, for
            # one) which would quietly reset that question.
            "revokedAt": _base.firestore.SERVER_TIMESTAMP,
            **_lease_clear_patch(),
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            "schemaVersion": SCHEMA_VERSION,
        })
        counters = {}
        if seats_used > 0:
            counters["seatsUsed"] = _base.firestore.Increment(-1)
        if held_lease:
            counters["leasesActive"] = _base.firestore.Increment(-1)
        if counters and lic_snap.exists:
            tx.update(lic_ref, counters)
        return True

    # On contention the other writer won and did the same thing. Re-read to
    # answer precisely rather than reporting a failure that did not happen.
    revoked = _run_tx(_revoke, on_contended=lambda: _get(seat_ref).exists)
    if revoked:
        _drop_user_to_demo_if_licensed(uid, license_id)
        deactivate_holder(license_id, uid, actor=actor)
    return revoked
