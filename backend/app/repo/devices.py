"""Registered devices and the challenge / client nonces they sign with.
"""
import secrets
from datetime import timedelta

from google.api_core.exceptions import AlreadyExists

from .. import apps, errors, statuses
from ..config import settings
from ..errors import Refusal
from ..models import DeviceReg

from . import _base
from ._base import (
    PURPOSE_CI,
    _BATCH_LIMIT,
    _active_devices,
    _stream,
    db,
    _load_user,
    _now,
    _run_tx,
    SCHEMA_VERSION,
    _get,
    _commit,
    _set_doc,
    _create_doc,
    max_test_devices,
    staff_released_from,
    test_account_of,
)
from .devlock import licensed_devices
from .entitlement import (
    ensure_entitlement,
)


# ---------------- devices ----------------
def get_device(device_id: str):
    snap = _get(db().collection("devices").document(device_id))
    return {**snap.to_dict(), "deviceId": device_id} if snap.exists else None


def _release_patch(device_id: str, app: str = apps.SEMPER, *,
                   drop_active: bool = True) -> dict:
    """The `users/{uid}` fields that release `device_id` from `app`: the id
    stamped, so `repo.devlock.released_device_held` holds it off, and the
    app's `activeDeviceId` dropped when it names this phone (`drop_active`).
    An account holds several phones (ADR-033), so the latest registered one
    may be another phone, which stays. Shared by a lock clear and a staff
    phone release."""
    released_at = _now()
    out = {
        **apps.written_fields("releasedDeviceId", app, device_id),
        **apps.written_fields("releasedAt", app, released_at),
    }
    if drop_active:
        out.update(apps.written_fields("activeDeviceId", app, _base.firestore.DELETE_FIELD))
    return out


def _retire_device(batch, device_ref, status: str, extra: dict | None = None) -> None:
    """Retire `device_ref` in `batch` (or a transaction), so its id stops
    counting against another account; `extra` fields go in the same write.

    The one way a device leaves service: superseded by a lock clear or a staff
    release (`_release_devices`), revoked with its account
    (`set_user_status`), or one test-account phone released by staff
    (`repo.testaccounts.release_test_device`, stamped `staffReleased`). The
    caller drops the app's `activeDeviceId` field on `users/{uid}` in the
    same batch.
    """
    batch.update(device_ref, {
        "status": status,
        "revokedAt": _base.firestore.SERVER_TIMESTAMP,
        **(extra or {}),
    })




def _release_devices(user_ref, devices: dict[str, str], patch: dict,
                     user: dict | None = None) -> None:
    """Release each `{app: device id}` from the account, in one batch with
    `patch`: the id held off (`_release_patch`), the app's `activeDeviceId`
    dropped when `user` (as read) names this phone there, or always when no
    `user` is given, and the device retired as superseded. Shared by a lock
    clear (`repo.seats._settle_holder`) and a staff phone release
    (`repo.users.release_account_device`), so the two cannot release a phone
    differently."""
    patch = dict(patch)
    batch = db().batch()
    for app, device in devices.items():
        drop = user is None or apps.read_field(user, "activeDeviceId", app) == device
        patch.update(_release_patch(device, app, drop_active=drop))
        device_ref = db().collection("devices").document(device)
        if _get(device_ref).exists:
            _retire_device(batch, device_ref, statuses.DEVICE_SUPERSEDED)
    batch.update(user_ref, patch)
    _commit(batch)


def forget_device_sessions(uid: str, device_ids) -> int:
    """Stop the account's analyses counting toward each of `device_ids`'
    demo cap: drop `deviceId` from the account's sessions created from them.
    Returns how many were changed.

    For a phone that staff or institution IT took off the account (a staff
    release, a staff or IT lock clear): the phone may go to someone else, and
    `count_device_sessions` would otherwise hold its next holder's demo to the
    previous holder's analyses (TD-276). The analyses stay the account's and
    still count toward its own cap. Never for a holder's own device change:
    that would let a second account on the same phone shed the first one's
    count. Two equality filters, served by the automatic indexes; chunked to
    the batch write cap.
    """
    changed = 0
    for device_id in {d for d in device_ids if d}:
        query = (db().collection("sessions")
                 .where("uid", "==", uid).where("deviceId", "==", device_id))
        refs = [snap.reference for snap in _stream(query)]
        for start in range(0, len(refs), _BATCH_LIMIT):
            batch = db().batch()
            for ref in refs[start:start + _BATCH_LIMIT]:
                batch.update(ref, {"deviceId": _base.firestore.DELETE_FIELD})
            _commit(batch)
        changed += len(refs)
    return changed


def _oldest_active_devices(uid: str, app: str, *, exclude: str, count: int) -> list[str]:
    """The `count` devices ACTIVE for `uid` in `app` that registered first,
    `exclude` left out: the ones a full CI test account gives up for a new
    phone. A record without `registeredAt` counts as the oldest; ties go by id.
    The same equality query as `_active_devices`."""
    if count <= 0:
        return []
    found = []
    for snap in _stream(db().collection("devices").where("uid", "==", uid)):
        dev = snap.to_dict() or {}
        if (snap.id != exclude and dev.get("status") == statuses.DEVICE_ACTIVE
                and apps.same_app(dev.get("app"), app)):
            registered = dev.get("registeredAt")
            found.append((registered is not None, registered or 0, snap.id))
    found.sort(key=lambda row: (row[0], row[1], row[2]))
    return [device_id for _, _, device_id in found[:count]]


def register_device(uid: str, body: DeviceReg, app: str = apps.SEMPER, *,
                    step_up_eligible: bool = False) -> dict:
    """Register `body.deviceId` to `uid` for `app`, or raise `Refusal`.

    An account holds several phones per app (ADR-033): registering signs a
    phone in, and only a licensed one is licensed; the rest run as Demo. Its
    `activeDeviceId` names the latest. The checks and the writes are one
    transaction (B5): the device must not be ACTIVE for another account
    (`device_in_use`), so two accounts registering one phone at once cannot
    both hold it. The same device id re-registering is the reinstall heal: its
    key and stamp are replaced (`healed` in the answer).

    A real account holds up to `MAX_PHONES_PER_APP` phones per app. Past it,
    the phones that registered first make room, retired as superseded in the
    same transaction, but never the phone its licence is locked to
    (`devlock.licensed_devices`, read before the transaction). The answer's
    `rotated` lists them.

    A test account (ADR-027) holds up to its own `maxDevices`. A full QC
    account is refused (`device_conflict`); a full CI account retires its
    oldest phones, since every CI run is a fresh emulator with a new key
    (TD-313). A phone staff released from the test account on its own
    (`staff_released_from`) is refused `device_released` for that account, so
    it cannot take back the place it gave up (TD-314); another account may
    still register it.

    A registration of the released phone itself ends its release hold (the
    router has already refused it while the hold lasts); another phone
    registering leaves the hold in place.

    `step_up_eligible` is whether the registering token proved a second factor
    (ADR-010, device trust). Stamped on every registration, a heal included,
    because a registration replaces the key: only a device registered from a
    second-factor sign-in may step up for staff or institution IT
    (`step_up.step_up_check`).
    """
    dev = {
        "uid": uid,
        # The stored name, which every backend reads (ADR-030, TD-330).
        "app": apps.stored_name(app),
        "publicKeyPem": body.publicKeyPem,
        "status": statuses.DEVICE_ACTIVE,
        "stepUpEligible": bool(step_up_eligible),
        "model": body.model,
        "osVersion": body.osVersion,
        "appVersion": body.appVersion,
        "registeredAt": _base.firestore.SERVER_TIMESTAMP,
        "lastAssertionAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    user_ref = db().collection("users").document(uid)
    device_ref = db().collection("devices").document(body.deviceId)
    # The licensed phone is never retired to make room. Read ahead of the
    # transaction: a transaction cannot query, and a lock that moves meanwhile
    # only changes which unlicensed phone goes.
    locked = licensed_devices(_load_user(uid) or {}, app)
    rotated: list[str] = []
    healed: list[bool] = []

    @_base.firestore.transactional
    def _register(tx):
        rotated.clear()  # a contended transaction runs this again
        healed.clear()
        user_snap = _get(user_ref, tx)
        held_user = (user_snap.to_dict() or {}) if user_snap.exists else {}
        tag = test_account_of(held_user)
        # Counted after the user document is read in this transaction: every
        # registration writes that document, so a second phone registering at
        # once retries and counts this one.
        others = _active_devices(uid, app, exclude=body.deviceId)
        if tag is not None:
            over = len(others) - max_test_devices(tag) + 1
            if over > 0 and tag.get("purpose") != PURPOSE_CI:
                raise Refusal(errors.DEVICE_CONFLICT)
            rotated.extend(_oldest_active_devices(uid, app, exclude=body.deviceId, count=over))
        else:
            over = len(others) - max(1, settings.MAX_PHONES_PER_APP) + 1
            if over > 0:
                spare = [d for d in _oldest_active_devices(
                    uid, app, exclude=body.deviceId, count=len(others)) if d not in locked]
                rotated.extend(spare[:over])
        existing = _get(device_ref, tx)
        held = (existing.to_dict() or {}) if existing.exists else {}
        if held.get("status") == statuses.DEVICE_ACTIVE and held.get("uid") != uid:
            raise Refusal(errors.DEVICE_IN_USE)
        if tag is not None and staff_released_from(held, uid):
            raise Refusal(errors.DEVICE_RELEASED)
        healed.append(held.get("status") == statuses.DEVICE_ACTIVE
                      and held.get("uid") == uid
                      and apps.same_app(held.get("app"), app))
        for old in rotated:  # the writes come after every read of the transaction
            tx.update(db().collection("devices").document(old), {
                "status": statuses.DEVICE_SUPERSEDED,
                "revokedAt": _base.firestore.SERVER_TIMESTAMP,
            })
        tx.set(device_ref, dev)
        user_patch = apps.written_fields("activeDeviceId", app, body.deviceId)
        # The released phone back after its hold ends the hold; another phone
        # registering leaves it, since the account holds several.
        if apps.read_field(held_user, "releasedDeviceId", app) == body.deviceId:
            user_patch.update(apps.written_fields(
                "releasedDeviceId", app, _base.firestore.DELETE_FIELD))
            user_patch.update(apps.written_fields(
                "releasedAt", app, _base.firestore.DELETE_FIELD))
        tx.update(user_ref, user_patch)

    def _contended():
        raise Refusal(errors.CLAIM_CONTENDED)

    _run_tx(_register, on_contended=_contended)
    user = _load_user(uid)
    if user:
        ensure_entitlement(user, body.deviceId)
    return {**dev, "deviceId": body.deviceId, "rotated": list(rotated),
            "healed": bool(healed and healed[0])}


# ---------------- challenge / nonce ----------------
def issue_nonce(uid: str, device_id: str) -> str:
    nonce = secrets.token_urlsafe(32)
    _set_doc(db().collection("challenges").document(nonce), {
        "uid": uid,
        "deviceId": device_id,
        "expireAt": _now() + timedelta(seconds=120),
        "schemaVersion": SCHEMA_VERSION,
    })
    return nonce


def consume_nonce(nonce: str, uid: str, device_id: str) -> bool:
    """Atomically claim a challenge. Delete only when uid/device/expiry match.

    A plain get→delete race let two concurrent callers both read a live nonce;
    wrapping in a transaction means only one commit wins. Invalid callers must
    not delete — otherwise a wrong-uid probe would burn a valid challenge.
    """
    ref = db().collection("challenges").document(nonce)
    # More than the default five attempts: this document is the hottest in the
    # service and losing the race means denying a legitimate caller.

    @_base.firestore.transactional
    def _consume(tx):
        snap = _get(ref, tx)
        if not snap.exists:
            return False
        d = snap.to_dict()
        if d.get("uid") != uid or d.get("deviceId") != device_id:
            return False
        # A used client nonce (`claim_client_nonce`) shares this collection.
        # It is a record of a spent nonce, not a challenge: with client
        # nonces switched off (`parse_client_nonce`) it would otherwise be
        # accepted once more as one, and deleted.
        if d.get("kind") == "client":
            return False
        exp = d.get("expireAt")
        if not (exp and exp > _now()):
            return False
        tx.delete(ref)
        return True

    # Concurrent consumption of one nonce is by definition a replay, and we
    # could not commit — so deny. Fail closed: claiming the nonce here would be
    # the one outcome that breaks single-use. A legitimate client never races
    # itself on a nonce; it just fetches a fresh challenge.
    return _run_tx(_consume, on_contended=lambda: False)


def claim_client_nonce(nonce: str, uid: str, device_id: str, expire_at) -> bool:
    """Record a client-minted (timestamped) nonce as used. False = replay.

    One `create()` — it fails with AlreadyExists when the document is there,
    so two concurrent claims cannot both win, without a read or a transaction.
    Stored in `challenges` so the existing TTL policy on `expireAt` reclaims it;
    server-issued IDs never contain '.', so the two kinds cannot collide.
    """
    try:
        _create_doc(db().collection("challenges").document(nonce), {
            "uid": uid,
            "deviceId": device_id,
            "kind": "client",
            "expireAt": expire_at,
            "schemaVersion": SCHEMA_VERSION,
        })
        return True
    except AlreadyExists:
        return False
