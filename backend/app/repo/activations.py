"""Activations: which phones a licence licenses, by explicit Activate (ADR-033).

A licence that names its `app` runs this model. Each phone it licenses has a
document `licenses/{id}/activations/{deviceId}`, and the licence counts them
in `devicesActive` against `maxDevices` (default 1). For an institution the
count is a pool across the whole licence, and each seat counts its member's
own phones too. Any other phone of the holder is Demo, for its own requests
only.

A licence without an `app` keeps the device lock (`repo.devlock`), unchanged,
until the split (`split_licences_per_app.py`) stamps it. `activate` still
answers for one, by binding its empty lock, so the app's Activate works on
both.

Deactivating, and who may, is `repo.deactivations`.

Nothing here touches an analysis. Deactivating a phone makes it Demo; its
files stay on it, and its backups stay in the account.
"""
import logging
from datetime import timedelta

from .. import apps, audit, errors, statuses
from .. import observability as obs
from ..config import settings
from ..errors import Refusal
from ..licenses import (
    MODE_LICENSED,
    SEATING_FLOATING,
    STATUS_ACTIVE,
    as_utc,
    email_domain,
    normalize_email,
    normalize_seating,
)

from . import _base
from ._base import (
    ACTOR_SELF,
    SCHEMA_VERSION,
    _get,
    _get_all,
    _is_institution,
    _is_revoked,
    _license_past_grace,
    _load_user,
    _now,
    _run_tx,
    _seat_ref,
    _stream,
    db,
    get_license,
    test_account_of,
)
from .user_config import effective_mode, inactive_licence_reason


log = logging.getLogger("semper.firestore")

#: The subcollection under `licenses/{id}` that holds one document per phone.
ACTIVATIONS = "activations"
#: An activation's `status`. A deactivated phone is marked, never deleted, so
#: its document keeps the history.
ACTIVE = "active"
DEACTIVATED = "deactivated"
#: `activatedBy` for a phone that activated itself on a build that does not
#: tap Activate (`IMPLICIT_ACTIVATION=legacy`).
ACTOR_LEGACY = "legacy"

#: `revalidate`'s answers, for `repo.devlock`.
OK = "ok"
REVOKED = "revoked"
MISMATCH = "mismatch"
IMPLICIT = "implicit"

#: `/v1/config`'s `activation.state`.
STATE_ACTIVE = "active"
STATE_AVAILABLE = "available"
STATE_FULL = "full"
STATE_NONE = "none"
STATE_FLOATING = "floating"


def runs_activations(lic: dict | None) -> bool:
    """Whether `lic` runs the activation model: it names its app. One without
    keeps the device lock."""
    return bool((lic or {}).get("app"))


def max_devices(lic: dict | None) -> int:
    """How many phones `lic` may have active at once: `maxDevices`. Unset, it
    is 1 on an individual licence and, on an institution one, its seat cap,
    or no limit without one (`_UNCOUNTED`)."""
    lic = lic or {}
    raw = lic.get("maxDevices")
    if raw is not None:
        return max(0, int(raw))
    if not _is_institution(lic):
        return 1
    seats = lic.get("maxSeats")
    return _UNCOUNTED if seats is None else max(0, int(seats))


#: An institution licence with no seat cap and no device count: as good as
#: no limit, and still an int for the `n/m` a refusal carries.
_UNCOUNTED = 1_000_000


def _count(doc: dict | None) -> int:
    return max(0, int((doc or {}).get("devicesActive") or 0))


def _ref(license_id: str, device_id: str):
    return (db().collection("licenses").document(license_id)
            .collection(ACTIVATIONS).document(device_id))


def _active_for(act: dict | None, uid: str) -> bool:
    return bool(act) and act.get("status") == ACTIVE and act.get("uid") == uid


def _floating(lic: dict) -> bool:
    return normalize_seating(lic.get("seating")) == SEATING_FLOATING


def _deactivated_lately(act: dict | None) -> bool:
    """A phone deactivated within `DEVICE_RELEASE_HOLD_HOURS`: it does not
    activate itself again (`IMPLICIT`), or deactivating an old build's phone
    would last until its next request."""
    if not act or act.get("status") != DEACTIVATED:
        return False
    hold = timedelta(hours=max(0, settings.DEVICE_RELEASE_HOLD_HOURS))
    when = as_utc(act.get("deactivatedAt"))
    return bool(hold) and when is not None and _now() - when < hold


def activation_block(state: str, active: int = 0, maximum: int = 0, *, pool: bool = False,
          model: str = "activation") -> dict:
    """`/v1/config`'s `activation` block: this phone's state, the licence's
    count, whether the count is an institution's pool, and which model the
    licence runs (`activation`, or `lock` before the split)."""
    return {"state": state, "devicesActive": active, "maxDevices": maximum,
            "pool": pool, "model": model}


NONE_BLOCK = activation_block(STATE_NONE)


def revalidate_activation(user: dict, license_id: str, lic: dict, device_id: str, app: str, *,
               explicit: bool) -> tuple[str, dict]:
    """Judge `device_id` against a licence that runs activations, with
    `user` as `app`'s licence sees it (`apps.licence_view`). One batched read:
    this phone's activation and, on an institution licence, the member's seat.

    `OK` for an active activation of this account, `REVOKED` for a revoked
    licence or seat, `IMPLICIT` where a build that does not tap Activate may
    activate this phone itself (`IMPLICIT_ACTIVATION=legacy`, nothing held,
    a slot free), `MISMATCH` for anything else, which is Demo for this request.
    With the verdict, the `activation` block `/v1/config` answers with, so
    that call reads nothing twice.
    """
    uid = user.get("uid") or ""
    institution = _is_institution(lic)
    pool = institution
    n, m = _count(lic), max_devices(lic)
    if _is_revoked(lic):
        return REVOKED, NONE_BLOCK
    if lic.get("app") and not apps.same_app(lic.get("app"), app):
        return MISMATCH, NONE_BLOCK
    act_ref = _ref(license_id, device_id)
    refs = [act_ref] + ([_seat_ref(license_id, uid)] if institution else [])
    snaps = {s.reference.path: s for s in _get_all(refs)}
    act_snap = snaps.get(act_ref.path)
    act = (act_snap.to_dict() or {}) if act_snap is not None and act_snap.exists else {}
    holder = lic
    if institution:
        seat_snap = snaps.get(_seat_ref(license_id, uid).path)
        if seat_snap is None or not seat_snap.exists:
            return MISMATCH, NONE_BLOCK
        holder = seat_snap.to_dict() or {}
        if holder.get("status") in ("revoked", "disabled"):
            return REVOKED, NONE_BLOCK
    if _floating(lic):
        # The lease is the activation: only the phone that checked it out.
        # A lease from before `leaseDeviceId` was mirrored names no phone.
        leased = user.get("leaseDeviceId") or ""
        verdict = OK if not leased or leased == device_id else MISMATCH
        return verdict, activation_block(STATE_FLOATING, pool=True)
    if _active_for(act, uid):
        return OK, activation_block(STATE_ACTIVE, n, m, pool=pool)
    full = n >= m
    if (not explicit and settings.IMPLICIT_ACTIVATION == "legacy" and not full
            and _count(holder) == 0 and act.get("status") != ACTIVE
            and not _deactivated_lately(act)):
        return IMPLICIT, activation_block(STATE_AVAILABLE, n, m, pool=pool)
    return MISMATCH, activation_block(STATE_FULL if full else STATE_AVAILABLE, n, m, pool=pool)


def activation_summary(user: dict, app: str, device_id: str = "") -> dict:
    """The `activation` block for a call that did not revalidate a phone (a
    browser, or a request with no `X-Device-Id`): the licence's count, and
    this phone's state when one is named. Reads the licence, and the
    activation and seat when there is one to read."""
    view = apps.licence_view(user, app)
    license_id = view.get("licenseId") or ""
    if not license_id or effective_mode(view) != MODE_LICENSED:
        return NONE_BLOCK
    lic = get_license(license_id) or {}
    if not lic or _is_revoked(lic) or _license_past_grace(lic):
        return NONE_BLOCK
    if _floating(lic):
        return activation_block(STATE_FLOATING, pool=True)
    institution = _is_institution(lic)
    uid = user.get("uid") or ""
    if not runs_activations(lic):
        holder = lic
        if institution:
            holder = _get(_seat_ref(license_id, uid)).to_dict() or {}
        lock = apps.read_field(holder, "deviceIdLock", app) or ""
        state = STATE_ACTIVE if device_id and lock == device_id else (
            STATE_FULL if lock else STATE_AVAILABLE)
        return activation_block(state, 1 if lock else 0, 1, model="lock")
    n, m = _count(lic), max_devices(lic)
    if device_id and _active_for(_get(_ref(license_id, device_id)).to_dict(), uid):
        return activation_block(STATE_ACTIVE, n, m, pool=institution)
    return activation_block(STATE_FULL if n >= m else STATE_AVAILABLE, n, m, pool=institution)


def _licence_for(user: dict, app: str) -> tuple[str, dict]:
    """The licence `user` holds for `app`, as `(id, document)`, or raise the
    refusal Activate answers with: none held, revoked, expired, not this app's,
    or floating (its lease is its activation)."""
    view = apps.licence_view(user, app)
    license_id = view.get("licenseId") or ""
    if not license_id:
        raise Refusal(errors.NO_LICENSE)
    lic = get_license(license_id)
    if not lic:
        raise Refusal(errors.LICENSE_NOT_FOUND)
    if lic.get("app") and not apps.same_app(lic.get("app"), app):
        raise Refusal(errors.NO_LICENSE)
    if _is_revoked(lic):
        raise Refusal(errors.LICENSE_REVOKED)
    if _floating(lic):
        raise Refusal(errors.SEATING_FLOATING)
    if effective_mode(view) != MODE_LICENSED:
        # Past its grace is expired; anything else (a held seat, a licence
        # dropped to Demo) holds nothing to activate.
        raise Refusal(errors.LICENSE_EXPIRED if inactive_licence_reason(user, app)
                      else errors.NO_LICENSE)
    return license_id, lic


def activate_device(uid: str, device_id: str, app: str, *, actor: str = ACTOR_SELF,
             by_uid: str = "", override: bool = False, implicit: bool = False,
             license_id: str = "", user: dict | None = None) -> dict:
    """License `device_id` on the licence `uid` holds for `app`, or raise
    `Refusal`.

    One transaction re-reads the licence, the phone, its activation and (an
    institution) the member's seat, and refuses when the phone is not
    registered to the account for `app`, the seat is not active, or every
    slot is taken (`device_limit_reached: <active>/<max>`). Staff may pass
    `override` to go past the count. Activating a phone that is already
    active answers as a success, so a double tap is harmless.

    `implicit` is a build that does not tap Activate (`IMPLICIT_ACTIVATION`):
    it activates only while the holder has no other phone active, so a second
    old-build phone stays Demo rather than taking a pool's slot.

    A licence that does not run activations yet (no `app`) binds its empty
    lock instead, and counts as one phone.

    `license_id`, when given (staff), must be the licence the account holds
    for `app`: staff add a phone to a licence, not to whatever the account
    holds.
    """
    stored = user if user is not None else (_load_user(uid) or {})
    held_id, lic = _licence_for(stored, app)
    if license_id and license_id != held_id:
        raise Refusal(errors.DEVICE_NOT_FOUND)
    license_id = held_id
    institution = _is_institution(lic)
    lic_ref = db().collection("licenses").document(license_id)
    act_ref = _ref(license_id, device_id)
    dev_ref = db().collection("devices").document(device_id)
    seat_ref = _seat_ref(license_id, uid)
    now = _now()

    @_base.firestore.transactional
    def _activate(tx):
        lic_snap = _get(lic_ref, tx)
        dev_snap = _get(dev_ref, tx)
        act_snap = _get(act_ref, tx)
        seat_snap = _get(seat_ref, tx) if institution else None
        if not lic_snap.exists:
            return Refusal(errors.LICENSE_NOT_FOUND)
        cur = lic_snap.to_dict() or {}
        if _is_revoked(cur):
            return Refusal(errors.LICENSE_REVOKED)
        dev = (dev_snap.to_dict() or {}) if dev_snap.exists else {}
        if not (dev.get("uid") == uid and dev.get("status") == statuses.DEVICE_ACTIVE
                and apps.same_app(dev.get("app"), app)):
            return Refusal(errors.DEVICE_NOT_FOUND)
        holder, holder_ref = cur, lic_ref
        if institution:
            if seat_snap is None or not seat_snap.exists:
                return Refusal(errors.SEAT_NOT_FOUND)
            holder, holder_ref = seat_snap.to_dict() or {}, seat_ref
            if holder.get("status") != STATUS_ACTIVE:
                return Refusal(errors.LICENSE_SEAT_DISABLED)
        if not runs_activations(cur):
            lock = apps.read_field(holder, "deviceIdLock", app) or ""
            if lock == device_id:
                return {"already": True, "devicesActive": 1, "maxDevices": 1, "model": "lock"}
            if lock:
                return Refusal(errors.DEVICE_LIMIT_REACHED, "1/1")
            tx.update(holder_ref, {**apps.written_fields("deviceIdLock", app, device_id),
                                   "schemaVersion": SCHEMA_VERSION})
            return {"already": False, "devicesActive": 1, "maxDevices": 1, "model": "lock"}
        act = (act_snap.to_dict() or {}) if act_snap.exists else {}
        n, m = _count(cur), max_devices(cur)
        if act.get("status") == ACTIVE:
            if act.get("uid") == uid:
                return {"already": True, "devicesActive": n, "maxDevices": m,
                        "model": "activation"}
            return Refusal(errors.DEVICE_IN_USE)
        if (n >= m and not override) or (implicit and _count(holder) > 0):
            return Refusal(errors.DEVICE_LIMIT_REACHED, f"{n}/{m}")
        tx.set(act_ref, {
            "uid": uid,
            "app": apps.stored_name(app),
            "status": ACTIVE,
            "activatedAt": now,
            "activatedBy": actor,
            "activatedByUid": by_uid or uid,
            "deviceModel": dev.get("model") or "",
            "schemaVersion": SCHEMA_VERSION,
        })
        # The lock stays filled as a mirror, for a rollback and the roster
        # export: the first active phone, until it is deactivated.
        mirror = ({} if apps.read_field(holder, "deviceIdLock", app)
                  else apps.written_fields("deviceIdLock", app, device_id))
        lic_patch = {"devicesActive": n + 1, "updatedAt": _base.firestore.SERVER_TIMESTAMP}
        if institution:
            tx.update(seat_ref, {**mirror, "devicesActive": _count(holder) + 1})
        else:
            lic_patch.update(mirror)
        tx.update(lic_ref, lic_patch)
        return {"already": False, "devicesActive": n + 1, "maxDevices": m,
                "model": "activation"}

    out = _run_tx(_activate, on_contended=lambda: Refusal(errors.CLAIM_CONTENDED))
    stage = ACTOR_LEGACY if implicit else actor
    if isinstance(out, Refusal):
        obs.log_event(log, logging.INFO, "activation", uid=uid or None, licenseId=license_id,
                      app=app, stage=stage, outcome=out.code)
        raise out
    answer = {"licenseId": license_id, "app": app, "deviceId": device_id,
              "pool": institution and out["model"] == "activation", **out}
    obs.log_event(log, logging.INFO, "activation", uid=uid or None, licenseId=license_id,
                  app=app, stage=stage, outcome="already" if out["already"] else "activated")
    if not out["already"]:
        audit.record(by_uid or uid, device_id, action="LICENSE_DEVICE_ACTIVATE",
                     target={"type": "license", "id": license_id},
                     detail={"deviceId": device_id, "app": app, "uid": uid, "actor": stage,
                             "devicesActive": out["devicesActive"],
                             "maxDevices": out["maxDevices"], "override": bool(override)})
    return answer


def _row(snap) -> dict:
    act = snap.to_dict() or {}
    return {
        "deviceId": snap.id,
        "uid": act.get("uid") or "",
        "app": apps.canonical(act.get("app")),
        "status": act.get("status") or "",
        "activatedAt": act.get("activatedAt"),
        "activatedBy": act.get("activatedBy") or "",
        "deactivatedAt": act.get("deactivatedAt"),
        "deactivatedBy": act.get("deactivatedBy") or "",
        "deviceModel": act.get("deviceModel") or "",
    }


def _holder_activations(license_id: str, uid: str) -> list[dict]:
    """`uid`'s active phones on `license_id`. One equality query on the
    licence's own subcollection (the automatic index)."""
    col = db().collection("licenses").document(license_id).collection(ACTIVATIONS)
    return [r for r in (_row(s) for s in _stream(col.where("uid", "==", uid)))
            if r["status"] == ACTIVE]


def licence_devices(license_id: str, lic: dict, *, domain: str | None = None) -> dict:
    """The active phones on `license_id`, with the count. On an institution
    licence each row names the member's address from their seat; `domain`
    (IT) keeps only members at that domain, and an empty one keeps none."""
    col = db().collection("licenses").document(license_id).collection(ACTIVATIONS)
    rows = [r for r in (_row(s) for s in _stream(col.where("status", "==", ACTIVE)))]
    if _is_institution(lic) and rows:
        seats = {s.id: (s.to_dict() or {}) for s in _get_all(
            [_seat_ref(license_id, uid) for uid in {r["uid"] for r in rows}]) if s.exists}
        for r in rows:
            r["email"] = (seats.get(r["uid"]) or {}).get("email") or ""
    if domain is not None:
        rows = [r for r in rows
                if domain and email_domain(r.get("email") or "") == normalize_email(domain)]
    rows.sort(key=lambda r: (r.get("email") or "", r["deviceId"]))
    return {"licenseId": license_id, "app": apps.canonical(lic.get("app")) if lic.get("app") else "",
            "devicesActive": _count(lic), "maxDevices": max_devices(lic),
            "pool": _is_institution(lic), "model": "activation" if runs_activations(lic) else "lock",
            "devices": rows}


def account_devices(user: dict, app: str, device_id: str = "") -> dict:
    """The holder's phones for `app`: every phone registered to the account
    for it, each marked whether the licence licenses it, and the licence's
    count. What the app's phones screen and the web account page show; a
    holder sees only their own phones, never another member's. Every row
    carries every key (`activatedAt` null and `activatedBy` "" on a phone the
    licence does not cover), as `contracts/devices_response.json` pins."""
    uid = user.get("uid") or ""
    phones = {}
    for snap in _stream(db().collection("devices").where("uid", "==", uid)):
        dev = snap.to_dict() or {}
        if dev.get("status") == statuses.DEVICE_ACTIVE and apps.same_app(dev.get("app"), app):
            phones[snap.id] = {"deviceId": snap.id, "model": dev.get("model") or "",
                               "registeredAt": dev.get("registeredAt") or dev.get("createdAt"),
                               "activated": False, "thisDevice": snap.id == device_id,
                               "activatedAt": None, "activatedBy": ""}
    view = apps.licence_view(user, app)
    license_id = view.get("licenseId") or ""
    lic = (get_license(license_id) or {}) if license_id else {}
    answer = {"app": app, "licenseId": license_id if lic else "", "devicesActive": 0,
              "maxDevices": 0, "pool": False, "model": "none", "devices": []}
    if lic and not _is_revoked(lic) and not _floating(lic):
        if runs_activations(lic):
            for act in _holder_activations(license_id, uid):
                row = phones.setdefault(act["deviceId"], {
                    "deviceId": act["deviceId"], "model": act["deviceModel"],
                    "registeredAt": None, "activated": False,
                    "thisDevice": act["deviceId"] == device_id,
                    "activatedAt": None, "activatedBy": ""})
                row.update(activated=True, activatedAt=act["activatedAt"],
                           activatedBy=act["activatedBy"])
            answer.update(devicesActive=_count(lic), maxDevices=max_devices(lic),
                          pool=_is_institution(lic), model="activation")
        else:
            holder = lic
            if _is_institution(lic):
                holder = _get(_seat_ref(license_id, uid)).to_dict() or {}
            lock = apps.read_field(holder, "deviceIdLock", app) or ""
            if lock in phones:
                phones[lock]["activated"] = True
            answer.update(devicesActive=1 if lock else 0, maxDevices=1, model="lock")
    answer["devices"] = sorted(phones.values(),
                               key=lambda r: (not r["activated"], not r["thisDevice"], r["deviceId"]))
    return answer


def activated_devices(user: dict, app: str) -> set[str]:
    """The phones `user`'s licence for `app` licenses, for registration,
    which must not retire one of them to make room. Empty for a test account
    (ADR-027) or no live licence."""
    view = apps.licence_view(user, app)
    license_id = view.get("licenseId") or ""
    if not license_id or test_account_of(user) is not None:
        return set()
    lic = get_license(license_id) or {}
    if not lic or _is_revoked(lic):
        return set()
    uid = user.get("uid") or ""
    if runs_activations(lic):
        return {a["deviceId"] for a in _holder_activations(license_id, uid)}
    holder = lic
    if _is_institution(lic):
        holder = _get(_seat_ref(license_id, uid)).to_dict() or {}
    lock = apps.read_field(holder, "deviceIdLock", app) or ""
    return {lock} if lock else set()
