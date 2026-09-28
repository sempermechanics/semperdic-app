"""Registered devices and the challenge / client nonces they sign with.
"""
import secrets
from datetime import timedelta

from google.api_core.exceptions import AlreadyExists

from .. import apps, statuses
from ..models import DeviceReg

from . import _base
from ._base import (
    db,
    _load_user,
    _now,
    _run_tx,
    SCHEMA_VERSION,
)
from .entitlement import (
    ensure_entitlement,
)


# ---------------- devices ----------------
def get_device(device_id: str):
    snap = db().collection("devices").document(device_id).get()
    return {**snap.to_dict(), "deviceId": device_id} if snap.exists else None


def user_has_active_device(uid: str) -> bool:
    """Whether the account has a phone registered for any app."""
    u = db().collection("users").document(uid).get()
    if not u.exists:
        return False
    doc = u.to_dict() or {}
    return any(doc.get(apps.field("activeDeviceId", app)) for app in apps.ALL)


def _release_patch(device_id: str, app: str = apps.SEMPER) -> dict:
    """The `users/{uid}` fields that release `device_id` from `app`: the binding
    dropped and the id stamped, so `repo.devlock.released_device_held` holds it
    off while the new phone registers. Shared by a lock clear and a staff phone
    release."""
    return {
        apps.field("activeDeviceId", app): _base.firestore.DELETE_FIELD,
        apps.field("releasedDeviceId", app): device_id,
        apps.field("releasedAt", app): _now(),
    }


def _retire_device(batch, device_ref, status: str) -> None:
    """Retire `device_ref` in `batch`, so its id stops counting against another account.

    The one way a device leaves service: superseded by a registration
    (`register_device`) or a lock clear (`repo.seats`), or revoked with its
    account (`set_user_status`). The caller drops the app's `activeDeviceId`
    field on `users/{uid}` in the same batch.
    """
    batch.update(device_ref, {
        "status": status,
        "revokedAt": _base.firestore.SERVER_TIMESTAMP,
    })




def register_device(uid: str, body: DeviceReg, app: str = apps.SEMPER) -> dict:
    dev = {
        "uid": uid,
        "app": app,
        "publicKeyPem": body.publicKeyPem,
        "status": statuses.DEVICE_ACTIVE,
        "model": body.model,
        "osVersion": body.osVersion,
        "appVersion": body.appVersion,
        "registeredAt": _base.firestore.SERVER_TIMESTAMP,
        "lastAssertionAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    user_ref = db().collection("users").document(uid)
    # Only one device may be active per account and app (the app's
    # `activeDeviceId` field is the single binding, ADR-010). Superseded
    # devices used to keep status ACTIVE forever, so an account accumulated
    # stale ACTIVE docs that still held their device ids against other
    # accounts via the device_in_use check.
    active_field = apps.field("activeDeviceId", app)
    user_snap = user_ref.get()
    previous = (user_snap.to_dict() or {}).get(active_field) if user_snap.exists else None
    batch = db().batch()
    if previous and previous != body.deviceId:
        _retire_device(batch, db().collection("devices").document(previous),
                       statuses.DEVICE_SUPERSEDED)
    batch.set(db().collection("devices").document(body.deviceId), dev)
    # A registration ends any release hold: either the new phone has arrived,
    # or the hold has run out and the old one is back.
    batch.update(user_ref, {
        active_field: body.deviceId,
        apps.field("releasedDeviceId", app): _base.firestore.DELETE_FIELD,
        apps.field("releasedAt", app): _base.firestore.DELETE_FIELD,
    })
    batch.commit()
    user = _load_user(uid)
    if user:
        ensure_entitlement(user, body.deviceId)
    return {**dev, "deviceId": body.deviceId}


# ---------------- challenge / nonce ----------------
def issue_nonce(uid: str, device_id: str) -> str:
    nonce = secrets.token_urlsafe(32)
    db().collection("challenges").document(nonce).set(
        {
            "uid": uid,
            "deviceId": device_id,
            "expireAt": _now() + timedelta(seconds=120),
            "schemaVersion": SCHEMA_VERSION,
        }
    )
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
        snap = ref.get(transaction=tx)
        if not snap.exists:
            return False
        d = snap.to_dict()
        if d.get("uid") != uid or d.get("deviceId") != device_id:
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
        db().collection("challenges").document(nonce).create(
            {
                "uid": uid,
                "deviceId": device_id,
                "kind": "client",
                "expireAt": expire_at,
                "schemaVersion": SCHEMA_VERSION,
            }
        )
        return True
    except AlreadyExists:
        return False
