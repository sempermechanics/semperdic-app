"""Redeeming a typed licence key onto an account.
"""
from .. import apps
from ..licenses import (
    KIND_INDIVIDUAL,
    KIND_INSTITUTION,
    MODE_LICENSED,
    key_hash,
    key_prefix,
    normalize_kind,
)

from . import _base
from ._base import (
    db,
    _license_mode,
    _license_past_grace,
    _load_user,
    _mode_patch,
)
from .devlock import (
    bind_device_lock,
    _may_bind,
)
from .user_config import (
    resolve_user_config,
)
from .holders import (
    licence_held_by,
)
from .claims import (
    claim_seat,
    _emails_match,
    _license_mirror_patch,
    _public_claim_error,
)


def _email_domain(email: str) -> str:
    email = (email or "").strip().lower()
    return email.rsplit("@", 1)[-1] if "@" in email else ""


def _activate_individual(user: dict, uid: str, email: str, device_id: str, lic: dict, ref, key: str,
                         app: str):
    status = lic.get("status") or "unused"
    if status == "revoked":
        return "license_revoked", None
    if not _emails_match(lic.get("emailLock"), email):
        return "license_email_mismatch", None
    lock_field = apps.field("deviceIdLock", app)
    locked = lic.get(lock_field) or ""
    if not locked and device_id and _may_bind(user, device_id, app):
        # Bind-on-first-use, the same rule the request path applies. A licence
        # minted against an address alone has no lock, so a key typed here for
        # support recovery has to be able to set one rather than demand it.
        # Re-read rather than assume: bind_device_lock is first-writer-wins,
        # and losing the race means some other device owns this licence. A
        # bind starved with the lock still empty raises DeviceLockContended
        # (503) instead, so this never answers a mismatch nobody holds. A
        # device that may not take the lock (`_may_bind`) is answered as a
        # mismatch, as the request path leaves it unbound.
        bind_device_lock(ref, device_id, app)
        locked = (ref.get().to_dict() or {}).get(lock_field) or ""
    if locked != device_id:
        return "license_device_mismatch", None
    if status == "redeemed" and lic.get("redeemedByUid") != uid:
        return "license_already_redeemed", None

    mode = _license_mode(lic)
    license_id = ref.id
    user_patch = {
        **_mode_patch(mode),
        "licenseId": license_id,
        "licenseKind": KIND_INDIVIDUAL,
        "licensePrefix": lic.get("keyPrefix") or key_prefix(key),
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }
    user_patch.update(_license_mirror_patch(lic))

    license_patch = {}
    if status == "unused":
        license_patch = {
            "status": "redeemed",
            "redeemedByUid": uid,
            "redeemedAt": _base.firestore.SERVER_TIMESTAMP,
        }

    batch = db().batch()
    batch.update(db().collection("users").document(uid), user_patch)
    if license_patch:
        batch.update(ref, license_patch)
    batch.commit()

    merged = _apply_patch(user, user_patch)
    return "", resolve_user_config(merged)


def _activate_institution(user: dict, uid: str, email: str, device_id: str, lic: dict, ref, key: str,
                          app: str):
    if (lic.get("status") or "active") == "revoked":
        return "license_revoked", None
    domain_lock = (lic.get("domainLock") or "").strip().lower()
    if not domain_lock or _email_domain(email) != domain_lock:
        return "license_email_mismatch", None

    license_id = ref.id
    user_patch = {
        **_mode_patch(MODE_LICENSED),
        "licenseId": license_id,
        "licenseKind": KIND_INSTITUTION,
        "licensePrefix": lic.get("keyPrefix") or key_prefix(key),
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }
    user_patch.update(_license_mirror_patch(lic))

    err = claim_seat(license_id, uid, email, device_id, user_patch, app=app)
    if err:
        return _public_claim_error(err, "claim_contended"), None

    merged = _apply_patch(user, user_patch)
    return "", resolve_user_config(merged)


def _apply_patch(user: dict, patch: dict) -> dict:
    merged = {**user, **{k: v for k, v in patch.items() if v is not _base.firestore.DELETE_FIELD}}
    for k, v in patch.items():
        if v is _base.firestore.DELETE_FIELD:
            merged.pop(k, None)
    return merged


def activate_license(uid: str, email: str, device_id: str, key: str,
                     app: str = apps.SEMPER) -> tuple[str, dict | None]:
    """Redeem a key onto this uid. Returns (error_code, config_or_none).

    Empty error_code means success. Branches on the license's `kind`:
    - individual: single email+device lock, same behaviour as before
      institution licensing existed. Same uid re-entering the same key is OK.
    - institution: verified-email domain match against `domainLock`; a seat is
      created (or re-validated) in `licenses/{id}/seats/{uid}`, capped at
      `maxSeats` when set. Re-entry from the same device is idempotent; from a
      different device it re-locks the seat only when no device is locked yet.

    Either way the device is checked against `app`'s lock (ADR-010).
    """
    user = _load_user(uid)
    if not user:
        return "user_not_found", None
    license_id = key_hash(key)
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return "license_not_found", None
    lic = snap.to_dict() or {}
    if _license_past_grace(lic):
        # Without this the activation "succeeds": the past expiry is mirrored
        # onto the user, effective_mode immediately resolves demo, and the
        # caller is handed err="" with a demo config and no explanation.
        return "license_expired", None
    if licence_held_by(email, user=user, exclude_id=license_id):
        # One licence per person. Before this the key simply won: an
        # institution seat or an individual licence replaced whatever the
        # account held, and the licence it left stayed `redeemed` in their
        # name. Asked before the kind branch so both refuse alike.
        return "already_licensed", None
    if normalize_kind(lic.get("kind")) == KIND_INSTITUTION:
        return _activate_institution(user, uid, email, device_id, lic, ref, key, app)
    return _activate_individual(user, uid, email, device_id, lic, ref, key, app)
