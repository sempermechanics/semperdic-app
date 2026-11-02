#!/usr/bin/env python3
"""Structural checks for the web consoles.

The consoles are the one part of this repository with no compiler and no
build step: four HTML pages that wire themselves to the DOM by element id and
to the backend by string path. Nothing else in the tree fails when one of
those strings goes stale, so the failure mode is a page that loads, looks
right, and silently does nothing — on the pages that mint and revoke
licences.

This is that missing gate. It checks the things a browser only discovers at
runtime, and a person only discovers in production:

1. No inline script or `on*=` handler on a console page. The `/console/**`
   Content-Security-Policy is `script-src 'self'` with no `'unsafe-inline'`,
   so the browser refuses to run inline code. Code in a page body is not
   "working but untidy" — it never executes.
2. Every module a page loads exists, and parses as an ES module (when node
   is on PATH; skipped, loudly, when it is not).
3. Every `$("id")` a module asks for is an id its own page defines.
4. The deploy-time placeholders are still placeholders. `deploy-console.sh`
   substitutes and restores them; a failed restore lands a live hostname in
   git, which is the footgun the console README warns about.
5. Every Hosting rewrite lands on a file that exists.
6. The two console CSP entries are character-for-character identical, which
   is what the comment beside them in `firebase.json` promises.
7. Every `/v1/...` path a console calls is declared in the API Gateway spec.
   ESPv2 is an allowlist: a route missing there is unreachable in production
   no matter what the backend serves.
8. Every backend code a console matches on is one the backend has: a code in
   `backend/app/errors.py`, or a reconciliation reason. A renamed code
   otherwise leaves the page's sentence for it unreachable, and the user sees
   the raw code. `tests/test_error_codes.py` holds the same line for the app.
9. Every page preloads exactly the modules it runs, so the browser fetches
   them in one wave instead of one wave per level of imports: a
   `<link rel="modulepreload">` for each module its scripts import, directly
   or not, by relative path or from www.gstatic.com, and none for a module
   only imported on demand (`import("./qr.js")`). Plus the preload of
   `/__/firebase/init.json` in the mode auth.js fetches it, and no preload
   that sends credentials. A missing preload costs a round trip; one too
   many downloads code the page never runs.
10. Every page starts as "checking your sign-in": `<body data-auth="pending">`
   (console.css keeps Sign in hidden until auth.js knows) and a `#status`
   that is a polite live region, so each step of a load is read out.
11. No Hosting CSP lets inline script run: no `'unsafe-inline'` or
   `'unsafe-eval'` in any `script-src`. Every other page under `public/`
   (the legal pages, anything added later) has no inline script or handler,
   so the strict default policy cannot break it.
12. The console `script-src` names www.gstatic.com only as the Firebase SDK
   path of the version the console imports
   (`https://www.gstatic.com/firebasejs/<version>/`), never the whole host,
   which serves scripts for every Google product; and every SDK version a
   console module imports is allowed there, or the pages load no Firebase.
13. Every backend code a route the consoles call names has words on the page
   that calls it: a key of `messages.js` (COMMON, REASONS) or of a map in one
   of the page's own modules, or a comparison with it. A code with none still
   reaches the screen, but only as "(ref: <code>)" after a sentence, and
   nothing failed when one appeared. Read from the router modules
   (`backend/app/routers/`): the codes a route function names
   (`errors.<NAME>`), directly or through a function of the same module it
   calls, matched to the calls by method and path. Codes raised further down
   (a repository's `Refusal`) are not followed, and `ROW_CODES` lists the
   codes a route answers inside a row of a 200, not as a refusal.
14. No repository document under `public/` is published: every `*.md` there
   (the console README, notes beside a page) matches a Hosting `ignore`
   glob. Hosting serves every file under `public/` it is not told to skip,
   and the console README described the deploy to anyone who asked for
   `/console/README.md`.

Checks 2, 3, 7, 8 and 13 read every module a page runs: its `<script src>` and,
transitively, what those import by relative path, including on demand.

Checks 1-3 also cover the auth continue-URL pages (`finishSignIn`,
`finishReset`).

Run: `python scripts/check_console.py [--root <repo>]`. Exit 1 on the first
failure found, after reporting all of them. `backend/tests/test_check_console.py`
plants each failure in a small tree and runs this against it.
"""

from __future__ import annotations

import argparse
import ast
import glob
import json
import os
import re
import shutil
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Set by `set_root`: the tree checked is the repository unless `--root` names
# another (backend/tests/test_check_console.py plants failures in a copy).
ROOT = HOSTING = PUBLIC = CONSOLE = GATEWAY = ERRORS = RECONCILE = ROUTERS = ""
PAGES: list[str] = []
AUTH_PAGES: list[str] = []


def set_root(root: str) -> None:
    """Point every check at the tree under `root`."""
    global ROOT, HOSTING, PUBLIC, CONSOLE, GATEWAY, ERRORS, RECONCILE, ROUTERS, PAGES, AUTH_PAGES
    ROOT = os.path.abspath(root)
    HOSTING = os.path.join(ROOT, "firebase-hosting")
    PUBLIC = os.path.join(HOSTING, "public")
    CONSOLE = os.path.join(PUBLIC, "console")
    GATEWAY = os.path.join(ROOT, "backend", "gateway", "openapi.yaml")
    ERRORS = os.path.join(ROOT, "backend", "app", "errors.py")
    RECONCILE = os.path.join(ROOT, "backend", "app", "repo", "reconcile.py")
    ROUTERS = os.path.join(ROOT, "backend", "app", "routers")
    # The pages that carry a dashboard: every check below.
    PAGES = [
        os.path.join(CONSOLE, "index.html"),
        os.path.join(CONSOLE, "account", "index.html"),
        os.path.join(CONSOLE, "institution", "index.html"),
        os.path.join(CONSOLE, "operator", "index.html"),
    ]
    # The auth continue-URLs. Not consoles — they call no API and load no
    # Firebase, so checks 4-9 have nothing to read — but checks 1-3 hold:
    # their scripts are files (the global CSP is script-src 'self', check 11),
    # present and parsing, and the ids they ask for exist.
    AUTH_PAGES = [
        os.path.join(PUBLIC, "finishSignIn", "index.html"),
        os.path.join(PUBLIC, "finishReset", "index.html"),
    ]


set_root(REPO_ROOT)

failures: list[str] = []

_RELATIVE_IMPORT = re.compile(
    r"""^\s*(?:import|export)\b[^;]*?\bfrom\s*["'](\.{1,2}/[^"']+)["']|^\s*import\s*["'](\.{1,2}/[^"']+)["']""",
    re.M,
)
#: `import("./qr.js")`: a module fetched on demand, not when the page loads.
_DYNAMIC_IMPORT = re.compile(r"""\bimport\(\s*["'](\.{1,2}/[^"']+)["']\s*\)""")
#: A module imported from another origin (the Firebase SDK).
_REMOTE_IMPORT = re.compile(
    r"""^\s*(?:import|export)\b[^;]*?\bfrom\s*["'](https://[^"']+)["']""", re.M,
)


def resolve_src(page: str, src: str) -> str:
    """The file a page's `src` names: under public/ when the path is absolute
    (the auth pages are served at two addresses, so a relative path would
    break at one of them), else beside the page."""
    if src.startswith("/"):
        return os.path.normpath(os.path.join(PUBLIC, src.lstrip("/")))
    return os.path.normpath(os.path.join(os.path.dirname(page), src))


def page_scripts(page: str) -> list[str]:
    """The modules a page names in `<script src>`."""
    return [
        resolve_src(page, src)
        for src in re.findall(r'<script\b[^>]*\bsrc="([^"]+)"', read(page))
    ]


def page_modules(page: str, *, on_demand: bool = True) -> list[str]:
    """Every module a page runs: each `<script src>` and, transitively, every
    module those import by relative path — and, unless `on_demand` is false,
    those imported on demand. A page module split into parts keeps every
    check below; reading only the entry script would let a moved `$("id")`
    or `/v1` path escape them."""
    queue = page_scripts(page)
    seen: list[str] = []
    while queue:
        module = queue.pop(0)
        if module in seen or not os.path.isfile(module):
            continue
        seen.append(module)
        code = read(module)
        targets = [a or b for a, b in _RELATIVE_IMPORT.findall(code)]
        if on_demand:
            targets += _DYNAMIC_IMPORT.findall(code)
        for target in targets:
            queue.append(os.path.normpath(os.path.join(os.path.dirname(module), target)))
    return seen


def fail(where: str, message: str) -> None:
    failures.append(f"{os.path.relpath(where, ROOT)}: {message}")


def read(path: str) -> str:
    with open(path, encoding="utf-8") as handle:
        return handle.read()


# ---------------- 1 & 2: scripts are external, present and parse ----------


def check_scripts() -> None:
    node = shutil.which("node")
    if not node:
        print("note: node not on PATH — module syntax not checked", file=sys.stderr)

    parsed: set[str] = set()
    for page in PAGES + AUTH_PAGES:
        html = read(page)

        for match in re.finditer(r"<script\b([^>]*)>(.*?)</script>", html, re.S):
            attrs, body = match.group(1), match.group(2)
            if body.strip() and page in AUTH_PAGES:
                fail(page, "inline <script> body — the global CSP is "
                           "script-src 'self', so it never runs; move it to a "
                           "file beside the page and load it with an absolute src=")
            elif body.strip():
                fail(page, "inline <script> body — the console CSP is "
                           "script-src 'self', so this never runs in production; "
                           "move it to a module file and load it with src=")
            src = re.search(r'src="([^"]+)"', attrs)
            if not src:
                continue
            module = resolve_src(page, src.group(1))
            if not os.path.isfile(module):
                fail(page, f"loads {src.group(1)}, which does not exist")

        for module in page_modules(page):
            for a, b in _RELATIVE_IMPORT.findall(read(module)):
                target = os.path.normpath(os.path.join(os.path.dirname(module), a or b))
                if not os.path.isfile(target):
                    fail(module, f"imports {a or b}, which does not exist")
            if node and module not in parsed:
                parsed.add(module)
                # Fed on stdin with an explicit module type. `node --check
                # <path>` looks like the obvious call and is not: for a bare
                # `.js` path Node 22 exits 0 on source that does not parse at
                # all, so the check would pass on anything.
                proc = subprocess.run(
                    [node, "--input-type=module", "--check"],
                    input=read(module), capture_output=True, text=True, encoding="utf-8",
                )
                if proc.returncode != 0:
                    detail = proc.stderr.strip().splitlines()
                    fail(module, "does not parse as an ES module:\n      "
                                 + "\n      ".join(detail[:4]))

        # An inline handler needs 'unsafe-inline' exactly as an inline script
        # does, so it is dead on arrival for the same reason.
        for match in re.finditer(r"\son(?:click|change|submit|input|load)=", html):
            line = html[: match.start()].count("\n") + 1
            fail(page, f"line {line}: inline event handler — blocked by the "
                       "console CSP; use addEventListener in the module")


_INLINE_HANDLER = re.compile(r"\son[a-z]+\s*=", re.I)


def check_script_policy() -> None:
    path = os.path.join(HOSTING, "firebase.json")
    for block in json.loads(read(path))["hosting"].get("headers", []):
        for header in block.get("headers", []):
            if header["key"] != "Content-Security-Policy":
                continue
            directive = re.search(r"script-src\s+([^;]+)", header["value"])
            sources = directive.group(1).split() if directive else []
            for loose in ("'unsafe-inline'", "'unsafe-eval'"):
                if loose in sources:
                    fail(path, f"the {block['source']} CSP's script-src allows "
                               f"{loose}; no page needs it, and it is what turns "
                               "an injected <script> into running code")

    checked = set(PAGES + AUTH_PAGES)
    for page in sorted(glob.glob(os.path.join(PUBLIC, "**", "*.html"), recursive=True)):
        if page in checked or os.path.commonpath([page, CONSOLE]) == CONSOLE:
            continue
        html = read(page)
        inline = any(body.strip() for body in re.findall(r"<script\b[^>]*>(.*?)</script>", html, re.S))
        if inline or _INLINE_HANDLER.search(html) or "javascript:" in html.lower():
            fail(page, "inline script, handler or javascript: URL — the global "
                       "CSP is script-src 'self', so it never runs; move it to "
                       "a file beside the page")


# ---------------- 12: the Firebase SDK is pinned in script-src -----------

_GSTATIC = "https://www.gstatic.com"
_SDK_IMPORT = re.compile(r"^https://www\.gstatic\.com/firebasejs/([^/]+)/[^/]+\.js$")


def check_sdk_pin(policies: dict[str, str]) -> None:
    """Each console CSP allows gstatic scripts from the SDK versions the
    console imports and from nowhere else on that host."""
    imported = {}
    for page in PAGES:
        for module in page_modules(page):
            for url in _REMOTE_IMPORT.findall(read(module)):
                match = _SDK_IMPORT.match(url)
                if match:
                    imported.setdefault(match.group(1), module)
    wanted = {f"{_GSTATIC}/firebasejs/{version}/" for version in imported}

    path = os.path.join(HOSTING, "firebase.json")
    for source, policy in policies.items():
        directive = re.search(r"script-src\s+([^;]+)", policy)
        sources = directive.group(1).split() if directive else []
        gstatic = [s for s in sources if s.rstrip("/").startswith(_GSTATIC)]
        for allowed in gstatic:
            if allowed.rstrip("/") == _GSTATIC:
                fail(path, f"the {source} CSP's script-src allows the whole "
                           "www.gstatic.com host, which serves scripts for every "
                           "Google product; allow the SDK path the console "
                           "imports instead, " + (", ".join(sorted(wanted)) or
                           f"{_GSTATIC}/firebasejs/<version>/"))
            elif allowed not in wanted:
                fail(path, f"the {source} CSP's script-src allows {allowed}, "
                           "which no console module imports; pin the version "
                           "auth.js imports and drop the old one")
        for version, module in sorted(imported.items()):
            pin = f"{_GSTATIC}/firebasejs/{version}/"
            if pin not in gstatic and _GSTATIC not in [s.rstrip("/") for s in gstatic]:
                fail(path, f"the {source} CSP's script-src does not allow {pin}: "
                           f"{os.path.relpath(module, HOSTING)} imports Firebase "
                           f"{version}, which the browser is then not allowed to "
                           "load, and no console page signs in")


# ---------------- 9: each page preloads exactly what it runs --------------

_LINK = re.compile(r"<link\b([^>]*)>")


def _attr(attrs: str, name: str) -> str | None:
    found = re.search(r"\b" + name + r'="([^"]*)"', attrs)
    return found.group(1) if found else None


def _href(module: str, page: str) -> str:
    """`module` as the page would write it in an href."""
    return os.path.relpath(module, os.path.dirname(page)).replace(os.sep, "/")


def check_preloads() -> None:
    for page in PAGES:
        html = read(page)
        links = [m.group(1) for m in _LINK.finditer(html)]
        preloaded, remote = set(), set()
        init_json = False
        for attrs in links:
            rel = _attr(attrs, "rel")
            href = _attr(attrs, "href") or ""
            if rel not in ("modulepreload", "preload"):
                continue
            if _attr(attrs, "crossorigin") == "use-credentials":
                fail(page, f"preloads {href} with credentials — every request "
                           "these pages make is anonymous, so the browser "
                           "would fetch it a second time")
            if rel == "preload":
                if href == "/__/firebase/init.json":
                    init_json = (_attr(attrs, "as") == "fetch"
                                 and _attr(attrs, "crossorigin") == "anonymous")
                continue
            if href.startswith("https://"):
                remote.add(href)
            else:
                preloaded.add(os.path.normpath(os.path.join(os.path.dirname(page), href)))

        if not init_json:
            fail(page, 'no <link rel="preload" href="/__/firebase/init.json" '
                       'as="fetch" crossorigin="anonymous"> — auth.js waits for '
                       "that file before anything else can start, and a preload "
                       "in another mode is not reused")

        static = page_modules(page, on_demand=False)
        on_demand = set(page_modules(page)) - set(static)
        wanted = set(static) - set(page_scripts(page))
        for module in sorted(wanted - preloaded):
            fail(page, f"does not preload {_href(module, page)}, which it runs — "
                       f'add <link rel="modulepreload" href="{_href(module, page)}">')
        for module in sorted(preloaded - wanted):
            why = ("is only imported on demand" if module in on_demand
                   else "is a <script src> of the page already" if module in page_scripts(page)
                   else "is not imported by this page")
            fail(page, f"preloads {_href(module, page)}, which {why}")

        imported = {url for module in static for url in _REMOTE_IMPORT.findall(read(module))}
        for url in sorted(imported - remote):
            fail(page, f'does not preload {url}, which it imports — add '
                       f'<link rel="modulepreload" href="{url}">')
        for url in sorted(remote - imported):
            fail(page, f"preloads {url}, which no module it runs imports")


# ---------------- 10: pages start pending, with a live status line --------


def check_pending() -> None:
    for page in PAGES:
        html = read(page)
        if not re.search(r'<body\b[^>]*\bdata-auth="pending"', html):
            fail(page, '<body> lacks data-auth="pending" — a signed-in visitor '
                       "is offered Sign in until auth.js has checked")
        status = re.search(r'<p\b[^>]*\bid="status"[^>]*>', html)
        attrs = status.group(0) if status else ""
        for want in ('role="status"', 'aria-live="polite"', "data-pending"):
            if want not in attrs:
                fail(page, f"#status lacks {want} — the status line is how "
                           "every wait is explained, and a screen reader "
                           "hears it only from a live region")


# ---------------- 3: every id a module asks for, its page defines ---------


def check_element_ids() -> None:
    for page in PAGES + AUTH_PAGES:
        html = read(page)
        declared = set(re.findall(r'\bid="([^"]+)"', html))

        for module in page_modules(page):
            code = read(module)
            wanted = set(re.findall(r'\$\(\s*"([^"]+)"\s*\)', code))
            wanted |= set(re.findall(r'getElementById\(\s*"([^"]+)"\s*\)', code))
            for missing in sorted(wanted - declared):
                fail(module, f'asks for #{missing}, which '
                             f'{os.path.basename(os.path.dirname(page))}/'
                             f'{os.path.basename(page)} does not define')


# ---------------- 4: deploy placeholders are still placeholders -----------


def check_placeholders(policies: dict[str, str]) -> None:
    """The value itself must still be the token, not a hostname it stands for.

    Counting occurrences would be fooled by the prose around them — both files
    name these tokens in comments — so each is checked where it is read.
    """
    config = os.path.join(CONSOLE, "config.js")
    value = re.search(r'API_BASE_URL\s*=\s*"([^"]*)"', read(config))
    if not value:
        fail(config, "no API_BASE_URL export to check")
    elif value.group(1) != "__API_BASE_URL__":
        fail(config, f"API_BASE_URL is {value.group(1)!r}, not the placeholder "
                     "— deploy-console.sh substitutes it and restores it "
                     "afterwards; a live hostname must never be committed")

    path = os.path.join(HOSTING, "firebase.json")
    for source, policy in policies.items():
        if "__API_ORIGIN__" not in policy:
            fail(path, f"the {source} CSP no longer carries __API_ORIGIN__ — "
                       "either it was substituted and not restored, or the "
                       "policy stopped naming it and the consoles can no "
                       "longer reach the API")
        if "frame-src 'self'" not in policy:
            fail(path, f"the {source} CSP's frame-src is not 'self' — auth.js "
                       "sets authDomain to the page's own host, so the SDK's "
                       "auth iframe is same-origin and sign-in cannot complete "
                       "without it")


# ---------------- 5 & 6: hosting rewrites and the two console CSPs --------


def check_hosting() -> dict[str, str]:
    path = os.path.join(HOSTING, "firebase.json")
    config = json.loads(read(path))
    hosting = config["hosting"]

    for rule in hosting.get("rewrites", []):
        destination = rule.get("destination")
        if not destination or destination.startswith("http"):
            continue
        target = os.path.join(PUBLIC, destination.lstrip("/"))
        if not os.path.isfile(target):
            fail(path, f'rewrite {rule["source"]} → {destination}, which does '
                       f"not exist under public/")

    policies = {}
    for block in hosting.get("headers", []):
        for header in block.get("headers", []):
            if header["key"] == "Content-Security-Policy":
                policies[block["source"]] = header["value"]

    console, aliases = policies.get("/console/**"), policies.get("{/login,/account}")
    if console is None or aliases is None:
        fail(path, "one of the two console CSP entries is missing")
    elif console != aliases:
        fail(path, "the /console/** and {/login,/account} CSPs have drifted; "
                   "a Hosting header matches the request path, so the aliases "
                   "would be served a different policy than the pages they "
                   "rewrite to")

    # The console pages resolve their stylesheet, module script and onward
    # links through <base href="/console/…">, so the same page works at its
    # rewritten address (/login, /account). A CSP `base-uri 'none'` makes the
    # browser drop that element — the page then loads no CSS and no script,
    # and "Sign in" does nothing. First production deploy shipped exactly
    # that. `'self'` keeps the injected-off-site-base defence.
    if console is not None:
        directive = re.search(r"base-uri\s+([^;]+)", console)
        allowed = directive.group(1).split() if directive else []
        pages_with_base = [
            os.path.relpath(page, HOSTING)
            for page in glob.glob(os.path.join(PUBLIC, "console", "**", "*.html"), recursive=True)
            if "<base " in read(page)
        ]
        if pages_with_base and "'self'" not in allowed:
            fail(path, "the console CSP's base-uri is "
                       f"{' '.join(allowed) or 'unset'!r}, but these pages rely "
                       f"on <base>: {', '.join(pages_with_base)} — the browser "
                       "blocks the element, every relative asset 404s and the "
                       "sign-in button is dead")

    ignored = [_glob_regex(g) for g in hosting.get("ignore", [])]
    for doc in sorted(glob.glob(os.path.join(PUBLIC, "**", "*.md"), recursive=True)):
        rel = os.path.relpath(doc, PUBLIC).replace(os.sep, "/")
        if not any(r.fullmatch(rel) for r in ignored):
            fail(path, f"public/{rel} would be published: no Hosting `ignore` glob "
                       f"matches it (add \"**/*.md\")")

    return {
        source: value
        for source, value in policies.items()
        if source in ("/console/**", "{/login,/account}")
    }


def _glob_regex(pattern: str) -> re.Pattern[str]:
    """A Hosting `ignore` glob as a regex over a path relative to `public/`:
    `**/` is any number of directories (none too), `*` and `?` stay within one."""
    out = ""
    i = 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out += "(?:.*/)?"
            i += 3
        elif pattern.startswith("**", i):
            out += ".*"
            i += 2
        elif pattern[i] == "*":
            out += "[^/]*"
            i += 1
        elif pattern[i] == "?":
            out += "[^/]"
            i += 1
        else:
            out += re.escape(pattern[i])
            i += 1
    return re.compile(out)


# ---------------- 7: every path a console calls is on the gateway ---------


def gateway_paths() -> list[list[str]]:
    """Declared paths, as segment lists. Read by regex to avoid a PyYAML dep."""
    body = read(GATEWAY)
    paths = body.split("\npaths:\n", 1)[1] if "\npaths:\n" in body else ""
    return [
        line.strip().rstrip(":").split("/")[1:]
        for line in re.findall(r"^  (/\S*):\s*$", paths, re.M)
    ]


_HOLE = "\x00"


def console_paths(code: str) -> set[str]:
    """`/v1/...` paths passed to api()/apiBlob(), with `${…}` marked as holes.

    A single-assignment `const name = ` + "`/v1/…`" + ` is expanded first, so a
    call built on a base variable is checked rather than skipped.
    """
    bases = dict(re.findall(r"const\s+(\w+)\s*=\s*`(/v1/[^`]*)`", code))
    found = set()
    for raw in re.findall(r"\bapi(?:Blob)?\(\s*[`\"']([^`\"']*)[`\"']", code):
        for name, value in bases.items():
            raw = raw.replace("${" + name + "}", value)
        if not raw.startswith("/v1/"):
            continue
        found.add(re.sub(r"\$\{[^}]*\}", _HOLE, raw.split("?", 1)[0]))
    return found


def segment_matches(called: str, declared: str) -> bool:
    if declared.startswith("{") and declared.endswith("}"):
        return True
    if called == _HOLE:
        return True
    # A hole at the end of a literal segment is a query string or a suffix the
    # page appends; the literal part still has to be the declared segment.
    return called.rstrip(_HOLE) == declared


def check_gateway() -> None:
    declared = gateway_paths()
    for page in PAGES:
        for module in page_modules(page):
            for called in sorted(console_paths(read(module))):
                parts = called.split("/")[1:]
                if not any(
                    len(parts) == len(row)
                    and all(segment_matches(a, b) for a, b in zip(parts, row))
                    for row in declared
                ):
                    shown = called.replace(_HOLE, "${…}")
                    fail(module, f"calls {shown}, which backend/gateway/"
                                 f"openapi.yaml does not declare — ESPv2 is an "
                                 f"allowlist, so it 404s in production")


# ---------------- 8: every code a console matches, the backend has ---------

_CODE = r"([a-z][a-z0-9]*(?:_[a-z0-9]+)+)"
#: A code compared against (`=== "license_revoked"`) or used as a key in a
#: code-to-sentence map (`license_revoked: "…"`).
_MATCHED_CODE = re.compile(r'(?:===|!==)\s*"' + _CODE + '"' + r'|^\s*"?' + _CODE + r'"?\s*:', re.M)


def backend_codes() -> set[str]:
    codes = set()
    for path in (ERRORS, RECONCILE):
        codes |= set(re.findall(r'^[A-Z_]+ = "([a-z_]+)"', read(path), re.M))
    return codes


def check_codes() -> None:
    known = backend_codes()
    modules = {module for page in PAGES for module in page_modules(page)}
    for module in sorted(modules):
        matched = {a or b for a, b in _MATCHED_CODE.findall(read(module))}
        for code in sorted(matched - known):
            fail(module, f"matches {code!r}, which backend/app/errors.py does not "
                         f"declare — a renamed code leaves this sentence unreachable")


# ---------------- 13: every code a console route names has words -----------

#: Codes a route answers inside a row of a 200, not as a refusal: the roster
#: import's `invalid` / `unprocessed` rows, which roster-tools.js `rowResult`
#: words by their outcome.
ROW_CODES = {"invalid_email", "roster_row_unprocessed"}
_METHODS = ("get", "post", "put", "patch", "delete")


def _declared_codes() -> dict[str, str]:
    """errors.py's constants, NAME -> code."""
    return dict(re.findall(r'^([A-Z_]+) = "([a-z_]+)"', read(ERRORS), re.M))


def _route_codes(fn: ast.AST, functions: dict[str, list[ast.AST]],
                 constants: dict[str, str], seen: set[str]) -> set[str]:
    """The codes `fn` names, and those of the same module's functions it calls."""
    codes = set()
    for node in ast.walk(fn):
        if (isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name)
                and node.value.id == "errors" and node.attr in constants):
            codes.add(constants[node.attr])
        elif isinstance(node, ast.Call):
            callee = node.func
            name = callee.id if isinstance(callee, ast.Name) else getattr(callee, "attr", None)
            if name in functions and name not in seen:
                seen.add(name)
                for other in functions[name]:
                    codes |= _route_codes(other, functions, constants, seen)
    return codes


def _route_decorator(node: ast.AST) -> tuple[str, str] | None:
    """`@router.<method>("<path>", …)` as (METHOD, path)."""
    if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
            and isinstance(node.func.value, ast.Name) and node.func.value.id == "router"
            and node.func.attr in _METHODS and node.args
            and isinstance(node.args[0], ast.Constant) and isinstance(node.args[0].value, str)):
        return node.func.attr.upper(), node.args[0].value
    return None


def backend_routes() -> list[tuple[str, str, set[str], bool]]:
    """(METHOD, full path, codes, mounted) for every route in backend/app/routers.

    A module's `APIRouter(prefix=…)` is prepended; a route built inside a
    factory (`roster_router`) is mounted at each `include_router(<factory>(…),
    prefix=…)` that names it, and is marked `mounted`.
    """
    if not os.path.isdir(ROUTERS):
        return []
    constants = _declared_codes()
    found: list[tuple[str, str, set[str], bool]] = []
    factory_routes: dict[str, list[tuple[str, str, set[str]]]] = {}
    mounts: dict[str, list[str]] = {}
    for path in sorted(glob.glob(os.path.join(ROUTERS, "*.py"))):
        tree = ast.parse(read(path), filename=path)
        functions: dict[str, list[ast.AST]] = {}
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                functions.setdefault(node.name, []).append(node)
        prefix = ""
        for node in tree.body:
            if (isinstance(node, ast.Assign) and isinstance(node.value, ast.Call)
                    and getattr(node.value.func, "id", "") == "APIRouter"):
                for keyword in node.value.keywords:
                    if keyword.arg == "prefix" and isinstance(keyword.value, ast.Constant):
                        prefix = keyword.value.value
        for node in ast.walk(tree):
            if (isinstance(node, ast.Call) and getattr(node.func, "attr", "") == "include_router"
                    and node.args and isinstance(node.args[0], ast.Call)
                    and isinstance(node.args[0].func, ast.Name)):
                for keyword in node.keywords:
                    if keyword.arg == "prefix" and isinstance(keyword.value, ast.Constant):
                        mounts.setdefault(node.args[0].func.id, []).append(keyword.value.value)
        factories = [node for node in tree.body
                     if isinstance(node, ast.FunctionDef)
                     and any(isinstance(n, ast.Call) and getattr(n.func, "id", "") == "APIRouter"
                             for n in ast.walk(node))]
        inside = {id(n): factory.name for factory in factories for n in ast.walk(factory)}
        for fn in (n for named in functions.values() for n in named):
            for decorator in fn.decorator_list:
                route = _route_decorator(decorator)
                if route is None:
                    continue
                codes = _route_codes(fn, functions, constants, {fn.name})
                if id(fn) in inside:
                    factory_routes.setdefault(inside[id(fn)], []).append((route[0], route[1], codes))
                else:
                    found.append((route[0], prefix + route[1], codes, False))
    for factory, routes in factory_routes.items():
        for mount in mounts.get(factory, []):
            found += [(method, mount + path, codes, True) for method, path, codes in routes]
    return found


def _call_end(code: str, start: int) -> int:
    """The index just past the `)` that closes the call opened before `start`."""
    depth, at = 1, start
    while at < len(code) and depth:
        depth += {"(": 1, ")": -1}.get(code[at], 0)
        at += 1
    return at


def console_calls(code: str) -> set[tuple[str | None, str]]:
    """(METHOD, path) of each api()/apiBlob() call, holes marked as check 7 does.

    The method is the call's `method: "…"`, GET without one, and None (any)
    when it is not a literal (`method: spec.method`). A path that starts with
    a hole (`${root}/seats`) is kept: it is relative to the licence the page
    has open, so it is matched on its tail against the mounted roster routes.
    """
    bases = dict(re.findall(r"const\s+(\w+)\s*=\s*`(/v1/[^`]*)`", code))
    found = set()
    for match in re.finditer(r"\bapi(?:Blob)?\(", code):
        call = code[match.end():_call_end(code, match.end())]
        literal = re.match(r"\s*[`\"']([^`\"']*)[`\"']", call)
        if not literal:
            continue
        raw = literal.group(1)
        for name, value in bases.items():
            raw = raw.replace("${" + name + "}", value)
        path = re.sub(r"\$\{[^}]*\}", _HOLE, raw.split("?", 1)[0])
        if not (path.startswith("/v1/") or path.startswith(_HOLE + "/")):
            continue
        literal_method = re.search(r"\bmethod:\s*[\"'](\w+)[\"']", call)
        if literal_method:
            method = literal_method.group(1).upper()
        else:
            method = None if re.search(r"\bmethod:", call) else "GET"
        found.add((method, path))
    return found


def _calls_route(called: str, declared: str, mounted: bool) -> bool:
    parts, row = called.split("/")[1:], declared.split("/")[1:]
    if called.startswith(_HOLE + "/"):
        if not mounted or len(row) <= len(parts):
            return False
        row = row[-len(parts):]
    return len(parts) == len(row) and all(segment_matches(a, b) for a, b in zip(parts, row))


def check_code_sentences() -> None:
    routes = backend_routes()
    if not routes:
        return
    for page in PAGES:
        modules = page_modules(page)
        worded = {a or b for module in modules for a, b in _MATCHED_CODE.findall(read(module))}
        missing: dict[str, set[str]] = {}
        for module in modules:
            for method, called in console_calls(read(module)):
                for verb, declared, codes, mounted in routes:
                    if method in (None, verb) and _calls_route(called, declared, mounted):
                        for code in codes - worded - ROW_CODES:
                            missing.setdefault(code, set()).add(f"{verb} {declared}")
        for code in sorted(missing):
            fail(page, f"{code!r} can come back from {', '.join(sorted(missing[code]))}, "
                       f"which this page calls, and no module of it has words for it "
                       f"(messages.js COMMON / REASONS, or the page's own map) — it "
                       f"would show only as \"(ref: {code})\"")


def run(root: str = REPO_ROOT) -> list[str]:
    """Every check against the tree under `root`; returns the failures."""
    set_root(root)
    failures.clear()
    check_scripts()
    check_element_ids()
    policies = check_hosting()
    check_placeholders(policies)
    check_sdk_pin(policies)
    check_gateway()
    check_codes()
    check_code_sentences()
    check_preloads()
    check_pending()
    check_script_policy()
    return list(failures)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--root", default=REPO_ROOT,
                        help="repository root to check (default: this checkout)")
    args = parser.parse_args(argv)
    run(args.root)

    if failures:
        print("Console checks failed:\n", file=sys.stderr)
        for line in failures:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print("Console checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
