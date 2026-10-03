#!/usr/bin/env python3
"""Fail when a Markdown doc points at a repository file that is not there.

The docs name hundreds of source paths — `docs/WORKFLOWS.md` alone maps every
workflow to its files. A rename silently rots those references, and a map that
sends you to a file that no longer exists is worse than no map, so this runs in
CI on every event.

Two kinds of reference are checked:

* relative Markdown links, `[text](../app/ARCHITECTURE.md)`
* backticked repository paths, `` `app/src/main/.../SessionStore.kt` ``

Deliberately not checked: URLs, anchors, and anything under `engine/` — that is
a submodule, absent from every job that does not check it out.

Usage: python scripts/check_doc_paths.py [--root DIR]
"""
from __future__ import annotations

import argparse
import re
import sys
from functools import lru_cache
from pathlib import Path

# A backticked path rooted at one of the repository's top-level directories.
_TOP_LEVEL = ("app", "backend", "benchmark", "docs", "firebase-hosting", "gradle", "scripts")
_BACKTICKED = re.compile(
    r"`((?:" + "|".join(_TOP_LEVEL) + r"|\.github)/[A-Za-z0-9_./+-]+)`"
)
_LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")

# Only references that name a file are checked. A backticked directory
# (`app/.cxx`) or a class shown with a package-ish prefix
# (`benchmark/HotPathMicroBenchmark`) is prose, not a link to follow.
_SOURCE_SUFFIXES = {
    ".cpp", ".css", ".h", ".html", ".json", ".kt", ".kts", ".md", ".pro",
    ".properties", ".py", ".sh", ".svg", ".toml", ".txt", ".xml", ".yaml", ".yml",
}

# Submodule content, and paths with a placeholder segment, are not resolvable.
_SKIP_PREFIXES = ("engine/", "http://", "https://", "mailto:", "#")

# Trees that are generated, vendored or not ours: skipped wherever they appear.
_SKIP_DIRS = {"engine", "build", ".git", ".gradle", ".cxx", ".venv", "node_modules", "venv"}
_PLACEHOLDERS = ("...", "<", "*", "{", "00N")

# Real paths that are absent from a fresh checkout by design. Each is either a
# build output, or a secret that CI injects — naming them in the docs is
# correct, and requiring them here would fail every clean tree.
_EXPECTED_ABSENT = {
    "app/release.keystore",                      # injected from a CI secret
    "backend/gateway/openapi.generated.yaml",    # generated during deploy
    "local.properties",                          # never committed
}


def _is_checkable(target: str) -> bool:
    if target.startswith(_SKIP_PREFIXES) or any(t in target for t in _PLACEHOLDERS):
        return False
    return Path(target).suffix.lower() in _SOURCE_SUFFIXES


@lru_cache(maxsize=None)
def _exists(target: str) -> bool:
    """Cached: the docs name the same source files from many pages."""
    return Path(target).exists()


def _skipped(target: Path, root: Path) -> bool:
    """A reference we do not police: a pruned tree, or absent by design."""
    if not target.is_relative_to(root):
        return False
    relative = target.relative_to(root)
    return bool(_SKIP_DIRS.intersection(relative.parts)) or relative.as_posix() in _EXPECTED_ABSENT


def _references(doc: Path, root: Path):
    """Yield (line number, resolved path) for every checkable reference."""
    for number, line in enumerate(doc.read_text(encoding="utf-8").splitlines(), 1):
        for match in _BACKTICKED.finditer(line):
            target = match.group(1).rstrip(".,;:")
            if _is_checkable(target):
                yield number, root / target
        for match in _LINK.finditer(line):
            target = match.group(1).split("#", 1)[0]
            if not target or not _is_checkable(target):
                continue
            # Resolved rather than joined: a link into the engine submodule is
            # written ../../engine/… and must be skipped in that form too.
            yield number, (doc.parent / target).resolve()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", help="repository root (default: cwd)")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    docs = sorted(
        path
        for path in root.rglob("*.md")
        if not _SKIP_DIRS.intersection(path.relative_to(root).parts)
    )

    missing = []
    for doc in docs:
        for number, target in _references(doc, root):
            if _skipped(target, root):
                continue
            if not _exists(str(target)):
                relative = doc.relative_to(root)
                try:
                    shown = target.relative_to(root)
                except ValueError:
                    shown = target
                missing.append(f"{relative}:{number}: {shown}")

    print(f"checked {len(docs)} markdown files")
    if missing:
        print("\nreferences to files that do not exist:\n", file=sys.stderr)
        for entry in missing:
            print(f"  {entry}", file=sys.stderr)
        print(
            "\nFix the reference or restore the file. If it lives in the engine "
            "submodule, path it under engine/ so this check skips it.",
            file=sys.stderr,
        )
        return 1
    print("every referenced path exists")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
