"""Deactivating a phone, scoped by who asks (ADR-033).

Who may deactivate follows the role, and each scope is checked here, not only
by which route a call came in on:

- the holder (`ACTOR_SELF`): their own phones, on the licence they hold;
- institution IT (`ACTOR_IT`): phones of members on the licence's
  `domainLock`, on an institution licence that names them in `adminEmails`;
- staff (`ACTOR_STAFF`): any phone.

Apart from `repo.activations` because an IT or staff deactivation also signs
the phone out (`repo.devices`), which loads after the licence check.
"""
import logging

from .. import apps, audit, errors, statuses
from .. import observability as obs
from ..errors import Refusal
from ..licenses import email_domain, normalize_email

from . import _base
from ._base import (
    ACTOR_IT,
    ACTOR_SELF,
    ACTOR_STAFF,
    _get,
    _is_institution,
    _now,
    _run_tx,
    _seat_ref,
    db,
)
from .activations import (
    ACTIVE,
    DEACTIVATED,
    _count,
    _holder_activations,
    _ref,
    max_devices,
)
from .devices import _release_devices, forget_device_sessions
from .holders import verified_address
from .institution_admin import is_institution_admin


log = logging.getLogger("semper.firestore")


def _in_scope(actor: str, caller: dict | None, license_id: str, lic: dict,
              act: dict, seat: dict | None) -> bool:
    """Whether `caller`, acting as `actor`, may deactivate `act` on `lic`."""
    if actor == ACTOR_STAFF:
        return True
    if actor == ACTOR_SELF:
        uid = (caller or {}).get("uid") or ""
        view = apps.licence_view(caller, act.get("app") or apps.SEMPER)
        return bool(uid) and act.get("uid") == uid and view.get("licenseId") == license_id
    if actor == ACTOR_IT:
        if not _is_institution(lic) or not is_institution_admin(lic, verified_address(caller)):
            return False
        domain = normalize_email(lic.get("domainLock") or "")
        return bool(domain) and email_domain((seat or {}).get("email") or "") == domain
    return False


def deactivate_device(license_id: str, device_id: str, *, actor: str,
               caller: dict | None = None) -> dict:
    """Stop licensing `device_id` on `license_id`, as `actor`, or raise
    `Refusal` (`activation_not_found` for a phone outside the caller's scope,
    which reads the same as one that is not there).

    The licence's count, and an institution seat's own, go down in the same
    transaction as the activation is marked, and the lock mirror empties when
    it named this phone. A deactivated phone is Demo from its next request;
    it may activate again at once, since the count is the only limit.

    An IT or staff deactivation also signs the phone out of the account (its
    registration released, with the release hold) and stops it holding the
    account's demo count (`forget_device_sessions`), since the phone may go
    to someone else. The holder's own does neither: their phone stays theirs.
    """
    lic_ref = db().collection("licenses").document(license_id)
    act_ref = _ref(license_id, device_id)
    lic_snap = _get(lic_ref)
    act_snap = _get(act_ref)
    lic = (lic_snap.to_dict() or {}) if lic_snap.exists else {}
    act = (act_snap.to_dict() or {}) if act_snap.exists else {}
    if not lic or act.get("status") != ACTIVE:
        raise Refusal(errors.ACTIVATION_NOT_FOUND)
    uid = act.get("uid") or ""
    institution = _is_institution(lic)
    seat_ref = _seat_ref(license_id, uid)
    seat = (_get(seat_ref).to_dict() or {}) if institution else None
    # No caller is a device clear (`repo.clear_device_lock`) its route scoped
    # to this holder or seat before it got here: staff, the holder's own, or
    # a seat revoke. The roster's clear passes its caller (`deactivate_holder`).
    if caller is not None and not _in_scope(actor, caller, license_id, lic, act, seat):
        raise Refusal(errors.ACTIVATION_NOT_FOUND)
    app = apps.canonical(act.get("app"))
    now = _now()
    by_uid = (caller or {}).get("uid") or ""

    @_base.firestore.transactional
    def _deactivate(tx):
        cur_lic = _get(lic_ref, tx).to_dict() or {}
        cur = _get(act_ref, tx).to_dict() or {}
        seat_snap = _get(seat_ref, tx) if institution else None
        if cur.get("status") != ACTIVE or cur.get("uid") != uid:
            return Refusal(errors.ACTIVATION_NOT_FOUND)
        unmirror = apps.written_fields("deviceIdLock", app, "")
        lic_patch = {"devicesActive": max(0, _count(cur_lic) - 1),
                     "updatedAt": _base.firestore.SERVER_TIMESTAMP}
        if institution and seat_snap is not None and seat_snap.exists:
            cur_seat = seat_snap.to_dict() or {}
            seat_patch = {"devicesActive": max(0, _count(cur_seat) - 1)}
            if apps.read_field(cur_seat, "deviceIdLock", app) == device_id:
                seat_patch.update(unmirror)
            tx.update(seat_ref, seat_patch)
        elif not institution and apps.read_field(cur_lic, "deviceIdLock", app) == device_id:
            lic_patch.update(unmirror)
        tx.update(act_ref, {"status": DEACTIVATED, "deactivatedAt": now,
                            "deactivatedBy": actor, "deactivatedByUid": by_uid})
        tx.update(lic_ref, lic_patch)
        return max(0, _count(cur_lic) - 1)

    out = _run_tx(_deactivate, on_contended=lambda: Refusal(errors.CLAIM_CONTENDED))
    if isinstance(out, Refusal):
        raise out
    released = False
    forgotten = 0
    if actor != ACTOR_SELF and uid:
        user_ref = db().collection("users").document(uid)
        user = _get(user_ref).to_dict() or {}
        dev = _get(db().collection("devices").document(device_id)).to_dict() or {}
        if dev.get("uid") == uid and dev.get("status") == statuses.DEVICE_ACTIVE:
            _release_devices(user_ref, {app: device_id},
                             {"updatedAt": _base.firestore.SERVER_TIMESTAMP}, user)
            released = True
        forgotten = forget_device_sessions(uid, [device_id])
    obs.log_event(log, logging.INFO, "activation", uid=uid or None, licenseId=license_id,
                  app=app, stage=actor, outcome="deactivated")
    audit.record(by_uid or uid, device_id, action="LICENSE_DEVICE_DEACTIVATE",
                 target={"type": "license", "id": license_id},
                 detail={"deviceId": device_id, "app": app, "uid": uid, "actor": actor,
                         "devicesActive": out, "released": released,
                         "sessionsForgotten": forgotten})
    return {"licenseId": license_id, "deviceId": device_id, "app": app, "uid": uid,
            "devicesActive": out, "maxDevices": max_devices(lic), "released": released}


def deactivate_holder(license_id: str, uid: str, *, actor: str,
                      caller: dict | None = None) -> list[str]:
    """Deactivate every phone `uid` has active on `license_id`: a device
    clear (`repo.clear_device_lock`) on a licence that runs activations, which
    its route has already scoped to this holder. The ids deactivated, in no
    order.

    `caller`, when given, must have every one of those phones in its scope
    (`_in_scope`), or nothing is deactivated and the answer is
    `activation_not_found`, as `deactivate_device` answers. The roster's
    **New device** is IT's too, and without it reached a member off the
    licence's `domainLock` that IT's own devices route refuses (review L2-6).
    """
    acts = _holder_activations(license_id, uid)
    if caller is not None and acts:
        lic = _get(db().collection("licenses").document(license_id)).to_dict() or {}
        seat = (_get(_seat_ref(license_id, uid)).to_dict() or {}) if _is_institution(lic) else None
        if not all(_in_scope(actor, caller, license_id, lic, act, seat) for act in acts):
            raise Refusal(errors.ACTIVATION_NOT_FOUND)
    out = []
    for act in acts:
        try:
            deactivate_device(license_id, act["deviceId"], actor=actor, caller=caller)
        except Refusal as exc:
            if exc.code != errors.ACTIVATION_NOT_FOUND:
                raise
            continue
        out.append(act["deviceId"])
    return out
