"""Phase 3: numbers and states the consoles and the app showed that were not true.

Each test pins one fix, named by its TECH_DEBT id:

- TD-116  a refusal's suffix and `Retry-After` reach the wire (its cooldown
          is gone, ADR-033)
- TD-117  `maxSeats` cannot be set below an assigned roster ("12 of 10 seats")
- TD-118  a whole-licence revoke answers with the counts it just wrote
- TD-119  an invite is not claimed onto a licence past its grace
- TD-120  a provisioning retry never moves a COMPLETED session back
- TD-121  a PROVISION_FAILED session is not charged to the quota
"""
from datetime import datetime, timedelta, timezone

import pytest

from app import audit, deps, drive, errors, rate_limit, session_provision, statuses, tasks
import repo_view as repo
from app.config import settings
from license_helpers import _mint_individual, _mint_institution, _signed_in
from refusals import attempt

DEV_UID = "dev-user"  # deps._DEV_USER in DEV_INSECURE_AUTH mode
_SHA = "a" * 64


def _university(store, *uids):
    store._data["users"] = {
        uid: {"email": f"{uid}@university.edu", "emailVerified": True, "access_status": "APPROVED", "mode": "demo"}
        for uid in uids
    }


def _seat(store, license_id, key, uid):
    err, _ = attempt(repo.activate_license, uid, f"{uid}@university.edu", f"dev-{uid}", key)
    assert err == ""


# ============================================================ TD-116 says when


@pytest.mark.asyncio
async def test_a_refusal_says_its_suffix_and_when_over_http(store, client, monkeypatch):
    """The route promised "the caller is told when"; the refusal was a bare code.
    The device-change cooldown that first needed it is gone (ADR-033), so a
    stubbed refusal carries the suffix and the instant through the real route."""
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    store._data["users"] = {}
    _mint_individual(email="dev@local")
    user = repo.ensure_entitlement(_signed_in(store, DEV_UID, "dev@local"), None)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, **user})
    when = datetime.now(timezone.utc) + timedelta(days=2)

    def refuse(*_a, **_k):
        raise errors.Refusal(errors.DEVICE_LIMIT_REACHED, "1/1", retry_at=when)

    repo.patch(monkeypatch, "clear_device_lock", refuse)
    resp = await client.post("/v1/licenses/unbind")

    assert resp.status_code == 409
    assert resp.json()["detail"] == "device_limit_reached: 1/1"
    retry_after = int(resp.headers["Retry-After"])
    remaining = (when - datetime.now(timezone.utc)).total_seconds()
    assert abs(retry_after - remaining) < 60


# ======================================================== TD-117 seat cap


@pytest.mark.asyncio
async def test_max_seats_below_an_assigned_roster_is_refused(store, client):
    _university(store, "u1", "u2")
    minted = _mint_institution(max_seats=3)
    license_id = minted["license"]["id"]
    _seat(store, license_id, minted["key"], "u1")
    _seat(store, license_id, minted["key"], "u2")

    resp = await client.patch(f"/v1/admin/licenses/{license_id}", json={"maxSeats": 1})

    assert resp.status_code == 422
    assert resp.json()["detail"] == "max_seats_below_used"
    assert store._data["licenses"][license_id]["maxSeats"] == 3

    # Down to exactly the roster is fine: nobody is over the cap.
    ok = await client.patch(f"/v1/admin/licenses/{license_id}", json={"maxSeats": 2})
    assert ok.status_code == 200, ok.text
    assert ok.json()["maxSeats"] == 2


@pytest.mark.asyncio
async def test_a_floating_pool_may_be_smaller_than_its_roster(store, client):
    """Floating `maxSeats` caps leases, not members, so a big roster is normal."""
    _university(store, "u1", "u2")
    minted = repo.create_institution_license(
        domain_lock="university.edu", admin_emails=["it@university.edu"],
        created_by_uid="admin", max_seats=3, seating="floating",
    )
    license_id = minted["license"]["id"]
    _seat(store, license_id, minted["key"], "u1")
    _seat(store, license_id, minted["key"], "u2")

    resp = await client.patch(f"/v1/admin/licenses/{license_id}", json={"maxSeats": 1})

    assert resp.status_code == 200, resp.text


# ===================================================== TD-118 revoke counts


def test_revoking_an_institution_licence_answers_with_zero_seats(store):
    _university(store, "u1", "u2")
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    _seat(store, license_id, minted["key"], "u1")
    _seat(store, license_id, minted["key"], "u2")
    assert store._data["licenses"][license_id]["seatsUsed"] == 2

    out = repo.revoke_license(license_id, "admin")

    assert out["status"] == "revoked"
    assert out["seatsUsed"] == 0
    assert out["leasesActive"] == 0
    assert out["seatsUsed"] == store._data["licenses"][license_id]["seatsUsed"]


# ==================================================== TD-119 lapsed invite


def _lapse(store, license_id):
    store._data["licenses"][license_id]["expiresAt"] = (
        datetime.now(timezone.utc) - timedelta(days=10)
    )
    store._data["licenses"][license_id]["graceDays"] = 0


def test_an_invite_to_a_lapsed_licence_is_not_claimed(store):
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    _lapse(store, license_id)

    out = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), "dev-1")

    assert out["licenseId"] != license_id
    assert out["mode"] == "demo"
    assert not store._data["licenses"][license_id].get("redeemedByUid")
    # Not dropped: an Extend renews the licence in place, so the invite waits.
    assert store._data["users"]["solo-1"]["inviteBlockedAt"] is not None
    assert repo._invite_ref("solo@lab.org").get().exists


def test_an_institution_invite_to_a_lapsed_licence_takes_no_seat(store):
    _university(store)
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "new@university.edu")
    _lapse(store, license_id)

    out = repo.ensure_entitlement(_signed_in(store, "n1", "new@university.edu"), "dev-n")

    assert out["licenseId"] != license_id
    assert int(store._data["licenses"][license_id].get("seatsUsed") or 0) == 0
    assert repo.list_institution_invites(license_id)


def test_the_invite_is_claimed_once_the_licence_is_extended(store):
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    _lapse(store, license_id)
    repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), "dev-1")

    repo.update_license(
        license_id, {"expiresAt": datetime.now(timezone.utc) + timedelta(days=365)}, "admin",
    )
    store._data["users"]["solo-1"]["inviteBlockedAt"] = (
        datetime.now(timezone.utc) - repo._INVITE_RETRY - timedelta(seconds=1)
    )
    out = repo.ensure_entitlement(
        {**store._data["users"]["solo-1"], "uid": "solo-1"}, "dev-1",
    )

    assert out["licenseId"] == license_id
    assert out["mode"] == "licensed"


# ================================================ TD-120 / TD-121 sessions


def _file(name: str) -> dict:
    return {"name": name, "role": "bundle", "bytes": 10, "sha256": _SHA}


@pytest.fixture
def uploads(store, monkeypatch):
    store._data["users"] = {DEV_UID: {"email": "dev@test", "emailVerified": True, "access_status": "APPROVED"}}
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(
        drive, "ensure_session_folders",
        lambda *a, **k: {"sessionFolderId": "sf", "userFolderId": "uf", "bundle": "sf"},
    )
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    monkeypatch.setattr(rate_limit.session_bucket, "allow", lambda uid: True)
    monkeypatch.setattr(tasks, "enqueue_provision", lambda sid: True)
    monkeypatch.setattr(settings, "INLINE_PROVISION_MAX_FILES", 0)
    return store


@pytest.mark.asyncio
async def test_a_provisioning_retry_after_completion_keeps_it_completed(uploads, client):
    sid = (await client.post(
        "/v1/sessions", json={"specimen": "s", "files": [_file("a")]},
    )).json()["sessionId"]
    session_provision.provision_session(sid)
    # Every file landed; the task's retry arrives afterwards.
    uploads._data["sessions"][sid].update(
        {"status": statuses.SESSION_COMPLETED, "completedCount": 1},
    )

    again = session_provision.provision_session(sid)

    assert again["status"] == statuses.SESSION_COMPLETED
    assert uploads._data["sessions"][sid]["status"] == statuses.SESSION_COMPLETED


@pytest.mark.parametrize("status", [
    statuses.SESSION_UPLOADING, statuses.SESSION_PROVISION_FAILED,
])
def test_no_status_write_leaves_completed(store, status):
    """A completion landing mid-provisioning must not be written over."""
    store._data["sessions"] = {"s1": {"uid": "u", "status": statuses.SESSION_COMPLETED}}

    repo.set_session_status("s1", status, error_code="x")

    assert store._data["sessions"]["s1"]["status"] == statuses.SESSION_COMPLETED
    assert "provisionError" not in store._data["sessions"]["s1"]


def test_status_writes_still_advance_an_open_session(store):
    store._data["sessions"] = {"s1": {"uid": "u", "status": statuses.SESSION_PROVISIONING}}

    repo.set_session_status("s1", statuses.SESSION_UPLOADING)

    assert store._data["sessions"]["s1"]["status"] == statuses.SESSION_UPLOADING
    repo.set_session_status("gone", statuses.SESSION_UPLOADING)  # erased: a no-op


def _seed(store, **by_status):
    store._data["sessions"] = {
        f"{status}-{i}": {"uid": DEV_UID, "status": status, "fileCount": 1,
                          "completedCount": 0, "totalBytes": 10}
        for status, n in by_status.items() for i in range(n)
    }


@pytest.mark.asyncio
async def test_failed_uploads_are_not_counted_as_stored(uploads, client):
    _seed(uploads, COMPLETED=2, UPLOADING=1, PROVISION_FAILED=2)

    assert repo.count_user_sessions(DEV_UID) == 3
    body = (await client.get("/v1/sessions")).json()
    assert body["quota"]["used"] == 3
    # Still listed, so the account page can show them as failed.
    assert len(body["sessions"]) == 5


@pytest.mark.asyncio
async def test_a_failed_upload_does_not_block_a_new_one_at_the_cap(
    uploads, client, monkeypatch,
):
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "maxSessions": 2})
    # A licensed ceiling is floored at demo's, so demo's has to be 2 as well.
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 2)
    _seed(uploads, COMPLETED=1, PROVISION_FAILED=1)

    resp = await client.post("/v1/sessions", json={"specimen": "s", "files": [_file("a")]})

    assert resp.status_code == 200, resp.text
    # And the cap still holds once the stored ones reach it.
    again = await client.post("/v1/sessions", json={"specimen": "t", "files": [_file("b")]})
    assert again.status_code == 409
    assert again.json()["detail"].startswith("session_quota_exceeded: 2/2")
