"""Upgrade by issue (ADR-033): staff issue the holder a new licence for the
same app, the old one keeps working until the holder taps Activate on the new
one, and that Activate moves the account, revokes the old licence and
activates the phone.

Repo level against the in-memory store, plus the holder's Activate and the
staff routes."""
from datetime import datetime, timedelta, timezone

import pytest
from pydantic import ValidationError

import repo_view as repo
from app import apps, deps, errors
from app.licenses import invite_id
from app.models import AdminLicenseUpgrade
from license_helpers import _mint_individual, _signed_in
from refusals import attempt

PHONE = "and-phone-a1"
EXPLICIT = {"X-Device-Id": PHONE, "X-Activation": "explicit"}


def _phone(store, uid, device_id, app="semper"):
    store._data.setdefault("devices", {})[device_id] = {
        "uid": uid, "status": "ACTIVE", "app": app}


def _held(store, uid="solo-1", email="solo@university.edu", max_devices=1):
    """A Semper licence held by `uid`, with PHONE registered and activated."""
    store._data["users"] = {}
    license_id = _mint_individual(email, app=apps.SEMPER, max_devices=max_devices)["license"]["id"]
    repo.ensure_entitlement(_signed_in(store, uid, email), None)
    _phone(store, uid, PHONE)
    repo.activate_device(uid, PHONE, apps.SEMPER)
    return license_id


def _user(store, uid="solo-1"):
    return {**store._data["users"][uid], "uid": uid}


def _lic(store, license_id):
    return store._data["licenses"][license_id]


def _acts(store, license_id):
    return store._data.get(f"licenses/{license_id}/activations", {})


# ------------------------------------------------------------------ issue


def test_issue_points_the_holder_at_the_new_licence_and_keeps_the_old(store):
    old = _held(store)
    out = repo.issue_upgrade(old, admin_uid="admin", terms={"maxDevices": 3})
    new = out["license"]["id"]

    assert out["pending"] is True and out["holderUid"] == "solo-1" and out["key"]
    assert _lic(store, new)["supersedes"] == old
    assert _lic(store, new)["maxDevices"] == 3
    assert apps.canonical(_lic(store, new)["app"]) == apps.SEMPER
    assert _lic(store, new).get("redeemedByUid") in (None, "")
    assert _lic(store, old)["upgradePendingId"] == new
    user = _user(store)
    assert user["licenseId"] == old and user["pendingLicenseId"] == new
    # The old licence still licenses the phone until Activate.
    assert repo.revalidate_device_lock(user, PHONE, explicit=True)["mode"] == "licensed"
    assert repo.pending_upgrade(user, apps.SEMPER)["licenseId"] == new
    assert repo.pending_upgrade(user, apps.SEMPER_CLASSROOM) is None


def test_the_new_licence_carries_the_old_terms_unless_named(store):
    old = _held(store, max_devices=2)
    ends = datetime.now(timezone.utc) + timedelta(days=400)
    _lic(store, old).update(expiresAt=ends, maxAnalyses=40, note="lab")
    new = repo.issue_upgrade(old, admin_uid="admin")["license"]["id"]
    assert (_lic(store, new)["maxDevices"], _lic(store, new)["maxAnalyses"]) == (2, 40)
    assert _lic(store, new)["expiresAt"] == ends and _lic(store, new)["note"] == "lab"


def test_the_desk_reads_an_upgrade_waiting_on_both_licences(store):
    old = _held(store)
    new = repo.issue_upgrade(old, admin_uid="admin")["license"]["id"]

    assert repo.get_license_public(old)["upgradePendingId"] == new
    assert repo.get_license_public(new)["supersedes"] == old
    assert repo.get_license_public(new)["upgradePendingId"] == ""


def test_an_end_in_the_past_is_refused(store):
    old = _held(store)
    err, _ = attempt(repo.issue_upgrade, old, admin_uid="admin",
                     terms={"expiresAt": datetime.now(timezone.utc) - timedelta(days=1)})
    assert err == errors.EXPIRY_IN_PAST


def test_one_upgrade_waits_at_a_time_until_it_is_revoked(store):
    old = _held(store)
    first = repo.issue_upgrade(old, admin_uid="admin")["license"]["id"]
    err, _ = attempt(repo.issue_upgrade, old, admin_uid="admin")
    assert err == errors.UPGRADE_PENDING

    repo.revoke_license(first, "admin")
    repo.finish_license_fanout(first)

    assert "pendingLicenseId" not in _user(store)
    assert "upgradePendingId" not in _lic(store, old)
    assert repo.issue_upgrade(old, admin_uid="admin")["pending"] is True


@pytest.mark.parametrize("shape", ["before_the_split", "institution", "demo"])
def test_only_an_apps_individual_licence_upgrades(store, shape):
    store._data["users"] = {}
    if shape == "institution":
        license_id = repo.create_institution_license(
            domain_lock="university.edu", admin_emails=["it@university.edu"],
            created_by_uid="admin", max_seats=2, app=apps.SEMPER)["license"]["id"]
    else:
        license_id = _mint_individual("solo@university.edu")["license"]["id"]
        if shape == "demo":
            _lic(store, license_id).update(app="semper", mode="demo", plan="demo")
    err, _ = attempt(repo.issue_upgrade, license_id, admin_uid="admin")
    assert err == errors.LICENSE_NOT_UPGRADABLE


def test_a_holder_who_never_signed_in_has_the_invite_moved(store):
    store._data["users"] = {}
    old = _mint_individual("later@lab.org", app=apps.SEMPER)["license"]["id"]
    out = repo.issue_upgrade(old, admin_uid="admin")
    new = out["license"]["id"]

    assert out["pending"] is False
    assert _lic(store, old)["status"] == "revoked" and _lic(store, old)["supersededBy"] == new
    assert store._data["licenseInvites"][invite_id("later@lab.org")]["licenseId"] == new
    # The old licence's revoke names its successor first, so it leaves it live.
    assert _lic(store, new)["status"] != "revoked"


def _successors(store, old):
    return sorted(i for i, lic in store._data["licenses"].items() if lic.get("supersedes") == old)


def test_two_issues_at_once_mint_one_successor(store, monkeypatch):
    """Two staff issues racing: the second commits while the first is still
    minting. The first's pointer write re-reads the old licence, finds the
    second's upgrade waiting, refuses, and its own licence is gone."""
    old = _held(store)
    real_mint = repo.licence_upgrade._mint_successor
    minted, rival = [], []

    def _mint_while_another_issues(*args, **kwargs):
        if not rival:
            rival.append(None)
            rival[0] = repo.issue_upgrade(old, admin_uid="admin-2")["license"]["id"]
        out = real_mint(*args, **kwargs)
        minted.append(out["id"])
        return out

    monkeypatch.setattr(repo.licence_upgrade, "_mint_successor", _mint_while_another_issues)
    with pytest.raises(errors.Refusal) as refused:
        repo.issue_upgrade(old, admin_uid="admin-1")

    won = rival[0]
    lost = next(i for i in minted if i != won)
    assert refused.value.detail == f"{errors.UPGRADE_PENDING}: {won}"
    assert _successors(store, old) == [won]
    assert lost not in store._data["licenses"]
    assert _lic(store, old)["upgradePendingId"] == won
    assert _user(store)["pendingLicenseId"] == won


def test_an_issue_refused_at_the_pointer_write_leaves_nothing(store, monkeypatch):
    """The old licence revoked between the first check and the pointer write."""
    old = _held(store)
    real_mint = repo.licence_upgrade._mint_successor
    minted = []

    def _mint_then_revoke(*args, **kwargs):
        out = real_mint(*args, **kwargs)
        minted.append(out["id"])
        _lic(store, old)["status"] = "revoked"
        return out

    monkeypatch.setattr(repo.licence_upgrade, "_mint_successor", _mint_then_revoke)
    err, _ = attempt(repo.issue_upgrade, old, admin_uid="admin")

    assert err == errors.LICENSE_REVOKED
    assert minted[0] not in store._data["licenses"]
    assert "pendingLicenseId" not in _user(store)


# ------------------------------------------------------------------ apply


def test_activate_moves_the_holder_and_revokes_the_old_licence(store):
    old = _held(store)
    _phone(store, "solo-1", "and-phone-b2")
    repo.activate_device("solo-1", "and-phone-b2", apps.SEMPER, override=True)
    new = repo.issue_upgrade(old, admin_uid="admin", terms={"maxDevices": 2})["license"]["id"]

    out = repo.apply_upgrade(new, "solo-1", apps.SEMPER, device_id=PHONE)

    assert out["supersededId"] == old and out["activation"]["devicesActive"] == 1
    user = _user(store)
    assert user["licenseId"] == new and "pendingLicenseId" not in user
    assert _lic(store, new)["redeemedByUid"] == "solo-1"
    assert _lic(store, old)["status"] == "revoked" and _lic(store, old)["supersededBy"] == new
    assert repo.effective_mode(user, apps.SEMPER) == "licensed"
    # This phone is active on the new licence; the other activates again.
    assert _acts(store, new)[PHONE]["status"] == "active"
    assert "and-phone-b2" not in _acts(store, new)
    assert repo.revalidate_device_lock(user, PHONE, explicit=True)["mode"] == "licensed"
    assert repo.revalidate_device_lock(user, "and-phone-b2", explicit=True)["mode"] == "demo"


def test_a_phone_that_cannot_activate_leaves_the_holder_where_they_were(store):
    old = _held(store)
    new = repo.issue_upgrade(old, admin_uid="admin")["license"]["id"]
    err, _ = attempt(repo.apply_upgrade, new, "solo-1", apps.SEMPER, device_id="stranger")
    assert err == errors.DEVICE_NOT_FOUND
    assert _user(store)["licenseId"] == old
    assert _lic(store, old)["status"] != "revoked"


def test_applying_again_after_a_partial_apply_finishes_it(store):
    """The pointer stays until the old licence is revoked, so a second
    Activate completes an apply that died after the claim."""
    old = _held(store)
    new = repo.issue_upgrade(old, admin_uid="admin")["license"]["id"]
    real_retire = repo.licence_upgrade._retire
    repo.licence_upgrade._retire = lambda *a: (_ for _ in ()).throw(RuntimeError("died"))
    try:
        with pytest.raises(RuntimeError):
            repo.apply_upgrade(new, "solo-1", apps.SEMPER)
    finally:
        repo.licence_upgrade._retire = real_retire
    assert _user(store)["pendingLicenseId"] == new

    repo.apply_upgrade(new, "solo-1", apps.SEMPER, device_id=PHONE)

    assert _lic(store, old)["status"] == "revoked"
    assert "pendingLicenseId" not in _user(store)


def test_apply_with_nothing_waiting_is_refused(store):
    old = _held(store)
    err, _ = attempt(repo.apply_upgrade, old, "solo-1", apps.SEMPER)
    assert err == errors.NO_PENDING_UPGRADE


@pytest.mark.parametrize("set_up, code", [
    # Issued with no phones: every Activate on it is over the count.
    (lambda store, new: store._data["licenses"][new].update(maxDevices=0),
     errors.DEVICE_LIMIT_REACHED),
    # Issued long enough ago that it ended, grace included.
    (lambda store, new: store._data["licenses"][new].update(
        expiresAt=datetime.now(timezone.utc) - timedelta(days=2), graceDays=0),
     errors.LICENSE_EXPIRED),
    # The phone was released from the account since it registered.
    (lambda store, new: store._data["devices"][PHONE].update(status="REVOKED"),
     errors.DEVICE_NOT_FOUND),
    # Staff turned the new licence floating: its lease is its activation.
    (lambda store, new: store._data["licenses"][new].update(seating="floating"),
     errors.SEATING_FLOATING),
    # The phone is active for another account on the new licence.
    (lambda store, new: store._data.setdefault(f"licenses/{new}/activations", {}).update(
        {PHONE: {"uid": "someone-else", "status": "active"}}),
     errors.DEVICE_IN_USE),
])
def test_a_refused_activation_is_found_before_anything_moves(store, set_up, code):
    old = _held(store)
    new = repo.issue_upgrade(old, admin_uid="admin")["license"]["id"]
    set_up(store, new)

    err, _ = attempt(repo.apply_upgrade, new, "solo-1", apps.SEMPER, device_id=PHONE)

    assert err == code
    user = _user(store)
    assert user["licenseId"] == old and user["pendingLicenseId"] == new
    assert _lic(store, old)["status"] != "revoked"
    assert _lic(store, new).get("redeemedByUid") in (None, "")


def test_a_phone_already_active_on_the_new_licence_applies_again(store):
    """An apply that died after the activation: the phone's own activation
    is not a slot it has to find free."""
    old = _held(store)
    new = repo.issue_upgrade(old, admin_uid="admin", terms={"maxDevices": 1})["license"]["id"]
    store._data.setdefault(f"licenses/{new}/activations", {})[PHONE] = {
        "uid": "solo-1", "status": "active"}
    _lic(store, new)["devicesActive"] = 1

    out = repo.apply_upgrade(new, "solo-1", apps.SEMPER, device_id=PHONE)

    assert out["activation"]["already"] is True
    assert _user(store)["licenseId"] == new


def test_revoking_the_old_licence_withdraws_its_waiting_upgrade(store):
    """Staff revoke a licence whose upgrade waits: the holder is Demo, is
    offered nothing, and Activate cannot license them on the successor."""
    old = _held(store)
    new = repo.issue_upgrade(old, admin_uid="admin")["license"]["id"]

    repo.revoke_license(old, "admin")
    repo.finish_license_fanout(old)
    repo.finish_license_fanout(new)

    user = _user(store)
    assert repo.pending_upgrade(user, apps.SEMPER) is None
    assert "pendingLicenseId" not in user and "upgradePendingId" not in _lic(store, old)
    # Minted for this holder only, as the old licence's replacement.
    assert _lic(store, new)["status"] == "revoked"
    err, _ = attempt(repo.apply_upgrade, new, "solo-1", apps.SEMPER, device_id=PHONE)
    assert err == errors.NO_PENDING_UPGRADE
    user = _user(store)
    assert user["licenseId"] == old and repo.effective_mode(user, apps.SEMPER) == "demo"


def test_apply_refuses_while_the_old_licence_revoke_is_still_fanning_out(store):
    """The revoke is written but its fan-out has not run yet (ADR-026): the
    pointer is still there, and apply refuses on the old licence alone."""
    old = _held(store)
    new = repo.issue_upgrade(old, admin_uid="admin")["license"]["id"]
    _lic(store, old).update(status="revoked")

    err, _ = attempt(repo.apply_upgrade, new, "solo-1", apps.SEMPER, device_id=PHONE)

    assert err == errors.NO_PENDING_UPGRADE
    assert _user(store)["licenseId"] == old
    assert _lic(store, new).get("redeemedByUid") in (None, "")


def test_revoking_the_old_licence_withdraws_but_keeps_an_institution_upgrade(store):
    """An institution successor has its own administrators: it stops being
    offered, and stays live for staff to revoke if that is meant."""
    old = _held(store)
    new = repo.issue_upgrade(old, admin_uid="admin", institution={
        "domainLock": "university.edu", "adminEmails": ["it@university.edu"],
        "maxSeats": 5})["license"]["id"]

    repo.revoke_license(old, "admin")

    assert repo.pending_upgrade(_user(store), apps.SEMPER) is None
    assert "upgradePendingId" not in _lic(store, old)
    assert _lic(store, new)["status"] != "revoked"
    err, _ = attempt(repo.apply_upgrade, new, "solo-1", apps.SEMPER)
    assert err == errors.NO_PENDING_UPGRADE


def test_an_upgrade_to_an_institution_seats_the_holder(store):
    old = _held(store)
    out = repo.issue_upgrade(old, admin_uid="admin", institution={
        "domainLock": "university.edu", "adminEmails": ["it@university.edu"], "maxSeats": 5})
    new = out["license"]["id"]
    assert _lic(store, new)["maxDevices"] == 5

    repo.apply_upgrade(new, "solo-1", apps.SEMPER, device_id=PHONE)

    assert _user(store)["licenseId"] == new
    assert store._data[f"licenses/{new}/seats"]["solo-1"]["status"] == "active"
    assert _acts(store, new)[PHONE]["status"] == "active"


def test_an_institution_off_the_holders_domain_is_refused(store):
    old = _held(store)
    err, _ = attempt(repo.issue_upgrade, old, admin_uid="admin", institution={
        "domainLock": "elsewhere.edu", "adminEmails": ["it@elsewhere.edu"], "maxSeats": 5})
    assert err == errors.CONVERT_DOMAIN_MISMATCH


def test_the_upgrade_body_shapes():
    with pytest.raises(ValidationError):
        AdminLicenseUpgrade(kind="institution", domainLock="b.org")
    with pytest.raises(ValidationError):
        AdminLicenseUpgrade(domainLock="b.org")
    with pytest.raises(ValidationError):
        AdminLicenseUpgrade(expiresAt=datetime.now(timezone.utc), perpetual=True)
    body = AdminLicenseUpgrade(maxDevices=3)
    assert body.terms() == {"maxDevices": 3} and body.institution() is None


# ------------------------------------------------------------------ routes


def _as_dev_user(store, monkeypatch):
    monkeypatch.setattr(deps, "_DEV_USER",
                        {**store._data["users"]["dev-user"], "role": "admin"})


@pytest.mark.asyncio
async def test_the_holder_activates_the_upgrade_and_config_shows_it_first(
        store, client, monkeypatch):
    old = _held(store, uid="dev-user", email="dev@university.edu")
    issued = await client.post(f"/v1/admin/licenses/{old}/upgrade", json={"maxDevices": 2})
    assert issued.status_code == 200, issued.text
    new = issued.json()["license"]["id"]
    _as_dev_user(store, monkeypatch)

    config = (await client.get("/v1/config", headers=EXPLICIT)).json()
    assert config["pendingLicence"]["licenseId"] == new
    me = (await client.get("/v1/me")).json()
    assert me["licences"]["semper"]["pendingLicence"]["licenseId"] == new

    done = await client.post("/v1/me/devices/activate", headers=EXPLICIT, json={"upgrade": True})

    assert done.status_code == 200, done.text
    assert done.json()["activation"]["licenseId"] == new
    assert done.json()["config"]["pendingLicence"] is None
    assert _lic(store, old)["status"] == "revoked"


@pytest.mark.asyncio
async def test_activate_upgrade_after_the_old_licence_is_revoked_is_404(
        store, client, monkeypatch):
    old = _held(store, uid="dev-user", email="dev@university.edu")
    issued = await client.post(f"/v1/admin/licenses/{old}/upgrade", json={})
    assert issued.status_code == 200, issued.text
    revoked = await client.post(f"/v1/admin/licenses/{old}/revoke")
    assert revoked.status_code == 200, revoked.text
    _as_dev_user(store, monkeypatch)

    assert (await client.get("/v1/config", headers=EXPLICIT)).json()["pendingLicence"] is None
    resp = await client.post("/v1/me/devices/activate", headers=EXPLICIT, json={"upgrade": True})

    assert (resp.status_code, resp.json()["detail"]) == (404, errors.NO_PENDING_UPGRADE)
    assert _user(store, "dev-user")["licenseId"] == old


@pytest.mark.asyncio
async def test_activate_upgrade_with_nothing_waiting_is_404(store, client, monkeypatch):
    _held(store, uid="dev-user", email="dev@university.edu")
    _as_dev_user(store, monkeypatch)
    resp = await client.post("/v1/me/devices/activate", headers=EXPLICIT, json={"upgrade": True})
    assert (resp.status_code, resp.json()["detail"]) == (404, errors.NO_PENDING_UPGRADE)


@pytest.mark.asyncio
async def test_staff_apply_moves_the_holder_without_a_phone(store, client, monkeypatch):
    old = _held(store)
    new = repo.issue_upgrade(old, admin_uid="admin")["license"]["id"]
    store._data["users"]["dev-user"] = {"uid": "dev-user", "email": "staff@semper.test",
                                        "role": "admin", "access_status": "APPROVED"}
    _as_dev_user(store, monkeypatch)

    resp = await client.post(f"/v1/admin/licenses/{new}/apply-upgrade")

    assert resp.status_code == 200, resp.text
    assert resp.json()["activation"] is None
    assert _user(store)["licenseId"] == new
    assert _lic(store, old)["status"] == "revoked"
