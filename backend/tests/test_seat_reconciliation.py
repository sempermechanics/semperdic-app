"""Gap C: the second seat count.

`seatsUsed` is what institution IT believes; it moves the instant a seat is
revoked. What a revoke actually reaches is two other places — the holder's
user document, demoted outside the revoke transaction and so best-effort, and
the holder's device, which keeps working off a cached /v1/config until it next
comes back. These tests pin which of those count as settled and which do not.
"""
from datetime import datetime, timedelta, timezone


import repo_view as repo
from refusals import attempt

NOW = datetime(2026, 9, 1, 12, 0, tzinfo=timezone.utc)


def _mint(max_seats=4):
    return repo.create_institution_license(
        domain_lock="university.edu",
        admin_emails=["it@university.edu"],
        created_by_uid="admin",
        max_seats=max_seats,
    )


def _roster(store, *uids):
    """Two-plus members, each activated onto a fresh seat of one licence."""
    store._data["users"] = {
        uid: {"email": f"{uid}@university.edu", "emailVerified": True, "access_status": "APPROVED",
              "plan": "demo"}
        for uid in uids
    }
    minted = _mint()
    license_id = minted["license"]["id"]
    for i, uid in enumerate(uids):
        repo.activate_license(uid, f"{uid}@university.edu", f"dev-{i}", minted["key"])
    return license_id


def _report(license_id):
    err, report = attempt(repo.reconcile_institution_seats, license_id)
    assert err == "", err
    return report


def _seat(license_id, uid):
    return repo._seat_ref(license_id, uid).get().to_dict() or {}


def _add_unclaimed_seat(license_id, uid="ghost"):
    """A seat with no account behind it (the account was erased)."""
    repo._seat_ref(license_id, uid).set({
        "uid": uid, "email": f"{uid}@university.edu", "status": "active",
    })


def _bucket(report, uid):
    row = next(s for s in report["seats"] if s["uid"] == uid)
    return row["bucket"], row["reason"]


def test_a_clean_roster_reconciles_with_nothing_outstanding(store):
    license_id = _roster(store, "u1", "u2")
    report = _report(license_id)

    assert report["counts"] == {
        "active": 2, "notEntitled": 0,
        "revokedConfirmed": 0, "revokedStillRunning": 0,
    }
    # Intent and reality agree, and the counter agrees with a recount.
    assert report["intended"] == report["intendedRecounted"] == 2
    assert report["entitled"] == 2


def test_an_unlanded_revoke_shows_as_still_running_then_clears_on_revalidation(
    store, monkeypatch,
):
    """The bucket the whole read exists for.

    `revoke_institution_seat` demotes the holder *after* its transaction
    commits, unretried. Suppressing that write is exactly what a crash between
    the two does: the seat is revoked, the counter has moved, and the backend
    would still answer "licensed" for the account.
    """
    license_id = _roster(store, "u1", "u2")
    repo.patch(monkeypatch, "_drop_user_to_demo_if_licensed", lambda *a, **k: None)
    assert repo.revoke_institution_seat(license_id, "u1") is True

    # IT's number has already moved; nothing told the holder.
    assert store._data["licenses"][license_id]["seatsUsed"] == 1
    assert store._data["users"]["u1"]["plan"] == "professional"

    report = _report(license_id)
    assert report["intended"] == 1
    assert report["counts"]["revokedStillRunning"] == 1
    assert _bucket(report, "u1") == ("revokedStillRunning", repo.STILL_LICENSED)
    # The licence still entitles both accounts, which is the drift itself.
    assert report["entitled"] == 2

    # The holder's next authed call revalidates the lock and demotes them.
    repo.revalidate_device_lock({**store._data["users"]["u1"], "uid": "u1"}, "dev-0")
    assert store._data["users"]["u1"]["plan"] == "demo"

    # The fault is repaired — the backend no longer answers "licensed" — but
    # the seat has not moved to confirmed yet, because the two conditions are
    # separate: the record has caught up, the device is not yet known to have.
    report = _report(license_id)
    assert report["entitled"] == 1
    assert _bucket(report, "u1") == (
        "revokedStillRunning", repo.NO_CHECKIN_SINCE_REVOKE,
    )

    # The same authed request writes lastSeenAt (`_touch_existing`), which is
    # the evidence that the device came back and re-read its entitlement.
    store._data["users"]["u1"]["lastSeenAt"] = (
        _seat(license_id, "u1")["revokedAt"] + timedelta(seconds=1)
    )
    report = _report(license_id)
    assert report["counts"]["revokedStillRunning"] == 0
    assert report["counts"]["revokedConfirmed"] == 1
    assert report["entitled"] == 1


def test_a_demoted_holder_who_has_not_been_back_is_not_yet_confirmed(store):
    """The record caught up; the device has not heard it.

    This is the ordinary lag rather than a fault — the app is still running on
    a cached entitlement — so it must not be reported as settled.
    """
    license_id = _roster(store, "u1")
    assert repo.revoke_institution_seat(license_id, "u1") is True
    assert store._data["users"]["u1"]["plan"] == "demo"

    # No lastSeenAt at all: the account has never been back to be told.
    report = _report(license_id)
    assert _bucket(report, "u1") == (
        "revokedStillRunning", repo.NO_CHECKIN_SINCE_REVOKE,
    )
    # Nothing is entitled — the drift here is on the device, not in Firestore.
    assert report["entitled"] == 0

    # A request after the revoke is what re-reads /v1/config.
    revoked_at = _seat(license_id, "u1")["revokedAt"]
    store._data["users"]["u1"]["lastSeenAt"] = revoked_at + timedelta(minutes=5)
    report = _report(license_id)
    assert _bucket(report, "u1") == ("revokedConfirmed", repo.CHECKED_IN)


def test_the_holders_next_request_confirms_the_revoke_despite_the_throttle(
    store, monkeypatch,
):
    """TD-23: the check-in has to register on the request, not an hour later.

    `lastSeenAt` is throttled to `_LAST_SEEN_THROTTLE`, and this read asks its
    question of exactly that field. Without a checkpoint, a holder seen five
    minutes before the revoke makes their next request, is demoted by it, and
    still reads *not checked in* for the rest of the hour — an operator
    watching the verified count sees nothing move.
    """
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    license_id = _roster(store, "u1")
    claims = {
        "sub": "u1", "email": "u1@university.edu", "email_verified": True,
        "firebase": {"sign_in_provider": "google.com"},
    }
    # Settle every other field first, so the only thing left to write is the
    # timestamp — otherwise `changed` would carry the write and prove nothing.
    repo.get_or_create_user(claims)
    store._data["users"]["u1"]["lastSeenAt"] = (
        datetime.now(timezone.utc) - timedelta(minutes=5)
    )

    assert repo.revoke_institution_seat(license_id, "u1") is True
    assert _bucket(_report(license_id), "u1") == (
        "revokedStillRunning", repo.NO_CHECKIN_SINCE_REVOKE,
    )

    # The holder's very next authed call — one request, well inside the hour.
    repo.get_or_create_user(claims)

    assert _bucket(_report(license_id), "u1") == (
        "revokedConfirmed", repo.CHECKED_IN,
    )


def test_a_check_in_from_before_the_revoke_does_not_confirm_it(store):
    license_id = _roster(store, "u1")
    assert repo.revoke_institution_seat(license_id, "u1") is True
    revoked_at = _seat(license_id, "u1")["revokedAt"]
    store._data["users"]["u1"]["lastSeenAt"] = revoked_at - timedelta(hours=3)

    report = _report(license_id)
    assert _bucket(report, "u1") == (
        "revokedStillRunning", repo.NO_CHECKIN_SINCE_REVOKE,
    )


def test_a_holder_who_moved_to_another_licence_settles_the_revoke(store):
    license_id = _roster(store, "u1")
    assert repo.revoke_institution_seat(license_id, "u1") is True
    # Whatever they are on now, it is not this licence, and getting there meant
    # a fresh config — so no check-in test is needed.
    store._data["users"]["u1"]["licenseId"] = "some-other-licence"

    report = _report(license_id)
    assert _bucket(report, "u1") == ("revokedConfirmed", repo.MOVED_ON)
    assert report["entitled"] == 0


def test_a_revoked_seat_with_no_account_behind_it_is_settled(store):
    license_id = _roster(store, "u1")
    assert repo.revoke_institution_seat(license_id, "u1") is True
    del store._data["users"]["u1"]

    report = _report(license_id)
    assert _bucket(report, "u1") == ("revokedConfirmed", repo.NO_ACCOUNT)


def test_a_seat_with_no_account_counts_against_intent_but_entitles_nobody(store):
    """The drift in the other direction, and why `entitled` is not `intended`.

    A roster place with no account behind it occupies a seat as far as IT is
    concerned and entitles no one at all. It used to be reported as "invited
    but never signed in", which a seat never is: invites are not seats.
    """
    license_id = _roster(store, "u1")
    _add_unclaimed_seat(license_id)

    report = _report(license_id)
    assert report["counts"]["active"] == 2
    assert report["counts"]["notEntitled"] == 1
    assert _bucket(report, "ghost") == ("active", repo.NO_ACCOUNT)
    assert report["entitled"] == 1


def test_an_idle_active_seat_says_why(store):
    """Each way an active seat can entitle nobody is named for what it is."""
    license_id = _roster(store, "held", "moved", "demoted")
    store._data[f"licenses/{license_id}/seats"]["held"]["status"] = "disabled"
    store._data["users"]["held"].update(mode="demo", plan="demo")
    store._data["users"]["moved"]["licenseId"] = "another-licence"
    store._data["users"]["demoted"].update(mode="demo", plan="demo")

    report = _report(license_id)

    assert _bucket(report, "held") == ("active", repo.ON_HOLD)
    assert _bucket(report, "moved") == ("active", repo.MOVED_ON)
    assert _bucket(report, "demoted") == ("active", repo.DEMOTED)
    assert report["counts"]["notEntitled"] == 3
    assert report["entitled"] == 0


def test_entitled_is_exactly_the_accounts_this_licence_still_answers_for(
    store, monkeypatch,
):
    """The identity that makes the two counts comparable.

    entitled = active seats actually taken up, plus every revoke that never
    reached the user document.
    """
    license_id = _roster(store, "u1", "u2", "u3")
    _add_unclaimed_seat(license_id)
    # One revoke that landed, one that did not.
    assert repo.revoke_institution_seat(license_id, "u2") is True
    repo.patch(monkeypatch, "_drop_user_to_demo_if_licensed", lambda *a, **k: None)
    assert repo.revoke_institution_seat(license_id, "u3") is True

    report = _report(license_id)
    c = report["counts"]
    unlanded = sum(
        1 for s in report["seats"] if s["reason"] == repo.STILL_LICENSED
    )
    assert unlanded == 1
    assert report["entitled"] == c["active"] - c["notEntitled"] + unlanded


def test_a_floating_member_between_leases_is_not_mistaken_for_a_failed_revoke(
    store,
):
    """Stored mode, not effective mode.

    A floating roster is mostly idle by design: `effective_mode` reads demo
    for anyone not currently holding a lease. Testing that would report a
    perfectly healthy pool as a licence entitling nobody.
    """
    store._data["users"] = {
        "u1": {"email": "u1@university.edu", "emailVerified": True, "access_status": "APPROVED",
               "plan": "demo"},
    }
    minted = repo.create_institution_license(
        domain_lock="university.edu",
        admin_emails=["it@university.edu"],
        created_by_uid="admin",
        max_seats=4,
        seating=repo.SEATING_FLOATING,
    )
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "u1@university.edu", "dev-0", minted["key"])

    user = store._data["users"]["u1"]
    assert repo.effective_mode({**user, "uid": "u1"}) == repo.MODE_DEMO

    report = _report(license_id)
    assert _bucket(report, "u1") == ("active", "")
    assert report["entitled"] == 1


def test_reconciling_an_individual_licence_is_refused_rather_than_empty(store):
    minted = repo.create_individual_license(
        email_lock="solo@lab.org",
        created_by_uid="admin",
    )
    err, report = attempt(repo.reconcile_institution_seats, minted["license"]["id"])
    assert err == "kind_not_institution"
    assert report is None


def test_reconciling_an_unknown_licence_is_not_found(store):
    err, report = attempt(repo.reconcile_institution_seats, "no-such-licence")
    assert err == "license_not_found"
    assert report is None


def test_a_lapsed_licence_entitles_nobody_though_its_roster_is_intact(store):
    """The stored mode stays `licensed` past expiry; the backend answers demo.

    Counting the stored mode told the operator a lapsed roster was in use.
    """
    license_id = _roster(store, "u1", "u2")
    for uid in ("u1", "u2"):
        store._data["users"][uid].update({
            "licenseExpiresAt": datetime.now(timezone.utc) - timedelta(days=30),
            "licenseGraceDays": 7,
        })

    report = _report(license_id)
    assert report["counts"]["active"] == 2
    assert report["entitled"] == 0


def test_an_uncapped_licence_reports_no_cap_rather_than_zero_seats(store):
    store._data["users"] = {}
    minted = _mint(max_seats=None)
    assert _report(minted["license"]["id"])["maxSeats"] is None
    assert _report(_roster(store, "u1"))["maxSeats"] == 4
