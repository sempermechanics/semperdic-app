#!/usr/bin/env python3
"""Fail when a screen's `lifecycleScope` runs network calls or enqueues work.

ADR-016 rule 1 (docs/adr/ADR-016-work-that-outlives-the-activity.md): work the
user would lose when the screen rotates does not run on the Activity's
`lifecycleScope`; it goes to the screen's ViewModel, an application-lifetime
run, or WorkManager. Until 2026-10-19 only review enforced it, and Home's
restore and sign-in broke it until #457. Its 2026-10-19 amendment asks for a
check; this is that check (TD-291), a source heuristic rather than a lint rule:

* **Launch sites.** A `lifecycleScope.launch { }` / `async { }` (any
  `launch*`), or a call that takes the scope as an argument and a trailing
  lambda (`job.launch(activity.lifecycleScope) { }`, `ConflatedRefresh(scope)
  { }`). The bare `lifecycleScope` counts in a file that declares an Activity
  or Fragment subclass; a `receiver.lifecycleScope` counts anywhere under
  `ui/`, since that is a screen's scope handed to one of its parts (the
  `Settings*Section`s, the viewer's controllers).
* **What the block may not call**, directly or through a function declared in
  the same file (followed transitively): the `data/net` and `data/cloud`
  clients (`api.<call>`, `SemperApi.get(…).<call>`, `authed { }`, `CloudSync`,
  `CloudErase`, `CloudReconcile`, `CloudRestore`, `CloudAccountExport`,
  `RecycleBin`, `RestoreStart`, `SessionDeletes`, `SessionMetadataSync`, `SeatLease`, less
  the members of each that only touch the phone), the `AuthRepository` calls
  that reach Firebase or the backend, and any `enqueue*( )` or `oneTimeWork`.
* **Exceptions** are listed in `scripts/activity_scope_allowlist.txt` by file,
  enclosing function and call, each with its reason and a TD- or ADR- pointer.
  An entry that matches nothing fails, so the list only shrinks.

Not caught: a scope stored in a variable first, a call made through a function
in another file, and a client reached under another name. Stdlib only.

Usage: python scripts/check_activity_scope_work.py [--root DIR]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import NamedTuple

PACKAGE_ROOT = Path("app/src/main/java/com/sempermechanics/semper")
SCAN = "ui"
ALLOWLIST = Path("scripts/activity_scope_allowlist.txt")

#: data/net and data/cloud entry points, each with the members that only
#: touch the phone (prefs, the session index, WorkManager names and cancels).
#: Any other lower-case member is a network call or an enqueue.
CLIENTS: dict[str, frozenset[str]] = {
    "CloudSync": frozenset({"eraseLocalOnly", "uploadsEnabled", "settleWithoutBackend"}),
    "CloudErase": frozenset({"forgetCloudCopy", "toProbe"}),
    "CloudReconcile": frozenset({"reconcileDue", "reconcileFailure", "repairRows"}),
    "CloudRestore": frozenset({
        "bundleDownloadWorkName", "cancelBundleDownload", "clearPartialArtifacts",
        "suggestedBundleFileName", "targetLocalId", "workName",
    }),
    "CloudAccountExport": frozenset(),
    "RestoreStart": frozenset({"isRunning"}),
    "SessionDeletes": frozenset({
        "cancel", "decode", "encode", "pendingPurgeIds", "pendingRowIds", "rowIdsIn", "rowTag",
    }),
    "RecycleBin": frozenset({"erase", "eraseRows", "outcome"}),
    "SessionMetadataSync": frozenset(),
    "SeatLease": frozenset({"holdsFloatingSeat"}),
}
#: `CloudApi` members that are not requests.
API_LOCAL = frozenset({"enabled"})
#: `AuthRepository` calls that reach Firebase Auth or the backend.
AUTH_CALLS = (
    r"signInWith\w+|signUpWith\w+|completeTotpChallenge|resolveTotpChallenge|completeEmailLink"
    r"|reauthenticateWith\w+|sendSignInLink|sendPasswordReset|verifyPasswordResetCode"
    r"|confirmPasswordReset|refreshStatus|acceptTerms|setImprovementConsent|deleteIdentity|signOut"
)

_CLIENT = re.compile(r"(?<![\w.])(" + "|".join(CLIENTS) + r")\s*\.\s*([a-z]\w*)\b")
_API = re.compile(
    r"(?<![\w.])(?:api|cloudApi|SemperApi\s*\.\s*get\s*\([^()]*\))\s*\.\s*([a-z]\w*)\b"
)
_AUTHED = re.compile(r"(?<![\w])authed\s*[({]")
_AUTH = re.compile(r"\.\s*(" + AUTH_CALLS + r")\s*\(")
_ENQUEUE = re.compile(r"(?<![\w])(enqueue\w*)\s*\(")
_ONE_TIME = re.compile(r"(?<![\w])oneTimeWork\b")

_SCOPE = re.compile(r"(?<![\w$.@])(?:([\w@]+)\s*\.\s*)?lifecycleScope\b")
_LAUNCH = re.compile(r"\s*\.\s*(launch\w*|async)\b")
_FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:([\w.<>?, ]+)\.)?(\w+)\s*\(")
_CLASS = re.compile(r"\b(?:class|object)\s+(\w+)")
_SCREEN_SUPER = re.compile(r"(?<![\w])(?:\w+\.)*\w*(?:Activity|Fragment)\s*\(")
_LOCAL_CALL = re.compile(r"(?<![\w.])(\w+)\s*[({]")
_POINTER = re.compile(r"\b(?:TD|ADR)-\d+\b")


# ── Kotlin source, read just enough ─────────────────────────────────────


def strip_code(text: str) -> str:
    """Blank comments, string and char literals; every offset and newline stays."""
    out = list(text)
    n = len(text)

    def blank(start: int, end: int) -> None:
        for k in range(start, min(end, n)):
            if out[k] != "\n":
                out[k] = " "

    i = 0
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
        elif text.startswith("/*", i):
            depth, end = 1, i + 2
            while end < n and depth:
                if text.startswith("/*", end):
                    depth, end = depth + 1, end + 2
                elif text.startswith("*/", end):
                    depth, end = depth - 1, end + 2
                else:
                    end += 1
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            while end < n and text[end] == '"':
                end += 1
        elif text[i] == '"':
            end = _string_end(text, i + 1)
        elif text[i] == "'":
            end = i + 1
            if end < n and text[end] == "\\":
                end += 2
            close = text.find("'", end)
            end = n if close < 0 else close + 1
        else:
            i += 1
            continue
        blank(i, end)
        i = end
    return "".join(out)


def _string_end(text: str, j: int) -> int:
    n = len(text)
    while j < n:
        c = text[j]
        if c == "\\":
            j += 2
        elif text.startswith("${", j):
            depth, j = 1, j + 2
            while j < n and depth:
                depth += {"{": 1, "}": -1}.get(text[j], 0)
                j += 1
        elif c == '"':
            return j + 1
        elif c == "\n":
            return j
        else:
            j += 1
    return n


_OPEN = {"(": ")", "{": "}", "[": "]"}
_CLOSE = {v: k for k, v in _OPEN.items()}


def matching(code: str, start: int) -> int:
    """Offset of the bracket closing the one at `start`, or -1."""
    depth = 0
    for k in range(start, len(code)):
        if code[k] in _OPEN:
            depth += 1
        elif code[k] in _CLOSE:
            depth -= 1
            if depth == 0:
                return k
    return -1


def _skip_space(code: str, pos: int) -> int:
    while pos < len(code) and code[pos].isspace():
        pos += 1
    return pos


def _enclosing_paren(code: str, pos: int) -> int:
    """The `(` whose argument list holds `pos`, or -1 when `pos` is not in one."""
    depth = 0
    for k in range(pos - 1, -1, -1):
        c = code[k]
        if c in _CLOSE:
            depth += 1
        elif c in _OPEN:
            if depth == 0:
                return k if c == "(" else -1
            depth -= 1
    return -1


def line_of(code: str, pos: int) -> int:
    return code.count("\n", 0, pos) + 1


def is_screen(code: str) -> bool:
    """Whether the file declares an Activity or Fragment subclass."""
    for match in _CLASS.finditer(code):
        pos = _skip_space(code, match.end())
        if pos < len(code) and code[pos] == "<":
            pos = code.find(">", pos) + 1
        header_end = code.find("{", pos)
        header_end = len(code) if header_end < 0 else header_end
        paren = code.find("(", pos, header_end)
        colon = code.find(":", pos, header_end)
        if 0 <= paren < (colon if colon >= 0 else header_end):
            # Skip the primary constructor: `activity: SettingsActivity` is a
            # parameter, not a supertype.
            close = matching(code, paren)
            colon = code.find(":", close, header_end) if close >= 0 else -1
        if colon >= 0 and _SCREEN_SUPER.search(code, colon, header_end):
            return True
    return False


class Function(NamedTuple):
    name: str
    start: int
    params: tuple[int, int]  # the parameter list's `(` and `)`
    body: int  # offset of the body's first character
    end: int
    receiver: str  # an extension's receiver type, else ""


def _expression_end(code: str, pos: int) -> int:
    """Where an expression body that starts at `pos` ends: the first line end at
    bracket depth 0 that the next line does not continue (`.let`, `?:`, `&&`)."""
    depth, end = 0, _skip_space(code, pos)
    while end < len(code):
        c = code[end]
        if c == "\n" and depth == 0:
            following = _skip_space(code, end)
            if following >= len(code) or code[following] not in ".?:&|+-*/":
                return end
        depth += 1 if c in _OPEN else -1 if c in _CLOSE else 0
        end += 1
    return end


def functions(code: str) -> list[Function]:
    """Every `fun` with a body, block (`{ }`) or expression (`= …`)."""
    found = []
    for match in _FUN.finditer(code):
        close = matching(code, match.end() - 1)
        if close < 0:
            continue
        brace, equals = code.find("{", close), code.find("=", close)
        if equals >= 0 and (brace < 0 or equals < brace):
            end = _expression_end(code, equals + 1)
            body = equals + 1
        elif brace >= 0:
            body, end = brace + 1, matching(code, brace)
            if end < 0:
                continue
        else:
            continue
        receiver = (match.group(1) or "").strip().split("<")[0].split(".")[-1]
        found.append(
            Function(match.group(2), match.start(), (match.end() - 1, close), body, end, receiver)
        )
    return found


_PROPERTY = re.compile(r"\b(?:val|var)\s+(\w+)")


def enclosing(code: str, funs: list[Function], pos: int) -> str:
    """The function holding `pos`; outside one, the property it initialises."""
    inside = [f for f in funs if f.start <= pos <= f.end]
    if inside:
        return max(inside, key=lambda f: f.start).name
    properties = list(_PROPERTY.finditer(code, 0, pos))
    return properties[-1].group(1) if properties else "<init>"


# ── The check ────────────────────────────────────────────────────────────


class Launch(NamedTuple):
    at: int          # offset of `lifecycleScope`, or of the launching call
    block: tuple[int, int]
    via: str = ""    # the same-scope launcher whose lambda this is (`runAuth`)


class Finding(NamedTuple):
    path: str
    function: str     # the function holding the launch site
    call: str         # e.g. `CloudSync.reconcile`, `enqueueUniqueWork`, `authed`
    launch_line: int
    call_line: int
    via: str = ""


_FUN_PARAM = re.compile(r"(\w+)\s*:\s*(?:@\w+\s+)?(?:suspend\s*)?\(")


class Launcher(NamedTuple):
    """A function that runs a lambda it is given inside its own launch."""
    path: str
    owner: str  # the class or object declaring it, or an extension's receiver
    name: str


def owner_of(code: str, pos: int) -> str:
    """The innermost class or object whose body holds `pos`, or ""."""
    owner, start = "", -1
    for match in _CLASS.finditer(code, 0, pos):
        brace = code.find("{", match.end())
        if 0 <= brace < pos and matching(code, brace) > pos and match.start() > start:
            owner, start = match.group(1), match.start()
    return owner


def launchers(code: str, screen: bool, rel: str) -> set[Launcher]:
    """`fun runAuth(call: suspend () -> Result<String>) { lifecycleScope.launch {
    routeResult(call()) } }` puts every caller's `runAuth { authRepo.signIn… }`
    on the screen's scope; that is how sign-in broke rule 1 before #457."""
    found = set()
    sites = launches(code, screen)
    for fun in functions(code):
        params = [m.group(1) for m in _FUN_PARAM.finditer(code, *fun.params)]
        for site in sites:
            if not params or not fun.body <= site.at <= fun.end:
                continue
            block = code[site.block[0]:site.block[1]]
            if any(re.search(r"(?<![\w.])" + re.escape(p) + r"\s*(?:\(|\.\s*invoke\b)", block)
                   for p in params):
                found.add(Launcher(rel, fun.receiver or owner_of(code, fun.start), fun.name))
    return found


def _calls_launcher(code: str, rel: str, receiver: str | None, launcher: Launcher) -> bool:
    """Whether `receiver.name(…)` here is that launcher, not a namesake.

    In its own file an unqualified call is; elsewhere the receiver must be the
    owner itself (`SignOutRun.observe`) or a name this file declares with the
    owner's type (`activity: AuthActivity` for `activity.runAuth { }`).
    """
    if receiver is None or receiver == "this" or receiver.startswith("this@"):
        return rel == launcher.path
    if receiver == launcher.owner:
        return True
    typed = r"(?<![\w])" + re.escape(receiver) + r"\s*:\s*" + re.escape(launcher.owner) + r"\b"
    return bool(launcher.owner) and re.search(typed, code) is not None


def launches(
    code: str, screen: bool, via: frozenset[Launcher] = frozenset(), rel: str = ""
) -> list[Launch]:
    """Launch sites on a screen's scope; with `via`, also the trailing lambdas
    passed to those launchers (`runAuth { … }`, `activity.runAuth { … }`)."""
    found = []
    for launcher in sorted(via):
        call = re.compile(r"(?<![\w$.])(?<!fun )(?:([\w@]+)\s*\.\s*)?" + re.escape(launcher.name) + r"\b")
        for match in call.finditer(code):
            if not _calls_launcher(code, rel, match.group(1), launcher):
                continue
            pos = _skip_space(code, match.end())
            if pos < len(code) and code[pos] == "(":
                pos = _skip_space(code, matching(code, pos) + 1)
            if pos < len(code) and code[pos] == "{":
                end = matching(code, pos)
                if end > pos:
                    found.append(Launch(match.start(), (pos, end), launcher.name))
    for match in _SCOPE.finditer(code):
        if match.group(1) is None and not screen:
            continue
        call = _LAUNCH.match(code, match.end())
        if call:
            pos = _skip_space(code, call.end())
            if pos < len(code) and code[pos] == "(":
                pos = _skip_space(code, matching(code, pos) + 1)
        else:
            paren = _enclosing_paren(code, match.start())
            if paren < 0:
                continue
            pos = _skip_space(code, matching(code, paren) + 1)
        if pos < len(code) and code[pos] == "{":
            end = matching(code, pos)
            if end > pos:
                found.append(Launch(match.start(), (pos, end)))
    return found


def calls_in(
    code: str, start: int, end: int, local: frozenset[str] = frozenset()
) -> list[tuple[int, str]]:
    """(offset, call) for every flagged call between `start` and `end`.

    A call to a function of the same file (`local`) is followed by the caller
    rather than flagged by its name, however it is named.
    """
    hits: list[tuple[int, int, str]] = []
    segment = code[start:end]
    for match in _CLIENT.finditer(segment):
        if match.group(2) not in CLIENTS[match.group(1)]:
            hits.append((match.start(), match.end(), f"{match.group(1)}.{match.group(2)}"))
    for match in _API.finditer(segment):
        if match.group(1) not in API_LOCAL:
            hits.append((match.start(), match.end(), f"api.{match.group(1)}"))
    for pattern, label in ((_AUTHED, "authed"), (_ONE_TIME, "oneTimeWork")):
        hits.extend((m.start(), m.end(), label) for m in pattern.finditer(segment))
    for pattern in (_AUTH, _ENQUEUE):
        hits.extend(
            (m.start(), m.end(), m.group(1))
            for m in pattern.finditer(segment)
            if pattern is _AUTH or m.group(1) not in local
        )
    # One call matched twice (`SessionDeletes.enqueue(` is a client call and an
    # enqueue) is reported once, by the pattern that matched first.
    hits.sort()
    kept: list[tuple[int, str]] = []
    last_end = -1
    for hit_start, hit_end, label in hits:
        if hit_start >= last_end:
            kept.append((start + hit_start, label))
            last_end = hit_end
    return kept


def check_file(code: str, rel: str, via: frozenset[Launcher] = frozenset()) -> list[Finding]:
    """Findings in one file; `via` names the launchers found across `ui/`."""
    screen = is_screen(code)
    funs = functions(code)
    by_name: dict[str, list[Function]] = {}
    for fun in funs:
        by_name.setdefault(fun.name, []).append(fun)
    local = frozenset(by_name)
    findings: list[Finding] = []
    seen: set[tuple[str, int]] = set()
    sites = launches(code, screen, via, rel)
    for launch in sites:
        holder = enclosing(code, funs, launch.at)
        here = launch.block
        # Another launch's block, reached through a function this one calls,
        # is reported as its own site, not again as this one's.
        elsewhere = [
            other.block for other in sites
            if not (here[0] <= other.block[0] and other.block[1] <= here[1])
        ]
        # The block, then every same-file function it calls, transitively.
        regions = [here]
        visited: set[int] = set()
        while regions:
            start, end = regions.pop()
            for pos, call in calls_in(code, start, end, local):
                if any(a <= pos <= b for a, b in elsewhere):
                    continue
                if (holder, pos) not in seen:
                    seen.add((holder, pos))
                    findings.append(Finding(
                        rel, holder, call, line_of(code, launch.at), line_of(code, pos), launch.via,
                    ))
            for match in _LOCAL_CALL.finditer(code, start, end):
                for fun in by_name.get(match.group(1), ()):
                    if fun.start not in visited:
                        visited.add(fun.start)
                        regions.append((fun.body, fun.end))
    return findings


class Entry(NamedTuple):
    path: str
    function: str
    call: str
    reason: str
    line: int


def read_allowlist(path: Path) -> tuple[list[Entry], list[str]]:
    entries: list[Entry] = []
    problems: list[str] = []
    if not path.is_file():
        return entries, problems
    keys: set[tuple[str, str, str]] = set()
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [part.strip() for part in line.split("|")]
        where = f"{ALLOWLIST}:{number}"
        if len(parts) != 4 or not all(parts):
            problems.append(f"{where}: expected `path | function | call | reason`, got {line!r}")
            continue
        entry = Entry(*parts, number)
        if not _POINTER.search(entry.reason):
            problems.append(f"{where}: the reason names no TD- or ADR- pointer")
        key = (entry.path, entry.function, entry.call)
        if key in keys:
            problems.append(f"{where}: {' | '.join(key)} is listed twice")
        keys.add(key)
        entries.append(entry)
    return entries, problems


def check(root: Path) -> tuple[list[str], int, int]:
    """Failures, files read, and findings the allowlist covers."""
    package_root = root / PACKAGE_ROOT
    entries, failures = read_allowlist(root / ALLOWLIST)
    allowed = {(e.path, e.function, e.call): e for e in entries}
    used: set[tuple[str, str, str]] = set()
    covered = 0
    sources = sorted((package_root / SCAN).rglob("*.kt"))
    codes = {
        source.relative_to(package_root).as_posix(): strip_code(source.read_text(encoding="utf-8"))
        for source in sources
    }
    via = frozenset().union(*(launchers(code, is_screen(code), rel) for rel, code in codes.items()))
    for rel, code in codes.items():
        for finding in check_file(code, rel, via):
            key = (finding.path, finding.function, finding.call)
            if key in allowed:
                used.add(key)
                covered += 1
                continue
            failures.append(
                f"{rel}:{finding.call_line}: `{finding.call}` runs on a screen's lifecycleScope "
                f"(launched at :{finding.launch_line}"
                + (f" through `{finding.via}`" if finding.via else "")
                + f", in `{finding.function}`)"
            )
    for entry in entries:
        if (entry.path, entry.function, entry.call) not in used:
            failures.append(
                f"{ALLOWLIST}:{entry.line}: {entry.path} | {entry.function} | {entry.call} "
                "matches nothing; remove its entry"
            )
    return failures, len(sources), covered


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".", help="repository root (default: cwd)")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    failures, checked, covered = check(root)
    print(f"checked {checked} Kotlin files under {PACKAGE_ROOT / SCAN}; "
          f"{covered} allowlisted finding(s)")
    if failures:
        print("\nADR-016 rule 1, work the user would lose on a rotation:\n", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        print(
            "\nRun it from the screen's ViewModel (viewModelScope), an application-lifetime "
            "run, or WorkManager (ADR-016). A refresh the next onResume repeats is not a "
            "request the user can lose: list it in scripts/activity_scope_allowlist.txt "
            "with that reason and a TD- or ADR- pointer.",
            file=sys.stderr,
        )
        return 1
    print("no screen lifecycleScope runs a network call or enqueues work outside the allowlist")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
