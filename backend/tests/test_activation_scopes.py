"""Who may deactivate which phone (ADR-033): the role matrix for
`repo.deactivate_device`.

Each scope is checked in the repo, not only by the route a call came in on,
so a route cannot widen what its caller may touch. A phone outside the
caller's scope answers `activation_not_found`, the same as one that is not
there, so the answer says nothing about other members or other licences."""
import pytest

from app import apps
import repo_view as repo
from license_helpers import _mint_individual, _signed_in
from refusals import attempt


IT = {"uid": "it-1", "email": "it@university.edu", "emailVerified": True}
OTHER_IT = {"uid": "it-2", "email": "it@college.edu", "emailVerified": True}
STAFF = {"uid": "staff-1", "email": "ops@sempermechanics.com", "role": "admin"}


def _phone(store, uid, device_id):
    store._data.setdefault("devices", {})[device_id] = {
        "uid": uid, "status": "ACTIVE", "app": "semper"}


def _user(store, uid):
    return {**store._data["users"][uid], "uid": uid}


def _seated(store, license_id, uid, email):
    _signed_in(store, uid, email)
    assert attempt(repo.add_institution_member, license_id, email)[0] == ""


@pytest.fixture
def pool(store):
    """An institution licence on university.edu, three phones active: two
    members on the domain and an outside collaborator staff seated."""
    store._data["users"] = {}
    license_id = repo.create_institution_license(
        domain_lock="university.edu", admin_emails=["it@university.edu"],
        created_by_uid="admin")["license"]["id"]
    _seated(store, license_id, "m1", "m1@university.edu")
    _seated(store, license_id, "m2", "m2@university.edu")
    _seated(store, license_id, "ext", "ext@partner.org")
    store._data["licenses"][license_id].update(app="semper", maxDevices=5)
    for uid in ("m1", "m2", "ext"):
        _phone(store, uid, f"{uid}-phone")
        assert attempt(repo.activate_device, uid, f"{uid}-phone", "semper")[0] == ""
    return license_id


def _deactivate(license_id, device_id, actor, caller):
    return attempt(repo.deactivate_device, license_id, device_id, actor=actor, caller=caller)[0]


# ------------------------------------------------------------------ holder


def test_the_holder_deactivates_their_own_phone(store, pool):
    assert _deactivate(pool, "m1-phone", repo.ACTOR_SELF, _user(store, "m1")) == ""


def test_the_holder_cannot_touch_another_members_phone(store, pool):
    """Same pool, same licence: still not theirs."""
    assert _deactivate(pool, "m2-phone", repo.ACTOR_SELF, _user(store, "m1")) == "activation_not_found"
    acts = store._data[f"licenses/{pool}/activations"]
    assert acts["m2-phone"]["status"] == "active"


def test_a_holder_deactivation_keeps_the_phone_signed_in(store, pool):
    """Their phone stays theirs: only the licence lets go of it."""
    assert _deactivate(pool, "m1-phone", repo.ACTOR_SELF, _user(store, "m1")) == ""

    assert store._data["devices"]["m1-phone"]["status"] == "ACTIVE"
    assert "releasedDeviceId" not in store._data["users"]["m1"]


# ---------------------------------------------------------------------- IT


def test_it_deactivates_a_member_on_the_domain(store, pool):
    assert _deactivate(pool, "m1-phone", repo.ACTOR_IT, IT) == ""


def test_it_cannot_reach_a_member_off_the_domain(store, pool):
    """Staff may seat a collaborator from elsewhere; IT administers only its
    own domain's members."""
    assert _deactivate(pool, "ext-phone", repo.ACTOR_IT, IT) == "activation_not_found"
    assert store._data[f"licenses/{pool}/activations"]["ext-phone"]["status"] == "active"


def test_another_institutions_it_cannot_reach_this_licence(store, pool):
    repo.create_institution_license(domain_lock="college.edu", admin_emails=["it@college.edu"],
                                    created_by_uid="admin")

    assert _deactivate(pool, "m1-phone", repo.ACTOR_IT, OTHER_IT) == "activation_not_found"


def test_it_with_an_unverified_address_is_nobody(store, pool):
    assert _deactivate(pool, "m1-phone", repo.ACTOR_IT,
                       {**IT, "emailVerified": False}) == "activation_not_found"


def test_it_cannot_reach_an_individual_licence(store):
    store._data["users"] = {}
    license_id = _mint_individual("solo@university.edu")["license"]["id"]
    repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@university.edu"), None)
    store._data["licenses"][license_id].update(app="semper", maxDevices=1)
    _phone(store, "solo-1", "solo-phone")
    assert attempt(repo.activate_device, "solo-1", "solo-phone", "semper")[0] == ""

    assert _deactivate(license_id, "solo-phone", repo.ACTOR_IT, IT) == "activation_not_found"


def test_an_it_deactivation_signs_the_phone_out(store, pool):
    """The phone may go to someone else, so its registration is released and
    the release hold stamped."""
    err, out = attempt(repo.deactivate_device, pool, "m1-phone", actor=repo.ACTOR_IT, caller=IT)

    assert err == ""
    assert out["released"] is True
    assert store._data["devices"]["m1-phone"]["status"] != "ACTIVE"
    assert store._data["users"]["m1"]["releasedDeviceId"] == "m1-phone"


# ------------------------------------------------------------------- staff


def test_staff_deactivate_any_phone(store, pool):
    for device_id in ("m1-phone", "m2-phone", "ext-phone"):
        assert _deactivate(pool, device_id, repo.ACTOR_STAFF, STAFF) == ""
    assert store._data["licenses"][pool]["devicesActive"] == 0


def test_staff_deactivate_on_an_individual_licence(store):
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    store._data["licenses"][license_id].update(app="semper", maxDevices=1)
    _phone(store, "solo-1", "solo-phone")
    assert attempt(repo.activate_device, "solo-1", "solo-phone", "semper")[0] == ""

    assert _deactivate(license_id, "solo-phone", repo.ACTOR_STAFF, STAFF) == ""


# --------------------------------------------- the roster's device clear (L2-6)


def test_it_cannot_clear_a_member_off_the_domain_by_the_roster(store, pool):
    """The roster's **New device** deactivates the member's phones. IT's
    devices route refuses a member off the domain; the clear did not ask."""
    err, _ = attempt(repo.clear_device_lock, pool, "ext", actor=repo.ACTOR_IT, caller=IT)

    assert err == "activation_not_found"
    assert store._data[f"licenses/{pool}/activations"]["ext-phone"]["status"] == "active"
    assert store._data["devices"]["ext-phone"]["status"] == "ACTIVE"


def test_it_clears_a_member_on_the_domain_by_the_roster(store, pool):
    err, out = attempt(repo.clear_device_lock, pool, "m1", actor=repo.ACTOR_IT, caller=IT)

    assert err == "" and out["deactivated"] == ["m1-phone"]
    act = store._data[f"licenses/{pool}/activations"]["m1-phone"]
    assert act["status"] == "deactivated" and act["deactivatedByUid"] == "it-1"


def test_staff_clear_any_member_by_the_roster(store, pool):
    err, out = attempt(repo.clear_device_lock, pool, "ext", actor=repo.ACTOR_STAFF, caller=STAFF)
    assert err == "" and out["deactivated"] == ["ext-phone"]


@pytest.mark.asyncio
async def test_the_roster_route_holds_it_to_the_domain(store, pool, client, monkeypatch):
    from app import deps

    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "uid": "it-1",
                                            "email": "it@university.edu", "role": "user"})
    base = f"/v1/institutions/licenses/{pool}/seats"

    off = await client.patch(f"{base}/ext", json={"clearDeviceLock": True})
    on = await client.patch(f"{base}/m1", json={"clearDeviceLock": True})

    assert (off.status_code, off.json()["detail"]) == (404, "activation_not_found")
    assert on.status_code == 200, on.text
    acts = store._data[f"licenses/{pool}/activations"]
    assert (acts["ext-phone"]["status"], acts["m1-phone"]["status"]) == ("active", "deactivated")


# ------------------------------------ a stale stored Demo, repaired by a clear (L2-4)


def test_a_clear_restamps_a_stale_demo_on_a_live_seat(store, pool):
    """Demoted by a device mismatch before 2026-09-28, the member stores Demo
    against a live seat: Activate refuses them, and the clear was the repair
    before the split."""
    store._data["users"]["m2"].update(mode="demo", plan="demo")
    assert attempt(repo.activate_device, "m2", "m2-phone", "semper")[0] == "no_license"

    assert attempt(repo.clear_device_lock, pool, "m2", actor=repo.ACTOR_STAFF, caller=STAFF)[0] == ""

    assert repo.effective_mode(_user(store, "m2"), "semper") == "licensed"
    _phone(store, "m2", "m2-new")
    assert attempt(repo.activate_device, "m2", "m2-new", "semper")[0] == ""


def test_a_clear_leaves_a_held_seat_on_demo(store, pool):
    """A seat on hold is Demo on purpose."""
    repo.set_seat_enabled(pool, "m2", False)

    assert attempt(repo.clear_device_lock, pool, "m2", actor=repo.ACTOR_STAFF, caller=STAFF)[0] == ""

    assert repo.effective_mode(_user(store, "m2"), "semper") == "demo"


def test_a_clear_restamps_a_classroom_twins_holder_in_its_own_slot(store):
    """The split copied the stale Demo into Semper Classroom's mirror."""
    store._data["users"] = {}
    license_id = _mint_individual("solo@university.edu")["license"]["id"]
    repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@university.edu"), None)
    store._data["licenses"][license_id].update(
        app=apps.stored_name(apps.SEMPER_CLASSROOM), maxDevices=1)
    user = store._data["users"]["solo-1"]
    user.update(apps.licence_patch({k: user[k] for k in ("licenseId", "licenseKind")},
                                   apps.SEMPER_CLASSROOM))
    user.update(apps.written_fields("mode", apps.SEMPER_CLASSROOM, "demo"))
    user.update(licenseApp=apps.stored_name(apps.SEMPER), licenseId="other-semper-licence")
    assert repo.effective_mode(_user(store, "solo-1"), apps.SEMPER_CLASSROOM) == "demo"

    assert attempt(repo.clear_device_lock, license_id, actor=repo.ACTOR_STAFF, caller=STAFF)[0] == ""

    user = _user(store, "solo-1")
    assert apps.read_field(user, "mode", apps.SEMPER_CLASSROOM) == "licensed"
    assert user["mode"] == "licensed"  # Semper's own slot, untouched
