"""scripts/check_licence_fields.py: every licence can appear in the staff desk.

`GET /v1/admin/licenses` filters on `mode` and `status` and orders by
`createdAt`; Firestore drops a document lacking any of the three from that
query without an error. The script reads every licence once, reports each
field's missing ids and exits 1 when any is missing. It is read-only.

Driven through `main()` against the in-memory store double, with
`firestore.Client` swapped for one that hands back the double.
"""
import copy
import sys
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
import check_licence_fields  # noqa: E402

_CREATED = datetime(2026, 1, 2, tzinfo=timezone.utc)


def _licence(**over):
    return {"mode": "licensed", "status": "active", "createdAt": _CREATED, **over}


@pytest.fixture
def run(store, monkeypatch):
    """Run `main()` with the given argv against the store double; returns
    (exit code, the project the client was built for)."""
    built: list[str | None] = []

    def _client(project=None):
        built.append(project)
        return store

    monkeypatch.setattr(check_licence_fields, "firestore", SimpleNamespace(Client=_client))

    def _run(*argv):
        monkeypatch.setattr(sys, "argv", ["check_licence_fields.py", *argv])
        code = check_licence_fields.main()
        return code, (built[-1] if built else None)

    return _run


def test_complete_licences_pass(store, run, capsys):
    store._data["licenses"] = {"a": _licence(), "b": _licence(status="revoked"), "c": _licence()}

    code, project = run("--project", "proj-x")

    assert code == 0
    assert project == "proj-x"
    assert capsys.readouterr().out.splitlines() == [
        "3 licence document(s) checked.",
        "  every licence has mode, status and createdAt.",
    ]


def test_empty_collection_passes(run, capsys):
    code, _ = run("--project", "p")

    assert code == 0
    out = capsys.readouterr().out
    assert out.startswith("0 licence document(s) checked.\n")
    assert "every licence has mode, status and createdAt." in out


@pytest.mark.parametrize("field", ["mode", "status", "createdAt"])
@pytest.mark.parametrize("how", ["absent", "none", "empty"])
def test_each_missing_field_fails(store, run, capsys, field, how):
    bad = _licence()
    if how == "absent":
        del bad[field]
    else:
        bad[field] = None if how == "none" else ""
    store._data["licenses"] = {"good": _licence(), "bad": bad}

    code, _ = run("--project", "p")

    assert code == 1
    lines = capsys.readouterr().out.splitlines()
    assert lines == ["2 licence document(s) checked.", f"  missing {field}: 1 (bad)"]


def test_one_licence_missing_several_fields_is_listed_under_each(store, run, capsys):
    store._data["licenses"] = {
        "only-mode": {"mode": "licensed"},
        "empty": {},
        "ok": _licence(),
    }

    code, _ = run("--project", "p")

    assert code == 1
    assert capsys.readouterr().out.splitlines() == [
        "3 licence document(s) checked.",
        "  missing mode: 1 (empty)",
        "  missing status: 2 (empty, only-mode)",
        "  missing createdAt: 2 (empty, only-mode)",
    ]


def test_falsy_but_present_values_are_not_missing(store, run, capsys):
    """Only None and "" count: Firestore indexes 0 and False like any other
    value, so such a licence still appears in the desk's query."""
    store._data["licenses"] = {"z": _licence(status=0, mode=False, createdAt=0)}

    code, _ = run("--project", "p")

    assert code == 0
    assert "every licence has" in capsys.readouterr().out


def test_long_lists_are_truncated_to_ten_short_ids(store, run, capsys):
    ids = [f"licence-{i:02d}-abcdefghij" for i in range(13)]
    store._data["licenses"] = {i: _licence(createdAt=None) for i in ids}

    code, _ = run("--project", "p")

    assert code == 1
    line = capsys.readouterr().out.splitlines()[1]
    shown = ", ".join(i[:12] for i in ids[:10])
    assert line == f"  missing createdAt: 13 ({shown} and 3 more)"
    assert "licence-10" not in line


def test_exactly_ten_missing_has_no_more_suffix(store, run, capsys):
    store._data["licenses"] = {f"l{i}": _licence(mode="") for i in range(10)}

    run("--project", "p")

    line = capsys.readouterr().out.splitlines()[1]
    assert line.startswith("  missing mode: 10 (")
    assert "more" not in line


def test_reads_only_top_level_licences(store, run, capsys):
    """Seats are a subcollection and other collections are not licences."""
    store._data["licenses"] = {"a": _licence()}
    store._data["licenses/a/seats"] = {"uid-1": {"email": "x@y"}}
    store._data["users"] = {"u": {"email": "x@y"}}

    code, _ = run("--project", "p")

    assert code == 0
    assert capsys.readouterr().out.startswith("1 licence document(s) checked.")


def test_writes_nothing(store, run):
    store._data["licenses"] = {"a": _licence(), "b": {"mode": "licensed"}}
    before = copy.deepcopy(store._data)

    run("--project", "p")

    assert store._data == before


def test_project_is_required(run, capsys):
    with pytest.raises(SystemExit) as exc:
        run()

    assert exc.value.code == 2
    assert "--project" in capsys.readouterr().err
