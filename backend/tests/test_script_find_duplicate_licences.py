"""scripts/find_duplicate_licences.py: people holding more than one live licence.

An address holds a licence four ways — `emailLock`, a roster seat, a pending
invite, or an account's `licenseId` — and "live" is `licence_is_live` (not
revoked, not past grace, not Demo). The script prints each address that holds
two or more live licences and exits 1, or exits 0 when nobody does. It is
read-only.

Driven through `main()` against the in-memory store double, with
`firestore.Client` swapped for one that hands back the double.
"""
import copy
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.repo import holders

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
import find_duplicate_licences as fdl  # noqa: E402


def _now():
    return datetime.now(timezone.utc)


def _lic(**over):
    return {"mode": "licensed", "status": "redeemed", "kind": "individual", **over}


def _inst(**over):
    return _lic(kind="institution", **over)


@pytest.fixture
def run(store, monkeypatch):
    """Run `main()` with argv against the store double; returns the exit code."""
    built: list[str | None] = []

    def _client(project=None):
        built.append(project)
        return store

    monkeypatch.setattr(fdl, "firestore", SimpleNamespace(Client=_client))

    def _run(*argv):
        monkeypatch.setattr(sys, "argv", ["find_duplicate_licences.py", *argv])
        return fdl.main()

    _run.built = built
    return _run


def _dupe_block(out: str) -> list[str]:
    """The lines after the summary."""
    return out.splitlines()[1:]


# ------------------------------------------------------------------ no dupes


def test_empty_store_reports_nobody(run, capsys):
    assert run("--project", "p") == 0
    assert capsys.readouterr().out.splitlines() == [
        "0 live licence(s), 0 address(es) holding one.",
        "  nobody holds more than one.",
    ]
    assert run.built == ["p"]


def test_one_licence_held_every_way_is_not_a_duplicate(store, run, capsys):
    """The same licence reached by lock, seat, invite and account is one."""
    store._data["licenses"] = {"L1": _inst(emailLock="a@x.com")}
    store._data["licenses/L1/seats"] = {"uid-a": {"email": "A@x.com", "status": "active"}}
    store._data["licenseInvites"] = {"i1": {"licenseId": "L1", "email": " a@X.com "}}
    store._data["users"] = {"uid-a": {"licenseId": "L1", "email": "a@x.com"}}

    assert run("--project", "p") == 0
    assert capsys.readouterr().out.splitlines() == [
        "1 live licence(s), 1 address(es) holding one.",
        "  nobody holds more than one.",
    ]


def test_different_people_on_different_licences_are_fine(store, run, capsys):
    store._data["licenses"] = {"L1": _lic(emailLock="a@x.com"), "L2": _lic(emailLock="b@x.com")}

    assert run("--project", "p") == 0
    assert "2 live licence(s), 2 address(es) holding one." in capsys.readouterr().out


# ------------------------------------------------------------------ dupes


def test_two_email_locks_differing_in_case_and_space_are_one_person(store, run, capsys):
    store._data["licenses"] = {
        "L1aaaaaaaaaaaaaa": _lic(emailLock="Ann@Example.com", keyPrefix="SEMP-AAAA"),
        "L2bbbbbbbbbbbbbb": _lic(emailLock="  ann@example.COM ", status="active"),
    }

    assert run("--project", "p") == 1
    out = capsys.readouterr().out
    assert out.splitlines() == [
        "2 live licence(s), 1 address(es) holding one.",
        "  1 address(es) hold more than one:",
        "  ann@example.com",
        "    SEMP-AAAA  individual  redeemed  via emailLock  (L1aaaaaaaaaaaaaa)",
        # No keyPrefix: the id's first 12 characters stand in.
        "    L2bbbbbbbbbb  individual  active  via emailLock  (L2bbbbbbbbbbbbbb)",
    ]


def test_seat_plus_account_on_another_licence(store, run, capsys):
    store._data["licenses"] = {"INST": _inst(keyPrefix="SEMP-INST"), "SOLO": _lic(keyPrefix="SEMP-SOLO")}
    store._data["licenses/INST/seats"] = {"uid-b": {"email": "b@uni.edu"}}  # no status: active
    store._data["users"] = {"uid-b": {"licenseId": "SOLO", "email": "B@uni.edu"}}

    assert run("--project", "p") == 1
    assert _dupe_block(capsys.readouterr().out) == [
        "  1 address(es) hold more than one:",
        "  b@uni.edu",
        "    SEMP-INST  institution  redeemed  via seat  (INST)",
        "    SEMP-SOLO  individual  redeemed  via account  (SOLO)",
    ]


def test_invite_plus_lock_and_several_routes_listed_together(store, run, capsys):
    store._data["licenses"] = {"A": _inst(emailLock="c@x.com"), "B": _lic()}
    store._data["licenseInvites"] = {"inv": {"licenseId": "B", "email": "c@x.com"}}
    store._data["users"] = {"u": {"licenseId": "A", "email": "c@x.com"}}

    assert run("--project", "p") == 1
    lines = _dupe_block(capsys.readouterr().out)
    assert "    A  institution  redeemed  via account, emailLock  (A)" in lines
    assert "    B  individual  redeemed  via invite  (B)" in lines


def test_missing_kind_and_status_print_defaults(store, run, capsys):
    store._data["licenses"] = {
        "K1": {"mode": "licensed", "emailLock": "d@x.com"},
        "K2": {"mode": "licensed", "emailLock": "d@x.com"},
    }

    assert run("--project", "p") == 1
    lines = _dupe_block(capsys.readouterr().out)
    assert "    K1  individual    via emailLock  (K1)" in lines


def test_addresses_are_sorted_and_counted(store, run, capsys):
    store._data["licenses"] = {
        "1": _lic(emailLock="zed@x.com"), "2": _lic(emailLock="zed@x.com"),
        "3": _lic(emailLock="amy@x.com"), "4": _lic(emailLock="amy@x.com"),
        "5": _lic(emailLock="solo@x.com"),
    }

    assert run("--project", "p") == 1
    lines = capsys.readouterr().out.splitlines()
    assert lines[0] == "5 live licence(s), 3 address(es) holding one."
    assert lines[1] == "  2 address(es) hold more than one:"
    addresses = [ln.strip() for ln in lines[2:] if not ln.startswith("    ")]
    assert addresses == ["amy@x.com", "zed@x.com"]


# ------------------------------------------------------------------ what counts


def test_seats_are_read_only_for_institution_licences(store, run, capsys):
    store._data["licenses"] = {"IND": _lic(), "OTHER": _lic(emailLock="e@x.com")}
    # A stray seat under an individual licence is not a way of holding it.
    store._data["licenses/IND/seats"] = {"uid-e": {"email": "e@x.com"}}

    assert run("--project", "p") == 0


@pytest.mark.parametrize("seat", [
    {"email": "f@x.com", "status": "revoked"},
    {"status": "active"},
    {"email": "", "status": "active"},
])
def test_revoked_or_addressless_seats_do_not_count(store, run, seat):
    store._data["licenses"] = {"INST": _inst(), "SOLO": _lic(emailLock="f@x.com")}
    store._data["licenses/INST/seats"] = {"uid-f": seat}

    assert run("--project", "p") == 0


def test_invites_and_accounts_on_dead_or_unknown_licences_do_not_count(store, run, capsys):
    store._data["licenses"] = {
        "LIVE": _lic(emailLock="g@x.com"),
        "DEAD": _lic(status="revoked"),
    }
    store._data["licenseInvites"] = {
        "i1": {"licenseId": "DEAD", "email": "g@x.com"},
        "i2": {"licenseId": "GONE", "email": "g@x.com"},
        "i3": {"licenseId": "LIVE"},  # no address
    }
    store._data["users"] = {
        "u1": {"licenseId": "DEAD", "email": "g@x.com"},
        "u2": {"email": "g@x.com"},  # no licence
        "u3": {"licenseId": "LIVE", "email": ""},
    }

    assert run("--project", "p") == 0
    assert capsys.readouterr().out.startswith("1 live licence(s), 1 address(es) holding one.")


@pytest.mark.parametrize("dead", [
    {"status": "revoked"},
    {"mode": "demo"},
    {"mode": None, "plan": "demo"},
    {"mode": None},  # neither mode nor plan reads as Demo
    {"expiresAt": _now() - timedelta(days=10), "graceDays": 3},
    {"expiresAt": (_now() - timedelta(days=1)).replace(tzinfo=None)},  # naive, past
])
def test_dead_licences_are_not_live(store, run, capsys, dead):
    store._data["licenses"] = {"X": _lic(emailLock="h@x.com", **dead), "Y": _lic(emailLock="h@x.com")}

    assert run("--project", "p") == 0
    assert capsys.readouterr().out.startswith("1 live licence(s)")


@pytest.mark.parametrize("live", [
    {},  # perpetual
    {"mode": None, "plan": "professional"},  # pre-rename vocabulary
    {"mode": "LICENSED "},
    {"expiresAt": _now() - timedelta(days=1), "graceDays": 7},  # inside grace
    {"expiresAt": _now() + timedelta(days=30)},
    {"expiresAt": "2020-01-01"},  # malformed: fails open, as the app does
])
def test_live_licences_count(store, run, capsys, live):
    store._data["licenses"] = {"X": _lic(emailLock="h@x.com", **live), "Y": _lic(emailLock="h@x.com")}

    assert run("--project", "p") == 1
    assert capsys.readouterr().out.startswith("2 live licence(s)")


@pytest.mark.parametrize("lic", [
    _lic(),
    _lic(status="revoked"),
    _lic(mode="demo"),
    _lic(mode="weird", plan="professional"),
    _lic(mode=None, plan=None),
    _lic(expiresAt=_now() - timedelta(days=2), graceDays=1),
    _lic(expiresAt=_now() - timedelta(days=2), graceDays=5),
    _lic(expiresAt=_now() + timedelta(days=2)),
    _lic(expiresAt="not a date"),
    _lic(expiresAt=_now() - timedelta(days=2), graceDays=None),
])
def test_live_matches_the_apps_licence_is_live(lic):
    """The docstring promises `licence_is_live`; the script re-derives it so
    it can run without the repo's client. Keep the two in step."""
    assert fdl._live(lic, _now()) is holders.licence_is_live(lic)


# ------------------------------------------------------------------ cli


def test_writes_nothing(store, run):
    store._data["licenses"] = {"A": _inst(emailLock="a@x.com"), "B": _lic(emailLock="a@x.com")}
    store._data["licenses/A/seats"] = {"u": {"email": "a@x.com"}}
    store._data["licenseInvites"] = {"i": {"licenseId": "B", "email": "a@x.com"}}
    store._data["users"] = {"u": {"licenseId": "A", "email": "a@x.com"}}
    before = copy.deepcopy(store._data)

    assert run("--project", "p") == 1
    assert store._data == before


def test_project_is_required(run, capsys):
    with pytest.raises(SystemExit) as exc:
        run()

    assert exc.value.code == 2
    assert "--project" in capsys.readouterr().err
    assert run.built == []
