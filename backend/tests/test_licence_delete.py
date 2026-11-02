"""Deleting a licence into the 30-day hold, and restoring it (repo/deletion.py)."""
from datetime import datetime, timedelta, timezone

import pytest

import repo_view as repo
from license_helpers import _mint_individual, _mint_institution, _signed_in
from refusals import attempt, attempt_add


def _claimed(store, uid):
    return repo.ensure_entitlement({**store._data["users"][uid], "uid": uid}, "and-1")


def _held(store, email="solo@lab.org", uid="solo-1", **kw):
    store._data["users"] = {}
    minted = _mint_individual(email=email, **kw)
    _signed_in(store, uid, email)
    _claimed(store, uid)
    return minted["license"]["id"]


def _seats(store, lid, bucket="licenses"):
    """The seat documents under a licence or a tombstone, by uid."""
    sub = "seats" if bucket == "licenses" else "deleted_seats"
    return store._data.get(f"{bucket}/{lid}/{sub}", {})


def test_a_deleted_licence_is_held_for_thirty_days(store):
    lid = _held(store, expires_at=datetime.now(timezone.utc) + timedelta(days=90))

    err, row = attempt(repo.delete_license, lid, "admin")

    assert err == ""
    assert lid not in store._data["licenses"]
    tomb = store._data["deleted_licenses"][lid]
    assert tomb["priorStatus"] == "redeemed" and tomb["deletedByUid"] == "admin"
    held = tomb["purgeAt"] - datetime.now(timezone.utc)
    assert timedelta(days=29, hours=23) < held <= timedelta(days=30)
    assert row["id"] == lid and row["purgeAt"] == tomb["purgeAt"]


def test_the_holder_drops_to_demo_and_points_at_nothing(store):
    lid = _held(store)

    repo.delete_license(lid, "admin")

    user = store._data["users"]["solo-1"]
    assert user["mode"] == "demo"
    assert "licenseId" not in user and "licenseExpiresAt" not in user
    # Their next request gives them a Demo key of their own.
    after = _claimed(store, "solo-1")
    assert after["licenseId"] and after["licenseId"] != lid
    assert repo.licence_held_by("solo@lab.org") == ""


def test_an_institution_is_deleted_with_its_roster(store):
    store._data["users"] = {}
    lid = _mint_institution()["license"]["id"]
    for i in range(3):
        _signed_in(store, f"m-{i}", f"m{i}@university.edu")
        assert attempt_add(repo.add_institution_member, lid, f"m{i}@university.edu")[0] == ""

    repo.delete_license(lid, "admin")

    assert _seats(store, lid) == {}
    assert set(_seats(store, lid, "deleted_licenses")) == {"m-0", "m-1", "m-2"}
    assert all(s["status"] == "active" for s in _seats(store, lid, "deleted_licenses").values())
    assert all("licenseId" not in store._data["users"][f"m-{i}"] for i in range(3))


def test_a_system_demo_key_is_not_deleted(store):
    store._data["users"] = {}
    user = _signed_in(store, "u-1", "u@lab.org")
    demo = repo.ensure_entitlement(user, "and-1")["licenseId"]
    assert attempt(repo.delete_license, demo, "admin") == ("demo_key_not_deletable", None)
    assert demo in store._data["licenses"]


def test_restoring_puts_the_holder_back(store):
    lid = _held(store)
    repo.delete_license(lid, "admin")
    demo = _claimed(store, "solo-1")["licenseId"]

    err, lic = attempt(repo.restore_license, lid, "admin")

    assert err == "" and lic["status"] == "redeemed"
    user = store._data["users"]["solo-1"]
    assert (user["licenseId"], user["mode"]) == (lid, "licensed")
    assert lid not in store._data.get("deleted_licenses", {})
    # The stand-in Demo key is gone, as after any claim.
    assert demo not in store._data["licenses"]


def test_restoring_leaves_someone_who_has_moved_on(store):
    lid = _held(store)
    repo.delete_license(lid, "admin")
    store._data["users"]["solo-1"].pop("licenseId", None)
    other = _mint_individual()["license"]["id"]
    assert _claimed(store, "solo-1")["licenseId"] == other

    err, lic = attempt(repo.restore_license, lid, "admin")

    assert err == ""
    assert lic["status"] == "unused" and lic["redeemedByUid"] == ""
    assert store._data["users"]["solo-1"]["licenseId"] == other


def test_restoring_an_institution_reseats_its_roster(store):
    store._data["users"] = {}
    lid = _mint_institution(max_seats=5)["license"]["id"]
    for i in range(2):
        _signed_in(store, f"m-{i}", f"m{i}@university.edu")
        repo.add_institution_member(lid, f"m{i}@university.edu")
    repo.delete_license(lid, "admin")

    err, lic = attempt(repo.restore_license, lid, "admin")

    assert err == "" and lic["seatsUsed"] == 2 and lic["status"] == "active"
    assert {s["status"] for s in _seats(store, lid).values()} == {"active"}
    assert all(store._data["users"][f"m-{i}"]["licenseId"] == lid for i in range(2))
    assert _seats(store, lid, "deleted_licenses") == {}


def test_a_revoked_licence_comes_back_revoked(store):
    lid = _held(store)
    repo.revoke_license(lid, "admin")
    repo.delete_license(lid, "admin")
    _claimed(store, "solo-1")

    err, lic = attempt(repo.restore_license, lid, "admin")

    assert (err, lic["status"]) == ("", "revoked")
    assert store._data["users"]["solo-1"]["licenseId"] != lid


def test_an_unclaimed_licence_is_promised_to_its_address_again(store):
    store._data["users"] = {}
    lid = _mint_individual(email="new@lab.org")["license"]["id"]
    repo.delete_license(lid, "admin")
    assert store._data["licenseInvites"] == {}

    repo.restore_license(lid, "admin")

    (invite,) = store._data["licenseInvites"].values()
    assert invite["licenseId"] == lid


def test_nothing_is_restored_after_the_hold(store):
    lid = _held(store)
    repo.delete_license(lid, "admin")
    store._data["deleted_licenses"][lid]["purgeAt"] = datetime.now(timezone.utc) - timedelta(minutes=1)
    assert attempt(repo.restore_license, lid, "admin") == ("deleted_license_purged", None)
    assert attempt(repo.restore_license, "nope", "admin") == ("deleted_license_not_found", None)


def test_the_deleted_list_is_newest_first(store):
    store._data["users"] = {}
    ids = [_mint_individual(email=f"p{i}@lab.org")["license"]["id"] for i in range(3)]
    for i, lid in enumerate(ids):
        repo.delete_license(lid, "admin")
        store._data["deleted_licenses"][lid]["deletedAt"] = datetime(2026, 9, 1 + i, tzinfo=timezone.utc)

    rows, next_token = repo.list_deleted_licenses()

    assert [r["id"] for r in rows] == ids[::-1]
    assert rows[0]["priorStatus"] == "unused"
    assert next_token is None


def test_the_deleted_list_pages_newest_first(store):
    store._data["users"] = {}
    ids = [_mint_individual(email=f"p{i}@lab.org")["license"]["id"] for i in range(5)]
    for i, lid in enumerate(ids):
        repo.delete_license(lid, "admin")
        store._data["deleted_licenses"][lid]["deletedAt"] = datetime(2026, 9, 1 + i, tzinfo=timezone.utc)

    first, token = repo.list_deleted_licenses(limit=2)
    second, token2 = repo.list_deleted_licenses(limit=2, page_token=token)
    third, token3 = repo.list_deleted_licenses(limit=2, page_token=token2)

    assert [r["id"] for r in first] == [ids[4], ids[3]]
    assert token == ids[3]
    assert [r["id"] for r in second] == [ids[2], ids[1]]
    assert [r["id"] for r in third] == [ids[0]]
    assert token3 is None


@pytest.mark.asyncio
async def test_the_deleted_list_route_pages(store, client):
    store._data["users"] = {}
    ids = [_mint_individual(email=f"q{i}@lab.org")["license"]["id"] for i in range(3)]
    for i, lid in enumerate(ids):
        repo.delete_license(lid, "admin")
        store._data["deleted_licenses"][lid]["deletedAt"] = datetime(2026, 9, 1 + i, tzinfo=timezone.utc)

    first = (await client.get("/v1/admin/deleted-licenses?limit=2")).json()
    assert [r["id"] for r in first["licenses"]] == [ids[2], ids[1]]
    assert first["page"]["hasMore"] is True
    token = first["page"]["nextPageToken"]
    rest = (await client.get(f"/v1/admin/deleted-licenses?limit=2&page_token={token}")).json()
    assert [r["id"] for r in rest["licenses"]] == [ids[0]]
    assert rest["page"]["hasMore"] is False
    assert rest["page"]["nextPageToken"] is None


# ------------------------------------------------- phones (ADR-033, review L2-2)


def _phone(store, uid, device_id):
    store._data.setdefault("devices", {})[device_id] = {
        "uid": uid, "status": "ACTIVE", "app": "semper"}
    assert attempt(repo.activate_device, uid, device_id, "semper")[0] == ""


def _acts(store, lid):
    return store._data.get(f"licenses/{lid}/activations", {})


def _moves_on(store, uid, email):
    """`uid` takes another live licence while `email`'s old one is held."""
    store._data["users"][uid].pop("licenseId", None)
    other = _mint_individual(email=email)["license"]["id"]
    assert _claimed(store, uid)["licenseId"] == other


def test_a_seat_restored_revoked_frees_its_phone_slots(store):
    """The member who moved on kept the pool's slot: `devicesActive` came
    back from the copy and their activation stayed active."""
    store._data["users"] = {}
    lid = _mint_institution(max_seats=5)["license"]["id"]
    store._data["licenses"][lid].update(app="semper", maxDevices=2)
    for i in range(2):
        _signed_in(store, f"m-{i}", f"m{i}@university.edu")
        assert attempt_add(repo.add_institution_member, lid, f"m{i}@university.edu")[0] == ""
        _phone(store, f"m-{i}", f"m{i}-phone")
    repo.delete_license(lid, "admin")
    _moves_on(store, "m-1", "m1@university.edu")

    assert attempt(repo.restore_license, lid, "admin")[0] == ""

    lic, seats, acts = store._data["licenses"][lid], _seats(store, lid), _acts(store, lid)
    assert seats["m-1"]["status"] == "revoked"
    assert acts["m1-phone"]["status"] == "deactivated"
    assert acts["m1-phone"]["deactivatedByUid"] == "admin"
    assert acts["m0-phone"]["status"] == "active"
    assert lic["devicesActive"] == 1
    assert (seats["m-0"]["devicesActive"], seats["m-1"]["devicesActive"]) == (1, 0)
    assert seats["m-1"]["deviceIdLock"] == "" and seats["m-0"]["deviceIdLock"] == "m0-phone"
    # The freed slot takes another member's phone.
    _signed_in(store, "m-2", "m2@university.edu")
    assert attempt_add(repo.add_institution_member, lid, "m2@university.edu")[0] == ""
    _phone(store, "m-2", "m2-phone")


def test_a_licence_restored_unused_takes_its_next_holders_phone(store):
    lid = _held(store)
    store._data["licenses"][lid].update(app="semper", maxDevices=1)
    _phone(store, "solo-1", "solo-phone")
    repo.delete_license(lid, "admin")
    _moves_on(store, "solo-1", "solo@lab.org")

    assert attempt(repo.restore_license, lid, "admin")[0] == ""

    lic = store._data["licenses"][lid]
    assert lic["status"] == "unused" and lic["devicesActive"] == 0
    assert lic["deviceIdLock"] == ""
    assert _acts(store, lid)["solo-phone"]["status"] == "deactivated"
    # Its phone is still the moved-on holder's: only the activation went.
    assert store._data["devices"]["solo-phone"]["status"] == "ACTIVE"


def test_a_holder_put_back_keeps_their_phone(store):
    lid = _held(store)
    store._data["licenses"][lid].update(app="semper", maxDevices=1)
    _phone(store, "solo-1", "solo-phone")
    repo.delete_license(lid, "admin")
    _claimed(store, "solo-1")

    assert attempt(repo.restore_license, lid, "admin")[0] == ""

    assert store._data["licenses"][lid]["devicesActive"] == 1
    assert _acts(store, lid)["solo-phone"]["status"] == "active"


@pytest.mark.asyncio
async def test_the_routes(store, client, audited):
    lid = _held(store)

    resp = await client.delete(f"/v1/admin/licenses/{lid}")
    assert resp.status_code == 200, resp.text
    assert resp.json()["id"] == lid
    listed = await client.get("/v1/admin/deleted-licenses")
    assert [r["id"] for r in listed.json()["licenses"]] == [lid]
    restored = await client.post(f"/v1/admin/deleted-licenses/{lid}/restore")
    assert restored.status_code == 200, restored.text
    assert (await client.get(f"/v1/admin/licenses/{lid}")).status_code == 200
    again = await client.post(f"/v1/admin/deleted-licenses/{lid}/restore")
    assert again.status_code == 404
    missing = await client.delete("/v1/admin/licenses/nope")
    assert missing.status_code == 404
    actions = [r["action"] for r in audited]
    assert "ADMIN_LICENSE_DELETE" in actions and "ADMIN_LICENSE_RESTORE" in actions
