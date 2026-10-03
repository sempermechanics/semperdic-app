"""What a newly usable account is entitled to at sign-in: an invite, else Demo.
"""
import logging
from datetime import timedelta

from .. import errors, statuses
from ..licenses import (
    KIND_INSTITUTION,
    MODE_DEMO,
    as_utc,
    normalize_email,
    normalize_kind,
)

from . import _base
from ._base import (
    _CONTENDED,
    db,
    get_license,
    _license_past_grace,
    _now,
)
from .claims import (
    claim_individual_license,
    claim_seat,
    _individual_member_patch,
    _institution_member_patch,
)
from .invites import (
    _invite_ref,
)
from .mint import (
    ensure_demo_license,
)


log = logging.getLogger("semper.firestore")


#: How often an account whose invite could not be claimed tries again. The
#: claim runs on the request path, so this bounds it to a few reads per
#: account per interval rather than one per request.
_INVITE_RETRY = timedelta(minutes=15)

#: Stamped on an account when its invite claim failed for a reason that can
#: clear later — a full roster, a seat on hold. Without it the Demo key minted
#: next stranded the invite for good: every later request short-circuits on
#: the account holding a licence, so a seat freed a day later never reached
#: the person it was promised to.
_INVITE_BLOCKED_AT = "inviteBlockedAt"


def _invite_retry_due(user: dict) -> bool:
    """Whether a Demo account whose invite claim failed should try it again."""
    blocked = as_utc(user.get(_INVITE_BLOCKED_AT))
    if blocked is None or (user.get("mode") or MODE_DEMO) != MODE_DEMO:
        return False
    return _now() - blocked >= _INVITE_RETRY


def _clear_invite_block(uid: str, user: dict) -> None:
    if user.get(_INVITE_BLOCKED_AT) is None:
        return
    db().collection("users").document(uid).update({
        _INVITE_BLOCKED_AT: _base.firestore.DELETE_FIELD,
    })


def claim_pending_invite(user: dict) -> dict:
    """Redeem a pending invite for an account that has just become usable.

    See `_claim_pending_invite`, which also reports why a claim failed.
    """
    return _claim_pending_invite(user)[0]


def _claim_pending_invite(user: dict) -> tuple[dict, str]:
    """Redeem a pending invite; returns (user, error), error "" on no failure.

    Both licence kinds arrive here. An institution invite takes a seat; an
    individual one attaches the licence itself — the two ways a licence is
    delivered without anybody typing a key, and the only two. The invite
    record does not say which; the licence it points at does, which is why
    there is one collection rather than two.

    Returns the updated user when something was claimed, otherwise the user
    unchanged. Called from `ensure_entitlement`, i.e. exactly where a Demo key
    would otherwise be minted — so an invited newcomer lands licensed on their
    first request rather than demo-then-upgraded.

    The guards are `ensure_demo_license`'s, and they bound the cost: this runs
    only for an approved, verified account that holds no licence yet, which is
    a one-request window before the Demo key exists. It is not a per-request
    read. The one exception is an account whose claim already failed for a
    reason that can clear, which tries again at most every `_INVITE_RETRY`
    while it holds Demo.

    Email verification is required and not merely preferred. The invite names
    an address, and the address is the whole claim to the seat; honouring an
    unverified one would let anyone who can type someone else's address take
    the institution seat meant for them.
    """
    uid = user.get("uid")
    if not uid:
        return user, ""
    if user.get("licenseId") and not _invite_retry_due(user):
        return user, ""
    if user.get("access_status") != statuses.ACCESS_APPROVED or not user.get("emailVerified"):
        return user, ""
    address = normalize_email(user.get("email"))
    if not address:
        return user, ""

    ref = _invite_ref(address)
    snap = ref.get()
    if not snap.exists:
        _clear_invite_block(uid, user)
        return user, ""
    license_id = ((snap.to_dict() or {}).get("licenseId") or "")
    lic = get_license(license_id) if license_id else None
    if not lic:
        # The licence was deleted out from under the invite. Drop it rather
        # than leaving a record that can never be redeemed.
        ref.delete()
        _clear_invite_block(uid, user)
        return user, ""
    if (lic.get("status") or "active") == "revoked":
        ref.delete()
        _clear_invite_block(uid, user)
        return user, ""

    if _license_past_grace(lic):
        # Activation refuses the same licence. Claimed anyway, the account
        # pointed at a licence that grants nothing and resolved demo with no
        # reason given. Unlike a revoke this can clear — Extend renews in
        # place — so the invite stays and is retried like a full roster.
        err = errors.LICENSE_EXPIRED
    # No device lock is passed either way: the invite predates any device
    # choice, and the lock is bound on the first authed request that carries a
    # device id — see revalidate_device_lock.
    elif normalize_kind(lic.get("kind")) == KIND_INSTITUTION:
        patch = _institution_member_patch(license_id, lic)
        if user.get(_INVITE_BLOCKED_AT) is not None:
            patch[_INVITE_BLOCKED_AT] = _base.firestore.DELETE_FIELD
        err = claim_seat(license_id, uid, address, "", patch, invite_ref=ref)
    else:
        patch = _individual_member_patch(license_id, lic)
        if user.get(_INVITE_BLOCKED_AT) is not None:
            patch[_INVITE_BLOCKED_AT] = _base.firestore.DELETE_FIELD
        err = claim_individual_license(license_id, uid, address, patch, invite_ref=ref)
    if err:
        # Leave the invite in place either way: seats exhausted or already
        # redeemed may be resolved by ops, and contention resolves itself on
        # the next request. Only the first two are worth a warning — logging
        # a lost race at the same level made a busy sign-in read exactly like
        # a licence with no room left.
        if err == _CONTENDED:
            log.info("invite claim lost the race uid=%s license=%s", uid, license_id)
        else:
            log.warning("invite claim failed uid=%s license=%s err=%s", uid, license_id, err)
            # Try again later, once the account holds Demo; see _INVITE_RETRY.
            db().collection("users").document(uid).update({_INVITE_BLOCKED_AT: _now()})
        # Answer with the account as stored, not with the caller's copy. The
        # copy was read before this request began, and the request that beat
        # us to the claim has already granted the entitlement; returning the
        # stale dict serves one request as demo to someone who is licensed.
        # `ensure_demo_license` usually rescues this by re-reading, but it
        # returns early when there is no device id to mint against — which is
        # every browser request, since the consoles send no `X-Device-Id`.
        stored = db().collection("users").document(uid).get()
        if not stored.exists:
            return user, err
        return {**user, **(stored.to_dict() or {}), "uid": uid}, err
    claimed = {**user, **patch, "uid": uid}
    claimed.pop(_INVITE_BLOCKED_AT, None)
    return claimed, ""


def ensure_entitlement(user: dict, device_id: str | None) -> dict:
    """Give a newly-usable account whatever it is entitled to.

    An institution seat it was invited to, if there is one, else a Demo key.
    The order is the point: `ensure_demo_license` stamps a `licenseId`, and
    every later call short-circuits on that field, so minting Demo first would
    strand the invite permanently.

    That includes a claim that lost every transaction attempt to contention
    with nobody winning (TD-33): the invite is still pending, so this request
    is served without a licence — which resolves to Demo limits anyway — and
    the next request claims again. Any other failure (seats exhausted, already
    redeemed) is not transient, so Demo is minted as before — and the invite
    is tried again every `_INVITE_RETRY`, so a seat freed later still reaches
    the person it was promised to.
    """
    claimed, err = _claim_pending_invite(user)
    if claimed.get("licenseId"):
        return claimed
    if err == _CONTENDED:
        return claimed
    return ensure_demo_license(claimed, device_id)
