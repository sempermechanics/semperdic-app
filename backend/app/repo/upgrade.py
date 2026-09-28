"""Turning an individual licence into an institution licence.

A lab buys one licence, then the department buys for everyone. The person
who held the individual licence must not lose a day of it, their device, or
their data, and must not end up holding two licences. So the institution
licence is minted carrying the individual one's terms, the holder is seated
on it in the same transaction that moves their account, and only then is the
individual licence revoked — with `supersededBy` naming its replacement, so
the audit trail and the desk can follow the chain.
"""
from .. import apps, errors
from ..licenses import (
    KIND_INDIVIDUAL,
    MODE_DEMO,
    STATUS_REVOKED,
    normalize_kind,
)

from . import _base
from ._base import (
    db,
    get_license,
    _license_mode,
    _load_user,
)
from .claims import (
    claim_seat,
    _institution_member_patch,
    _public_claim_error,
)
from .invites import (
    _delete_license_invites,
    _write_invite,
)
from .mint import (
    create_institution_license,
)
from .license_admin import (
    get_license_public,
    revoke_license,
)


def _email_domain(email: str) -> str:
    email = (email or "").strip().lower()
    return email.rsplit("@", 1)[-1] if "@" in email else ""


def convert_to_institution(
    license_id: str,
    *,
    domain_lock: str,
    admin_emails: list[str],
    max_seats: int | None,
    seating: str,
    admin_uid: str,
) -> tuple[str, dict | None]:
    """Replace an individual licence with an institution one. Returns
    (error, {"key", "license", "claimedByUid", "supersededId"}).

    The new licence copies the expiry, grace, support date, analysis cap and
    note. Its holder — the account the old licence is on, or, before anyone
    has signed in, the address it is locked to — must be on `domain_lock`,
    since that is who the new licence is for. The holder is seated with the
    old licence's device lock, so their phone keeps working without a new
    sign-in. Someone who has not signed in yet gets the invite moved instead.

    Nothing is revoked until the holder is seated: a failed claim deletes the
    new licence and leaves the old one as it was.
    """
    lic = get_license(license_id)
    if not lic:
        return errors.LICENSE_NOT_FOUND, None
    if normalize_kind(lic.get("kind")) != KIND_INDIVIDUAL or _license_mode(lic) == MODE_DEMO:
        return errors.LICENSE_NOT_CONVERTIBLE, None
    if (lic.get("status") or "") == STATUS_REVOKED:
        return errors.LICENSE_REVOKED, None

    holder_uid = lic.get("redeemedByUid") or ""
    holder = _load_user(holder_uid) if holder_uid else None
    if holder and (holder.get("licenseId") or "") != license_id:
        # They have moved to another licence since; this one is theirs no more.
        holder = None
    address = ((holder or {}).get("email") or lic.get("emailLock") or "").strip().lower()
    if address and _email_domain(address) != domain_lock:
        return errors.CONVERT_DOMAIN_MISMATCH, None

    minted = create_institution_license(
        domain_lock=domain_lock,
        admin_emails=admin_emails,
        created_by_uid=admin_uid,
        max_seats=max_seats,
        seating=seating,
        expires_at=lic.get("expiresAt"),
        grace_days=lic.get("graceDays"),
        support_until=lic.get("supportUntil"),
        max_analyses=lic.get("maxAnalyses"),
        note=lic.get("note") or "",
    )
    new_id = minted["license"]["id"]
    new_ref = db().collection("licenses").document(new_id)
    claimed = ""
    if holder:
        # Every app's device comes with the holder (ADR-010), so neither
        # app has to be signed in again on the phone it was already on.
        err = claim_seat(new_id, holder_uid, address, lic.get("deviceIdLock") or "",
                         _institution_member_patch(new_id, get_license(new_id) or {}),
                         carried_locks={a: lic.get(apps.field("deviceIdLock", a)) or ""
                                        for a in apps.ALL})
        if err:
            new_ref.delete()
            return _public_claim_error(err, errors.CLAIM_CONTENDED), None
        claimed = holder_uid
    elif address:
        # Not signed in yet: the promise moves to the new licence. The old
        # invite goes first — `_write_invite` refuses an address promised
        # to another live licence.
        _delete_license_invites(license_id, lic.get("emailLock") or "")
        _write_invite(new_id, address, admin_uid)

    # The holder's account points at the new licence now, so the revoke's
    # demotion (guarded on `licenseId`) passes them by.
    revoke_license(license_id, admin_uid)
    db().collection("licenses").document(license_id).update({
        "supersededBy": new_id,
        "supersededAt": _base.firestore.SERVER_TIMESTAMP,
    })
    return "", {
        "key": minted["key"],
        "license": get_license_public(new_id),
        "claimedByUid": claimed,
        "supersededId": license_id,
    }
