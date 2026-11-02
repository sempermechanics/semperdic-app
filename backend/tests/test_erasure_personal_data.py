"""Account erasure leaves no address and no sign-in link behind (TD-243).

`delete_all_user_data` deletes `auth_links/{firebaseSub}` for every sub that
resolves to the account, and blanks the address on every seat the account
held, on any licence, live or in a deleted licence's hold. The seats stay, so
`seatsUsed`, reconciliation and the licence's audit export still count them;
a live one is revoked first so the counts read as after any other revoke.
Only `audit_logs` keeps anything, and it holds no address (TD-242).
"""
import json
import logging

import pytest

import repo_view as repo
from app import deps
from drive_stubs import erasure_drive


def _mint(domain="university.edu"):
    minted = repo.create_institution_license(
        domain_lock=domain, admin_emails=[f"it@{domain}"], created_by_uid="admin")
    return minted["license"]["id"]


def _account(store, uid, email, **extra):
    store._data.setdefault("users", {})[uid] = {
        "email": email, "access_status": "APPROVED", "emailVerified": True, "mode": "demo", **extra}


def _seats(store, license_id):
    return store._data[f"licenses/{license_id}/seats"]


def _outside_audit(store) -> str:
    return json.dumps({k: v for k, v in store._data.items() if k != "audit_logs"}, default=str)


def test_a_seat_holder_is_erased_with_no_address_left(store):
    store._data["users"] = {}
    _account(store, "u1", "a@university.edu")
    license_id = _mint()
    repo.add_institution_member(license_id, "a@university.edu")
    store._data["auth_links"] = {"u1": {"uid": "u1"}, "other": {"uid": "u2"}}

    counts = repo.delete_all_user_data("u1")

    assert counts["seatsBlanked"] == 1
    assert counts["authLinks"] == 1
    assert counts["seatScanComplete"] is True
    seat = _seats(store, license_id)["u1"]
    assert (seat["status"], seat["email"]) == ("revoked", "")
    assert store._data["licenses"][license_id]["seatsUsed"] == 0
    assert store._data["auth_links"] == {"other": {"uid": "u2"}}
    assert "a@university.edu" not in _outside_audit(store)


def test_every_linked_sign_in_is_deleted(store):
    """A device-bound account adopted by a second Firebase sub links both."""
    store._data["users"] = {}
    _account(store, "sub-a", "a@lab.org", linkedAuthUids=["sub-b"])
    store._data["auth_links"] = {"sub-a": {"uid": "sub-a"}, "sub-b": {"uid": "sub-a"},
                                 "sub-c": {"uid": "sub-c"}}

    assert repo.delete_all_user_data("sub-a")["authLinks"] == 2
    assert set(store._data["auth_links"]) == {"sub-c"}


def test_seats_on_licences_the_account_left_are_blanked_and_counted_out(store):
    store._data["users"] = {}
    _account(store, "u1", "a@university.edu")
    old = _mint()
    repo.add_institution_member(old, "a@university.edu")
    repo.revoke_institution_seat(old, "u1")
    # The account moved on: its old licence no longer names it.
    _account(store, "u1", "a@university.edu")
    current = _mint("school.edu")
    repo.add_institution_member(current, "a@university.edu")
    # A seat left active on a licence the account no longer points at, which
    # one-licence-per-person refuses to create today, so it is written here.
    stale = _mint("college.edu")
    store._data[f"licenses/{stale}/seats"] = {
        "u1": {"uid": "u1", "email": "a@university.edu", "status": "active"}}
    store._data["licenses"][stale]["seatsUsed"] = 1
    assert store._data["users"]["u1"]["licenseId"] == current

    counts = repo.delete_all_user_data("u1")

    assert counts["seatsBlanked"] == 3
    for license_id in (old, stale, current):
        seat = _seats(store, license_id)["u1"]
        assert (seat["status"], seat["email"]) == ("revoked", ""), license_id
        assert store._data["licenses"][license_id]["seatsUsed"] == 0, license_id
    assert "a@university.edu" not in _outside_audit(store)


def test_a_seat_in_a_deleted_licences_hold_is_blanked(store):
    store._data["users"] = {}
    _account(store, "u1", "a@university.edu")
    license_id = _mint()
    repo.add_institution_member(license_id, "a@university.edu")
    repo.delete_license(license_id, "admin")
    held = store._data[f"deleted_licenses/{license_id}/deleted_seats"]["u1"]
    assert held["email"] == "a@university.edu"

    assert repo.delete_all_user_data("u1")["seatsBlanked"] == 1

    assert held["email"] == ""
    assert "a@university.edu" not in _outside_audit(store)
    # Restoring the licence brings the seat back revoked: its holder is gone.
    repo.restore_license(license_id, "admin")
    seat = _seats(store, license_id)["u1"]
    assert (seat["status"], seat["email"]) == ("revoked", "")
    assert store._data["licenses"][license_id]["seatsUsed"] == 0


def test_a_failed_seat_search_still_blanks_the_current_seat(store, monkeypatch, caplog):
    """Before the collection-group index is deployed the query is refused."""
    store._data["users"] = {}
    _account(store, "u1", "a@university.edu")
    license_id = _mint()
    repo.add_institution_member(license_id, "a@university.edu")

    def refused(_name):
        raise RuntimeError("FAILED_PRECONDITION: the query requires an index")

    monkeypatch.setattr(store, "collection_group", refused)
    with caplog.at_level(logging.ERROR, logger="semper.firestore"):
        counts = repo.delete_all_user_data("u1")

    assert (counts["seatsBlanked"], counts["seatScanComplete"]) == (1, False)
    assert _seats(store, license_id)["u1"]["email"] == ""
    assert "u1" not in store._data["users"]
    [line] = [json.loads(r.message) for r in caplog.records if r.message.startswith("{")]
    assert (line["event"], line["errorCode"], line["uid"]) == (
        "erasure_seat_scan_failed", "RuntimeError", "u1")


@pytest.mark.asyncio
async def test_the_erasure_audit_row_carries_the_counts(store, audited, client, monkeypatch):
    store._data["users"] = {}
    _account(store, "dev-user", "dev@university.edu", driveFolderId="uf")
    license_id = _mint()
    repo.add_institution_member(license_id, "dev@university.edu")
    store._data["auth_links"] = {"dev-user": {"uid": "dev-user"}}
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "driveFolderId": "uf"})
    erasure_drive(monkeypatch)

    r = await client.delete("/v1/me")

    assert r.status_code == 200, r.text
    row = next(a for a in audited if a["action"] == "ACCOUNT_DELETE")
    assert (row["detail"]["authLinks"], row["detail"]["seatsBlanked"]) == (1, 1)
    assert row["detail"]["seatScanComplete"] is True
    assert r.json()["seatsBlanked"] == 1
    assert "dev@university.edu" not in _outside_audit(store)
    assert "@" not in json.dumps(audited, default=str)


@pytest.mark.asyncio
async def test_linked_auth_users_are_deleted(store, audited, client, monkeypatch):
    """An account adopted by a second Firebase sub (`linkedAuthUids`) is
    reached by two Auth users, and each holds the address and the name.
    Erasure deletes every one of them, not only the canonical uid."""
    from app import google_auth

    store._data["users"] = {}
    _account(store, "dev-user", "dev@university.edu", driveFolderId="uf",
             linkedAuthUids=["sub-b"])
    store._data["auth_links"] = {"dev-user": {"uid": "dev-user"}, "sub-b": {"uid": "dev-user"},
                                 "sub-c": {"uid": "dev-user"}, "other": {"uid": "u2"}}
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "driveFolderId": "uf"})
    erasure_drive(monkeypatch)
    deleted = []
    monkeypatch.setattr(google_auth.fb_auth, "delete_user", lambda uid, app=None: deleted.append(uid))

    r = await client.delete("/v1/me")

    assert r.status_code == 200, r.text
    assert deleted == ["dev-user", "sub-b", "sub-c"]
    row = next(a for a in audited if a["action"] == "ACCOUNT_DELETE")
    assert (row["detail"]["authUsers"], row["detail"]["authUsersDeleted"]) == (3, 3)
    assert row["detail"]["authUserDeleted"] is True


@pytest.mark.asyncio
async def test_a_linked_auth_user_that_survives_is_reported(store, audited, client, monkeypatch):
    from app import google_auth

    store._data["users"] = {}
    _account(store, "dev-user", "dev@university.edu", driveFolderId="uf",
             linkedAuthUids=["sub-b"])
    erasure_drive(monkeypatch)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "driveFolderId": "uf"})

    def delete_user(uid, app=None):
        if uid == "sub-b":
            raise PermissionError("no firebaseauth.admin")

    monkeypatch.setattr(google_auth.fb_auth, "delete_user", delete_user)

    r = await client.delete("/v1/me")

    assert r.status_code == 200, r.text
    row = next(a for a in audited if a["action"] == "ACCOUNT_DELETE")
    assert (row["detail"]["authUsers"], row["detail"]["authUsersDeleted"]) == (2, 1)
    assert row["detail"]["authUserDeleted"] is False
