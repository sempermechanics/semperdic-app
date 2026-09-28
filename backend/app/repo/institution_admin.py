"""Institution licence self-service for the licence's own IT admins.
"""
from .. import apps
from ..licenses import (
    KIND_INSTITUTION,
    normalize_kind,
)

from ._base import (
    db,
    get_license,
)
from .claims import (
    claim_seat,
    _institution_member_patch,
    _public_claim_error,
)
from .holders import (
    licence_held_by,
)
from .invites import (
    find_user_by_email,
    invite_institution_member,
)
from .mint import (
    _license_public,
)


# ---------------- institution seat administration ----------------
# Reached only via routers/institutions.py, gated on current_user + APPROVED +
# verified email present in the license's adminEmails — deliberately NOT
# verified_device and NOT Semper role=admin. Mint/whole-key-revoke stays on
# the existing device-attested admin path in routers/admin.py.

def is_institution_admin(license_doc: dict, email: str) -> bool:
    admin_emails = {e.strip().lower() for e in (license_doc.get("adminEmails") or [])}
    return bool(email) and email.strip().lower() in admin_emails


def list_licenses_administered_by(email: str) -> list[dict]:
    """Every live institution licence that names this address in `adminEmails`.

    The inverse of `is_institution_admin`, which can only answer for a licence
    id you already hold. Sign-in has an address and nothing else, so without
    this a member of institution IT has no way to reach their own roster
    except by being told the id out of band.

    `kind` and `status` are filtered in Python rather than added to the query:
    one address administers a handful of licences at most, and each extra
    equality clause on top of `array_contains` costs another composite index
    for no measurable gain.

    Same redaction as `institution_license_summary` — no key plaintext.
    """
    wanted = (email or "").strip().lower()
    if not wanted:
        return []
    out = []
    q = db().collection("licenses").where("adminEmails", "array_contains", wanted)
    for d in q.stream():
        data = d.to_dict() or {}
        if normalize_kind(data.get("kind")) != KIND_INSTITUTION:
            continue
        if (data.get("status") or "") == "revoked":
            continue
        out.append(_license_public(d.id, data))
    out.sort(key=lambda lic: lic["id"])
    return out


def add_institution_member(license_id: str, email: str,
                           invited_by_uid: str = "") -> tuple[str, dict | None, dict | None]:
    """Put someone on an institution license's roster. Returns (error, seat, invite).

    On an assigned license this entitles them immediately. On a floating one
    it makes them eligible; they still check out a lease to work, and adding
    a member therefore consumes no slot.

    Exactly one of `seat` and `invite` is set on success. An address with no
    account yet becomes a pending invite rather than a `user_not_found` error:
    IT works from a list of addresses and cannot make people sign up first, so
    refusing them was pushing a scheduling problem onto the wrong person.

    Idempotent for an active seat, and re-adding someone previously revoked
    gives them a fresh slot. A *disabled* seat is refused with
    `license_seat_disabled` — disable is a deliberate hold that IT lifts with
    `enabled=true`, and silently undoing it here would make the two routes
    fight over the same state.
    """
    lic = get_license(license_id)
    if not lic:
        return "license_not_found", None, None
    user = find_user_by_email(email)
    # One licence per person: someone who holds, or is promised, a different
    # live licence is refused rather than moved onto this roster. Their own
    # seat on this licence is excluded, so re-adding a member stays a no-op.
    if licence_held_by(email, user=user or {}, exclude_id=license_id):
        return "member_already_licensed", None, None
    if not user:
        err, invite = invite_institution_member(license_id, email, invited_by_uid)
        return err, None, invite
    uid = user["uid"]

    # No device lock: IT adds a member before that member has picked a device,
    # and the lock is set the first time they actually use the license.
    err = claim_seat(license_id, uid, user.get("email") or email, "",
                     _institution_member_patch(license_id, lic))
    if err:
        return _public_claim_error(err, "claim_contended"), None, None
    seats = list_institution_seats(license_id)
    return "", next((s for s in seats if s["uid"] == uid), None), None


def institution_license_summary(license_id: str) -> dict | None:
    """Public (no key plaintext) summary of one institution license, for IT
    self-service — same redaction as the Semper-staff admin listing, scoped to
    callers who already passed the adminEmails membership check."""
    lic = get_license(license_id)
    return _license_public(license_id, lic) if lic else None


def list_institution_seats(license_id: str) -> list[dict]:
    out = []
    for doc in db().collection("licenses").document(license_id).collection("seats").stream():
        s = doc.to_dict() or {}
        out.append({
            "uid": doc.id,
            "email": s.get("email") or "",
            **{apps.field("deviceIdLock", app): s.get(apps.field("deviceIdLock", app)) or ""
               for app in apps.ALL},
            "status": s.get("status") or "active",
            # The lease is the point of the floating roster view: without it
            # IT cannot see who is actually using a seat right now, only who
            # is allowed to. Null on an assigned licence, which has no leases.
            "leaseExpiresAt": s.get("leaseExpiresAt"),
            "lastHeartbeatAt": s.get("lastHeartbeatAt"),
            "createdAt": s.get("createdAt"),
            "updatedAt": s.get("updatedAt"),
        })
    return out
