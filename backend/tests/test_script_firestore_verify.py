"""`scripts/firestore_verify.py`: the manifest a restore drill is checked against.

`--emit` runs at export time (`firestore-backup.yml`) and `--verify` against a
restored database. A verifier that passes an empty or shuffled restore makes
the drill prove nothing, so each failure branch is planted here on a small
in-memory stand-in for `google.cloud.firestore.Client` — no GCP is touched.
"""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "firestore_verify.py"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")


@pytest.fixture(scope="module")
def fv():
    pytest.importorskip("google.cloud.firestore")
    spec = importlib.util.spec_from_file_location("firestore_verify", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# --- a Firestore stand-in: only the calls the script makes --------------------

class _CountQuery:
    def __init__(self, n: int):
        self._n = n

    def get(self):
        # The aggregation API answers [[AggregationResult(value=...)]].
        return [[SimpleNamespace(value=self._n)]]


class _Doc:
    def __init__(self, doc_id: str, data: dict | None):
        self.id = doc_id
        self._data = data

    @property
    def exists(self) -> bool:
        return self._data is not None

    def to_dict(self):
        return None if self._data is None else dict(self._data)


class _DocRef:
    def __init__(self, docs: dict, doc_id: str):
        self._docs, self._id = docs, doc_id

    def get(self) -> _Doc:
        return _Doc(self._id, self._docs.get(self._id))


class _Query:
    def __init__(self, db: FakeDb, name: str, group: bool = False, filters=(), limit=None, ordered=False):
        self._db, self._name, self._group = db, name, group
        self._filters, self._limit, self._ordered = tuple(filters), limit, ordered

    def _copy(self, **kw):
        state = dict(filters=self._filters, limit=self._limit, ordered=self._ordered)
        state.update(kw)
        return _Query(self._db, self._name, self._group, **state)

    def where(self, field, op, value):
        assert op == "=="
        return self._copy(filters=self._filters + ((field, value),))

    def order_by(self, field):
        assert field == "__name__"
        return self._copy(ordered=True)

    def limit(self, n):
        return self._copy(limit=n)

    def _matching(self):
        docs = sorted(self._db.data.get(self._name, {}).items())
        docs = [(i, d) for i, d in docs if all(d.get(f) == v for f, v in self._filters)]
        return docs[: self._limit] if self._limit is not None else docs

    def count(self):
        return _CountQuery(len(self._matching()))

    def stream(self):
        assert self._ordered, "the script samples in __name__ order"
        return iter([_Doc(i, d) for i, d in self._matching()])

    def document(self, doc_id):
        return _DocRef(self._db.data.get(self._name, {}), doc_id)


class FakeDb:
    def __init__(self, data: dict[str, dict[str, dict]]):
        self.data = data
        self.calls: list[tuple[str, str]] = []

    def collection(self, name):
        self.calls.append(("collection", name))
        return _Query(self, name)

    def collection_group(self, name):
        self.calls.append(("group", name))
        return _Query(self, name, group=True)


def _source() -> dict[str, dict[str, dict]]:
    """A small export: two sessions with files, one versioned user, seats."""
    return {
        "users": {"u1": {"schemaVersion": 3}, "u2": {}},
        "devices": {"d1": {}},
        "sessions": {
            "s1": {"uid": "u1", "fileCount": 2},
            "s2": {"uid": "u2", "fileCount": 1},
        },
        "files": {
            "f1": {"sessionId": "s1"},
            "f2": {"sessionId": "s1"},
            "f3": {"sessionId": "s2"},
        },
        "licenses": {"L1": {}},
        "seats": {"L1-a": {}, "L1-b": {}},
        "challenges": {"c1": {}, "c2": {}},
    }


@pytest.fixture
def connect(fv, monkeypatch):
    """Point `firestore.Client(project=...)` at a FakeDb; returns a setter that
    answers the projects the script opens from then on."""

    def use(db: FakeDb) -> list[str]:
        projects: list[str] = []

        def client(project):
            projects.append(project)
            return db
        monkeypatch.setattr(fv, "firestore", SimpleNamespace(Client=client))
        return projects

    return use


def _manifest(fv, connect, data=None) -> dict:
    connect(FakeDb(data if data is not None else _source()))
    return fv.emit("src-project")


# --- emit ------------------------------------------------------------------------

def test_emit_counts_every_collection_and_samples_session_edges(fv, connect):
    projects = connect(FakeDb(_source()))
    manifest = fv.emit("src-project")
    assert projects == ["src-project"]
    assert manifest["project"] == "src-project"
    assert set(manifest["counts"]) == set(fv.COLLECTIONS) | set(fv.GROUPS)
    assert manifest["counts"]["users"] == 2
    assert manifest["counts"]["files"] == 3
    assert manifest["counts"]["seats"] == 2
    assert manifest["counts"]["auth_links"] == 0
    assert "challenges" not in manifest["counts"]
    assert manifest["volatileCounts"] == {"challenges": 2}
    assert manifest["sample"] == [
        {"sessionId": "s1", "uid": "u1", "fileCount": 2, "actualFiles": 2},
        {"sessionId": "s2", "uid": "u2", "fileCount": 1, "actualFiles": 1},
    ]


def test_subcollections_are_counted_as_collection_groups(fv, connect):
    db = FakeDb(_source())
    connect(db)
    fv.emit("p")
    groups = {name for kind, name in db.calls if kind == "group"}
    assert groups == set(fv.GROUPS) == {"seats", "deleted_seats"}
    assert ("collection", "seats") not in db.calls


def test_the_sample_is_capped_and_in_document_id_order(fv, connect):
    data = _source()
    data["sessions"] = {f"s{i:02d}": {"uid": "u1"} for i in range(fv.SAMPLE_SIZE + 5, 0, -1)}
    manifest = _manifest(fv, connect, data)
    ids = [s["sessionId"] for s in manifest["sample"]]
    assert len(ids) == fv.SAMPLE_SIZE
    assert ids == sorted(ids) == [f"s{i:02d}" for i in range(1, fv.SAMPLE_SIZE + 1)]


def test_a_session_body_without_fields_samples_as_none(fv, connect):
    data = _source()
    data["sessions"]["s3"] = {}
    sample = _manifest(fv, connect, data)["sample"]
    assert sample[-1] == {"sessionId": "s3", "uid": None, "fileCount": None, "actualFiles": 0}


# --- verify: a good restore, and each failure it must catch ------------------

def test_an_identical_restore_verifies(fv, connect, capsys):
    manifest = _manifest(fv, connect)
    projects = connect(FakeDb(_source()))
    assert fv.verify("restore-project", manifest) == []
    assert projects == ["restore-project"]
    out = capsys.readouterr().out
    assert "  users: 2 ✓" in out
    assert "  auth_links: 0 (empty in the source too)" in out
    assert "  relationship sample: 2 sessions ✓" in out


def test_volatile_challenges_may_differ(fv, connect):
    manifest = _manifest(fv, connect)
    restored = _source()
    restored["challenges"] = {}
    connect(FakeDb(restored))
    assert fv.verify("r", manifest) == []


def test_an_empty_restore_fails_on_every_non_empty_collection(fv, connect):
    manifest = _manifest(fv, connect)
    connect(FakeDb({}))
    failures = fv.verify("r", manifest)
    for name in ("users", "devices", "sessions", "files", "licenses", "seats"):
        assert f"{name}: expected {manifest['counts'][name]} documents, restored 0" in failures
    assert "sample session s1 is missing after restore" in failures
    assert "no sampled user document carries schemaVersion — wrong or stale export?" in failures


def test_a_partial_restore_fails_on_the_short_collection(fv, connect, capsys):
    manifest = _manifest(fv, connect)
    restored = _source()
    del restored["devices"]["d1"]
    connect(FakeDb(restored))
    assert fv.verify("r", manifest) == ["devices: expected 1 documents, restored 0"]
    assert "relationship sample" not in capsys.readouterr().out


def test_extra_documents_fail_too(fv, connect):
    manifest = _manifest(fv, connect)
    restored = _source()
    restored["seats"]["L1-c"] = {}
    connect(FakeDb(restored))
    assert fv.verify("r", manifest) == ["seats: expected 2 documents, restored 3"]


def test_a_missing_sample_session_fails_without_further_checks_on_it(fv, connect):
    manifest = _manifest(fv, connect)
    manifest["counts"]["sessions"] = 1  # isolate the sample check from the count check
    restored = _source()
    del restored["sessions"]["s2"]
    connect(FakeDb(restored))
    assert fv.verify("r", manifest) == ["sample session s2 is missing after restore"]


def test_a_sample_session_owned_by_another_uid_fails(fv, connect):
    manifest = _manifest(fv, connect)
    restored = _source()
    restored["sessions"]["s1"]["uid"] = "u2"
    connect(FakeDb(restored))
    assert fv.verify("r", manifest) == ["sample session s1 came back owned by a different uid"]


def test_files_shuffled_between_sessions_fail_although_counts_match(fv, connect):
    manifest = _manifest(fv, connect)
    restored = _source()
    restored["files"]["f2"]["sessionId"] = "s2"
    connect(FakeDb(restored))
    assert fv.verify("r", manifest) == [
        "sample session s1: expected 2 files, restored 1",
        "sample session s2: expected 1 files, restored 2",
    ]


def test_users_without_schema_version_mean_a_stale_export(fv, connect):
    manifest = _manifest(fv, connect)
    restored = _source()
    restored["users"] = {"u1": {"schemaVersion": "3"}, "u2": {}}  # a string is not a stamp
    connect(FakeDb(restored))
    assert fv.verify("r", manifest) == [
        "no sampled user document carries schemaVersion — wrong or stale export?"
    ]


def test_one_versioned_user_is_enough(fv, connect):
    manifest = _manifest(fv, connect)
    restored = _source()
    restored["users"] = {"u1": {}, "u2": {"schemaVersion": 0}}
    connect(FakeDb(restored))
    assert fv.verify("r", manifest) == []


def test_schema_version_is_not_required_when_the_source_had_no_users(fv, connect):
    data = _source()
    data["users"] = {}
    manifest = _manifest(fv, connect, data)
    connect(FakeDb(data))
    assert fv.verify("r", manifest) == []


def test_an_empty_sample_verifies_without_the_sample_line(fv, connect, capsys):
    data = {"users": {"u1": {"schemaVersion": 1}}}
    manifest = _manifest(fv, connect, data)
    assert manifest["sample"] == []
    connect(FakeDb(data))
    assert fv.verify("r", manifest) == []
    assert "relationship sample" not in capsys.readouterr().out


# --- main: flags, files, exit status ------------------------------------------

def _main(fv, monkeypatch, *args: str):
    monkeypatch.setattr(sys, "argv", ["firestore_verify.py", *args])
    return fv.main()


@pytest.mark.parametrize("flags", [[], ["--emit", "a.json", "--verify", "b.json"]], ids=["neither", "both"])
def test_main_needs_exactly_one_of_emit_or_verify(fv, connect, monkeypatch, flags):
    connect(FakeDb(_source()))
    with pytest.raises(SystemExit) as exit_info:
        _main(fv, monkeypatch, "--project", "p", *flags)
    assert exit_info.value.code == "pass exactly one of --emit or --verify"


def test_main_requires_a_project(fv, monkeypatch, capsys):
    with pytest.raises(SystemExit) as exit_info:
        _main(fv, monkeypatch, "--emit", "a.json")
    assert exit_info.value.code == 2
    assert "--project" in capsys.readouterr().err


def test_main_emit_writes_a_sorted_manifest(fv, connect, monkeypatch, tmp_path, capsys):
    connect(FakeDb(_source()))
    path = tmp_path / "manifest.json"
    assert _main(fv, monkeypatch, "--project", "src", "--emit", str(path)) is None
    text = path.read_text(encoding="utf-8")
    manifest = json.loads(text)
    assert text == json.dumps(manifest, indent=2, sort_keys=True)
    assert manifest["project"] == "src"
    total = sum(manifest["counts"].values())
    assert total == 2 + 1 + 2 + 3 + 1 + 2
    n = len(fv.COLLECTIONS) + len(fv.GROUPS)
    assert f"Manifest written to {path}: {total} documents across {n} collections" in capsys.readouterr().out


def test_main_verify_passes_a_good_restore(fv, connect, monkeypatch, tmp_path, capsys):
    path = tmp_path / "manifest.json"
    connect(FakeDb(_source()))
    _main(fv, monkeypatch, "--project", "src", "--emit", str(path))
    projects = connect(FakeDb(_source()))
    assert _main(fv, monkeypatch, "--project", "drill", "--verify", str(path)) is None
    assert projects == ["drill"]
    out = capsys.readouterr()
    assert f"Verifying drill against {path} (exported from src)" in out.out
    assert "Restore verified: counts and sampled relationships match the export." in out.out
    assert out.err == ""


def test_main_verify_exits_1_and_lists_every_failure(fv, connect, monkeypatch, tmp_path, capsys):
    path = tmp_path / "manifest.json"
    connect(FakeDb(_source()))
    _main(fv, monkeypatch, "--project", "src", "--emit", str(path))
    restored = _source()
    del restored["devices"]["d1"]
    restored["sessions"]["s1"]["uid"] = "someone-else"
    connect(FakeDb(restored))
    with pytest.raises(SystemExit) as exit_info:
        _main(fv, monkeypatch, "--project", "drill", "--verify", str(path))
    assert exit_info.value.code == 1
    out = capsys.readouterr()
    assert "RESTORE VERIFICATION FAILED:" in out.err
    assert "  - devices: expected 1 documents, restored 0" in out.err
    assert "  - sample session s1 came back owned by a different uid" in out.err
    assert "Restore verified" not in out.out
