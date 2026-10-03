"""Minting licence keys: the Demo key at sign-in, and ops mints of individual
and institution keys.
"""
import logging

from .. import apps, statuses
from ..config import settings
from ..licenses import (
    DURATION_PERPETUAL,
    DURATION_TIMED,
    SEATING_ASSIGNED,
    KIND_INDIVIDUAL,
    KIND_INSTITUTION,
    MODE_DEMO,
    MODE_LICENSED,
    generate_key,
    grace_ends_at,
    key_hash,
    key_prefix,
    legacy_plan,
    normalize_duration,
    normalize_email,
    normalize_kind,
    normalize_seating,
)

from . import _base
from ._base import (
    db,
    get_license,
    _license_mode,
    _mode_patch,
    _run_tx,
    SCHEMA_VERSION,
)
from .claims import (
    claim_individual_license,
    _individual_member_patch,
    _public_claim_error,
)
from .holders import (
    licence_is_live,
)
from .invites import (
    find_user_by_email,
    _invite_ref,
    _write_invite,
)


log = logging.getLogger("semper.firestore")


def _license_public(license_id: str, data: dict) -> dict:
    return {
        "id": license_id,
        "keyPrefix": data.get("keyPrefix") or "",
        "kind": normalize_kind(data.get("kind")),
        "mode": _license_mode(data),
        # Pre-rename mirror; see resolve_user_config.
        "plan": legacy_plan(_license_mode(data)),
        "status": data.get("status") or "unused",
        "emailLock": data.get("emailLock") or "",
        **{apps.field("deviceIdLock", app): data.get(apps.field("deviceIdLock", app)) or ""
           for app in apps.ALL},
        "domainLock": data.get("domainLock") or "",
        "adminEmails": list(data.get("adminEmails") or []),
        "maxSeats": data.get("maxSeats"),
        "seating": normalize_seating(data.get("seating")),
        # seatsUsed counts the roster; leasesActive counts who is using it
        # right now. On an assigned license they are the same number, so only
        # the first is meaningful.
        "seatsUsed": data.get("seatsUsed", 0),
        "leasesActive": data.get("leasesActive", 0),
        "createdAt": data.get("createdAt"),
        "createdByUid": data.get("createdByUid") or "",
        "redeemedAt": data.get("redeemedAt"),
        "redeemedByUid": data.get("redeemedByUid") or "",
        "duration": normalize_duration(
            data.get("duration"), has_expiry=data.get("expiresAt") is not None,
        ),
        "expiresAt": data.get("expiresAt"),
        "graceDays": data.get("graceDays"),
        "graceEndsAt": grace_ends_at(data.get("expiresAt"), data.get("graceDays") or 0),
        # Informational; never gates. A perpetual license whose support has
        # lapsed still grants full use.
        "supportUntil": data.get("supportUntil"),
        "maxAnalyses": data.get("maxAnalyses"),
        "note": data.get("note") or "",
        # Set when an individual licence was converted to an institution one
        # (repo/upgrade.py): the id of the licence that replaced it.
        "supersededBy": data.get("supersededBy") or "",
    }


def _write_license(
    *,
    mode: str,
    email_lock: str,
    device_id_lock: str,
    created_by_uid: str,
    status: str,
    kind: str = KIND_INDIVIDUAL,
    domain_lock: str = "",
    admin_emails: list[str] | None = None,
    max_seats: int | None = None,
    seating: str = SEATING_ASSIGNED,
    redeemed_by_uid: str | None = None,
    expires_at=None,
    grace_days: int | None = None,
    support_until=None,
    max_analyses: int | None = None,
    note: str = "",
) -> tuple[str, str, dict]:
    """Mint a key, persist the hash, return (plaintext, license_id, stored)."""
    key = generate_key()
    license_id = key_hash(key)
    stored = {
        "keyPrefix": key_prefix(key),
        # Redundant with the document id, which is this same hash. Recorded as
        # a field so licenses can later move to opaque ids — needed once a
        # license may be issued with no key at all.
        "keyHash": license_id,
        "kind": kind,
        "mode": mode,
        # Pre-rename mirror; see _mode_patch.
        "plan": legacy_plan(mode),
        "status": status,
        "emailLock": (email_lock or "").strip().lower(),
        "deviceIdLock": device_id_lock,
        "createdAt": _base.firestore.SERVER_TIMESTAMP,
        "createdByUid": created_by_uid,
        "schemaVersion": SCHEMA_VERSION,
        "note": note or "",
    }
    if kind == KIND_INSTITUTION:
        stored["domainLock"] = (domain_lock or "").strip().lower()
        stored["adminEmails"] = [
            (e or "").strip().lower() for e in (admin_emails or []) if (e or "").strip()
        ]
        if max_seats is not None:
            stored["maxSeats"] = int(max_seats)
        stored["seatsUsed"] = 0
        stored["seating"] = normalize_seating(seating)
        stored["leasesActive"] = 0
    if expires_at is not None:
        stored["expiresAt"] = expires_at
        stored["duration"] = DURATION_TIMED
        # Stamped explicitly at mint so the value in force is recorded on the
        # document rather than inherited from whatever the env says later.
        stored["graceDays"] = (
            settings.LICENSE_GRACE_DAYS_DEFAULT if grace_days is None else max(0, int(grace_days))
        )
    else:
        stored["duration"] = DURATION_PERPETUAL
    if support_until is not None:
        # Informational only. A perpetual license whose support has lapsed
        # still grants full use — nothing reads this to gate anything.
        stored["supportUntil"] = support_until
    if max_analyses is not None:
        stored["maxAnalyses"] = int(max_analyses)
    if redeemed_by_uid:
        stored["redeemedByUid"] = redeemed_by_uid
        stored["redeemedAt"] = _base.firestore.SERVER_TIMESTAMP
    ref = db().collection("licenses").document(license_id)
    ref.set(stored)
    # Read back rather than return what was written: `createdAt` (and
    # `redeemedAt`) above are the SERVER_TIMESTAMP sentinel, and the mint
    # routes hand this dict to the response, where the sentinel cannot be
    # serialised — the licence was written and the request still failed.
    return key, license_id, (ref.get().to_dict() or stored)


def ensure_demo_license(user: dict, device_id: str | None) -> dict:
    """Issue a redeemed Demo key once the account is approved, verified, and bound.

    Idempotent. Licensed accounts are left alone. The plaintext Demo key is
    not returned — the user never types it; the record exists so the seat is
    locked to this email and device.

    The attachment is a compare-and-set on the stored document, not a blind
    update. `user` was read before this request began, and several requests
    arrive together at app launch: one that lost the race to claim a real
    licence — the delivery path for every individual licence and every invited
    seat — falls through to here holding a stale copy, and a blind write would
    overwrite the entitlement granted moments earlier with a Demo key.
    """
    uid = user.get("uid")
    if not uid or user.get("licenseId"):
        return user
    if user.get("access_status") != statuses.ACCESS_APPROVED:
        return user
    if not user.get("emailVerified"):
        return user
    email = (user.get("email") or "").strip().lower()
    device = device_id or user.get("activeDeviceId") or user.get("claimedDeviceId")
    if not email or not device:
        return user
    _key, license_id, stored = _write_license(
        mode=MODE_DEMO,
        email_lock=email,
        device_id_lock=device,
        created_by_uid="system",
        status="redeemed",
        redeemed_by_uid=uid,
    )
    patch = {
        **_mode_patch(MODE_DEMO),
        "licenseId": license_id,
        "licenseKind": KIND_INDIVIDUAL,
        "licensePrefix": stored["keyPrefix"],
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }
    user_ref = db().collection("users").document(uid)

    @_base.firestore.transactional
    def _attach(tx) -> bool:
        snap = user_ref.get(transaction=tx)
        if not snap.exists or ((snap.to_dict() or {}).get("licenseId") or ""):
            return False
        tx.update(user_ref, patch)
        return True

    attached = _run_tx(_attach, on_contended=lambda: False)

    if attached:
        return {**user, **patch}
    # Something reached this account first. Drop the key nobody will ever hold
    # rather than leaving a redeemed Demo record pointing at no one, and answer
    # with what the account actually has — which on the losing side of an
    # invite claim is the licence, not demo.
    db().collection("licenses").document(license_id).delete()
    current = user_ref.get()
    if not current.exists:
        return user
    return {**user, **(current.to_dict() or {}), "uid": uid}


def create_individual_license(
    *,
    email_lock: str,
    device_id_lock: str = "",
    created_by_uid: str,
    expires_at=None,
    grace_days: int | None = None,
    support_until=None,
    max_analyses: int | None = None,
    note: str = "",
) -> dict:
    """Ops mint of an individual licensed key. Returns the plaintext key
    once; only the hash is stored.

    Also records a pending invite against `email_lock`, which is how the
    licence actually reaches the customer: they sign in with that address and
    `claim_pending_invite` attaches the licence on their first request. The
    key is the fallback for support recovery, not the delivery mechanism —
    nobody should have to type one.

    `device_id_lock` stays available for the rare mint against a device we
    already know, but is empty in normal use; the lock is bound at first
    sign-in instead.
    """
    key, license_id, stored = _write_license(
        mode=MODE_LICENSED,
        email_lock=email_lock,
        device_id_lock=device_id_lock,
        created_by_uid=created_by_uid,
        status="unused",
        kind="individual",
        expires_at=expires_at,
        grace_days=grace_days,
        support_until=support_until,
        max_analyses=max_analyses,
        note=note,
    )
    err, _ = _write_invite(license_id, email_lock, created_by_uid)
    if err:
        # The licence exists and the key in hand still redeems it, so this is
        # degraded delivery rather than a failed mint. `invite_exists` means
        # the address is already promised another licence — a real conflict
        # for ops to resolve, and one the returned licence makes visible.
        log.warning("individual licence %s minted without an invite: %s", license_id, err)
        claimed_uid, claim_err = "", ""
    else:
        claimed_uid, claim_err = _attach_to_existing_holder(license_id, stored, email_lock)
    return {"key": key, "license": _license_public(license_id, stored),
            "inviteError": err or "", "claimedByUid": claimed_uid,
            "claimError": claim_err}


def _holds_only_a_demo_key(user: dict) -> bool:
    """True when this account can take a licence right now: it points at no
    licence, at the auto-minted Demo key, or at one that is revoked, past its
    grace, or gone — `licence_is_live`, the one-licence rule's own test.

    The discriminator is the licence document, as in `_drop_superseded_demo`:
    a revoked holder is left demoted in place and still pointing at the real
    licence, so its stored `mode` says nothing about whether a new grant is
    welcome. A *live* non-demo licence is the one thing that must not be
    overwritten — that is a support question, not a mint."""
    license_id = user.get("licenseId") or ""
    if not license_id:
        return True
    return not licence_is_live(get_license(license_id))


def _attach_to_existing_holder(license_id: str, lic: dict, email: str) -> tuple[str, str]:
    """Hand a freshly minted individual licence to the account that already
    signed in at its address. Returns (uid, error) — both empty when nobody
    holds the address yet and the invite alone will deliver.

    The counterpart of `add_institution_member`'s existing-account branch, and
    needed for the same reason: `claim_pending_invite` runs only for an
    account holding no licence, and `ensure_demo_license` stamps one on the
    first request an approved account makes. Every account that predates
    licensing therefore holds a Demo key by the time ops mints for it, and an
    invite left for it would never be read. The claim is the same transaction
    the sign-in path uses (`claim_individual_license`), which consumes the
    invite and drops the superseded Demo key.

    Fail closed on an unverified address or an unapproved account: the address
    is the whole claim to the licence, exactly as at sign-in, and neither of
    those accounts holds a Demo key yet — so for them the invite still works
    the moment they qualify. A holder of a live non-demo licence is left as
    they are and reported, rather than silently moved between licences.
    """
    address = normalize_email(email)
    holder = find_user_by_email(address) if address else None
    if not holder:
        return "", ""
    if holder.get("access_status") != statuses.ACCESS_APPROVED or not holder.get("emailVerified"):
        return "", ""
    if not _holds_only_a_demo_key(holder):
        return "", "holder_already_licensed"
    err = claim_individual_license(
        license_id, holder["uid"], address,
        _individual_member_patch(license_id, lic), invite_ref=_invite_ref(address),
    )
    if err:
        # A lost race here has no next request to fall back on — the holder's
        # Demo key short-circuits the sign-in path — so say so plainly rather
        # than leaking the private marker. The licence is minted and the key
        # in hand redeems it through the support route.
        public = _public_claim_error(err, "claim_contended")
        log.warning("individual licence %s not attached to %s: %s",
                    license_id, holder["uid"], public)
        return "", public
    return holder["uid"], ""


def create_institution_license(
    *,
    domain_lock: str,
    admin_emails: list[str],
    created_by_uid: str,
    max_seats: int | None = None,
    seating: str = SEATING_ASSIGNED,
    expires_at=None,
    grace_days: int | None = None,
    support_until=None,
    max_analyses: int | None = None,
    note: str = "",
) -> dict:
    """Ops mint of an institution key. Seats are granted individually via
    activate_license as members of `domain_lock` redeem the same key; ops never
    pre-allocates seats. Returns the plaintext key once."""
    key, license_id, stored = _write_license(
        mode=MODE_LICENSED,
        email_lock="",
        device_id_lock="",
        created_by_uid=created_by_uid,
        status="active",
        kind=KIND_INSTITUTION,
        domain_lock=domain_lock,
        admin_emails=admin_emails,
        max_seats=max_seats,
        seating=seating,
        expires_at=expires_at,
        grace_days=grace_days,
        support_until=support_until,
        max_analyses=max_analyses,
        note=note,
    )
    return {"key": key, "license": _license_public(license_id, stored)}
