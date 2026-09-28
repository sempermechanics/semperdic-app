"""Individual licences delivered by email: claim, starved binds, raced sign-ins, minting for an existing account."""

import pytest

from google.api_core.exceptions import Aborted

from app import firestore_repo as repo
from license_helpers import (  # noqa: F401
    _mint_individual,
    _mint_institution,
    _recording_stubs,
    _signed_in,
)


# ---------------- individual licences delivered by email ----------------
# Minting used to demand the customer's device id, which meant reading it off
# their phone and sending it to us before we could issue anything, and then
# typing a key. These cover the delivery that replaces it: mint against the
# address, sign in, done.


def test_minting_an_individual_licence_needs_only_an_address(store):
    store._data["users"] = {}
    minted = _mint_individual()
    license_id = minted["license"]["id"]
    assert minted["inviteError"] == ""
    assert store._data["licenses"][license_id]["deviceIdLock"] == ""
    # The invite is the delivery: nobody has to be told a key.
    invites = list(store._data["licenseInvites"].values())
    assert [i["email"] for i in invites] == ["solo@lab.org"]
    assert invites[0]["licenseId"] == license_id


def test_an_individual_licence_attaches_at_first_sign_in(store):
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = _signed_in(store, "solo-1", "solo@lab.org")

    out = repo.ensure_entitlement(user, "and-first")

    assert out["licenseId"] == license_id
    assert out["licenseKind"] == "individual"
    assert out["mode"] == "licensed"
    lic = store._data["licenses"][license_id]
    assert lic["status"] == "redeemed"
    assert lic["redeemedByUid"] == "solo-1"
    # Consumed, so a second account cannot claim the same licence by typing
    # the address.
    assert store._data["licenseInvites"] == {}


def test_the_first_device_to_sign_in_takes_the_lock(store):
    """Binding is what ties an emailed licence to one device. It happens on
    the request path, not at mint, so nobody has to know a device id early."""
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    assert store._data["licenses"][license_id]["deviceIdLock"] == ""

    bound = repo.revalidate_device_lock(user, "and-first")

    assert store._data["licenses"][license_id]["deviceIdLock"] == "and-first"
    assert bound["mode"] == "licensed"


def test_a_second_device_is_refused_once_the_lock_is_taken(store):
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    repo.revalidate_device_lock(user, "and-first")

    demoted = repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-second")

    assert demoted["mode"] == "demo"
    # Demo for the second device's own requests only: the account, and so the
    # device that holds the lock, stays licensed.
    assert store._data["users"]["solo-1"]["mode"] == "licensed"
    assert repo.revalidate_device_lock(
        store._data["users"]["solo-1"], "and-first")["mode"] == "licensed"
    # Demotion never touches the lock or the licence itself.
    assert store._data["licenses"][license_id]["deviceIdLock"] == "and-first"
    assert store._data["licenses"][license_id]["status"] == "redeemed"


def test_a_second_app_on_the_licensed_phone_does_not_demote_the_account(store):
    """Material Testing and Semper share an account, but each app on a phone
    has its own ANDROID_ID, so the second one arrives as another device. Its
    launch calls used to write Demo onto the account, which then read Demo in
    both apps until someone cleared the lock (2026-09-28)."""
    store._data["users"] = {}
    _mint_individual()
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    repo.revalidate_device_lock(user, "and-semper")

    for _ in range(3):
        assert repo.revalidate_device_lock(
            store._data["users"]["solo-1"], "and-materialtesting")["mode"] == "demo"

    assert store._data["users"]["solo-1"]["mode"] == "licensed"
    assert repo.effective_mode(store._data["users"]["solo-1"]) == "licensed"


def test_a_revoked_licence_still_stores_demo(store):
    """Only the mismatch stopped being stored. A licence that has ended drops
    the account for every device, the one holding the lock included."""
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    repo.revalidate_device_lock(user, "and-first")
    store._data["licenses"][license_id]["status"] = "revoked"

    demoted = repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-first")

    assert demoted["mode"] == "demo"
    assert store._data["users"]["solo-1"]["mode"] == "demo"


def test_binding_is_idempotent_for_the_device_that_holds_the_lock(store):
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    repo.revalidate_device_lock(user, "and-first")
    again = repo.revalidate_device_lock(store._data["users"]["solo-1"], "and-first")
    assert again["mode"] == "licensed"
    assert store._data["licenses"][license_id]["deviceIdLock"] == "and-first"


def test_an_institution_seat_from_an_invite_also_binds_on_first_use(store):
    """The same rule, the other document. A seat added by IT carries no lock
    until its holder shows up with a device.

    Assigned rather than floating on purpose: on a floating licence the holder
    is demo until they check out a lease, so there is no entitlement for a
    device lock to be about yet."""
    store._data["users"] = {}
    license_id = _mint_institution()["license"]["id"]
    repo.add_institution_member(license_id, "newcomer@university.edu")
    user = repo.ensure_entitlement(
        _signed_in(store, "new-1", "newcomer@university.edu"), None,
    )
    seats = store._data[f"licenses/{license_id}/seats"]
    assert seats["new-1"]["deviceIdLock"] == ""

    repo.revalidate_device_lock(user, "and-seat")

    assert seats["new-1"]["deviceIdLock"] == "and-seat"


def test_an_individual_invite_is_refused_to_the_wrong_address(store):
    """The invite is keyed by address hash, so this cannot happen by mistake —
    but emailLock is checked inside the claim anyway, because the licence's
    own lock is the authority and the invite is only a pointer to it."""
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    _signed_in(store, "other-1", "other@lab.org")

    err = repo.claim_individual_license(
        license_id, "other-1", "other@lab.org", {"mode": "licensed"},
    )

    assert err == "license_email_mismatch"
    assert store._data["users"]["other-1"].get("mode") != "licensed"


def test_an_individual_licence_is_claimed_once(store):
    """Two accounts, one licence. The second is refused rather than silently
    sharing the entitlement."""
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    _signed_in(store, "first", "solo@lab.org")
    _signed_in(store, "second", "solo@lab.org")
    patch = repo._individual_member_patch(
        license_id, store._data["licenses"][license_id],
    )

    assert repo.claim_individual_license(license_id, "first", "solo@lab.org", patch) == ""
    err = repo.claim_individual_license(license_id, "second", "solo@lab.org", patch)

    assert err == "license_already_redeemed"
    assert store._data["licenses"][license_id]["redeemedByUid"] == "first"


def test_a_revoked_individual_licence_is_never_claimed(store):
    store._data["users"] = {}
    minted = _mint_individual()
    license_id = minted["license"]["id"]
    repo.revoke_license(license_id, "admin")

    out = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), "and-1")

    assert out["licenseId"] != license_id
    assert out["mode"] == "demo"
    assert store._data["licenseInvites"] == {}


def test_a_second_individual_licence_for_one_address_is_reported(store):
    """Minting still succeeds — the licence exists and its key redeems it —
    but the address is already promised elsewhere, and ops has to see that."""
    store._data["users"] = {}
    _mint_individual()
    second = _mint_individual()
    assert second["inviteError"] == "invite_exists"
    assert len(store._data["licenseInvites"]) == 1


def test_a_key_typed_for_recovery_binds_an_unbound_licence(store):
    """activateLicense is the support path, not the delivery one — but it has
    to cope with the licences delivery now mints, which carry no lock."""
    store._data["users"] = {}
    _signed_in(store, "solo-1", "solo@lab.org")
    minted = _mint_individual()

    err, cfg = repo.activate_license("solo-1", "solo@lab.org", "and-first", minted["key"])

    assert err == ""
    assert cfg["mode"] == "licensed"
    assert store._data["licenses"][minted["license"]["id"]]["deviceIdLock"] == "and-first"
    err2, _ = repo.activate_license("solo-1", "solo@lab.org", "and-second", minted["key"])
    assert err2 == "license_device_mismatch"


# ================================================ a starved bind
# Firestore aborts contended transactions, and a burst of binds can all run out
# of retries with nothing committed. Losing the race is then not the same as
# someone winning it, and the lock must never be reported as held by another
# device while it is empty.


def _contended_binds(monkeypatch, starved, rival=None):
    """Abort the first `starved` bind transactions; the rest run normally.

    With `rival`, the first aborted round is the one another device won: its
    lock is committed before this caller's transaction gives up.
    """
    monkeypatch.setattr(repo, "_BIND_BACKOFF_S", 0)
    real = repo.firestore.transactional
    calls = {"n": 0}

    def _decorator(fn):
        body = real(fn)

        def _run(tx, *a, **k):
            calls["n"] += 1
            if calls["n"] <= starved:
                if rival is not None:
                    ref, device = rival
                    ref.update({"deviceIdLock": device})
                raise ValueError("Failed to commit transaction") from Aborted("contention")
            return body(tx, *a, **k)
        return _run
    monkeypatch.setattr(repo.firestore, "transactional", _decorator)
    return calls


def _unbound_individual(store):
    store._data["users"] = {}
    minted = _mint_individual()
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    ref = repo.db().collection("licenses").document(minted["license"]["id"])
    assert store._data["licenses"][ref.id]["deviceIdLock"] == ""
    return minted, user, ref


def test_a_starved_bind_runs_again_while_the_lock_is_empty(store, monkeypatch):
    _, _, ref = _unbound_individual(store)
    calls = _contended_binds(monkeypatch, starved=repo._BIND_ROUNDS - 1)

    assert repo.bind_device_lock(ref, "and-first") is True

    assert calls["n"] == repo._BIND_ROUNDS
    assert store._data["licenses"][ref.id]["deviceIdLock"] == "and-first"


def test_a_bind_lost_to_another_device_is_a_plain_loss(store, monkeypatch):
    minted, _, ref = _unbound_individual(store)
    calls = _contended_binds(monkeypatch, starved=1, rival=(ref, "and-rival"))

    err, _ = repo.activate_license("solo-1", "solo@lab.org", "and-first", minted["key"])

    # The lock is held, so this device really is the mismatch — and one
    # round was enough to find that out.
    assert err == "license_device_mismatch"
    assert calls["n"] == 1
    assert store._data["licenses"][ref.id]["deviceIdLock"] == "and-rival"


def test_a_starved_bind_never_reports_a_mismatch_nobody_holds(store, monkeypatch):
    """Every round starves and the lock stays empty. Activation used to read
    the empty lock back and answer `license_device_mismatch` — telling a
    device it had lost a licence no device held."""
    minted, _, ref = _unbound_individual(store)
    _contended_binds(monkeypatch, starved=10 * repo._BIND_ROUNDS)

    with pytest.raises(repo.DeviceLockContended) as raised:
        repo.activate_license("solo-1", "solo@lab.org", "and-first", minted["key"])

    assert raised.value.status_code == 503
    assert raised.value.code == "device_lock_contended"
    assert store._data["licenses"][ref.id]["deviceIdLock"] == ""


def test_a_starved_bind_on_the_request_path_leaves_the_licence_unbound(store, monkeypatch):
    """The request the bind rides on is not failed for it, and the account is
    not demoted: nobody holds the lock, so the next request binds it."""
    _, user, ref = _unbound_individual(store)
    _contended_binds(monkeypatch, starved=repo._BIND_ROUNDS)

    out = repo.revalidate_device_lock(dict(user), "and-first")

    assert out["mode"] == "licensed"
    assert store._data["users"]["solo-1"]["mode"] == "licensed"
    assert store._data["licenses"][ref.id]["deviceIdLock"] == ""

    bound = repo.revalidate_device_lock(dict(user), "and-first")

    assert bound["mode"] == "licensed"
    assert store._data["licenses"][ref.id]["deviceIdLock"] == "and-first"


def test_mint_still_accepts_a_device_id_when_ops_knows_one(store):
    store._data["users"] = {}
    minted = repo.create_individual_license(
        email_lock="known@lab.org", device_id_lock="and-known", created_by_uid="admin",
    )
    assert store._data["licenses"][minted["license"]["id"]]["deviceIdLock"] == "and-known"
    user = repo.ensure_entitlement(_signed_in(store, "k-1", "known@lab.org"), None)
    demoted = repo.revalidate_device_lock(user, "and-other")
    assert demoted["mode"] == "demo"


def test_a_demo_key_never_overwrites_a_licence_granted_a_moment_earlier(store):
    """Several requests arrive together at app launch. One claims the invited
    licence; the others are still holding the copy of the account they read
    before it existed, and must not stamp a Demo key over it."""
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    stale = _signed_in(store, "solo-1", "solo@lab.org")
    stale["activeDeviceId"] = "and-first"

    claimed = repo.ensure_entitlement(dict(stale), "and-first")
    assert claimed["licenseId"] == license_id

    out = repo.ensure_demo_license(stale, "and-first")

    assert out["licenseId"] == license_id
    assert out["mode"] == "licensed"
    assert store._data["users"]["solo-1"]["licenseId"] == license_id
    # And no orphan Demo record left behind pointing at nobody.
    assert list(store._data["licenses"]) == [license_id]


# ============================================================ raced sign-ins
# Delivery by invite means several requests can arrive at one account at the
# same moment, each holding the copy it read before the others wrote. These
# cover what the losers leave behind and what they are told.


def _always_contended(monkeypatch):
    """Make every transaction lose the race, as one does under real load."""
    def _decorator(fn):
        def _lost(*_a, **_k):
            raise Aborted("too much contention")
        return _lost
    monkeypatch.setattr(repo.firestore, "transactional", _decorator)


def test_a_demo_key_minted_first_is_removed_when_the_licence_lands(store):
    """The other interleaving. The losing request commits its Demo key before
    the winner claims, so the Demo record is left redeemed with nobody
    pointing at it — a live-looking key in the operator listing for every
    raced sign-in."""
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    demo_id = repo.ensure_demo_license(
        _signed_in(store, "solo-1", "solo@lab.org"), "and-1",
    )["licenseId"]
    assert demo_id != license_id

    patch = repo._individual_member_patch(
        license_id, store._data["licenses"][license_id],
    )
    assert repo.claim_individual_license(license_id, "solo-1", "solo@lab.org", patch) == ""

    assert store._data["users"]["solo-1"]["licenseId"] == license_id
    assert list(store._data["licenses"]) == [license_id]


# ================================================ minting for an existing account
# Every account that predates licensing holds a Demo key by the time ops mints
# for it, and `claim_pending_invite` never runs for an account holding a
# licence — so a mint against an address that has already signed in has to
# attach directly, the way `add_institution_member` does for a seat.


def test_minting_for_a_signed_in_demo_account_attaches_at_once(store):
    store._data["users"] = {}
    user = _signed_in(store, "solo-1", "solo@lab.org")
    demo_id = repo.ensure_demo_license(user, "and-1")["licenseId"]

    minted = _mint_individual()
    license_id = minted["license"]["id"]

    assert (minted["claimedByUid"], minted["claimError"]) == ("solo-1", "")
    holder = store._data["users"]["solo-1"]
    assert holder["licenseId"] == license_id
    assert holder["mode"] == "licensed"
    assert store._data["licenses"][license_id]["redeemedByUid"] == "solo-1"
    assert store._data["licenses"][license_id]["status"] == "redeemed"
    # The invite was consumed in the claim and the Demo key nobody holds is gone.
    assert store._data["licenseInvites"] == {}
    assert demo_id not in store._data["licenses"]
    # And the sign-in path leaves it alone afterwards.
    after = repo.ensure_entitlement({**holder, "uid": "solo-1"}, "and-1")
    assert after["licenseId"] == license_id


def test_minting_for_an_account_that_predates_licensing_attaches_too(store):
    """The shape every user document has on the day licensing deploys: no
    `mode`, no `licenseId`, nothing stamped yet."""
    store._data["users"] = {"old-1": {
        "email": "old@lab.org", "access_status": "APPROVED", "emailVerified": True,
    }}
    minted = _mint_individual(email="old@lab.org")
    assert minted["claimedByUid"] == "old-1"
    assert store._data["users"]["old-1"]["licenseId"] == minted["license"]["id"]
    assert repo.effective_mode({**store._data["users"]["old-1"], "uid": "old-1"}) == "licensed"


def test_minting_for_an_unverified_or_pending_account_leaves_the_invite(store):
    """Fail closed exactly as sign-in does: the address is the whole claim.
    Neither account holds a Demo key yet, so the invite still delivers the
    moment they qualify."""
    store._data["users"] = {
        "p-1": {"email": "pending@lab.org", "access_status": "PENDING", "emailVerified": True},
        "v-1": {"email": "unverified@lab.org", "access_status": "APPROVED",
                "emailVerified": False},
    }
    for address, uid in (("pending@lab.org", "p-1"), ("unverified@lab.org", "v-1")):
        minted = _mint_individual(email=address)
        assert (minted["claimedByUid"], minted["claimError"]) == ("", "")
        assert "licenseId" not in store._data["users"][uid]
    assert len(store._data["licenseInvites"]) == 2

    # Verification arrives; the invite is claimed on that request.
    verified = {**store._data["users"]["v-1"], "uid": "v-1", "emailVerified": True}
    store._data["users"]["v-1"]["emailVerified"] = True
    claimed = repo.ensure_entitlement(verified, "and-1")
    assert claimed["mode"] == "licensed"


def test_minting_for_a_live_licence_holder_is_refused_not_overwritten(store):
    store._data["users"] = {}
    first = _mint_individual()
    _signed_in(store, "solo-1", "solo@lab.org")
    assert repo.ensure_entitlement(
        {**store._data["users"]["solo-1"], "uid": "solo-1"}, "and-1",
    )["licenseId"] == first["license"]["id"]

    second = _mint_individual()

    # The first invite was consumed when it was claimed, so the second mint
    # records a fresh one — but the holder is not moved off a live licence.
    # The invite stays for ops to resolve, and the response says why.
    assert second["inviteError"] == ""
    assert (second["claimedByUid"], second["claimError"]) == ("", "holder_already_licensed")
    assert store._data["users"]["solo-1"]["licenseId"] == first["license"]["id"]
    assert [i["licenseId"] for i in store._data["licenseInvites"].values()] ==         [second["license"]["id"]]


def test_minting_again_for_a_revoked_holder_re_attaches(store):
    """Revoke leaves the holder demoted in place, still pointing at the dead
    licence. A fresh mint is how they come back."""
    store._data["users"] = {}
    first = _mint_individual()
    _signed_in(store, "solo-1", "solo@lab.org")
    repo.ensure_entitlement({**store._data["users"]["solo-1"], "uid": "solo-1"}, "and-1")
    repo.revoke_license(first["license"]["id"], "admin")
    assert store._data["users"]["solo-1"]["mode"] == "demo"

    second = _mint_individual()

    assert second["claimedByUid"] == "solo-1"
    assert store._data["users"]["solo-1"]["licenseId"] == second["license"]["id"]
    assert store._data["users"]["solo-1"]["mode"] == "licensed"


def test_a_contended_direct_claim_is_reported_not_hidden(store, monkeypatch):
    store._data["users"] = {}
    user = _signed_in(store, "solo-1", "solo@lab.org")
    repo.ensure_demo_license(user, "and-1")
    _always_contended(monkeypatch)

    minted = _mint_individual()

    assert (minted["claimedByUid"], minted["claimError"]) == ("", "claim_contended")
    assert minted["claimError"] != repo._CONTENDED


def test_a_seat_also_clears_the_demo_key_it_replaces(store):
    """Same rule on the other claim. IT adding someone who has been using
    demo moves their pointer, and the key they leave is nobody's."""
    store._data["users"] = {}
    demo_id = repo.ensure_demo_license(
        _signed_in(store, "new-1", "newcomer@university.edu"), "and-1",
    )["licenseId"]
    license_id = _mint_institution()["license"]["id"]

    err, seat, _invite = repo.add_institution_member(license_id, "newcomer@university.edu")

    assert (err, seat["uid"]) == ("", "new-1")
    assert store._data["users"]["new-1"]["licenseId"] == license_id
    assert demo_id not in store._data["licenses"]


def test_a_revoked_licence_a_demoted_holder_points_at_is_kept(store):
    """The discriminator has to be the licence, not the holder's mode.

    Revocation demotes in place and leaves the pointer alone, so the account
    sits at `mode: demo` addressing a real, revoked licence. Reading the
    mirror would delete the revocation record itself."""
    store._data["users"] = {}
    first_id = _mint_individual()["license"]["id"]
    repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    repo.revoke_license(first_id, "admin")
    assert store._data["users"]["solo-1"]["mode"] == "demo"
    assert store._data["users"]["solo-1"]["licenseId"] == first_id

    second_id = _mint_individual()["license"]["id"]
    patch = repo._individual_member_patch(
        second_id, store._data["licenses"][second_id],
    )
    assert repo.claim_individual_license(second_id, "solo-1", "solo@lab.org", patch) == ""

    assert store._data["licenses"][first_id]["status"] == "revoked"
    assert store._data["users"]["solo-1"]["licenseId"] == second_id


def test_a_claim_that_loses_answers_with_the_account_as_stored(store, monkeypatch):
    """No device id in play — the shape of every console request, since a
    browser sends no `X-Device-Id`. `ensure_demo_license` returns early there,
    so nothing else re-reads, and the caller's pre-race copy would otherwise
    be served: demo for one request to someone who is licensed."""
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    stale = _signed_in(store, "solo-1", "solo@lab.org")
    # What the request that beat us to it already committed.
    store._data["users"]["solo-1"].update(
        repo._individual_member_patch(license_id, store._data["licenses"][license_id]),
    )
    _always_contended(monkeypatch)

    out = repo.ensure_entitlement(dict(stale), None)

    assert out["licenseId"] == license_id
    assert out["mode"] == "licensed"


def test_contention_is_not_reported_as_an_exhausted_licence(store, monkeypatch, caplog):
    """Both claims fail closed on a lost race, which is right — but saying so
    with the public code made a busy sign-in read in the logs exactly like a
    licence that genuinely has no room left."""
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = _signed_in(store, "solo-1", "solo@lab.org")
    _always_contended(monkeypatch)

    assert repo.claim_individual_license(
        license_id, "solo-1", "solo@lab.org", {"mode": "licensed"},
    ) == repo._CONTENDED
    assert repo.claim_seat(license_id, "solo-1", "solo@lab.org", "", {}) == repo._CONTENDED

    with caplog.at_level("INFO"):
        repo.claim_pending_invite(dict(user))
    records = [r for r in caplog.records if "invite claim" in r.message]
    assert [r.levelname for r in records] == ["INFO"]
    assert "seats_exhausted" not in caplog.text

    # A caller who has to answer a route gets the public code it names; the
    # private marker never reaches the wire.
    assert repo._public_claim_error(
        repo._CONTENDED, "claim_contended") == "claim_contended"
    assert repo._public_claim_error("license_revoked", "x") == "license_revoked"


def test_revoking_frees_the_address_for_a_replacement_licence(store):
    """Mint against the wrong terms, revoke, mint again — the most ordinary
    correction there is, and it used to produce a licence nobody could
    receive."""
    store._data["users"] = {}
    first_id = _mint_individual()["license"]["id"]
    repo.revoke_license(first_id, "admin")
    assert store._data["licenseInvites"] == {}

    second = _mint_individual()

    assert second["inviteError"] == ""
    invites = list(store._data["licenseInvites"].values())
    assert [i["licenseId"] for i in invites] == [second["license"]["id"]]

    out = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    assert out["licenseId"] == second["license"]["id"]
    assert out["mode"] == "licensed"


def test_an_invite_left_by_a_dead_licence_is_overwritten(store):
    """The second layer. However a stale invite arose — a revoke that predates
    the cleanup, a licence deleted by hand — it promises nothing, because the
    claim discards it on sight. Refusing on its behalf only makes the
    replacement undeliverable too."""
    store._data["users"] = {}
    revoked_id = _mint_individual()["license"]["id"]
    # Straight to the document, so revoke_license's own cleanup is not what is
    # under test here.
    store._data["licenses"][revoked_id]["status"] = "revoked"
    assert _mint_individual()["inviteError"] == ""

    gone_id = _mint_individual("second@lab.org")["license"]["id"]
    del store._data["licenses"][gone_id]
    assert _mint_individual("second@lab.org")["inviteError"] == ""


def test_revoking_an_institution_licence_withdraws_its_invites(store):
    store._data["users"] = {}
    license_id = _mint_institution()["license"]["id"]
    repo.add_institution_member(license_id, "one@university.edu")
    repo.add_institution_member(license_id, "two@university.edu")
    other_id = _mint_individual()["license"]["id"]
    assert len(store._data["licenseInvites"]) == 3

    repo.revoke_license(license_id, "admin")

    remaining = list(store._data["licenseInvites"].values())
    assert [i["licenseId"] for i in remaining] == [other_id]
