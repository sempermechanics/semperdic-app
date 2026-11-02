"""Accounts: first sign-in, auth-provider links, the admin user list and access status.
"""
from datetime import datetime, timedelta, timezone

from google.api_core.exceptions import AlreadyExists, NotFound

from .. import apps, errors, notify, statuses
from ..errors import Refusal
from ..config import settings
from ..licenses import (
    KIND_INSTITUTION,
    MODE_LICENSED,
    as_utc,
    normalize_email,
    normalize_kind,
)

from . import _base
from ._base import (
    _cursor_page,
    db,
    _load_user,
    SCHEMA_VERSION,
    _get,
    _stream,
    _commit,
    _set_doc,
    _create_doc,
    _update_doc,
    _seat_ref,
)
from .devices import (
    _release_devices,
    _retire_device,
    get_device,
)
from .entitlement import (
    ensure_entitlement,
)
from .user_config import (
    effective_mode,
)


# get_or_create_user's "last seen" write is throttled to this granularity — a
# proxied restore makes dozens of authenticated requests (one per download
# window) in quick succession, so an unconditional write here was dozens of
# Firestore writes to record a timestamp nobody reads at finer resolution.
_LAST_SEEN_THROTTLE = timedelta(hours=1)


# ---------------- users ----------------
def is_admin_email(claims: dict) -> bool:
    """Whether the token proves an address in ADMIN_EMAILS.

    Never grant admin off an unverified email — an email/password user could
    otherwise claim an admin address without proving they own it. Read off
    the token, not the stored profile: `deps.admin_user` asks it per request.
    """
    return bool(claims.get("email_verified")) and \
        claims.get("email", "").lower() in settings.ADMIN_EMAILS


def _auto_approved(claims: dict) -> bool:
    """Admins, blanket AUTO_APPROVE, or a *verified* email in AUTO_APPROVE_HD.

    Auto-approval always requires a verified email. Email-link and Google/
    Microsoft sign-ins are verified by construction; a new email/password user
    is not verified until they confirm, so they land PENDING until then.
    """
    if is_admin_email(claims):
        return True
    if not claims.get("email_verified"):
        return False
    if settings.AUTO_APPROVE:
        return True
    hd = settings.AUTO_APPROVE_HD
    if not hd:
        return False
    return claims.get("email", "").lower().endswith("@" + hd)


class DeviceInUseError(Exception):
    """This device is already bound to a different email."""


def _emails_conflict(left, right) -> bool:
    """True unless both sides name the same address.

    Fail closed. This gates adopting a device-bound account, so a caller that
    presents no address at all is a conflict, not a match: the old fail-open
    form ("either side blank — no conflict") let any sign-in without an email
    claim an account by device id alone. Two address-less identities still
    match, which is the only case that form got right.
    """
    return normalize_email(left) != normalize_email(right)


def _link_auth_uid(canonical_uid: str, firebase_sub: str) -> None:
    _set_doc(db().collection("auth_links").document(firebase_sub), {"uid": canonical_uid})
    if firebase_sub == canonical_uid:
        return
    ref = db().collection("users").document(canonical_uid)
    snap = _get(ref)
    if not snap.exists:
        return
    linked = list((snap.to_dict() or {}).get("linkedAuthUids") or [])
    if firebase_sub not in linked:
        linked.append(firebase_sub)
        _update_doc(ref, {"linkedAuthUids": linked})


def sign_in_uids(uid: str) -> list[str]:
    """Every Firebase Auth uid that signs in to account `uid`, its own first.

    An account adopted by a second Firebase sub (`_link_auth_uid`) is reached
    by more than one Auth user, and each of them holds the address and the
    name. Erasure deletes, and a suspension revokes, every one of them, not
    only the canonical uid. Read from both records of the link: the profile's
    `linkedAuthUids` and the `auth_links` that point at the account (one
    equality filter, served by the automatic index).
    """
    out = [uid]
    user = _load_user(uid) or {}
    links = db().collection("auth_links").where("uid", "==", uid)
    for sub in [*(user.get("linkedAuthUids") or []), *(snap.id for snap in _stream(links))]:
        if sub and sub not in out:
            out.append(sub)
    return out


def _user_for_device(device_id: str | None):
    if not device_id:
        return None
    dev = get_device(device_id)
    if dev and dev.get("status") == statuses.DEVICE_ACTIVE:
        found = _load_user(dev["uid"])
        if found:
            return found
    if dev and dev.get("status") != statuses.DEVICE_ACTIVE:
        # A phone retired from service (released, superseded, revoked) no
        # longer ties a new sign-in to the account that once held it. An
        # account's `claimedDeviceId` is the first phone it signed in from
        # and is never cleared, so read without this a released phone was
        # `device_in_use` to every other account for good. The claim alone
        # still counts while the phone has no record at all: an account that
        # signed in there but has not registered it yet.
        return None
    query = (
        db().collection("users")
        .where("claimedDeviceId", "==", device_id)
        .limit(1)
    )
    for snap in _stream(query):
        return {**snap.to_dict(), "uid": snap.id}
    return None


def _touch_existing(cur: dict, claims: dict, device_id: str | None) -> dict:
    uid = cur["uid"]
    ref = db().collection("users").document(uid)
    verified = bool(claims.get("email_verified"))
    provider = (claims.get("firebase") or {}).get("sign_in_provider")
    # Only the fields that actually changed — a write with nothing new to say
    # is exactly what the throttle below is trying to avoid.
    changed: dict = {}
    if provider and cur.get("signInProvider") != provider:
        changed["signInProvider"] = provider
    if cur.get("emailVerified") != verified:
        changed["emailVerified"] = verified
    # The stored address follows the token, as `emailVerified` does. Kept
    # from the first sign-in while the flag followed every token, an account
    # that squatted someone's address unverified, then changed its email to
    # one it owns and verified that, read as the victim's address verified:
    # their invite, their roster seat, their IT membership. A token with no
    # address leaves the stored one, and the flag above is then false.
    email = claims.get("email")
    if email and cur.get("email") != email:
        changed["email"] = email
    if cur.get("schemaVersion") != SCHEMA_VERSION:
        changed["schemaVersion"] = SCHEMA_VERSION
    # Keep admin role in sync with ADMIN_EMAILS for pre-existing users, both
    # ways: an address taken out of ADMIN_EMAILS (staff off-boarding) is
    # demoted on its next request, not left `admin` in the record for the
    # consoles and the app to read. `deps.admin_user` asks the token itself,
    # so authority goes with the redeploy either way; this keeps the stored
    # role from saying otherwise.
    if is_admin_email(claims):
        if cur.get("role") != "admin":
            changed["role"] = "admin"
    elif cur.get("role") == "admin":
        changed["role"] = "user"
    # A previously-PENDING user who has since verified a domain email (or been
    # made admin) is auto-approved on this sign-in.
    if cur.get("access_status") == statuses.ACCESS_PENDING and _auto_approved(claims):
        changed["access_status"] = statuses.ACCESS_APPROVED
    if device_id and not cur.get("claimedDeviceId"):
        changed["claimedDeviceId"] = device_id

    last_seen = cur.get("lastSeenAt")
    stale = last_seen is None or (datetime.now(timezone.utc) - last_seen) >= _LAST_SEEN_THROTTLE
    # This used to write lastSeenAt on every authenticated request — but a
    # single restore is now dozens of requests (one challenge+content pair
    # per adaptive download window, see DriveTransfer.nextWindowBytes), so
    # that was dozens of writes to say the same thing. "Last seen" only
    # needs hour granularity; skip the write when nothing else changed and
    # the timestamp is still fresh.
    #
    # One thing does want finer granularity, and asks for it explicitly. A
    # revoke stamps `seenCheckpointAt` (see _drop_user_to_demo_if_licensed),
    # and seat reconciliation reads "has the holder been back since?" off
    # lastSeenAt. Under the throttle alone that answer could stay "not yet"
    # for an hour after the device had in fact checked in and been demoted.
    # So a stamp predating the checkpoint is stale whatever its age: one
    # unthrottled write per revoked account, once, after which the answer is
    # exact instead of conservative.
    checkpoint, seen = as_utc(cur.get("seenCheckpointAt")), as_utc(last_seen)
    if checkpoint is not None and (seen is None or seen <= checkpoint):
        stale = True
    if not changed and not stale:
        return ensure_entitlement({**cur, "uid": uid}, device_id)

    patch = {**changed, "lastSeenAt": _base.firestore.SERVER_TIMESTAMP}
    _update_doc(ref, patch)
    if "email" in changed and verified:
        _refresh_seat_address(cur, changed["email"])
    return ensure_entitlement({**cur, **patch, "uid": uid}, device_id)


def _refresh_seat_address(user: dict, email: str) -> None:
    """Give the seat `user` holds on their institution licence their new
    verified address (TD-297).

    The seat keeps a copy of the address it was claimed with, and the roster
    and reconcile show that copy. An unverified address is not copied: it is
    no address (`holders.verified_address`), and the seat keeps the one that
    was proved. Display only; nothing is decided on the copy. Seats on
    licences the account has left keep the address it had there. A seat that
    is gone is left gone.
    """
    seated = {apps.read_field(user, "licenseId", app): apps.read_field(user, "licenseKind", app)
              for app in apps.ALL}
    for license_id, kind in seated.items():
        if not license_id or normalize_kind(kind) != KIND_INSTITUTION:
            continue
        try:
            _update_doc(_seat_ref(license_id, user["uid"]), {"email": normalize_email(email)})
        except NotFound:
            pass


def get_or_create_user(claims: dict, device_id: str | None = None) -> dict:
    uid = claims["sub"]
    ref = db().collection("users").document(uid)
    snap = _get(ref)
    if snap.exists:
        return _touch_existing({**snap.to_dict(), "uid": uid}, claims, device_id)

    link = _get(db().collection("auth_links").document(uid))
    if link.exists:
        canonical = (link.to_dict() or {}).get("uid")
        existing = _load_user(canonical) if canonical else None
        if existing:
            return _touch_existing(existing, claims, device_id)

    bound = _user_for_device(device_id)
    if bound:
        # Adopting a bound account hands over its sessions and entitlement, so
        # the address has to be proven rather than asserted: any provider can
        # mint a token carrying an address it never checked.
        # Both ends: the bound account's address must have been proven too.
        # An account that typed someone's address and never verified it would
        # otherwise be handed to them on a shared phone, with its own
        # password sign-in still reaching it.
        unproven = bool(bound.get("email")) and not (
            claims.get("email_verified") and bound.get("emailVerified"))
        if unproven or _emails_conflict(bound.get("email"), claims.get("email")):
            raise DeviceInUseError()
        _link_auth_uid(bound["uid"], uid)
        return _touch_existing(bound, claims, device_id)

    verified = bool(claims.get("email_verified"))
    provider = (claims.get("firebase") or {}).get("sign_in_provider")
    data = {
        "email": claims.get("email"),
        "emailVerified": verified,
        "signInProvider": provider,
        "displayName": claims.get("name"),
        "role": "admin" if is_admin_email(claims) else "user",
        "access_status": (statuses.ACCESS_APPROVED if _auto_approved(claims)
                          else statuses.ACCESS_PENDING),
        "activeDeviceId": None,
        "claimedDeviceId": device_id,
        "linkedAuthUids": [],
        "createdAt": _base.firestore.SERVER_TIMESTAMP,
        "lastSeenAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    try:
        # create(), not set(): two first-ever requests from one account race
        # here (the app fires /v1/me and /v1/config back to back on launch),
        # and an unconditional set let the loser overwrite the winner — resetting
        # an already-approved profile to PENDING and mailing support twice.
        _create_doc(ref, data)
    except AlreadyExists:
        existing = _load_user(uid)
        if existing:
            return _touch_existing(existing, claims, device_id)
        raise
    _link_auth_uid(uid, uid)
    # Only ever reached once per account — every later sign-in takes the
    # snap.exists / auth_links branch above — so support gets exactly one mail per user.
    created = {**data, "uid": uid}
    if data["access_status"] == statuses.ACCESS_PENDING:
        notify.access_request(uid, data["email"], data["displayName"], provider)
    return ensure_entitlement(created, device_id)


def list_users(
    status: str = "",
    limit: int = 50,
    page_token: str | None = None,
) -> tuple[list, str | None]:
    """Cursor-paginated user list. Returns (page, next_page_token_or_None)."""
    col = db().collection("users")
    query = col.where("access_status", "==", status) if status else col
    docs, next_token = _cursor_page(col, query, limit, page_token)
    out = []
    for d in docs:
        u = d.to_dict()
        out.append({
            "uid": d.id,
            "email": u.get("email"),
            "displayName": u.get("displayName"),
            "role": u.get("role"),
            "access_status": u.get("access_status"),
            # Each app's phone, under its new name and its old (ADR-030).
            **{k: v for app in apps.ALL
               for k, v in apps.written_fields(
                   "activeDeviceId", app, apps.read_field(u, "activeDeviceId", app)).items()},
        })
    return out, next_token


def release_account_device(uid: str) -> dict[str, str]:
    """Free the account's registered phones so a new one can register.
    `{app: released id}`, naming only the apps that had one; raises `Refusal`.

    Staff only, on the holder's request (decided 2026-09-26, TD-126). A Demo
    account has no licence lock to clear, so before this its first phone was its
    only phone: registration refuses any device but `activeDeviceId`
    (`device_conflict`), and only suspending the account emptied it. The release
    is the one a lock clear makes (`repo.seats._settle_holder`): the old device
    retired, its id held off for `DEVICE_RELEASE_HOLD_HOURS` so its upload worker
    cannot take the account straight back.

    An account on a live licence is refused with `license_device_clear_required`:
    its licence lock would still name the old phone and demote the new one, and
    **New device** on the licence moves both. Nothing registered is not an error;
    the answer is then empty.

    Every app's phone is released (ADR-010): the holder is changing phones, and
    both apps are on the phone they are changing.
    """
    user_ref = db().collection("users").document(uid)
    snap = _get(user_ref)
    if not snap.exists:
        raise Refusal(errors.USER_NOT_FOUND)
    user = snap.to_dict() or {}
    if any(apps.licence_view(user, app).get("licenseId") and effective_mode(user, app) == MODE_LICENSED
           for app in apps.ALL):
        raise Refusal(errors.LICENSE_DEVICE_CLEAR_REQUIRED)
    released = {app: apps.read_field(user, "activeDeviceId", app) or "" for app in apps.ALL}
    released = {app: device for app, device in released.items() if device}
    if not released:
        return {}
    _release_devices(user_ref, released, {"updatedAt": _base.firestore.SERVER_TIMESTAMP}, user)
    return released


def set_user_status(uid: str, status: str) -> bool:
    """Set access_status, revoking the account's devices when suspending.

    Revoking access used to leave devices/{id}.status == "ACTIVE". Nothing broke
    — current_user rejects a non-APPROVED account before any device check — but
    the binding survived the revocation, so re-approving silently restored the
    old device's authority and `device_in_use` still held the id against another
    account. Revocation should mean the same thing at both layers.
    """
    ref = db().collection("users").document(uid)
    if not _get(ref).exists:
        return False
    batch = db().batch()
    batch.update(ref, {"access_status": status, "updatedAt": _base.firestore.SERVER_TIMESTAMP})
    if status != statuses.ACCESS_APPROVED:
        batch.update(ref, {k: v for app in apps.ALL
                           for k, v in apps.written_fields(
                               "activeDeviceId", app, _base.firestore.DELETE_FIELD).items()})
        for dev in _stream(db().collection("devices").where("uid", "==", uid)):
            _retire_device(batch, dev.reference, statuses.DEVICE_REVOKED)
    _commit(batch)
    return True
