"""Moving a licence or seat to a new device: the holder's own change and the admin unbind."""
from datetime import datetime, timedelta, timezone

import pytest

import fake_firestore

from app import deps, firestore_repo as repo
from app.config import settings
from key_helpers import _ec_pem
from license_helpers import (  # noqa: F401
    _mint_individual,
    _mint_institution,
    _recording_stubs,
    _signed_in,
)


# ======================================================= changing device (D)
# One primitive, three actors. Since an individual licence binds on first use,
# emptying the lock *is* the device change: whoever signs in next takes it.
# The holder's own change is the one that carries a cooldown, because a second
# factor proves who is asking and not how often.


def _bound_individual(store, device_id="old-phone"):
    """An individual licence held by solo-1 and bound to `device_id`."""
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    repo.revalidate_device_lock(user, device_id)
    assert store._data["licenses"][license_id]["deviceIdLock"] == device_id
    return license_id


def _bound_seat(store, device_id="old-phone"):
    """An institution seat held by u1 and bound to `device_id`."""
    store._data["users"] = {
        "u1": {"uid": "u1", "email": "a@university.edu",
               "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", device_id, minted["key"])
    return license_id


def test_staff_clear_an_individual_lock_and_the_next_device_binds(store):
    license_id = _bound_individual(store)

    err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert cleared["scope"] == "license"
    assert cleared["previousDeviceId"] == "old-phone"
    rebound = repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")
    assert rebound["mode"] == "licensed"
    assert store._data["licenses"][license_id]["deviceIdLock"] == "new-phone"


def test_it_clears_a_seat_and_the_next_device_binds(store):
    license_id = _bound_seat(store)

    err, cleared = repo.clear_device_lock(license_id, "u1", actor=repo.ACTOR_IT)

    assert err == ""
    assert cleared["scope"] == "seat"
    assert cleared["previousDeviceId"] == "old-phone"
    rebound = repo.revalidate_device_lock(store._data["users"]["u1"], "new-phone")
    assert rebound["mode"] == "licensed"
    seats = store._data[f"licenses/{license_id}/seats"]
    assert seats["u1"]["deviceIdLock"] == "new-phone"


def test_the_holder_changes_their_own_device(store):
    license_id = _bound_individual(store)

    err, cleared = repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF)

    assert err == ""
    assert cleared["previousDeviceId"] == "old-phone"
    # Only the self-service path stamps the clock the cooldown is read from.
    assert store._data["licenses"][license_id]["deviceChangedAt"]
    assert cleared["nextChangeAllowedAt"]
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")
    assert store._data["licenses"][license_id]["deviceIdLock"] == "new-phone"


def test_a_second_self_service_change_inside_the_cooldown_is_refused(store):
    """The licence-sharing vector: one person re-binding daily passes a single
    licence round a lab. A second factor cannot see that; the cooldown can."""
    license_id = _bound_individual(store)
    assert repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF)[0] == ""
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")

    err, cleared = repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF)

    assert err == "device_change_too_soon"
    # The refusal says when (TD-116): a cooldown from the change just made.
    changed = store._data["licenses"][license_id]["deviceChangedAt"]
    assert cleared == {"nextChangeAllowedAt": (
        changed + timedelta(days=settings.SELF_DEVICE_CHANGE_COOLDOWN_DAYS)
    ).isoformat()}
    assert store._data["licenses"][license_id]["deviceIdLock"] == "new-phone"


def test_the_cooldown_lapses(store):
    license_id = _bound_individual(store)
    store._data["licenses"][license_id]["deviceChangedAt"] = (
        datetime.now(timezone.utc)
        - timedelta(days=settings.SELF_DEVICE_CHANGE_COOLDOWN_DAYS + 1)
    )

    assert repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF)[0] == ""


def test_a_support_clear_ignores_the_cooldown(store):
    """A lost phone does not wait 30 days. Staff and IT neither read nor write
    the stamp, so a support request always works however recently the holder
    changed device themselves."""
    license_id = _bound_individual(store)
    repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF)
    stamped = store._data["licenses"][license_id]["deviceChangedAt"]
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")

    err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert cleared["previousDeviceId"] == "new-phone"
    assert store._data["licenses"][license_id]["deviceChangedAt"] == stamped


def test_clearing_a_lock_is_not_revoking(store):
    """The lock goes empty and nothing else moves: the seat still counts
    against maxSeats, the holder keeps their entitlement, and no data is
    touched."""
    license_id = _bound_seat(store)
    assert store._data["licenses"][license_id]["seatsUsed"] == 1

    repo.clear_device_lock(license_id, "u1", actor=repo.ACTOR_STAFF)

    assert store._data["licenses"][license_id]["seatsUsed"] == 1
    assert store._data["licenses"][license_id]["status"] == "active"
    seat = store._data[f"licenses/{license_id}/seats"]["u1"]
    assert (seat["status"], seat["deviceIdLock"]) == ("active", "")
    assert store._data["users"]["u1"]["plan"] == "professional"


def test_a_revoked_licence_is_not_re_entitled_by_clearing_its_lock(store):
    """Clearing restores a mode the mismatch took away — it must not restore
    one a revoke took away. Support clearing a lock on a dead licence would
    otherwise hand the holder their entitlement back."""
    license_id = _bound_individual(store)
    repo.revoke_license(license_id, "admin")
    assert store._data["users"]["solo-1"]["mode"] == "demo"

    err, _ = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert store._data["users"]["solo-1"]["mode"] == "demo"


def test_a_device_change_is_audited_at_both_ends(store, audited):
    """The record has to name the device given up and the one that took its
    place; neither half is the change on its own."""
    license_id = _bound_individual(store)
    err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)
    assert (err, cleared["previousDeviceId"]) == ("", "old-phone")
    assert cleared["releasedDeviceId"] == ""  # nothing was registered
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")

    bound = [r for r in audited if r["action"] == "LICENSE_DEVICE_BIND"]
    assert [r["deviceId"] for r in bound] == ["old-phone", "new-phone"]
    assert bound[-1]["target"] == {"type": "license", "id": license_id}


def test_restore_on_a_new_device_waits_for_the_lock_to_move(store):
    """The ordering Gap D's runbook turns on.

    `/v1/files/{id}/content` is device-attested, and `verified_device`
    re-validates the lock before the route reads `cloud_backup_enabled`. So on
    a device the licence is not bound to, restore is refused by the licence
    check rather than half-served; once the lock has moved and the new device
    has bound, the same call is entitled.
    """
    license_id = _bound_individual(store)

    # Before: the new phone is not the bound device, so the account resolves
    # to demo for this request and cloud restore is not licensed.
    demoted = repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")
    assert demoted["mode"] == "demo"
    assert repo.cloud_backup_enabled(demoted) is False

    repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    # After: the first authed request from the new phone binds the lock, and
    # the restore that follows it is entitled.
    rebound = repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")
    assert rebound["mode"] == "licensed"
    assert repo.cloud_backup_enabled(rebound) is True


# A cleared lock has to let the new phone register, not only bind. Registration
# checks `users/{uid}.activeDeviceId`, which the lock clear used to leave
# naming the old phone: every new phone got 409 device_conflict at sign-in.


def _registered_on(store, uid, device_id="old-phone"):
    """`uid` signed in on `device_id` the way the app does: registered, active."""
    store._data["users"][uid]["activeDeviceId"] = device_id
    store._data.setdefault("devices", {})[device_id] = {"uid": uid, "status": "ACTIVE"}


def test_staff_clear_releases_the_old_phone(store):
    license_id = _bound_individual(store)
    _registered_on(store, "solo-1")

    assert repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)[0] == ""

    assert "activeDeviceId" not in store._data["users"]["solo-1"]
    assert store._data["devices"]["old-phone"]["status"] == "SUPERSEDED"


def test_it_clear_releases_the_seat_holders_phone(store):
    license_id = _bound_seat(store)
    _registered_on(store, "u1")

    assert repo.clear_device_lock(license_id, "u1", actor=repo.ACTOR_IT)[0] == ""

    assert "activeDeviceId" not in store._data["users"]["u1"]


def test_a_refused_self_change_keeps_the_phone(store):
    license_id = _bound_individual(store)
    _registered_on(store, "solo-1")
    assert repo.clear_device_lock(license_id, actor=repo.ACTOR_SELF)[0] == ""
    _registered_on(store, "solo-1", "second-phone")

    err, _ = repo.clear_device_lock(license_id, actor=repo.ACTOR_SELF)

    assert err == "device_change_too_soon"
    assert store._data["users"]["solo-1"]["activeDeviceId"] == "second-phone"


def test_an_account_on_another_licence_keeps_its_phone(store):
    license_id = _bound_individual(store)
    _registered_on(store, "solo-1")
    store._data["users"]["solo-1"]["licenseId"] = "a-later-licence"

    assert repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)[0] == ""

    assert store._data["users"]["solo-1"]["activeDeviceId"] == "old-phone"


def test_the_clear_names_the_phone_it_signed_out(store):
    """The audit needs the lock's device and the registered one, since the
    release stamp on the user is gone at the next registration."""
    license_id = _bound_individual(store)
    _registered_on(store, "solo-1")

    err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert cleared["previousDeviceId"] == "old-phone"
    assert cleared["releasedDeviceId"] == "old-phone"


def _stored_demotion(store, uid="solo-1"):
    """The Demo that `revalidate_device_lock` used to write on a lock mismatch.
    It no longer does, but accounts demoted before that change still carry it,
    and a device-lock clear is what restores them."""
    store._data["users"][uid].update({"mode": "demo", "plan": "demo"})


def _split(store, lock="emulator", registered="pixel"):
    """solo-1's licence locked to one device and the account registered on
    another: what a refused sign-in left behind while any device could bind."""
    license_id = _bound_individual(store, lock)
    _registered_on(store, "solo-1", registered)
    assert repo.revalidate_device_lock(store._data["users"]["solo-1"], registered)["mode"] == "demo"
    _stored_demotion(store)
    return license_id


def test_a_clear_keeps_the_registered_phone_the_lock_did_not_name(store):
    license_id = _split(store)

    err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert cleared["previousDeviceId"] == "emulator"
    assert cleared["releasedDeviceId"] == ""
    user = store._data["users"]["solo-1"]
    assert user["activeDeviceId"] == "pixel"
    assert user["mode"] == "licensed"
    assert "releasedDeviceId" not in user
    assert store._data["devices"]["pixel"]["status"] == "ACTIVE"


def test_after_that_clear_the_registered_phone_takes_the_lock(store):
    license_id = _split(store)
    assert repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)[0] == ""

    assert repo.revalidate_device_lock(store._data["users"]["solo-1"], "emulator")["mode"] == "licensed"
    assert store._data["licenses"][license_id]["deviceIdLock"] == ""
    rebound = repo.revalidate_device_lock(store._data["users"]["solo-1"], "pixel")

    assert rebound["mode"] == "licensed"
    assert store._data["licenses"][license_id]["deviceIdLock"] == "pixel"


def test_a_second_clear_then_releases_the_registered_phone(store):
    """A holder who did want a new phone is one more clear away from it."""
    license_id = _split(store)
    assert repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)[0] == ""
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "pixel")

    err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert cleared["releasedDeviceId"] == "pixel"
    assert "activeDeviceId" not in store._data["users"]["solo-1"]


def test_a_self_change_on_a_split_account_keeps_the_registered_phone(store):
    license_id = _split(store)

    err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_SELF)

    assert err == ""
    assert cleared["releasedDeviceId"] == ""
    assert store._data["users"]["solo-1"]["activeDeviceId"] == "pixel"
    assert store._data["users"]["solo-1"]["mode"] == "licensed"


# Only the registered phone takes an empty lock. A phone refused at
# registration still makes config and profile calls, and those used to bind it.


def test_a_phone_the_account_has_not_registered_cannot_take_the_lock(store):
    license_id = _bound_individual(store)
    assert repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)[0] == ""
    _registered_on(store, "solo-1", "pixel")

    seen = repo.revalidate_device_lock(store._data["users"]["solo-1"], "emulator")

    assert seen["mode"] == "licensed"
    assert store._data["licenses"][license_id]["deviceIdLock"] == ""
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "pixel")
    assert store._data["licenses"][license_id]["deviceIdLock"] == "pixel"
    assert store._data["users"]["solo-1"]["mode"] == "licensed"


def test_a_released_phone_cannot_retake_the_lock_during_its_hold(store):
    license_id = _bound_individual(store)
    _registered_on(store, "solo-1")
    assert repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)[0] == ""

    repo.revalidate_device_lock(store._data["users"]["solo-1"], "old-phone")

    assert store._data["licenses"][license_id]["deviceIdLock"] == ""
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")
    assert store._data["licenses"][license_id]["deviceIdLock"] == "new-phone"


def test_a_key_typed_on_a_phone_the_account_has_not_registered_does_not_bind(store):
    store._data["users"] = {}
    minted = _mint_individual()
    license_id = minted["license"]["id"]
    _signed_in(store, "solo-1", "solo@lab.org")
    _registered_on(store, "solo-1", "pixel")

    err, _ = repo.activate_license("solo-1", "solo@lab.org", "emulator", minted["key"])

    assert err == "license_device_mismatch"
    assert store._data["licenses"][license_id].get("deviceIdLock", "") == ""
    err, _ = repo.activate_license("solo-1", "solo@lab.org", "pixel", minted["key"])
    assert err == ""
    assert store._data["licenses"][license_id]["deviceIdLock"] == "pixel"


def test_a_seat_binds_only_its_members_registered_phone(store):
    license_id = _bound_seat(store)
    assert repo.clear_device_lock(license_id, "u1", actor=repo.ACTOR_IT)[0] == ""
    _registered_on(store, "u1", "pixel")

    repo.revalidate_device_lock(store._data["users"]["u1"], "emulator")
    seats = store._data[f"licenses/{license_id}/seats"]
    assert seats["u1"]["deviceIdLock"] == ""
    repo.revalidate_device_lock(store._data["users"]["u1"], "pixel")
    assert seats["u1"]["deviceIdLock"] == "pixel"


def test_a_self_change_names_the_phone_it_signed_out(store):
    license_id = _bound_individual(store)
    _registered_on(store, "solo-1")

    err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_SELF)

    assert err == ""
    assert cleared["releasedDeviceId"] == "old-phone"
    assert cleared["nextChangeAllowedAt"]


def test_the_mode_and_the_release_land_together(store, monkeypatch):
    """One batch: a failed commit leaves the holder as they were, not with their
    mode back and the old phone still bound."""
    license_id = _bound_individual(store)
    _registered_on(store, "solo-1")
    _stored_demotion(store)

    def _fail(self):
        raise RuntimeError("commit failed")

    monkeypatch.setattr(fake_firestore._Batch, "commit", _fail)
    with pytest.raises(RuntimeError):
        repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    user = store._data["users"]["solo-1"]
    assert user["mode"] == "demo"
    assert user["activeDeviceId"] == "old-phone"


def test_a_release_stamps_updated_at(store):
    license_id = _bound_individual(store)
    _registered_on(store, "solo-1")
    store._data["users"]["solo-1"].pop("updatedAt", None)

    assert repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)[0] == ""

    assert "updatedAt" in store._data["users"]["solo-1"]


# A clear releases the phone only where it would also restore the mode. A held
# or revoked seat, or a revoked licence, leaves its holder on Demo, and a Demo
# account does not get to change phone (TD-126).


def test_new_device_on_a_held_seat_keeps_the_phone(store):
    license_id = _bound_seat(store)
    _registered_on(store, "u1")
    assert repo.set_seat_enabled(license_id, "u1", False) == ""

    err, cleared = repo.clear_device_lock(license_id, "u1", actor=repo.ACTOR_IT)

    assert (err, cleared["releasedDeviceId"]) == ("", "")

    assert store._data["users"]["u1"]["activeDeviceId"] == "old-phone"
    assert store._data["devices"]["old-phone"]["status"] == "ACTIVE"


def test_new_device_on_a_revoked_seat_keeps_the_phone(store):
    license_id = _bound_seat(store)
    _registered_on(store, "u1")
    assert repo.revoke_institution_seat(license_id, "u1") is True
    assert store._data["users"]["u1"]["licenseId"] == license_id  # a revoke keeps it

    assert repo.clear_device_lock(license_id, "u1", actor=repo.ACTOR_STAFF)[0] == ""

    assert store._data["users"]["u1"]["activeDeviceId"] == "old-phone"


def test_new_device_on_a_revoked_licence_keeps_the_phone(store):
    license_id = _bound_individual(store)
    _registered_on(store, "solo-1")
    assert repo.revoke_license(license_id, "staff-1") is not None
    assert store._data["users"]["solo-1"]["licenseId"] == license_id  # a revoke keeps it

    assert repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)[0] == ""

    assert store._data["users"]["solo-1"]["activeDeviceId"] == "old-phone"
    assert "releasedDeviceId" not in store._data["users"]["solo-1"]


@pytest.mark.asyncio
async def test_after_a_clear_the_new_phone_registers_over_http(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    _dev_user_holds(store, monkeypatch)
    _registered_on(store, "dev-user")
    monkeypatch.setattr(deps, "_DEV_USER", dict(store._data["users"]["dev-user"]))
    new_phone = {"deviceId": "and-newphone1", "publicKeyPem": _ec_pem()}
    refused = await client.post("/v1/devices/register", json=new_phone)
    assert refused.status_code == 409, "the old phone still holds the account"

    assert (await client.post("/v1/licenses/unbind")).status_code == 200
    monkeypatch.setattr(deps, "_DEV_USER", dict(store._data["users"]["dev-user"]))
    resp = await client.post("/v1/devices/register", json=new_phone)

    assert resp.status_code == 201, resp.text
    assert store._data["users"]["dev-user"]["activeDeviceId"] == "and-newphone1"


# The released phone must not take the account straight back. Its upload
# worker re-registers as soon as a signed call reads `device_not_active`, which
# the clear has just made every call from it read.


def test_a_clear_holds_the_released_phone_off(store):
    license_id = _bound_individual(store)
    _registered_on(store, "solo-1")

    assert repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)[0] == ""

    user = store._data["users"]["solo-1"]
    assert user["releasedDeviceId"] == "old-phone"
    assert repo.released_device_held(user, "old-phone") is True
    assert repo.released_device_held(user, "new-phone") is False


def test_the_hold_runs_out(store, monkeypatch):
    user = {"releasedDeviceId": "old-phone",
            "releasedAt": datetime.now(timezone.utc)
            - timedelta(hours=settings.DEVICE_RELEASE_HOLD_HOURS, minutes=1)}
    assert repo.released_device_held(user, "old-phone") is False

    user["releasedAt"] = datetime.now(timezone.utc)
    monkeypatch.setattr(settings, "DEVICE_RELEASE_HOLD_HOURS", 0)
    assert repo.released_device_held(user, "old-phone") is False


def _as_dev_user(store, monkeypatch):
    monkeypatch.setattr(deps, "_DEV_USER", dict(store._data["users"]["dev-user"]))


@pytest.mark.asyncio
async def test_the_old_phone_cannot_take_the_account_back(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    _dev_user_holds(store, monkeypatch, device_id="and-oldphone1")
    _registered_on(store, "dev-user", "and-oldphone1")
    _as_dev_user(store, monkeypatch)
    assert (await client.post("/v1/licenses/unbind")).status_code == 200
    _as_dev_user(store, monkeypatch)
    old_phone = {"deviceId": "and-oldphone1", "publicKeyPem": _ec_pem()}

    # What the old phone's upload worker does after `device_not_active`.
    refused = await client.post("/v1/devices/register", json=old_phone)

    assert refused.status_code == 409
    assert refused.json()["detail"] == "device_conflict"
    assert "activeDeviceId" not in store._data["users"]["dev-user"]
    new_phone = {"deviceId": "and-newphone1", "publicKeyPem": _ec_pem()}
    assert (await client.post("/v1/devices/register", json=new_phone)).status_code == 201
    user = store._data["users"]["dev-user"]
    assert user["activeDeviceId"] == "and-newphone1"
    assert "releasedDeviceId" not in user and "releasedAt" not in user


@pytest.mark.asyncio
async def test_the_old_phone_comes_back_once_the_hold_runs_out(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    _dev_user_holds(store, monkeypatch, device_id="and-oldphone1")
    _registered_on(store, "dev-user", "and-oldphone1")
    _as_dev_user(store, monkeypatch)
    assert (await client.post("/v1/licenses/unbind")).status_code == 200
    store._data["users"]["dev-user"]["releasedAt"] -= timedelta(
        hours=settings.DEVICE_RELEASE_HOLD_HOURS, minutes=1)
    _as_dev_user(store, monkeypatch)

    old_phone = {"deviceId": "and-oldphone1", "publicKeyPem": _ec_pem()}
    resp = await client.post("/v1/devices/register", json=old_phone)

    assert resp.status_code == 201, resp.text
    user = store._data["users"]["dev-user"]
    assert user["activeDeviceId"] == "and-oldphone1"
    assert "releasedDeviceId" not in user


def test_clearing_a_lock_on_a_licence_that_does_not_exist(store):
    store._data["users"] = {}
    assert repo.clear_device_lock("no-such-licence")[0] == "license_not_found"


def test_clearing_a_seat_that_does_not_exist(store):
    license_id = _mint_institution()["license"]["id"]
    assert repo.clear_device_lock(license_id, "nobody")[0] == "seat_not_found"
    # An institution licence holds no lock of its own — there is no seat to
    # name, so this is a caller error rather than a licence-wide clear.
    assert repo.clear_device_lock(license_id)[0] == "seat_not_found"


def _dev_user_holds(store, monkeypatch, email="dev@local", device_id="old-phone"):
    """Sign the DEV_INSECURE_AUTH caller in against an individual licence.

    `current_user` returns the literal `deps._DEV_USER` in dev mode rather
    than reading the store, so a route that acts on the caller's own licence
    sees a licenceless account unless that dict is the one carrying it.
    """
    license_id = _mint_individual(email)["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "dev-user", email), None)
    repo.revalidate_device_lock(user, device_id)
    monkeypatch.setattr(deps, "_DEV_USER", dict(store._data["users"]["dev-user"]))
    return license_id


@pytest.mark.asyncio
async def test_the_holder_changes_device_over_http(client, monkeypatch, audited):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    license_id = _dev_user_holds(store, monkeypatch)

    resp = await client.post("/v1/licenses/unbind")

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["deviceIdLock"] == ""
    assert body["previousDeviceId"] == "old-phone"
    assert body["nextChangeAllowedAt"]
    assert store._data["licenses"][license_id]["deviceIdLock"] == ""
    assert [r["action"] for r in audited if r["action"] == "LICENSE_DEVICE_UNBIND"]


@pytest.mark.asyncio
async def test_a_holder_with_no_licence_has_no_device_to_change(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {"dev-user": {"uid": "dev-user", "email": "dev@local"}}
    monkeypatch.setattr(deps, "_DEV_USER", dict(store._data["users"]["dev-user"]))

    resp = await client.post("/v1/licenses/unbind")

    assert resp.status_code == 404
    assert resp.json()["detail"] == "no_license"


@pytest.mark.asyncio
async def test_the_holder_cannot_change_device_twice_over_http(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    _dev_user_holds(store, monkeypatch)
    assert (await client.post("/v1/licenses/unbind")).status_code == 200

    resp = await client.post("/v1/licenses/unbind")

    assert resp.status_code == 429
    # The code, then when the holder may change again (TD-116).
    code, _, when = resp.json()["detail"].partition(": ")
    assert code == "device_change_too_soon"
    assert datetime.fromisoformat(when) > datetime.now(timezone.utc)
    assert int(resp.headers["Retry-After"]) > 0


@pytest.mark.asyncio
async def test_staff_clear_an_individual_lock_over_http(client, monkeypatch, audited):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    repo.revalidate_device_lock(user, "old-phone")

    resp = await client.patch(
        f"/v1/admin/licenses/{license_id}", json={"clearDeviceLock": True},
    )

    assert resp.status_code == 200, resp.text
    assert resp.json()["deviceIdLock"] == ""
    assert store._data["licenses"][license_id]["deviceIdLock"] == ""
    cleared = [r for r in audited if r["action"] == "ADMIN_DEVICE_LOCK_CLEAR"]
    assert cleared and cleared[0]["detail"]["previousDeviceId"] == "old-phone"


@pytest.mark.asyncio
async def test_staff_clear_a_seat_lock_over_http(client, monkeypatch, audited):
    """The gap this route closes: the operator console used to call the
    institution-tier route, which 404s for staff who are not in that licence's
    adminEmails — every licence Semper did not itself administer."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    license_id = _bound_seat(store)

    resp = await client.patch(f"/v1/admin/licenses/{license_id}/seats/u1/device")

    assert resp.status_code == 200, resp.text
    assert resp.json() == {
        "licenseId": license_id, "uid": "u1",
        "deviceIdLock": "", "previousDeviceId": "old-phone",
        "previousDeviceIdMaterialTesting": "",
    }
    assert store._data[f"licenses/{license_id}/seats"]["u1"]["deviceIdLock"] == ""
    assert [r for r in audited if r["action"] == "ADMIN_DEVICE_LOCK_CLEAR"]
