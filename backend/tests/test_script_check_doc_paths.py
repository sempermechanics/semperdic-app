"""`scripts/check_doc_paths.py`: the CI gate on doc references to repository files.

It runs on every CI event, so a reference it misses rots silently and one it
flags wrongly blocks every PR. Each test builds a small clean tree, plants one
reference in a doc, and runs the script against that tree through `--root`.
"""
from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "check_doc_paths.py"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")


@pytest.fixture(scope="module")
def checker():
    spec = importlib.util.spec_from_file_location("check_doc_paths", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(autouse=True)
def _fresh_exists_cache(checker):
    """`_exists` is an lru_cache keyed on the path: clear it so no test sees
    another's answer for a path it later creates."""
    checker._exists.cache_clear()
    yield
    checker._exists.cache_clear()


def _write(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))
    return path


_README = """# Docs

See [the architecture](app/ARCHITECTURE.md) and [its runtime part](app/ARCHITECTURE.md#runtime).
The store is `app/src/main/java/com/example/SessionStore.kt`, the gate is
`scripts/check.py`, and CI lives in `.github/workflows/ci.yml`.
"""

_GUIDE = """# Guide

Back to the [index](../README.md); the backend entry point is `backend/app/main.py`.
"""


@pytest.fixture
def tree(tmp_path):
    """A clean repository: three docs whose every reference exists."""
    root = tmp_path / "repo"
    _write(root / "README.md", _README)
    _write(root / "docs" / "GUIDE.md", _GUIDE)
    _write(root / "app" / "ARCHITECTURE.md", "# Architecture\n")
    _write(root / "app" / "src" / "main" / "java" / "com" / "example" / "SessionStore.kt", "class SessionStore\n")
    _write(root / "scripts" / "check.py", "")
    _write(root / ".github" / "workflows" / "ci.yml", "on: push\n")
    _write(root / "backend" / "app" / "main.py", "")
    return root


def _plant(root: Path, line: str, doc: str = "docs/GUIDE.md") -> int:
    """Append `line` to `doc`; return its 1-based line number."""
    path = root / doc
    text = path.read_text(encoding="utf-8") if path.exists() else ""
    _write(path, text + line + "\n")
    return len((text + line).splitlines())


def _run(checker, root: Path, monkeypatch, capsys) -> tuple[int, str, str]:
    monkeypatch.setattr(sys, "argv", ["check_doc_paths.py", "--root", str(root)])
    status = checker.main()
    out = capsys.readouterr()
    return status, out.out, out.err


def test_a_clean_tree_passes(checker, tree, monkeypatch, capsys):
    status, out, err = _run(checker, tree, monkeypatch, capsys)
    assert status == 0, err
    assert "checked 3 markdown files" in out
    assert "every referenced path exists" in out
    assert err == ""


def test_the_cli_exits_0_on_a_clean_tree_and_1_with_the_reference_listed(tree):
    ok = subprocess.run([sys.executable, str(_SCRIPT), "--root", str(tree)],
                        capture_output=True, text=True, timeout=60)
    assert ok.returncode == 0, ok.stderr
    assert "every referenced path exists" in ok.stdout

    _plant(tree, "Gone: `app/src/Gone.kt`.")
    bad = subprocess.run([sys.executable, str(_SCRIPT), "--root", str(tree)],
                         capture_output=True, text=True, timeout=60)
    assert bad.returncode == 1
    assert str(Path("app/src/Gone.kt")) in bad.stderr
    assert "engine/" in bad.stderr  # the hint for submodule paths


def test_root_defaults_to_the_working_directory(tree):
    run = subprocess.run([sys.executable, str(_SCRIPT)], cwd=tree, capture_output=True, text=True, timeout=60)
    assert run.returncode == 0, run.stderr
    assert "checked 3 markdown files" in run.stdout


# --- each kind of broken reference is reported --------------------------------

@pytest.mark.parametrize(("line", "shown"), [
    ("A [relative link](MISSING.md) to nothing.", "docs/MISSING.md"),
    ("A [link with an anchor](MISSING.md#part) to nothing.", "docs/MISSING.md"),
    ("A [link up a level](../app/GONE.md).", "app/GONE.md"),
    ("A backticked `app/src/main/Gone.kt` path.", "app/src/main/Gone.kt"),
    ("A backticked workflow `.github/workflows/gone.yml`.", ".github/workflows/gone.yml"),
    ("Each top-level dir: `benchmark/src/Gone.kt`.", "benchmark/src/Gone.kt"),
    ("Each top-level dir: `firebase-hosting/public/gone.html`.", "firebase-hosting/public/gone.html"),
    ("Each top-level dir: `gradle/libs.versions.toml`.", "gradle/libs.versions.toml"),
    ("Each top-level dir: `docs/adr/ADR-099-gone.md`.", "docs/adr/ADR-099-gone.md"),
    ("A backticked path with a trailing stop `scripts/gone.py.` inside.", "scripts/gone.py"),
])
def test_a_missing_reference_is_reported_with_its_doc_and_line(checker, tree, monkeypatch, capsys, line, shown):
    number = _plant(tree, line)
    status, out, err = _run(checker, tree, monkeypatch, capsys)
    assert status == 1
    expected = f"{Path('docs/GUIDE.md')}:{number}: {Path(shown)}"
    assert expected in err, err
    assert "every referenced path exists" not in out


def test_a_link_out_of_the_repository_is_reported_by_its_absolute_path(checker, tree, monkeypatch, capsys):
    number = _plant(tree, "[outside](../../outside.md)")
    status, _, err = _run(checker, tree, monkeypatch, capsys)
    assert status == 1
    outside = (tree / "docs" / "../../outside.md").resolve()
    assert f"{Path('docs/GUIDE.md')}:{number}: {outside}" in err


def test_a_reference_is_resolved_against_the_root_not_the_doc(checker, tree, monkeypatch, capsys):
    """Backticked paths are repository-rooted: `scripts/check.py` exists at the
    root, so naming it from docs/ must pass, while a link written the same way
    resolves next to the doc and does not."""
    _plant(tree, "Rooted `scripts/check.py`; linked [check](scripts/check.py).")
    status, _, err = _run(checker, tree, monkeypatch, capsys)
    assert status == 1
    assert str(Path("docs/scripts/check.py")) in err
    assert err.count("check.py") == 1


def test_every_missing_reference_is_reported_not_just_the_first(checker, tree, monkeypatch, capsys):
    _plant(tree, "`app/One.kt` and `app/Two.kt`")
    _plant(tree, "[three](Three.md)", doc="README.md")
    status, _, err = _run(checker, tree, monkeypatch, capsys)
    assert status == 1
    for shown in ("app/One.kt", "app/Two.kt", "Three.md"):
        assert str(Path(shown)) in err
    assert f"{Path('README.md')}:" in err


def test_a_doc_anywhere_in_the_tree_is_checked(checker, tree, monkeypatch, capsys):
    _write(tree / "backend" / "deploy" / "NOTES.md", "Run `backend/deploy/gone.sh`.\n")
    status, out, err = _run(checker, tree, monkeypatch, capsys)
    assert status == 1
    assert "checked 4 markdown files" in out
    assert f"{Path('backend/deploy/NOTES.md')}:1: {Path('backend/deploy/gone.sh')}" in err


# --- what it deliberately does not police -------------------------------------

@pytest.mark.parametrize("line", [
    "A URL [link](https://example.com/docs/GONE.md).",
    "A plain [http link](http://example.com/GONE.md).",
    "A [mail link](mailto:someone@example.com).",
    "An [anchor](#section) on this page.",
    "Submodule [link up](../engine/docs/GONE.md), resolved into engine/.",
    "Submodule `engine/src/solver.cpp` — not a checked top-level dir.",
    "A placeholder segment `app/src/main/.../Gone.kt`.",
    "A numbered placeholder `docs/adr/ADR-00N-gone.md`.",
    "An angle placeholder `app/src/<flavor>/Gone.kt`.",
    "A glob `docs/*.md`.",
    "A brace set `app/{a,b}.kt`.",
    "A directory `app/.cxx` and a class `benchmark/HotPathMicroBenchmark`.",
    "An image ![logo](img/logo.png) is not a source suffix.",
    "A build output `app/build/outputs/mapping/release/mapping.txt`.",
    "Generated at deploy `backend/gateway/openapi.generated.yaml`.",
    "Never committed [local props](../local.properties).",
    "No top-level dir `src/Gone.kt` is prose.",
    "Trailing punctuation on a real path `scripts/check.py.`",
])
def test_a_reference_it_does_not_police_passes(checker, tree, monkeypatch, capsys, line):
    _plant(tree, line)
    status, _, err = _run(checker, tree, monkeypatch, capsys)
    assert status == 0, err


def test_a_root_level_engine_link_is_skipped_by_its_prefix(checker, tree, monkeypatch, capsys):
    _plant(tree, "[engine](engine/docs/GONE.md)", doc="README.md")
    status, _, err = _run(checker, tree, monkeypatch, capsys)
    assert status == 0, err


@pytest.mark.parametrize("doc", [
    "engine/README.md",
    "node_modules/pkg/README.md",
    "app/build/NOTES.md",
    ".gradle/NOTES.md",
    "backend/.venv/lib/README.md",
    "venv/README.md",
])
def test_docs_in_a_pruned_tree_are_not_read(checker, tree, monkeypatch, capsys, doc):
    _write(tree / doc, "Broken: `app/src/Gone.kt` and [gone](GONE.md).\n")
    status, out, err = _run(checker, tree, monkeypatch, capsys)
    assert status == 0, err
    assert "checked 3 markdown files" in out


# --- the helpers ----------------------------------------------------------------

def test_references_yield_line_numbers_and_resolved_targets(checker, tree):
    refs = list(checker._references(tree / "README.md", tree.resolve()))
    root = tree.resolve()
    assert refs == [
        (3, (tree / "app" / "ARCHITECTURE.md").resolve()),
        (3, (tree / "app" / "ARCHITECTURE.md").resolve()),
        (4, root / "app/src/main/java/com/example/SessionStore.kt"),
        (5, root / "scripts/check.py"),
        (5, root / ".github/workflows/ci.yml"),
    ]


@pytest.mark.parametrize(("target", "checkable"), [
    ("app/Foo.kt", True),
    ("docs/A.MD", True),
    ("backend/app/main.py", True),
    ("app/.cxx", False),
    ("app/Foo.class", False),
    ("https://x/a.md", False),
    ("engine/a.md", False),
    ("#top", False),
    ("app/.../Foo.kt", False),
    ("docs/ADR-00N.md", False),
])
def test_is_checkable(checker, target, checkable):
    assert checker._is_checkable(target) is checkable
