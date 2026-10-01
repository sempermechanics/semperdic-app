#!/usr/bin/env python3
"""Move Kotlin files between packages and repair every import the move breaks.

Written for the 2026-10 package split (ADR-015) and kept so the
material_testing fork can replay the same move on its own tree. Kotlin has no
package-private visibility, so moving a file between packages breaks only name
resolution: references that used to resolve through "same package" now need an
import, and imports that named the old package now name nothing. This script
fixes both with a deliberately simple model of the language:

1. Index every ``.kt`` file under ``app/src/*/{java,kotlin}`` and
   ``benchmark/src/*/{java,kotlin}``: its package, its imports, its top-level
   declarations (classes, objects, interfaces, type aliases, functions and
   properties, extensions included; ``private`` ones are file-local and
   ignored) and the identifiers its body uses (comments and string text are
   skipped, KDoc ``[links]`` and string templates are kept).
2. ``git mv`` each mapped file to the directory of its new package and rewrite
   its ``package`` line.
3. Rewrite explicit imports of moved top-level symbols (nested imports such as
   ``import p.Outer.Inner`` and aliases included) in every file. A star import
   of a package that loses files stays while the package still exists, and
   each symbol the file uses from it that moved gets an explicit import.
4. Add an import wherever a file used a symbol through the old shared package
   and the two now live apart: moved files importing what stayed behind, files
   left behind importing what moved, and moved files importing each other
   across new packages. ``R`` and ``BuildConfig`` count as root-package
   symbols, so a file that leaves the root package imports them.
5. Rewrite moved FQCNs where no import can help: inline in Kotlin code
   (``com.example.data.Foo.bar()``; never inside a string literal, where a
   class name may be persisted or compared, so a match there is only
   reported) and in XML under each source set
   (layout custom views, which ViewBinding compiles against, and manifest
   entries, shorthand ``.ui.Foo`` names included).
6. Optionally (``--compile``) run the Gradle compile tasks and add imports for
   any ``Unresolved reference`` the model missed, until a pass fixes nothing.

Over-approximation is intended: an identifier that merely matches a moved
symbol's name (a local of the same name, say) gets an import too, and
ktlint's ``no-unused-imports`` removes the unused ones. So the full recipe is::

    python scripts/move_kotlin_packages.py --mapping scripts/package_moves_2026_10.json
    ./gradlew --no-daemon spotlessApply      # sorts imports, drops unused ones
    python scripts/move_kotlin_packages.py --mapping ... --compile   # optional
    python scripts/move_kotlin_packages.py --mapping ... --kdoc      # FQCNs in comments
    python scripts/move_kotlin_packages.py --mapping ... --docs      # doc paths

``--kdoc`` rewrites fully qualified names in Kotlin comments (KDoc links
such as ``[com.example.data.Foo]``). ``--docs`` rewrites source paths and
FQCNs in tracked Markdown, workflow YAML, Python, TOML, ProGuard and Gradle
files. Both are separate passes so the move commit holds only ``package`` and
``import`` lines plus the few code/XML FQCNs the build cannot do without.
``--docs`` leaves dated records as written (metrics snapshots, the
changelog, decision records, dated perf reports: ``_DOCS_SKIP``) and names
any it skipped that mention moved files; ``--docs-skip GLOB...`` replaces
that list. ``--self-test`` checks the text-level rewriting without a
repository.

Mapping file (JSON)::

    {"moves": {"com.example.data.SessionStore": "com.example.data.session", ...},
     "test_moves": {"com.example.data.SessionStoreTest": "com.example.data.session"}}

A key is a file FQN: the file's package plus its file name without ``.kt``
(a repository-relative ``.kt`` path also works). The value is the new package.
``moves`` and ``test_moves`` are treated alike; the split is for reviewers.

Idempotent: a key whose file already sits in its new package is not moved
again, but it still drives import rewriting, so re-running after a partial or
complete move only fixes what is left. Warnings flag the cases the model cannot
settle by itself, notably a moved declaration that may now shadow a default or
star import in its new package.

Usage: python scripts/move_kotlin_packages.py --mapping FILE [--root DIR]
       [--dry-run] [--compile] [--max-passes N] [--kdoc] [--docs]
       [--docs-skip [GLOB ...]]
       python scripts/move_kotlin_packages.py --self-test
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import re
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

_MODULES = ("app", "benchmark")
_LANG_DIRS = ("java", "kotlin")
_SYNTHETIC_ROOT_SYMBOLS = ("R", "BuildConfig")

_PACKAGE_RE = re.compile(r"^package\s+([\w.]+)\s*$")
_IMPORT_RE = re.compile(r"^import\s+([\w.`]+?)(\.\*)?(?:\s+as\s+(\w+))?\s*$")
_IDENT_RE = re.compile(r"[A-Za-z_]\w*")
_CHAR_RE = re.compile(r"'(?:\\u[0-9A-Fa-f]{4}|\\.|[^'\\\n])'")
_ANNOTATION_PREFIX = re.compile(r"^(?:@[\w.:]+(?:\((?:[^()]|\([^()]*\))*\))?\s+)*")
_MODIFIERS = {
    "public", "internal", "private", "protected", "abstract", "open", "final",
    "sealed", "data", "enum", "annotation", "inline", "value", "inner", "const",
    "lateinit", "override", "suspend", "tailrec", "operator", "infix",
    "external", "expect", "actual", "noinline", "crossinline",
}
_DECL_KEYWORDS = {"class", "interface", "object", "fun", "val", "var", "typealias"}

# Which source sets each source set compiles against (Android Gradle defaults).
_VISIBLE_SETS = {
    "app:main": {"app:main"},
    "app:debug": {"app:main", "app:debug"},
    "app:release": {"app:main", "app:release"},
    "app:benchmark": {"app:main", "app:benchmark"},
    "app:test": {"app:main", "app:debug", "app:test", "app:testDebug"},
    "app:testDebug": {"app:main", "app:debug", "app:test", "app:testDebug"},
    "app:androidTest": {"app:main", "app:debug", "app:androidTest"},
    "benchmark:main": {"benchmark:main", "app:main"},
}


@dataclass
class Import:
    path: str          # dotted path without the trailing .* (backticks removed)
    star: bool
    alias: str | None

    def render(self) -> str:
        tail = ".*" if self.star else ""
        alias = f" as {self.alias}" if self.alias else ""
        return f"import {self.path}{tail}{alias}"

    @property
    def simple_name(self) -> str:
        return self.alias or self.path.rsplit(".", 1)[-1]


@dataclass
class KtFile:
    path: Path                     # current repository-relative path
    source_set: str                # e.g. "app:main"
    source_root: Path              # repository-relative .../java or .../kotlin
    lines: list[str]
    package: str
    imports: list[Import]
    declarations: set[str]         # non-private top-level names
    own_names: set[str]            # every top-level name, private included
    used: set[str]
    old_package: str = ""
    new_package: str = ""
    new_path: Path | None = None
    added: set[str] = field(default_factory=set)

    @property
    def moves(self) -> bool:
        return self.new_path is not None and self.new_path != self.path


# --------------------------------------------------------------------------- #
# Lexing: identifiers used, declarations made.
# --------------------------------------------------------------------------- #

def _code_text(text: str) -> str:
    """The text with comments and string contents blanked.

    KDoc/comment ``[links]`` and string templates (``$name``, ``${expr}``) are
    kept, because both resolve names through imports.
    """
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            out.append(" " + " ".join(re.findall(r"\[([\w.]+)\]", text[i:end])) + " ")
            i = end
        elif text.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif text.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            out.append(" " + " ".join(re.findall(r"\[([\w.]+)\]", text[i:j])) + " ")
            i = j
        elif c == '"':
            raw = text.startswith('"""', i)
            quote = '"""' if raw else '"'
            j = i + len(quote)
            while j < n and not text.startswith(quote, j):
                if not raw and text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "$" and j + 1 < n:
                    if text[j + 1] == "{":
                        depth, k = 1, j + 2
                        while k < n and depth:
                            depth += {"{": 1, "}": -1}.get(text[k], 0)
                            k += 1
                        out.append(" " + _code_text(text[j + 2:k - 1]) + " ")
                        j = k
                        continue
                    m = _IDENT_RE.match(text, j + 1)
                    if m:
                        out.append(" " + m.group(0) + " ")
                        j = m.end()
                        continue
                j += 1
            i = j + len(quote)
            out.append(" ")
        elif c == "`":
            # A backticked name (test names often hold apostrophes): skip it.
            end = text.find("`", i + 1)
            i = n if end < 0 else end + 1
            out.append(" ")
        elif c == "'":
            m = _CHAR_RE.match(text, i)
            i = m.end() if m else i + 1
            out.append(" ")
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _literal_spans(text: str) -> list[tuple[int, int, str]]:
    """(start, end, kind) of every comment and string literal in ``text``.

    ``kind`` is ``"comment"`` or ``"string"``. A string's ``${expr}``
    templates are code, so a string is reported as the pieces around them.
    Both normal and raw (triple-quoted) strings are covered.
    """
    spans: list[tuple[int, int, str]] = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            spans.append((i, end, "comment"))
            i = end
        elif text.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif text.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            spans.append((i, j, "comment"))
            i = j
        elif text[i] == '"':
            quote = '"""' if text.startswith('"""', i) else '"'
            start, j = i, i + len(quote)
            while j < n and not text.startswith(quote, j):
                if quote == '"' and text[j] == "\\":
                    j += 2
                elif text.startswith("${", j):
                    spans.append((start, j, "string"))
                    depth, j = 1, j + 2
                    while j < n and depth:
                        depth += {"{": 1, "}": -1}.get(text[j], 0)
                        j += 1
                    start = j
                else:
                    j += 1
            j = min(n, j + len(quote))
            spans.append((start, j, "string"))
            i = j
        elif text[i] == "`":
            end = text.find("`", i + 1)
            i = n if end < 0 else end + 1
        elif text[i] == "'":
            m = _CHAR_RE.match(text, i)
            i = m.end() if m else i + 1
        else:
            i += 1
    return spans


def _strip_generics(text: str) -> str:
    depth, out = 0, []
    for ch in text:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth = max(0, depth - 1)
        elif depth == 0:
            out.append(ch)
    return "".join(out)


def _declared_name(line: str) -> tuple[str, bool] | None:
    """(name, is_private) for a column-0 top-level declaration line, else None."""
    if not line or line[0] in " \t}/*)" or line.startswith(("package ", "import ")):
        return None
    rest = _ANNOTATION_PREFIX.sub("", line)
    words = rest.split()
    private = False
    idx = 0
    while idx < len(words) and words[idx] in _MODIFIERS:
        private = private or words[idx] == "private"
        idx += 1
    if idx >= len(words) or words[idx] not in _DECL_KEYWORDS:
        return None
    keyword = words[idx]
    tail = " ".join(words[idx + 1:])
    if keyword == "fun" and tail.startswith("interface "):
        tail, keyword = tail[len("interface"):], "interface"
    if keyword in {"class", "interface", "object", "typealias"}:
        m = _IDENT_RE.search(tail)
        return (m.group(0), private) if m else None
    # fun / val / var: the name is the last identifier before the parameter
    # list (fun) or the type / initializer (val, var), after any receiver.
    head = _strip_generics(tail)
    stop = re.search(r"\(" if keyword == "fun" else r"[:=]|\bby\b", head)
    head = head[: stop.start()] if stop else head
    names = _IDENT_RE.findall(head)
    return (names[-1], private) if names else None


def _parse(path: Path, root: Path, source_set: str, source_root: Path) -> KtFile:
    text = (root / path).read_text(encoding="utf-8")
    lines = text.split("\n")
    package = ""
    imports: list[Import] = []
    body: list[str] = []
    declarations: set[str] = set()
    own: set[str] = set()
    for line in lines:
        pm = _PACKAGE_RE.match(line)
        if pm and not package:
            package = pm.group(1)
            continue
        im = _IMPORT_RE.match(line)
        if im:
            imports.append(Import(im.group(1).replace("`", ""), bool(im.group(2)), im.group(3)))
            continue
        body.append(line)
        decl = _declared_name(line)
        if decl:
            own.add(decl[0])
            if not decl[1]:
                declarations.add(decl[0])
    used = set(_IDENT_RE.findall(_code_text("\n".join(body))))
    return KtFile(path, source_set, source_root, lines, package, imports,
                  declarations, own, used, package, package)


def _source_roots(root: Path):
    for module in _MODULES:
        src = root / module / "src"
        if not src.is_dir():
            continue
        for set_dir in sorted(p for p in src.iterdir() if p.is_dir()):
            for lang in _LANG_DIRS:
                base = set_dir / lang
                if base.is_dir():
                    yield f"{module}:{set_dir.name}", base


def _index(root: Path) -> list[KtFile]:
    files = []
    for source_set, base in _source_roots(root):
        for path in sorted(base.rglob("*.kt")):
            files.append(_parse(path.relative_to(root), root, source_set, base.relative_to(root)))
    return files


def _visible(user_set: str, decl_set: str) -> bool:
    return decl_set in _VISIBLE_SETS.get(user_set, {user_set, "app:main"})


# --------------------------------------------------------------------------- #
# Planning.
# --------------------------------------------------------------------------- #

def _load_mapping(path: Path) -> dict[str, str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    mapping: dict[str, str] = {}
    for section in ("moves", "test_moves"):
        mapping.update(data.get(section, {}))
    return mapping


def _fqn(f: KtFile) -> str:
    return f"{f.package}.{f.path.stem}" if f.package else f.path.stem


def _plan(files: list[KtFile], mapping: dict[str, str], warn) -> None:
    """Set old/new package and new path on every mapped file."""
    by_fqn: dict[str, list[KtFile]] = defaultdict(list)
    by_path = {f.path.as_posix(): f for f in files}
    for f in files:
        by_fqn[_fqn(f)].append(f)
    for key, new_pkg in mapping.items():
        if key.endswith(".kt"):
            target = by_path.get(Path(key).as_posix())
            old_pkg = target.package if target else None
            candidates = [target] if target else []
        else:
            old_pkg, _, stem = key.rpartition(".")
            candidates = by_fqn.get(key, [])
            if not candidates:
                # Already moved (a re-run): it sits in its new package now.
                candidates = by_fqn.get(f"{new_pkg}.{stem}", [])
        if not candidates:
            warn(f"mapping key {key}: no such file, skipped")
            continue
        if len(candidates) > 1:
            warn(f"mapping key {key}: ambiguous ({', '.join(c.path.as_posix() for c in candidates)}), skipped")
            continue
        f = candidates[0]
        f.old_package = old_pkg or f.package
        f.new_package = new_pkg
        rel_dir = Path(*new_pkg.split(".")) if new_pkg else Path()
        f.new_path = f.source_root / rel_dir / f.path.name


def _decl_table(files: list[KtFile]):
    """(old package, name) -> list of (new package, source set)."""
    table: dict[tuple[str, str], list[tuple[str, str]]] = defaultdict(list)
    for f in files:
        for name in f.declarations:
            table[(f.old_package, name)].append((f.new_package, f.source_set))
    root = _root_package(files)
    for name in _SYNTHETIC_ROOT_SYMBOLS:
        table[(root, name)].append((root, "app:main"))
    return table


def _split_import(path: str, table) -> tuple[str, str, str] | None:
    """(package, top-level name, rest) when the import names a known symbol."""
    parts = path.split(".")
    for k in range(len(parts) - 1, 0, -1):
        pkg, name = ".".join(parts[:k]), parts[k]
        if (pkg, name) in table:
            return pkg, name, ".".join(parts[k + 1:])
    return None


def _rewrite_imports(f: KtFile, table, packages_after: set[str]) -> list[Import]:
    result: list[Import] = []
    for imp in f.imports:
        if imp.star:
            if imp.path in packages_after or not any(pkg == imp.path for pkg, _ in table):
                result.append(imp)
            continue
        split = _split_import(imp.path, table)
        if not split:
            result.append(imp)
            continue
        pkg, name, rest = split
        targets = sorted({np for np, s in table[(pkg, name)] if _visible(f.source_set, s)}) or [pkg]
        for np in targets:
            path = ".".join(p for p in (np, name, rest) if p)
            if np == f.new_package and not rest and not imp.alias:
                continue  # now a same-package symbol; the import is redundant
            result.append(Import(path, False, imp.alias))
    return result


def _added_imports(f: KtFile, imports: list[Import], table) -> set[str]:
    explicit = {i.simple_name for i in imports if not i.star}
    stars = [i.path for i in f.imports if i.star]
    adds: set[str] = set()
    for name in f.used:
        if name in f.own_names or name in explicit:
            continue
        same_pkg = [(np, s) for np, s in table.get((f.old_package, name), ()) if _visible(f.source_set, s)]
        sources = same_pkg
        if not same_pkg:
            for star in stars:
                sources = sources + [
                    (np, s) for np, s in table.get((star, name), ())
                    if np != star and _visible(f.source_set, s)
                ]
        for np, _ in sources:
            if np != f.new_package:
                adds.add(f"{np}.{name}" if np else name)
    return adds


def _shadow_warnings(files: list[KtFile], warn) -> None:
    """A declaration moved into a package may out-rank a default/star import there."""
    arriving: dict[str, set[str]] = defaultdict(set)
    for f in files:
        if f.old_package != f.new_package:
            for name in f.declarations:
                arriving[f.new_package].add(name)
    for f in files:
        names = arriving.get(f.new_package, set())
        for name in sorted(names & f.used):
            if name in f.own_names or any(i.simple_name == name for i in f.imports if not i.star):
                continue
            declarers = [g for g in files if name in g.declarations and g.new_package == f.new_package]
            if all(g.old_package == f.old_package for g in declarers):
                continue
            warn(f"{f.path.as_posix()}: '{name}' now also resolves to {f.new_package}.{name}; check it is the one meant")


# --------------------------------------------------------------------------- #
# Rendering.
# --------------------------------------------------------------------------- #

def _import_key(imp: Import) -> tuple[int, str]:
    """ktlint_official layout: *, java.**, javax.**, kotlin.**, then aliases."""
    if imp.alias:
        group = 4
    elif imp.path.startswith("java."):
        group = 1
    elif imp.path.startswith("javax."):
        group = 2
    elif imp.path.startswith("kotlin."):
        group = 3
    else:
        group = 0
    return group, imp.render()


def _is_comment(line: str) -> bool:
    return line.lstrip().startswith(("*", "//", "/*"))


def _render(f: KtFile, imports: list[Import], fix_code=None) -> str:
    """The file with its new package line and import block.

    The import block is only re-rendered (deduplicated, in ktlint_official
    order) when its set of imports changed, so untouched files stay
    byte-identical whatever order they were in. ``fix_code`` rewrites moved
    FQCNs written inline in code (``com.example.data.Foo.bar()``), which no
    import can repair; comment lines are left to ``--kdoc``. When the file
    already imports the rewritten name, the FQCN collapses to the simple name
    instead, so the line does not grow past detekt's MaxLineLength.
    """
    seen: dict[str, Import] = {}
    for imp in imports:
        seen.setdefault(imp.render(), imp)
    imported = {i.path: i.path.rsplit(".", 1)[-1] for i in seen.values() if not i.star and not i.alias}
    lines = list(f.lines)
    if fix_code:
        lines = fix_code("\n".join(lines), imported, f.path.as_posix()).split("\n")
    if f.package != f.new_package:
        for i, line in enumerate(lines):
            if _PACKAGE_RE.match(line):
                lines[i] = f"package {f.new_package}"
                break
    if set(seen) == {imp.render() for imp in f.imports}:
        return "\n".join(lines)
    ordered = sorted(seen.values(), key=_import_key)
    import_idx = [i for i, line in enumerate(lines) if _IMPORT_RE.match(line)]
    rendered = [imp.render() for imp in ordered]
    if import_idx:
        first, last = import_idx[0], import_idx[-1]
        block = lines[first:last + 1]
        if all(_IMPORT_RE.match(x) or not x.strip() for x in block):
            lines[first:last + 1] = rendered
        else:  # comments inside the block: keep them, edit around them
            keep = [x for x in block if not _IMPORT_RE.match(x)]
            lines[first:last + 1] = rendered + keep
    elif rendered:
        pkg_idx = next((i for i, x in enumerate(lines) if _PACKAGE_RE.match(x)), -1)
        lines[pkg_idx + 1:pkg_idx + 1] = [""] + rendered
    return "\n".join(lines)


# --------------------------------------------------------------------------- #
# FQCN and path rewriting outside import lines.
# --------------------------------------------------------------------------- #

def _moved_symbols(files: list[KtFile]) -> dict[tuple[str, str], str]:
    moved: dict[tuple[str, str], str] = {}
    for f in files:
        if f.old_package != f.new_package:
            for name in f.own_names:
                moved[(f.old_package, name)] = f.new_package
    return moved


class _FqnRewriter:
    """Rewrites dotted names that start with a moved top-level symbol.

    Called on plain text (XML, docs, comment lines) it rewrites every match;
    ``code`` is the form for Kotlin sources.
    """

    def __init__(self, moved: dict[tuple[str, str], str], warn=None):
        self.moved = moved
        prefixes = sorted({pkg for pkg, _ in moved})
        self.pattern = (re.compile(r"\b(?:" + "|".join(re.escape(p) for p in prefixes) + r")(?:\.\w+)+")
                        if prefixes else None)
        self.warn = warn or (lambda msg: print(f"WARNING: {msg}", file=sys.stderr))

    def rewrite(self, name: str) -> str:
        parts = name.split(".")
        for k in range(len(parts) - 1, 0, -1):
            key = (".".join(parts[:k]), parts[k])
            if key in self.moved:
                return ".".join([self.moved[key]] + parts[k:])
        return name

    def __call__(self, text: str) -> str:
        return self.pattern.sub(lambda m: self.rewrite(m.group(0)), text) if self.pattern else text

    def code(self, text: str, imported: dict[str, str], where: str = "") -> str:
        """Rewrite moved FQCNs in Kotlin *code* only.

        Comments are left to ``--kdoc``; package and import lines are
        rendered separately. Text inside a string literal (normal or raw
        ``\"\"\"``) is never touched: a class name there may be persisted or
        compared, so a match there is reported for a human to decide. A
        ``${expr}`` template is code and is rewritten. A rewritten name the
        file already imports collapses to its simple name, which keeps the
        line inside detekt's MaxLineLength.
        """
        if not self.pattern:
            return text
        spans = _literal_spans(text)
        header = [(m.start(), m.end()) for m in re.finditer(r"(?m)^(?:package|import)\s.*$", text)]

        def inside(pos: int) -> str | None:
            for start, end, kind in spans:
                if start <= pos < end:
                    return kind
            return "header" if any(a <= pos < b for a, b in header) else None

        out, last = [], 0
        for m in self.pattern.finditer(text):
            new = self.rewrite(m.group(0))
            if new == m.group(0):
                continue
            kind = inside(m.start())
            if kind == "string":
                line = text.count("\n", 0, m.start()) + 1
                self.warn(f"{where}:{line}: '{m.group(0)}' is inside a string literal; left unchanged")
                continue
            if kind:
                continue
            for path, simple in imported.items():
                if new == path or new.startswith(path + "."):
                    new = simple + new[len(path):]
                    break
            out.append(text[last:m.start()])
            out.append(new)
            last = m.end()
        out.append(text[last:])
        return "".join(out)


def _root_package(files: list[KtFile]) -> str:
    """The app's root package: the shortest package in app/src/main."""
    pkgs = {f.old_package for f in files if f.source_set == "app:main"}
    return min(pkgs, key=len) if pkgs else ""


def _path_rewriter(files: list[KtFile]):
    """Rewrites ``<old package dir>/<File>[.kt]`` path fragments to the new dirs.

    Paths are matched relative to the root package directory, which is how
    the docs abbreviate them (``data/SessionStore.kt``,
    ``app/src/main/java/com/.../data/SessionStore.kt``, ``…/data/SessionStore.kt``
    all contain that tail). A file directly in the root package is matched
    with the root's last segment kept (``semper/DicResult.kt``) so a bare
    file name is never rewritten.
    """
    root_pkg = _root_package(files)
    root_parts = root_pkg.split(".")

    def rel(pkg: str) -> list[str]:
        parts = pkg.split(".")
        return parts[len(root_parts):] if parts[:len(root_parts)] == root_parts else parts

    pairs = []
    for f in files:
        if f.old_package == f.new_package:
            continue
        old_rel, new_rel = rel(f.old_package), rel(f.new_package)
        if root_pkg in (f.old_package, f.new_package):
            old_rel, new_rel = root_parts[-1:] + old_rel, root_parts[-1:] + new_rel
        pairs.append(("/".join(old_rel + [f.path.stem]), "/".join(new_rel + [f.path.stem])))
    pairs.sort(key=lambda p: -len(p[0]))
    # The stem must end at a word boundary, so data/SessionStore matches
    # data/SessionStore.kt and data/SessionStore but not data/SessionStoreTest.
    regexes = [(re.compile(r"(?<![\w.])" + re.escape(o) + r"\b"), n) for o, n in pairs]

    def fix(text: str) -> str:
        for rx, new in regexes:
            text = rx.sub(new, text)
        return text

    return fix


# Dated records: they describe the tree as it was on their date, so --docs
# leaves their paths alone (a decision record's "as built" list, a metrics
# snapshot, the changelog). Override with --docs-skip.
_DOCS_SKIP = (
    "docs/ops/QUALITY_BASELINE_*",
    "docs/ops/CHANGELOG.md",
    "docs/adr/ADR-*.md",
    "docs/engine/PERF_BASELINE_*",
    "docs/perf/*-20[0-9][0-9]-*",
)


def _docs_skipped(rel: Path, globs) -> bool:
    return any(fnmatch.fnmatchcase(rel.as_posix(), g) for g in globs)


def _tracked(root: Path, suffixes: tuple[str, ...]) -> list[Path]:
    out = subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True, check=True).stdout
    return [Path(p) for p in out.splitlines()
            if p.endswith(suffixes) and not p.startswith("native/")]


# --------------------------------------------------------------------------- #
# Compile loop.
# --------------------------------------------------------------------------- #

_UNRESOLVED = re.compile(
    r"^e: (?:file:///)?(?P<file>.+?\.kt):(?:(?P<l1>\d+):\d+| \((?P<l2>\d+), \d+\):?)\s+"
    r"Unresolved reference:? '?(?P<name>\w+)'?"
)


def _compile_pass(root: Path, tasks: list[str], files: list[KtFile], warn) -> int:
    gradlew = "gradlew.bat" if sys.platform == "win32" else "./gradlew"
    proc = subprocess.run([str(root / gradlew), "--no-daemon", "--continue", *tasks],
                          cwd=root, capture_output=True, text=True)
    output = proc.stdout + proc.stderr
    by_abs = {str((root / f.path).resolve()).lower(): f for f in files}
    decls: dict[str, list[KtFile]] = defaultdict(list)
    for f in files:
        for name in f.declarations:
            decls[name].append(f)
    fixes: dict[Path, set[str]] = defaultdict(set)
    for line in output.splitlines():
        m = _UNRESOLVED.match(line.strip())
        if not m:
            continue
        target = by_abs.get(str(Path(m.group("file")).resolve()).lower())
        if not target:
            continue
        name = m.group("name")
        pkgs = {g.package for g in decls.get(name, []) if _visible(target.source_set, g.source_set)}
        pkgs.discard(target.package)
        if len(pkgs) == 1:
            fixes[target.path].add(f"{pkgs.pop()}.{name}")
        else:
            warn(f"{target.path.as_posix()}: unresolved '{name}', candidates {sorted(pkgs) or 'none'}")
    applied = 0
    for path, adds in fixes.items():
        f = _parse(path, root, *next((g.source_set, g.source_root) for g in files if g.path == path))
        imports = f.imports + [Import(a, False, None) for a in sorted(adds)]
        text = _render(f, imports)
        if text == "\n".join(f.lines):
            warn(f"{path.as_posix()}: imports {sorted(adds)} already present; fix by hand")
            continue
        (root / path).write_text(text, encoding="utf-8", newline="")
        applied += 1
        print(f"compile fix: {path.as_posix()}: +{', '.join(sorted(adds))}")
    if proc.returncode != 0 and not applied:
        errors = "\n".join(x for x in output.splitlines() if x.startswith("e: ")) or output[-4000:]
        warn(f"compile still failing and nothing left to auto-fix:\n{errors}")
    return applied


# --------------------------------------------------------------------------- #
# Main.
# --------------------------------------------------------------------------- #

def _write(root: Path, rel: Path, text: str, dry_run: bool) -> None:
    if not dry_run:
        (root / rel).write_text(text, encoding="utf-8", newline="")


def _self_test() -> int:
    """Checks of the text-level rewriting; no repository needed."""
    warned: list[str] = []
    fix = _FqnRewriter({("com.example.data", "Foo"): "com.example.data.session"}, warned.append)
    old, new = "com.example.data.Foo", "com.example.data.session.Foo"
    q3 = '"' * 3
    cases = [
        ("code", f"val x = {old}.bar()", {}, f"val x = {new}.bar()", 0),
        ("code, already imported", f"val x = {old}.bar()", {new: "Foo"}, "val x = Foo.bar()", 0),
        ("string", f'val s = "{old}"', {}, f'val s = "{old}"', 1),
        ("string after escaped quote", f'val s = "a \\" {old}"', {}, f'val s = "a \\" {old}"', 1),
        ("raw string", f"val s = {q3}\n    {old}\n{q3}", {}, f"val s = {q3}\n    {old}\n{q3}", 1),
        ("string template", f'val s = "${{{old}.NAME}}"', {}, f'val s = "${{{new}.NAME}}"', 0),
        ("comment", f"// see [{old}]\n/* {old} */", {}, f"// see [{old}]\n/* {old} */", 0),
        ("import and package lines", f"package com.example.data\nimport {old}", {},
         f"package com.example.data\nimport {old}", 0),
        ("code after a string", f'f("x", {old}.bar())', {}, f'f("x", {new}.bar())', 0),
    ]
    failures = []
    for name, text, imported, want, want_warnings in cases:
        warned.clear()
        got = fix.code(text, imported, "test.kt")
        if got != want or len(warned) != want_warnings:
            failures.append(f"{name}: got {got!r} with {len(warned)} warning(s), want {want!r} with {want_warnings}")
    if fix(f"<{old}/>") != f"<{new}/>":
        failures.append("plain text: XML/doc text is not rewritten")
    for path, skipped in [
        ("docs/ops/QUALITY_BASELINE_2026-10-01.md", True), ("docs/ops/CHANGELOG.md", True),
        ("docs/adr/ADR-004-runspec.md", True), ("docs/perf/engine-viewer-check-2026-09.md", True),
        ("docs/adr/README.md", False), ("docs/app/ARCHITECTURE.md", False), ("CONTEXT.md", False),
    ]:
        if _docs_skipped(Path(path), _DOCS_SKIP) != skipped:
            failures.append(f"docs skip: {path} should {'' if skipped else 'not '}be skipped")
    for line, want in [
        ("@Serializable data class SessionRecord(", ("SessionRecord", False)),
        ("internal fun <T> List<T>.second(): T = this[1]", ("second", False)),
        ("private val TAG = \"x\"", ("TAG", True)),
        ("fun interface Listener {", ("Listener", False)),
        ("    fun nested() {}", None),
    ]:
        if _declared_name(line) != want:
            failures.append(f"declaration: {line!r} -> {_declared_name(line)!r}, want {want!r}")
    for failure in failures:
        print(f"FAIL {failure}")
    print(f"self-test: {'FAILED' if failures else 'ok'} ({len(failures)} failure(s))")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mapping", type=Path, help="JSON mapping file (required unless --self-test)")
    parser.add_argument("--root", default=Path(__file__).resolve().parent.parent, type=Path,
                        help="repository root (default: this script's repo)")
    parser.add_argument("--dry-run", action="store_true", help="print the plan, change nothing")
    parser.add_argument("--compile", action="store_true",
                        help="after the move, loop Gradle compiles adding imports for unresolved references")
    parser.add_argument("--max-passes", type=int, default=4, help="compile-loop passes (default 4)")
    parser.add_argument("--tasks", nargs="*", default=[
        ":app:compileDebugKotlin", ":app:compileDebugUnitTestKotlin",
        ":app:compileDebugAndroidTestKotlin", ":app:compileBenchmarkKotlin",
        ":benchmark:compileBenchmarkKotlin",
    ], help="Gradle tasks for --compile")
    parser.add_argument("--kdoc", action="store_true",
                        help="rewrite moved FQCNs in Kotlin comment lines (KDoc links)")
    parser.add_argument("--docs", action="store_true",
                        help="rewrite source paths and FQCNs in tracked .md/.yml/.yaml/.py/.toml/.pro/.kts files")
    parser.add_argument("--docs-skip", nargs="*", metavar="GLOB",
                        help="dated records --docs leaves as written (repository-relative globs); replaces "
                             f"the default {' '.join(_DOCS_SKIP)}; give it no globs to skip nothing")
    parser.add_argument("--self-test", action="store_true", help="run the built-in checks and exit")
    args = parser.parse_args()
    if args.self_test:
        return _self_test()
    if not args.mapping:
        parser.error("--mapping is required")
    root: Path = args.root.resolve()
    warnings: list[str] = []

    def warn(msg: str) -> None:
        warnings.append(msg)
        print(f"WARNING: {msg}", file=sys.stderr)

    files = _index(root)
    _plan(files, _load_mapping(args.mapping), warn)
    table = _decl_table(files)
    packages_after = {f.new_package for f in files}
    moved = _moved_symbols(files)

    pending_moves = [f for f in files if f.moves]
    print(f"indexed {len(files)} Kotlin files; {len(pending_moves)} to move, "
          f"{sum(1 for f in files if f.old_package != f.new_package)} mapped")
    if args.dry_run:
        for f in pending_moves:
            print(f"  {f.path.as_posix()} -> {f.new_path.as_posix()}")

    fix = _FqnRewriter(moved, warn)
    fix_code = fix.code
    changed = 0
    for f in files:
        imports = _rewrite_imports(f, table, packages_after)
        adds = _added_imports(f, imports, table)
        imports += [Import(a, False, None) for a in sorted(adds)]
        text = _render(f, imports, fix_code)
        before = "\n".join(f.lines)
        if text == before and not f.moves:
            continue
        changed += 1
        if args.dry_run:
            print(f"  rewrite {f.path.as_posix()} (+{len(adds)} imports)")
            continue
        if f.moves:
            (root / f.new_path).parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(["git", "mv", f.path.as_posix(), f.new_path.as_posix()], cwd=root, check=True)
            f.path = f.new_path
        _write(root, f.path, text, args.dry_run)
        f.package = f.new_package
    _shadow_warnings(files, warn)
    print(f"rewrote {changed} Kotlin files")

    root_pkg = _root_package(files)

    def fix_relative(m: re.Match) -> str:
        # Manifest shorthand: android:name=".ui.Foo" is <namespace>.ui.Foo.
        full = fix(f"{root_pkg}.{m.group(2)}")
        short = full[len(root_pkg):] if full.startswith(root_pkg + ".") else full
        return f'{m.group(1)}{short}"'

    xml = set()
    for _, base in _source_roots(root):
        xml |= {p.relative_to(root) for p in base.parent.rglob("*.xml") if "build" not in p.parts}
    for rel in sorted(xml):
        text = (root / rel).read_text(encoding="utf-8")
        new = fix(text)
        if rel.name == "AndroidManifest.xml":
            new = re.sub(r'(android:name=")\.([\w.]+)"', fix_relative, new)
        if new != text:
            print(f"xml: {rel.as_posix()}")
            _write(root, rel, new, args.dry_run)

    if args.kdoc:
        for f in files:
            text = (root / f.path).read_text(encoding="utf-8")
            new = "\n".join(fix(line) if _is_comment(line) else line for line in text.split("\n"))
            if new != text:
                print(f"kdoc: {f.path.as_posix()}")
                _write(root, f.path, new, args.dry_run)

    if args.docs:
        fix_path = _path_rewriter(files)
        skip = {Path(args.mapping).resolve(), Path(__file__).resolve()}
        skip_globs = _DOCS_SKIP if args.docs_skip is None else args.docs_skip
        for rel in _tracked(root, (".md", ".yml", ".yaml", ".py", ".toml", ".pro", ".kts")):
            if (root / rel).resolve() in skip or not (root / rel).is_file():
                continue
            text = (root / rel).read_text(encoding="utf-8")
            new = fix_path(fix(text))
            if new != text and _docs_skipped(rel, skip_globs):
                print(f"docs: {rel.as_posix()} names moved files but is a dated record; left as written")
                continue
            if new != text:
                print(f"docs: {rel.as_posix()}")
                _write(root, rel, new, args.dry_run)

    if args.compile and not args.dry_run:
        for n in range(1, args.max_passes + 1):
            current = _index(root)
            print(f"compile pass {n}")
            if not _compile_pass(root, args.tasks, current, warn):
                break

    if warnings:
        print(f"\n{len(warnings)} warning(s); review them before committing.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
