"""Licence-side repo helpers that other tests only reach through a route:
whether a licence is live, who administers one, its roster pages, invites,
the desk's edit rules, and what an account is entitled to."""
from datetime import datetime, timedelta, timezone

import pytest

from app import errors
from app.licenses import (
    MODE_DEMO,
    MODE_LICENSED,
    SEATING_ASSIGNED,
    SEATING_FLOATING,
    invite_id,
)
import repo_view as repo


def _in(days: float) -> datetime:
    return datetime.now(timezone.utc) + timedelta(days=days)


def _licensed(**kw) -> dict:
    return {"mode": MODE_LICENSED, "status": "redeemed", **kw}


# ------------------------------------------------------------ licence_is_live

@pytest.mark.parametrize("lic", [
    None,
    {},  # no mode reads as demo
    {"mode": "demo"},
    _licensed(status="revoked"),
    _licensed(expiresAt=_in(-10), graceDays=3),
    _licensed(expiresAt=_in(-1)),  # no grace at all
])
def test_licence_is_not_live(lic):
    assert repo.licence_is_live(lic) is False


@pytest.mark.parametrize("lic", [
    _licensed(),  # perpetual
    _licensed(status="unused"),
    {"plan": "professional"},  # pre-rename spelling
    _licensed(expiresAt=_in(30)),
    _licensed(expiresAt=_in(-1), graceDays=7),  # in grace still counts
    _licensed(expiresAt="2020-01-01"),  # malformed fails open
    _licensed(kind="institution", status="active"),
])
def test_licence_is_live(lic):
    assert repo.licence_is_live(lic) is True


# ------------------------------------------------------- is_institution_admin

@pytest.mark.parametrize("admins, email, expected", [
    (["it@uni.edu"], "it@uni.edu", True),
    (["IT@Uni.edu "], " it@UNI.edu", True),
    (["it@uni.edu"], "other@uni.edu", False),
    (["it@uni.edu"], "", False),
    ([], "it@uni.edu", False),
    (None, "it@uni.edu", False),
])
def test_is_institution_admin(admins, email, expected):
    assert repo.is_institution_admin({"adminEmails": admins}, email) is expected


def test_a_licence_with_no_admin_list_has_no_admins():
    assert repo.is_institution_admin({}, "it@uni.edu") is False


# ------------------------------------------------- institution seats / pages

def _roster(store, lid="lic-1", uids=("u3", "u1", "u5", "u2", "u4")):
    store._data["licenses"] = {lid: {"kind": "institution", "mode": MODE_LICENSED}}
    store._data[f"licenses/{lid}/seats"] = {
        uid: {"uid": uid, "email": f"{uid}@uni.edu", "deviceIdLock": f"dev-{uid}"}
        for uid in uids
    }


def test_institution_seat_is_the_roster_row_or_none(store):
    _roster(store)
    store._data["licenses/lic-1/seats"]["u1"].update(
        status="disabled", leaseExpiresAt="later", secret="x")
    seat = repo.institution_seat("lic-1", "u1")
    assert seat["uid"] == "u1" and seat["email"] == "u1@uni.edu"
    assert seat["deviceIdLock"] == "dev-u1"
    assert seat["status"] == "disabled" and seat["leaseExpiresAt"] == "later"
    assert "secret" not in seat
    assert repo.institution_seat("lic-1", "nobody") is None
    assert repo.institution_seat("no-licence", "u1") is None


def test_a_seat_with_no_status_reads_active(store):
    _roster(store, uids=("u1",))
    store._data["licenses/lic-1/seats"]["u1"] = {}
    seat = repo.institution_seat("lic-1", "u1")
    assert seat["status"] == "active" and seat["email"] == "" and seat["deviceIdLock"] == ""


def test_page_institution_seats_walks_the_roster_in_uid_order(store):
    _roster(store)
    seen, token, pages = [], None, 0
    while True:
        page, token = repo.page_institution_seats("lic-1", 2, token)
        seen += [s["uid"] for s in page]
        pages += 1
        if not token:
            break
        assert token == page[-1]["uid"]
    assert seen == ["u1", "u2", "u3", "u4", "u5"]
    assert pages == 3


def test_a_full_last_page_issues_no_token(store):
    _roster(store, uids=("u1", "u2"))
    page, token = repo.page_institution_seats("lic-1", 2)
    assert [s["uid"] for s in page] == ["u1", "u2"] and token is None


def test_a_page_token_for_a_removed_seat_restarts(store):
    _roster(store)
    page, _ = repo.page_institution_seats("lic-1", 2, "removed-uid")
    assert [s["uid"] for s in page] == ["u1", "u2"]


def test_another_licences_roster_is_not_paged(store):
    _roster(store)
    assert repo.page_institution_seats("lic-2", 10) == ([], None)


# ---------------------------------------------------- invite_institution_member

def _institution(store, lid="lic-1", **kw):
    store._data.setdefault("licenses", {})[lid] = {
        "kind": "institution", "mode": MODE_LICENSED, "status": "active", **kw}


def test_invite_reserves_a_place_keyed_by_the_address_hash(store):
    _institution(store)
    inv = repo.invite_institution_member("lic-1", "  New.Person@Uni.EDU ", "it-uid")
    key = invite_id("new.person@uni.edu")
    assert inv["id"] == key
    assert inv["email"] == "new.person@uni.edu"
    assert inv["licenseId"] == "lic-1" and inv["invitedByUid"] == "it-uid"
    stored = store._data["licenseInvites"][key]
    assert stored["email"] == "new.person@uni.edu" and stored["licenseId"] == "lic-1"
    # A promise, not a seat: nothing on the licence moved.
    assert "seatsUsed" not in store._data["licenses"]["lic-1"]
    assert not store._data.get("licenses/lic-1/seats")


def test_inviting_the_same_address_again_is_a_no_op(store):
    _institution(store)
    first = repo.invite_institution_member("lic-1", "a@uni.edu", "it-1")
    again = repo.invite_institution_member("lic-1", "A@uni.edu", "it-2")
    assert again["id"] == first["id"]
    assert again["invitedByUid"] == "it-1"  # the original promise stands
    assert len(store._data["licenseInvites"]) == 1


def test_an_address_promised_to_another_live_licence_is_refused(store):
    _institution(store, "lic-1")
    _institution(store, "lic-2")
    repo.invite_institution_member("lic-1", "a@uni.edu", "it")
    with pytest.raises(errors.Refusal) as exc:
        repo.invite_institution_member("lic-2", "a@uni.edu", "it")
    assert exc.value.code == errors.INVITE_EXISTS
    assert store._data["licenseInvites"][invite_id("a@uni.edu")]["licenseId"] == "lic-1"


@pytest.mark.parametrize("dead", ["revoked", "deleted"])
def test_a_promise_from_a_dead_licence_is_overwritten(store, dead):
    _institution(store, "lic-old")
    _institution(store, "lic-new")
    repo.invite_institution_member("lic-old", "a@uni.edu", "it")
    if dead == "revoked":
        store._data["licenses"]["lic-old"]["status"] = "revoked"
    else:
        del store._data["licenses"]["lic-old"]
    inv = repo.invite_institution_member("lic-new", "a@uni.edu", "it")
    assert inv["licenseId"] == "lic-new"


@pytest.mark.parametrize("setup, code", [
    (lambda s: None, errors.LICENSE_NOT_FOUND),
    (lambda s: s._data.__setitem__("licenses", {"lic-1": {"kind": "individual",
                                                           "mode": MODE_LICENSED}}),
     errors.LICENSE_NOT_FOUND),
    (lambda s: _institution(s, status="revoked"), errors.LICENSE_REVOKED),
])
def test_invite_refusals_about_the_licence(store, setup, code):
    setup(store)
    with pytest.raises(errors.Refusal) as exc:
        repo.invite_institution_member("lic-1", "a@uni.edu", "it")
    assert exc.value.code == code
    assert not store._data.get("licenseInvites")


def test_adding_a_member_to_no_licence_is_refused(store):
    with pytest.raises(errors.Refusal) as exc:
        repo.add_institution_member("nope", "a@uni.edu")
    assert exc.value.code == errors.LICENSE_NOT_FOUND


def test_adding_someone_with_no_account_makes_an_invite(store):
    _institution(store)
    seat, invite = repo.add_institution_member("lic-1", "new@uni.edu", "it")
    assert seat is None and invite["licenseId"] == "lic-1"


def test_no_address_finds_no_account_and_holds_no_licence(store):
    store._data["users"] = {"u1": {"email": ""}}
    assert repo.find_user_by_email("  ") is None
    assert repo.licence_held_by("", user={"licenseId": ""}) == ""


@pytest.mark.parametrize("email", ["", "   "])
def test_invite_without_an_address_is_refused(store, email):
    _institution(store)
    with pytest.raises(errors.Refusal) as exc:
        repo.invite_institution_member("lic-1", email, "it")
    assert exc.value.code == errors.INVALID_EMAIL
    assert not store._data.get("licenseInvites")


# ---------------------------------------------------------- get_license_public

def test_get_license_public_is_the_desk_row_without_the_secret(store):
    store._data["licenses"] = {"lic-1": {
        "keyHash": "deadbeef", "key": "SEMP-AAAA-BBBB-CCCC-DDDD", "keyPrefix": "SEMP-AAAA",
        "kind": "campus", "plan": "professional", "status": "active", "seatsUsed": 3,
        "expiresAt": datetime(2030, 1, 1, tzinfo=timezone.utc), "graceDays": 14,
    }}
    row = repo.get_license_public("lic-1")
    assert row["id"] == "lic-1" and row["keyPrefix"] == "SEMP-AAAA"
    assert row["kind"] == "institution" and row["mode"] == MODE_LICENSED
    assert row["duration"] == "timed" and row["seatsUsed"] == 3
    assert row["graceEndsAt"] == datetime(2030, 1, 15, tzinfo=timezone.utc)
    assert "keyHash" not in row and "key" not in row
    assert "SEMP-AAAA-BBBB" not in repr(row)


def test_get_license_public_of_no_licence_is_none(store):
    assert repo.get_license_public("nope") is None


# ---------------------------------------------------------- expiry_change_error

def test_no_new_expiry_is_no_objection():
    assert repo.expiry_change_error(_licensed(expiresAt=_in(10)), None) == ""
    assert repo.expiry_change_error(_licensed(), "not-a-date") == ""


@pytest.mark.parametrize("allow", [False, True])
def test_an_expiry_in_the_past_is_refused_even_when_shortening_is_allowed(allow):
    assert repo.expiry_change_error(_licensed(expiresAt=_in(10)), _in(-1),
                                    allow_shorten=allow) == errors.EXPIRY_IN_PAST


def test_a_naive_expiry_is_read_as_utc():
    naive_past = (datetime.now(timezone.utc) - timedelta(hours=1)).replace(tzinfo=None)
    assert repo.expiry_change_error(_licensed(), naive_past) == errors.EXPIRY_IN_PAST


def test_extending_a_timed_licence_is_allowed():
    assert repo.expiry_change_error(_licensed(expiresAt=_in(10)), _in(400)) == ""


def test_shortening_needs_the_desks_confirmation():
    lic = _licensed(expiresAt=_in(100))
    assert repo.expiry_change_error(lic, _in(50)) == errors.EXPIRY_BEFORE_CURRENT
    assert repo.expiry_change_error(lic, _in(50), allow_shorten=True) == ""


def test_an_end_date_on_a_perpetual_licence_needs_the_desks_confirmation():
    assert repo.expiry_change_error(_licensed(), _in(365)) == errors.LICENSE_PERPETUAL
    assert repo.expiry_change_error(_licensed(), _in(365), allow_shorten=True) == ""


# ----------------------------------------------------------- analysis_cap_error

@pytest.mark.parametrize("lic, expected", [
    ({"mode": "demo"}, errors.CAP_ON_DEMO_KEY),
    ({}, errors.CAP_ON_DEMO_KEY),
    ({"plan": "demo"}, errors.CAP_ON_DEMO_KEY),
    (_licensed(), ""),
    ({"plan": "professional"}, ""),
])
def test_analysis_cap_error(lic, expected):
    assert repo.analysis_cap_error(lic) == expected


# ------------------------------------------------------------ license_edit_error

def _inst(**kw):
    return _licensed(kind="institution", status="active", **kw)


@pytest.mark.parametrize("field, value", [
    ("maxSeats", 5), ("seating", SEATING_FLOATING), ("adminEmails", ["it@uni.edu"]),
])
def test_roster_fields_are_institution_only(field, value):
    assert repo.license_edit_error(_licensed(), {field: value}) == errors.INSTITUTION_ONLY


def test_a_roster_field_sent_as_null_is_not_an_edit():
    assert repo.license_edit_error(_licensed(), {"maxSeats": None, "note": "hi"}) == ""


def test_a_cap_on_a_demo_key_is_refused_and_clearing_is_not():
    assert repo.license_edit_error({"mode": "demo"}, {"maxAnalyses": 5}) == errors.CAP_ON_DEMO_KEY
    assert repo.license_edit_error({"mode": "demo"}, {"clearMaxAnalyses": True}) == ""


def test_edit_carries_the_expiry_rules_and_their_confirmation():
    lic = _licensed(expiresAt=_in(100))
    assert repo.license_edit_error(lic, {"expiresAt": _in(10)}) == errors.EXPIRY_BEFORE_CURRENT
    assert repo.license_edit_error(lic, {"expiresAt": _in(10), "allowShorten": True}) == ""


def test_switching_to_floating_needs_a_pool_size():
    assert repo.license_edit_error(_inst(), {"seating": SEATING_FLOATING}) == \
        errors.FLOATING_NEEDS_MAX_SEATS
    assert repo.license_edit_error(_inst(), {"seating": SEATING_FLOATING, "maxSeats": 3}) == ""
    assert repo.license_edit_error(_inst(maxSeats=3), {"seating": SEATING_FLOATING}) == ""


def test_an_assigned_cap_below_the_roster_is_refused():
    lic = _inst(seatsUsed=8)
    assert repo.license_edit_error(lic, {"maxSeats": 5}) == errors.MAX_SEATS_BELOW_USED
    assert repo.license_edit_error(lic, {"maxSeats": 8}) == ""


def test_switching_a_big_floating_roster_to_assigned_must_hold_everyone():
    lic = _inst(seating=SEATING_FLOATING, maxSeats=3, seatsUsed=10)
    assert repo.license_edit_error(lic, {"seating": SEATING_ASSIGNED}) == errors.MAX_SEATS_BELOW_USED
    assert repo.license_edit_error(lic, {"seating": SEATING_ASSIGNED, "maxSeats": 10}) == ""
    # A floating pool smaller than its roster is its normal state.
    assert repo.license_edit_error(lic, {"maxSeats": 2}) == ""


def test_the_first_refusal_wins_in_order():
    """Institution-only is decided before the cap, the cap before the expiry."""
    assert repo.license_edit_error({"mode": "demo"}, {"maxSeats": 1, "maxAnalyses": 1}) == \
        errors.INSTITUTION_ONLY
    assert repo.license_edit_error({"mode": "demo"},
                                   {"maxAnalyses": 1, "expiresAt": _in(-1)}) == errors.CAP_ON_DEMO_KEY


# --------------------------------------------------------------- entitlement_of

def test_a_demo_account_holds_nothing():
    ent = repo.entitlement_of({"mode": "demo"})
    assert (ent.held, ent.mode, ent.inactive_reason) == (False, MODE_DEMO, "")
    assert ent.expires_at is None and ent.grace_ends_at is None and ent.in_grace is False
    assert ent.seating == SEATING_ASSIGNED and ent.lease_expires_at is None


def test_a_perpetual_assigned_licence_is_licensed():
    ent = repo.entitlement_of({"mode": MODE_LICENSED})
    assert (ent.held, ent.mode, ent.inactive_reason) == (True, MODE_LICENSED, "")


def test_in_grace_is_still_licensed_and_says_so():
    expiry = _in(-2)
    ent = repo.entitlement_of({"mode": MODE_LICENSED, "licenseExpiresAt": expiry,
                               "licenseGraceDays": 7})
    assert ent.mode == MODE_LICENSED and ent.in_grace is True
    assert ent.expires_at == expiry and ent.grace_ends_at == expiry + timedelta(days=7)


def test_past_grace_is_demo_with_the_licence_ended_reason():
    ent = repo.entitlement_of({"mode": MODE_LICENSED, "licenseExpiresAt": _in(-10),
                               "licenseGraceDays": 7})
    assert ent.held is True and ent.mode == MODE_DEMO
    assert ent.inactive_reason == repo.INACTIVE_LICENCE_ENDED and ent.in_grace is False


def test_absent_grace_days_means_no_grace():
    ent = repo.entitlement_of({"mode": MODE_LICENSED, "licenseExpiresAt": _in(-0.01)})
    assert ent.mode == MODE_DEMO and ent.inactive_reason == repo.INACTIVE_LICENCE_ENDED


def test_a_floating_member_without_a_lease_is_demo_with_no_seat():
    for lease in (None, _in(-0.01), "garbage"):
        ent = repo.entitlement_of({"mode": MODE_LICENSED, "licenseSeating": "floating",
                                   "leaseExpiresAt": lease})
        assert ent.held is True and ent.mode == MODE_DEMO
        assert ent.inactive_reason == repo.INACTIVE_NO_SEAT
        assert ent.seating == SEATING_FLOATING


def test_a_floating_member_with_a_live_lease_is_licensed():
    lease = _in(0.01)
    ent = repo.entitlement_of({"mode": MODE_LICENSED, "licenseSeating": "FLOATING",
                               "leaseExpiresAt": lease})
    assert ent.mode == MODE_LICENSED and ent.lease_expires_at == lease


def test_an_ended_licence_outranks_a_live_lease():
    ent = repo.entitlement_of({"mode": MODE_LICENSED, "licenseSeating": "floating",
                               "leaseExpiresAt": _in(1), "licenseExpiresAt": _in(-1)})
    assert ent.inactive_reason == repo.INACTIVE_LICENCE_ENDED


def test_a_malformed_expiry_fails_open():
    ent = repo.entitlement_of({"mode": MODE_LICENSED, "licenseExpiresAt": "2020-01-01",
                               "licenseGraceDays": "x"})
    assert ent.mode == MODE_LICENSED and ent.expires_at is None


def test_a_lease_on_a_demo_account_grants_nothing():
    ent = repo.entitlement_of({"mode": "demo", "licenseSeating": "floating",
                               "leaseExpiresAt": _in(1)})
    assert (ent.held, ent.mode, ent.inactive_reason) == (False, MODE_DEMO, "")


def test_entitlement_agrees_with_the_derived_answers():
    user = {"mode": MODE_LICENSED, "licenseSeating": "floating"}
    assert repo.effective_mode(user) == repo.entitlement_of(user).mode == MODE_DEMO
    assert repo.inactive_licence_reason(user) == repo.INACTIVE_NO_SEAT
