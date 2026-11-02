"""Redeeming a typed licence key onto an account.
"""
from .. import apps, errors
from ..errors import Refusal
from ..licenses import (
    key_hash,
    key_prefix,
    email_domain,
    normalize_email,
)

from ._base import (
    _is_institution,
    _is_revoked,
    _apply_patch,
    db,
    _license_past_grace,
    _load_user,
    _get,
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
    verified_address,
)
from .claims import (
    claim_individual_license,
    claim_seat,
    _emails_match,
    _member_patch,
    _public_claim_error,
)


def _activate_individual(user: dict, uid: str, email: str, device_id: str, lic: dict, ref,
                         app: str) -> dict:
    # Asked before the device, so a revoked or someone else's licence says
    # so rather than reading as a device mismatch. The claim asks again,
    # inside its transaction.
    if _is_revoked(lic):
        raise Refusal(errors.LICENSE_REVOKED)
    if not _emails_match(lic.get("emailLock"), email):
        raise Refusal(errors.LICENSE_EMAIL_MISMATCH)
    locked = apps.read_field(lic, "deviceIdLock", app) or ""
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
        locked = apps.read_field(_get(ref).to_dict(), "deviceIdLock", app) or ""
    # No device (a claim code from the web account page, `device_id` ""):
    # nothing to compare and nothing bound. The account takes the licence and
    # the lock stays as it is, to be checked or bound on the request path,
    # as `claim_seat` already treats a seat claimed without a device. The app
    # route always names its device, so it is checked as before.
    if device_id and locked != device_id:
        raise Refusal(errors.LICENSE_DEVICE_MISMATCH)
    # The same transaction an invite or a staff mint attaches through: the
    # single-redeemer check, the licence's `redeemed` stamp and the account
    # settle together, and the Demo key the account held goes with them.
    patch = _member_patch(ref.id, lic)
    err = claim_individual_license(ref.id, uid, email, patch)
    if err:
        raise Refusal(_public_claim_error(err))
    return resolve_user_config(_apply_patch(user, patch), app)


def _activate_institution(user: dict, uid: str, email: str, device_id: str, lic: dict, ref,
                          app: str) -> dict:
    if _is_revoked(lic):
        raise Refusal(errors.LICENSE_REVOKED)
    domain_lock = (lic.get("domainLock") or "").strip().lower()
    if not domain_lock or email_domain(email) != domain_lock:
        raise Refusal(errors.LICENSE_EMAIL_MISMATCH)
    patch = _member_patch(ref.id, lic)
    err = claim_seat(ref.id, uid, email, device_id, patch, app=app)
    if err:
        raise Refusal(_public_claim_error(err))
    return resolve_user_config(_apply_patch(user, patch), app)


def activate_license(uid: str, email: str, device_id: str, key: str,
                     app: str = apps.SEMPER) -> dict:
    """Redeem a key onto this uid. Returns the account's config, or raises
    `Refusal`. Branches on the license's `kind`:
    - individual: single email+device lock, same behaviour as before
      institution licensing existed. Same uid re-entering the same key is OK.
    - institution: verified-email domain match against `domainLock`; a seat is
      created (or re-validated) in `licenses/{id}/seats/{uid}`, capped at
      `maxSeats` when set. Re-entry from the same device is idempotent; from a
      different device it re-locks the seat only when no device is locked yet.

    Either way the device is checked against `app`'s lock (ADR-010). An
    empty `device_id` is a redemption with no device (the web claim,
    `POST /v1/me/claim`): every other check applies, and no lock is compared
    or bound.
    """
    user = _load_user(uid)
    if not user:
        raise Refusal(errors.USER_NOT_FOUND)
    # The email and domain locks are asked of a proven address, whichever
    # route redeems the key: the stored `email` follows the sign-in token
    # whether or not it was verified, so an account that typed an address it
    # does not own would pass an institution's domain lock with it. The
    # `email` a caller passes has to be that proven address.
    address = verified_address(user)
    if not address:
        raise Refusal(errors.EMAIL_NOT_VERIFIED)
    if normalize_email(email) != address:
        raise Refusal(errors.LICENSE_EMAIL_MISMATCH)
    email = address
    license_id = key_hash(key)
    ref = db().collection("licenses").document(license_id)
    snap = _get(ref)
    if not snap.exists:
        raise Refusal(errors.LICENSE_NOT_FOUND)
    lic = snap.to_dict() or {}
    # A licence record that predates `keyPrefix` still shows the typed key's.
    lic = {**lic, "keyPrefix": lic.get("keyPrefix") or key_prefix(key)}
    if _license_past_grace(lic):
        # Without this the activation "succeeds": the past expiry is mirrored
        # onto the user, effective_mode immediately resolves demo, and the
        # caller is handed err="" with a demo config and no explanation.
        raise Refusal(errors.LICENSE_EXPIRED)
    if licence_held_by(email, user=user, exclude_id=license_id, app=lic.get("app")):
        # One licence per person. Before this the key simply won: an
        # institution seat or an individual licence replaced whatever the
        # account held, and the licence it left stayed `redeemed` in their
        # name. Asked before the kind branch so both refuse alike.
        raise Refusal(errors.ALREADY_LICENSED)
    if lic.get("app"):
        # A licence that names its app licenses phones by Activate
        # (ADR-033), never by the key: the account takes it, and no lock is
        # compared or bound, as for a redemption with no device.
        device_id = ""
    if _is_institution(lic):
        return _activate_institution(user, uid, email, device_id, lic, ref, app)
    return _activate_individual(user, uid, email, device_id, lic, ref, app)
