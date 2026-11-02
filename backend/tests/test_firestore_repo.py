"""Security-critical repo logic: nonce single-use/TTL, admin+approval policy,
and the complete_file idempotency + bump-once invariant. All against an
in-memory Firestore double — no live backend, no auth bypass."""
from datetime import datetime, timedelta, timezone

import pytest


import repo_view as repo
from app.models import FileComplete


def _claims(sub="u1", email="a@b.com", verified=True, provider="google.com"):
    return {
        "sub": sub,
        "email": email,
        "email_verified": verified,
        "name": "Test",
        "firebase": {"sign_in_provider": provider},
    }


# ---------------- nonce ----------------
def test_nonce_round_trip_consumes_once(store):
    nonce = repo.issue_nonce("u1", "d1")
    assert repo.consume_nonce(nonce, "u1", "d1") is True
    # Single-use: the same nonce cannot be replayed.
    assert repo.consume_nonce(nonce, "u1", "d1") is False


def test_nonce_rejects_wrong_uid_or_device(store):
    nonce = repo.issue_nonce("u1", "d1")
    assert repo.consume_nonce(nonce, "someone-else", "d1") is False
    # Invalid callers must not burn the challenge — owner can still consume it.
    assert nonce in store._data["challenges"]
    assert repo.consume_nonce(nonce, "u1", "d1") is True


def test_nonce_wrong_device_rejected(store):
    nonce = repo.issue_nonce("u1", "d1")
    assert repo.consume_nonce(nonce, "u1", "d2") is False
    assert nonce in store._data["challenges"]


def test_nonce_expired_rejected(store):
    nonce = repo.issue_nonce("u1", "d1")
    # Force the stored challenge to be in the past.
    store._data["challenges"][nonce]["expireAt"] = datetime.now(timezone.utc) - timedelta(seconds=1)
    assert repo.consume_nonce(nonce, "u1", "d1") is False
    # Expired docs are left for TTL reclaim, not deleted by a failed consume.
    assert nonce in store._data["challenges"]


def test_nonce_unknown_rejected(store):
    assert repo.consume_nonce("never-issued", "u1", "d1") is False


def test_a_used_client_nonce_is_not_a_server_challenge(store):
    """With client nonces off (`CLIENT_NONCE_WINDOW_SECONDS <= 0`) every nonce
    is looked up as a challenge, and a claimed client nonce shares the
    collection: it was accepted once more, and deleted (L1-7)."""
    nonce = "t1.1790000000." + "A" * 30
    expire = datetime.now(timezone.utc) + timedelta(minutes=5)
    assert repo.claim_client_nonce(nonce, "u1", "d1", expire) is True
    assert repo.consume_nonce(nonce, "u1", "d1") is False
    assert nonce in store._data["challenges"]


# ---------------- admin / approval ----------------
def test_admin_email_requires_verified(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", {"boss@corp.com"})
    assert repo.is_admin_email(_claims(email="boss@corp.com", verified=True)) is True
    # Unverified email must never grant admin, even if it matches.
    assert repo.is_admin_email(_claims(email="boss@corp.com", verified=False)) is False
    assert repo.is_admin_email(_claims(email="nobody@corp.com", verified=True)) is False


def test_auto_approved_policy(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "corp.com")
    assert repo._auto_approved(_claims(email="x@corp.com", verified=True)) is True
    assert repo._auto_approved(_claims(email="x@corp.com", verified=False)) is False
    assert repo._auto_approved(_claims(email="x@other.com", verified=True)) is False


def test_new_user_pending_by_default(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    u = repo.get_or_create_user(_claims(sub="new1", email="x@nowhere.com"))
    assert u["access_status"] == "PENDING"
    assert u["role"] == "user"


def test_new_admin_user_approved(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", {"boss@corp.com"})
    u = repo.get_or_create_user(_claims(sub="admin1", email="boss@corp.com"))
    assert u["access_status"] == "APPROVED"
    assert u["role"] == "admin"


def test_admin_taken_out_of_admin_emails_is_demoted_on_next_sign_in(monkeypatch, store):
    """`role` was only ever promoted, so off-boarded staff stayed `admin` in
    the record the consoles and the app read (L1-1)."""
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", {"boss@corp.com"})
    assert repo.get_or_create_user(_claims(sub="admin1", email="boss@corp.com"))["role"] == "admin"
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    u = repo.get_or_create_user(_claims(sub="admin1", email="boss@corp.com"))
    assert u["role"] == "user"
    assert store._data["users"]["admin1"]["role"] == "user"
    # Nor does an unverified token keep the role.
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", {"boss@corp.com"})
    repo.get_or_create_user(_claims(sub="admin1", email="boss@corp.com"))
    repo.get_or_create_user(_claims(sub="admin1", email="boss@corp.com", verified=False))
    assert store._data["users"]["admin1"]["role"] == "user"


def test_pending_user_promoted_on_qualifying_signin(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    repo.get_or_create_user(_claims(sub="u2", email="x@corp.com"))  # PENDING
    # Domain is now auto-approved; the next sign-in flips them to APPROVED.
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "corp.com")
    u = repo.get_or_create_user(_claims(sub="u2", email="x@corp.com"))
    assert u["access_status"] == "APPROVED"


# ---------------- lastSeenAt throttle ----------------
# A proxied restore is dozens of authenticated requests (one challenge+content
# pair per adaptive download window) in quick succession — an unconditional
# lastSeenAt write on every one of them was that many Firestore writes to
# record a timestamp nobody reads at finer-than-hour resolution.
def test_last_seen_write_is_throttled_when_nothing_else_changed(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    claims = _claims(sub="u3", email="x@nowhere.com")
    repo.get_or_create_user(claims)  # creates the doc

    fresh = datetime.now(timezone.utc) - timedelta(minutes=5)
    store._data["users"]["u3"]["lastSeenAt"] = fresh

    u = repo.get_or_create_user(claims)  # identical claims — nothing new to say

    assert u["lastSeenAt"] == fresh, "a fresh, unchanged sign-in must not write"
    assert store._data["users"]["u3"]["lastSeenAt"] == fresh


def test_last_seen_write_happens_once_stale(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    claims = _claims(sub="u4", email="x@nowhere.com")
    repo.get_or_create_user(claims)

    stale = datetime.now(timezone.utc) - timedelta(hours=2)
    store._data["users"]["u4"]["lastSeenAt"] = stale

    u = repo.get_or_create_user(claims)

    assert u["lastSeenAt"] != stale, "a stale timestamp must still refresh"
    assert store._data["users"]["u4"]["lastSeenAt"] != stale


def test_last_seen_write_ignores_the_throttle_after_a_revoke_checkpoint(
    monkeypatch, store,
):
    """A revoke asks for one un-throttled write, and gets exactly one.

    Seat reconciliation reads "has the holder been back since the revoke?" off
    lastSeenAt. The throttle alone would keep that answer wrong for an hour,
    so a stamp older than `seenCheckpointAt` is stale whatever its age — and
    once the write lands, the throttle applies again.
    """
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    claims = _claims(sub="u6", email="x@nowhere.com")
    repo.get_or_create_user(claims)

    doc = store._data["users"]["u6"]
    fresh = datetime.now(timezone.utc) - timedelta(minutes=5)
    doc["lastSeenAt"] = fresh
    # The revoke ran a minute after the account was last seen.
    doc["seenCheckpointAt"] = fresh + timedelta(minutes=1)

    u = repo.get_or_create_user(claims)

    assert u["lastSeenAt"] != fresh, "a stamp predating the checkpoint must refresh"
    moved = store._data["users"]["u6"]["lastSeenAt"]
    assert moved > doc["seenCheckpointAt"]

    # Second request: the stamp is now past the checkpoint, so the ordinary
    # throttle takes over and the restore's dozens of calls stay one write.
    repo.get_or_create_user(claims)
    assert store._data["users"]["u6"]["lastSeenAt"] == moved


def test_last_seen_write_happens_when_another_field_changed(monkeypatch, store):
    # A real field change (role, access_status, provider, schema) must not be
    # swallowed by the throttle just because lastSeenAt itself is fresh.
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    repo.get_or_create_user(_claims(sub="u5", email="x@corp.com", provider="google.com"))

    u = repo.get_or_create_user(_claims(sub="u5", email="x@corp.com", provider="apple.com"))

    assert u["signInProvider"] == "apple.com"


# ---------------- complete_file idempotency ----------------
def _seed_pending_file(store, file_id="f1", uid="u1", size=100):
    store._data.setdefault("files", {})[file_id] = {
        "uid": uid, "sizeBytes": size, "status": "PENDING", "sessionId": "s1",
    }


_MD5 = "a" * 32


def test_complete_file_first_call_ok(store):
    _seed_pending_file(store)
    body = FileComplete(sessionId="s1", driveFileId="drive1", bytes=100, md5=_MD5)
    assert repo.complete_file("f1", "u1", body) == "ok"
    assert store._data["files"]["f1"]["status"] == "COMPLETED"


def test_complete_file_retry_is_idempotent(store):
    _seed_pending_file(store)
    body = FileComplete(sessionId="s1", driveFileId="drive1", bytes=100, md5=_MD5)
    assert repo.complete_file("f1", "u1", body) == "ok"
    # A retried completion must return "already" and NOT bump again.
    assert repo.complete_file("f1", "u1", body) == "already"


def test_complete_file_rejects_wrong_uid(store):
    _seed_pending_file(store, uid="u1")
    body = FileComplete(sessionId="s1", driveFileId="drive1", bytes=100, md5=_MD5)
    assert repo.complete_file("f1", "someone-else", body) == ""


def test_complete_file_rejects_size_mismatch(store):
    _seed_pending_file(store, size=100)
    body = FileComplete(sessionId="s1", driveFileId="drive1", bytes=999, md5=_MD5)
    assert repo.complete_file("f1", "u1", body) == ""


def test_complete_file_missing_returns_empty(store):
    body = FileComplete(sessionId="s1", driveFileId="drive1", bytes=100, md5=_MD5)
    assert repo.complete_file("nope", "u1", body) == ""


# ---------------- bump_session_progress ----------------
def test_bump_advances_and_completes(store):
    store._data.setdefault("sessions", {})["s1"] = {
        "uid": "u1", "fileCount": 2, "completedCount": 0, "status": "UPLOADING",
    }
    repo.bump_session_progress("s1")
    assert store._data["sessions"]["s1"]["completedCount"] == 1
    assert store._data["sessions"]["s1"]["status"] == "UPLOADING"
    repo.bump_session_progress("s1")
    assert store._data["sessions"]["s1"]["completedCount"] == 2
    assert store._data["sessions"]["s1"]["status"] == "COMPLETED"


# ---------------- unbounded erasure ----------------
def test_account_erasure_deletes_more_than_old_2000_record_cap(store):
    count = 2005
    store._data["users"] = {"u1": {"email": "erase@example.com"}}
    store._data["sessions"] = {
        f"s{i}": {"uid": "u1"} for i in range(count)
    }
    store._data["files"] = {
        f"f{i}": {"uid": "u1", "sessionId": f"s{i}"} for i in range(count)
    }
    store._data["devices"] = {
        f"d{i}": {"uid": "u1"} for i in range(count)
    }
    store._data["audit_logs"] = {
        "a1": {"uid": "u1", "action": "SESSION_CREATE"}
    }

    result = repo.delete_all_user_data("u1")

    assert result == {"sessions": count, "binnedSessions": 0, "files": count, "devices": count,
                      "authLinks": 0, "seatsBlanked": 0, "seatScanComplete": True}
    assert store._data["sessions"] == {}
    assert store._data["files"] == {}
    assert store._data["devices"] == {}
    assert store._data["users"] == {}
    # Explicit policy: append-only security audit facts survive account erasure.
    assert "a1" in store._data["audit_logs"]


def test_session_erasure_deletes_more_than_old_2000_file_cap(store):
    count = 2005
    store._data["sessions"] = {"s1": {"uid": "u1"}}
    store._data["files"] = {
        f"f{i}": {"uid": "u1", "sessionId": "s1"} for i in range(count)
    }

    assert repo.delete_session("s1") == count
    assert store._data["files"] == {}
    assert store._data["sessions"] == {}


# ---------------- device-bound accounts ----------------
def test_same_device_same_email_reuses_pending_user(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    first = repo.get_or_create_user(_claims(sub="sub-a", email="a@b.com"), device_id="dev-1")
    assert first["uid"] == "sub-a"
    assert first["claimedDeviceId"] == "dev-1"

    second = repo.get_or_create_user(_claims(sub="sub-b", email="a@b.com"), device_id="dev-1")
    assert second["uid"] == "sub-a"
    assert "sub-b" not in store._data["users"]
    assert store._data["auth_links"]["sub-b"]["uid"] == "sub-a"
    assert "sub-b" in store._data["users"]["sub-a"]["linkedAuthUids"]


def test_same_device_different_email_does_not_steal(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    repo.get_or_create_user(_claims(sub="sub-a", email="a@b.com"), device_id="dev-1")
    with pytest.raises(repo.DeviceInUseError):
        repo.get_or_create_user(_claims(sub="sub-c", email="other@b.com"), device_id="dev-1")
    assert "sub-c" not in store._data.get("users", {})


def test_active_device_same_email_reuses_user(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    first = repo.get_or_create_user(_claims(sub="sub-a", email="a@b.com"), device_id="dev-1")
    store._data.setdefault("devices", {})["dev-1"] = {
        "uid": first["uid"], "status": "ACTIVE",
    }
    second = repo.get_or_create_user(_claims(sub="sub-b", email="A@B.com"), device_id="dev-1")
    assert second["uid"] == first["uid"]
    assert "sub-b" not in store._data["users"]


def test_same_device_no_email_does_not_adopt(monkeypatch, store):
    """A token carrying no address must not inherit a bound account.

    `_emails_conflict` used to answer "no conflict" whenever either side was
    blank, so a sign-in with no email at all adopted the account bound to the
    device id it presented — and the device id is a header the caller picks.
    """
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    repo.get_or_create_user(_claims(sub="sub-a", email="a@b.com"), device_id="dev-1")
    with pytest.raises(repo.DeviceInUseError):
        repo.get_or_create_user(_claims(sub="sub-x", email=None), device_id="dev-1")
    assert "sub-x" not in store._data.get("users", {})


def test_same_device_unverified_email_does_not_adopt(monkeypatch, store):
    """Matching the address is not enough — it has to be a proven address."""
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    repo.get_or_create_user(_claims(sub="sub-a", email="a@b.com"), device_id="dev-1")
    with pytest.raises(repo.DeviceInUseError):
        repo.get_or_create_user(
            _claims(sub="sub-y", email="a@b.com", verified=False), device_id="dev-1",
        )
    assert "sub-y" not in store._data.get("users", {})


@pytest.mark.parametrize("status", ["SUPERSEDED", "REVOKED"])
def test_a_retired_phone_is_not_held_by_the_account_that_first_claimed_it(monkeypatch, store, status):
    """`claimedDeviceId` is never cleared, and the device-id lookup fell back
    to it whatever the phone's record said, so a phone released from one
    account was `device_in_use` to every other account for good (L1-4)."""
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    repo.get_or_create_user(_claims(sub="sub-a", email="a@b.com"), device_id="dev-1")
    store._data.setdefault("devices", {})["dev-1"] = {"uid": "sub-a", "status": status}

    other = repo.get_or_create_user(_claims(sub="sub-c", email="other@b.com"), device_id="dev-1")

    assert other["uid"] == "sub-c"
    assert "sub-c" not in store._data["users"]["sub-a"].get("linkedAuthUids", [])


def test_an_unproven_bound_account_is_not_handed_to_a_verified_caller(monkeypatch, store):
    """The caller's address was asked to be proven, the bound account's was
    not: an account that typed someone's address unverified was adopted by
    its owner on a shared phone, its own password sign-in still reaching it
    (L1-3)."""
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    repo.get_or_create_user(_claims(sub="squat", email="a@b.com", verified=False,
                                    provider="password"), device_id="dev-1")
    with pytest.raises(repo.DeviceInUseError):
        repo.get_or_create_user(_claims(sub="owner", email="a@b.com"), device_id="dev-1")
    assert "owner" not in store._data.get("auth_links", {})
    assert store._data["users"]["squat"]["linkedAuthUids"] == []


def test_first_sign_in_race_keeps_the_winner(monkeypatch, store):
    """The loser of a create race reads the winner's profile, never over-writes it.

    Two first-ever requests from one account overlap on launch. An
    unconditional `set` reset an approved profile back to PENDING; `create`
    fails instead, and the caller falls through to the existing document.
    """
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    repo.get_or_create_user(_claims(sub="racer", email="r@b.com"))
    store._data["users"]["racer"]["access_status"] = "APPROVED"

    again = repo.get_or_create_user(_claims(sub="racer", email="r@b.com"))

    assert again["access_status"] == "APPROVED"
    assert store._data["users"]["racer"]["access_status"] == "APPROVED"


def test_new_documents_include_schema_version(store):
    monkey_claims = _claims(sub="versioned")
    repo.get_or_create_user(monkey_claims)
    assert store._data["users"]["versioned"]["schemaVersion"] == repo.SCHEMA_VERSION
    nonce = repo.issue_nonce("versioned", "device-123")
    assert store._data["challenges"][nonce]["schemaVersion"] == repo.SCHEMA_VERSION


# ---------------- the verified address (B2) ----------------
# The stored `email` was written once, at first sign-in, while `emailVerified`
# followed every token. Squat someone's address unverified, change the
# account's email to one you own and verify that, and the flag turned true
# against the victim's stored address: their invite, their IT roster.
def test_email_change_does_not_carry_old_address(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", True)
    lic = repo.create_institution_license(
        domain_lock="uni.edu", admin_emails=["it@uni.edu"], created_by_uid="staff")
    license_id = lic["license"]["id"]
    repo.invite_institution_member(license_id, "victim@uni.edu", "it")
    store._data["users"] = {"u1": {"email": "victim@uni.edu", "emailVerified": False,
                                   "access_status": "APPROVED", "role": "user"}}

    user = repo.get_or_create_user(_claims(email="attacker@own.example", verified=True))

    assert store._data["users"]["u1"]["email"] == "attacker@own.example"
    assert repo.verified_address(user) == "attacker@own.example"
    assert user.get("licenseId") != license_id
    assert not store._data.get(f"licenses/{license_id}/seats")


def test_an_unverified_address_is_no_address():
    assert repo.verified_address({"email": "A@Uni.edu", "emailVerified": True}) == "a@uni.edu"
    assert repo.verified_address({"email": "a@uni.edu", "emailVerified": False}) == ""
    assert repo.verified_address({"email": "a@uni.edu"}) == ""
    assert repo.verified_address({"emailVerified": True}) == ""


# ---------------- the seat's copy of the address (TD-297) ----------------
def _seated_member(monkeypatch, store):
    """`u1` signed in as old@uni.edu and seated on an institution licence."""
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", True)
    lic = repo.create_institution_license(
        domain_lock="uni.edu", admin_emails=["it@uni.edu"], created_by_uid="staff")
    license_id = lic["license"]["id"]
    repo.invite_institution_member(license_id, "old@uni.edu", "it")
    user = repo.get_or_create_user(_claims(email="old@uni.edu"))
    assert user["licenseId"] == license_id
    seats = store._data[f"licenses/{license_id}/seats"]
    assert seats["u1"]["email"] == "old@uni.edu"
    return license_id, seats


def test_a_verified_email_change_reaches_the_seat(monkeypatch, store):
    """The roster and reconcile read the seat's copy: it follows the account's
    verified address, as the account's own `email` does."""
    license_id, seats = _seated_member(monkeypatch, store)

    repo.get_or_create_user(_claims(email="New@Uni.edu"))

    assert seats["u1"]["email"] == "new@uni.edu"
    roster, _ = repo.page_institution_seats(license_id, 10)
    assert [s["email"] for s in roster] == ["new@uni.edu"]


def test_an_unverified_email_change_leaves_the_seat(monkeypatch, store):
    """An address nobody has proved is no address (`verified_address`): the
    seat keeps the one that was."""
    _, seats = _seated_member(monkeypatch, store)

    repo.get_or_create_user(_claims(email="someone@else.example", verified=False))

    assert seats["u1"]["email"] == "old@uni.edu"


def test_an_email_change_without_a_seat_writes_no_seat(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    store._data["users"] = {"u1": {"email": "a@b.com", "emailVerified": True,
                                   "access_status": "APPROVED", "role": "user",
                                   "licenseId": "gone", "licenseKind": "institution"}}

    repo.get_or_create_user(_claims(email="c@d.com"))

    assert store._data["users"]["u1"]["email"] == "c@d.com"
    assert not store._data.get("licenses/gone/seats")
