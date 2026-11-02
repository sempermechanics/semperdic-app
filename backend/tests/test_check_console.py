"""The console gate, `scripts/check_console.py`, fails on what it says it does.

It is the consoles' only gate — they have no compiler — and until this module
its checks had only been shown to fire by hand. Each test builds a small,
clean tree (four console pages, the two auth pages, a gateway spec, the
backend's code lists), plants one failure in it, and runs the script against
that tree through `run(root)` / `--root`.
"""
from __future__ import annotations

import importlib.util
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "check_console.py"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")

_needs_node = pytest.mark.skipif(shutil.which("node") is None, reason="node not on PATH")

_CSP = ("default-src 'self'; script-src 'self'; connect-src 'self' __API_ORIGIN__; "
        "frame-src 'self'; object-src 'none'; base-uri 'self'")

_CONSOLE_DIRS = {"": "./", "account/": "../", "institution/": "../", "operator/": "../"}


def _load():
    spec = importlib.util.spec_from_file_location("check_console", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def checker():
    return _load()


def _page(up: str, body: str = "", head: str = "") -> str:
    return f"""<!doctype html>
<html lang="en">
<head>
  <base href="/console/" />
  <link rel="preload" href="/__/firebase/init.json" as="fetch" crossorigin="anonymous" />
  <link rel="modulepreload" href="{up}shared.js" />
  <link rel="modulepreload" href="{up}config.js" />
{head}</head>
<body data-auth="pending">
  <p id="go"></p>
  <p id="status" role="status" aria-live="polite" data-pending></p>
{body}  <script type="module" src="page.js"></script>
</body>
</html>
"""


_PAGE_JS = """import {{ $, api }} from "{up}shared.js";
import {{ API_BASE_URL }} from "{up}config.js";

$("go").textContent = API_BASE_URL;
export async function load(answer) {{
  if (answer === "known_code") return null;
  return api(`/v1/me`);
}}
"""


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))


_GLOBAL_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'"


@pytest.fixture
def tree(tmp_path: Path) -> Path:
    """A tree every check passes."""
    hosting = tmp_path / "firebase-hosting"
    public = hosting / "public"
    console = public / "console"
    _write(hosting / "firebase.json", json.dumps({"hosting": {
        "rewrites": [
            {"source": "/login", "destination": "/console/index.html"},
            {"source": "/finishReset", "destination": "/finishReset/index.html"},
        ],
        "headers": [
            {"source": "**", "headers": [{"key": "Content-Security-Policy", "value": _GLOBAL_CSP}]},
            {"source": "/console/**", "headers": [{"key": "Content-Security-Policy", "value": _CSP}]},
            {"source": "{/login,/account}", "headers": [{"key": "Content-Security-Policy", "value": _CSP}]},
        ],
    }}, indent=2))
    _write(console / "config.js", 'export const API_BASE_URL = "__API_BASE_URL__";\n')
    _write(console / "shared.js",
           "export const $ = (id) => document.getElementById(id);\n"
           "export async function api(path) {\n  return fetch(path);\n}\n")
    for sub, up in _CONSOLE_DIRS.items():
        _write(console / sub / "index.html", _page(up))
        _write(console / sub / "page.js", _PAGE_JS.format(up=up))
    _write(public / "terms" / "index.html", "<!doctype html><title>Terms</title><p>The terms.</p>\n")
    _write(public / "finishSignIn" / "index.html", "<!doctype html><title>Finish</title><p>Open it on your phone.</p>\n")
    _write(public / "finishReset" / "index.html",
           '<!doctype html><head><script src="/finishReset/reset.js"></script></head>\n'
           '<body><a id="continueLink" href="#">Continue</a></body>\n')
    _write(public / "finishReset" / "reset.js",
           'document.getElementById("continueLink").href = "/__/auth/action";\n')
    _write(tmp_path / "backend" / "gateway" / "openapi.yaml",
           "swagger: '2.0'\npaths:\n  /v1/me:\n    get:\n      operationId: me\n")
    _write(tmp_path / "backend" / "app" / "errors.py", 'KNOWN_CODE = "known_code"\n')
    _write(tmp_path / "backend" / "app" / "repo" / "reconcile.py", 'SEAT_GONE = "seat_gone"\n')
    return tmp_path


def _edit(path: Path, old: str, new: str) -> None:
    text = path.read_bytes().decode("utf-8")
    assert old in text, f"{old!r} not in {path}"
    _write(path, text.replace(old, new, 1))


def _console(tree: Path, *parts: str) -> Path:
    return tree.joinpath("firebase-hosting", "public", "console", *parts)


def _one_failure(checker, tree: Path, *expected: str) -> str:
    found = checker.run(str(tree))
    assert len(found) == 1, f"expected one failure, got {found}"
    for text in expected:
        assert text in found[0], f"{text!r} not in {found[0]!r}"
    return found[0]


# ---------------- the clean tree ----------------
def test_a_clean_tree_passes(checker, tree):
    assert checker.run(str(tree)) == []


def test_the_cli_exits_0_on_a_clean_tree_and_1_with_the_failures_listed(tree):
    ok = subprocess.run([sys.executable, str(_SCRIPT), "--root", str(tree)],  # noqa: S603 - argv is sys.executable and this repo's own script, no shell
                        capture_output=True, text=True, encoding="utf-8")
    assert ok.returncode == 0, ok.stderr
    assert "Console checks passed." in ok.stdout

    _edit(_console(tree, "operator", "page.js"), '$("go")', '$("gone")')
    bad = subprocess.run([sys.executable, str(_SCRIPT), "--root", str(tree)],  # noqa: S603 - argv is sys.executable and this repo's own script, no shell
                         capture_output=True, text=True, encoding="utf-8")
    assert bad.returncode == 1
    assert "Console checks failed" in bad.stderr
    assert "asks for #gone" in bad.stderr


# ---------------- 1: inline code ----------------
def test_an_inline_script_on_a_console_page_fails(checker, tree):
    _edit(_console(tree, "operator", "index.html"), "</head>", "<script>boot()</script>\n</head>")
    message = _one_failure(checker, tree, "inline <script> body", "script-src 'self'")
    assert "operator" in message


def test_an_inline_handler_fails_with_its_line(checker, tree):
    _edit(_console(tree, "account", "index.html"), '<p id="go">', '<p id="go" onclick="go()">')
    _one_failure(checker, tree, "inline event handler", "line 10")


def test_an_inline_script_on_an_auth_page_fails(checker, tree):
    _edit(tree / "firebase-hosting/public/finishReset/index.html", "</body>", "<script>x()</script></body>")
    _one_failure(checker, tree, "finishReset", "global CSP is script-src 'self'")


# ---------------- 2: modules exist and parse ----------------
def test_a_script_src_that_does_not_exist_fails(checker, tree):
    _edit(_console(tree, "institution", "index.html"), 'src="page.js"', 'src="pages.js"')
    found = checker.run(str(tree))
    assert any("loads pages.js, which does not exist" in f for f in found), found
    # With its script gone the page imports nothing, so its preloads are flagged too.
    assert all("institution" in f for f in found), found


def test_an_auth_page_script_resolves_from_the_site_root(checker, tree):
    (tree / "firebase-hosting/public/finishReset/reset.js").unlink()
    _one_failure(checker, tree, "loads /finishReset/reset.js, which does not exist")


def test_an_import_that_does_not_exist_fails(checker, tree):
    _edit(_console(tree, "operator", "page.js"), '"../config.js";', '"../config.js";\nimport "./gone.js";')
    _one_failure(checker, tree, "imports ./gone.js, which does not exist")


@_needs_node
def test_a_module_that_does_not_parse_fails(checker, tree):
    _edit(_console(tree, "shared.js"), "return fetch(path);", "return fetch(path;")
    _one_failure(checker, tree, "shared.js", "does not parse as an ES module")


# ---------------- 3: element ids ----------------
def test_an_id_the_page_does_not_define_fails(checker, tree):
    _edit(_console(tree, "institution", "page.js"), '$("go")', '$("missing")')
    _one_failure(checker, tree, "asks for #missing", "institution/index.html does not define")


def test_an_auth_page_id_is_checked_too(checker, tree):
    _edit(tree / "firebase-hosting/public/finishReset/index.html", 'id="continueLink"', 'id="continue"')
    _one_failure(checker, tree, "reset.js", "asks for #continueLink")


# ---------------- 4: deploy placeholders ----------------
def test_a_committed_api_hostname_fails(checker, tree):
    _edit(_console(tree, "config.js"), "__API_BASE_URL__", "https://api.example.com")
    _one_failure(checker, tree, "'https://api.example.com'", "not the placeholder")


def test_a_csp_without_the_api_origin_placeholder_fails(checker, tree):
    path = tree / "firebase-hosting/firebase.json"
    text = path.read_bytes().decode("utf-8").replace("__API_ORIGIN__", "https://api.example.com")
    _write(path, text)
    found = checker.run(str(tree))
    assert len(found) == 2, found  # one per console CSP entry
    assert all("no longer carries __API_ORIGIN__" in f for f in found)


def test_a_csp_without_same_origin_frames_fails(checker, tree):
    path = tree / "firebase-hosting/firebase.json"
    _write(path, path.read_bytes().decode("utf-8").replace("frame-src 'self'", "frame-src 'none'"))
    found = checker.run(str(tree))
    assert len(found) == 2 and all("frame-src is not 'self'" in f for f in found), found


# ---------------- 5 & 6: hosting ----------------
def test_mismatched_console_csps_fail(checker, tree):
    path = tree / "firebase-hosting/firebase.json"
    config = json.loads(path.read_bytes())
    config["hosting"]["headers"][1]["headers"][0]["value"] = _CSP + "; upgrade-insecure-requests"
    _write(path, json.dumps(config))
    _one_failure(checker, tree, "have drifted")


def test_a_missing_console_csp_entry_fails(checker, tree):
    path = tree / "firebase-hosting/firebase.json"
    config = json.loads(path.read_bytes())
    del config["hosting"]["headers"][1]
    _write(path, json.dumps(config))
    _one_failure(checker, tree, "one of the two console CSP entries is missing")


def test_a_rewrite_to_a_missing_file_fails(checker, tree):
    path = tree / "firebase-hosting/firebase.json"
    config = json.loads(path.read_bytes())
    config["hosting"]["rewrites"].append({"source": "/gone", "destination": "/gone/index.html"})
    _write(path, json.dumps(config))
    _one_failure(checker, tree, "rewrite /gone", "does not exist under public/")


def test_base_uri_none_with_a_base_element_fails(checker, tree):
    path = tree / "firebase-hosting/firebase.json"
    _write(path, path.read_bytes().decode("utf-8").replace("base-uri 'self'", "base-uri 'none'"))
    _one_failure(checker, tree, "base-uri", "rely on <base>")


# ---------------- 14: repository documents are not published ----------------
def test_a_readme_under_public_that_hosting_would_serve_fails(checker, tree):
    _write(_console(tree, "README.md"), "# How the console deploys\n")
    _one_failure(checker, tree, "public/console/README.md would be published")


def test_a_readme_under_public_that_hosting_ignores_passes(checker, tree):
    _write(_console(tree, "README.md"), "# How the console deploys\n")
    _write(tree / "firebase-hosting/public/NOTES.md", "notes\n")
    path = tree / "firebase-hosting/firebase.json"
    config = json.loads(path.read_bytes())
    config["hosting"]["ignore"] = ["firebase.json", "**/.*", "**/*.md"]
    _write(path, json.dumps(config))
    assert checker.run(str(tree)) == []


def test_the_ignore_glob_matches_as_hosting_does(checker):
    md = checker._glob_regex("**/*.md")
    assert md.fullmatch("README.md") and md.fullmatch("console/README.md") and md.fullmatch("a/b/c.md")
    assert not md.fullmatch("console/README.mdx")
    dot = checker._glob_regex("**/.*")
    assert dot.fullmatch(".env") and dot.fullmatch("console/.DS_Store") and not dot.fullmatch("console/a.js")
    assert checker._glob_regex("firebase.json").fullmatch("firebase.json")
    assert not checker._glob_regex("*.md").fullmatch("console/README.md"), "* stays within one directory"


# ---------------- 7: gateway paths ----------------
def test_a_path_the_gateway_does_not_declare_fails(checker, tree):
    _edit(_console(tree, "account", "page.js"), "api(`/v1/me`)", "api(`/v1/me/${answer}/export`)")
    _one_failure(checker, tree, "calls /v1/me/${…}/export", "does not declare")


def test_a_path_built_on_a_base_constant_is_checked(checker, tree):
    _edit(_console(tree, "account", "page.js"), "api(`/v1/me`)",
          "api(`${BASE}/devices`)")
    _edit(_console(tree, "account", "page.js"), "export async", "const BASE = `/v1/me`;\nexport async")
    _one_failure(checker, tree, "calls /v1/me/devices")


# ---------------- 8: backend codes ----------------
def test_a_code_the_backend_does_not_have_fails(checker, tree):
    _edit(_console(tree, "page.js"), '"known_code"', '"renamed_code"')
    _one_failure(checker, tree, "matches 'renamed_code'", "errors.py does not declare")


def test_a_reconciliation_reason_counts_as_a_backend_code(checker, tree):
    _edit(_console(tree, "page.js"), '"known_code"', '"seat_gone"')
    assert checker.run(str(tree)) == []


# ---------------- 13: every code a console route names has words ----------
def _router(tree: Path, name: str, body: str) -> None:
    _write(tree / "backend" / "app" / "routers" / name,
           "from fastapi import APIRouter, HTTPException\n\nfrom app import errors\n\n" + body)


def _unworded(tree: Path) -> None:
    _edit(tree / "backend" / "app" / "errors.py", 'KNOWN_CODE = "known_code"\n',
          'KNOWN_CODE = "known_code"\nOTHER_CODE = "other_code"\n')


def test_a_code_a_called_route_names_with_no_words_fails(checker, tree):
    _unworded(tree)
    _router(tree, "me.py", 'router = APIRouter()\n\n\n@router.get("/v1/me")\n'
                           "def me():\n    raise HTTPException(404, errors.OTHER_CODE)\n")
    found = checker.run(str(tree))
    # Every console page calls GET /v1/me, and none has words for it.
    assert len(found) == 4, found
    assert all("'other_code' can come back from GET /v1/me" in f and "(ref: other_code)" in f
               for f in found), found


def test_a_code_with_words_on_the_page_passes(checker, tree):
    _unworded(tree)
    _router(tree, "me.py", 'router = APIRouter()\n\n\n@router.get("/v1/me")\n'
                           "def me():\n    raise HTTPException(404, errors.KNOWN_CODE)\n")
    _edit(_console(tree, "shared.js"), "export async function api",
          'export const SAID = {\n  other_code: "Words for it.",\n};\nexport async function api')
    assert checker.run(str(tree)) == []
    _router(tree, "me.py", 'router = APIRouter()\n\n\n@router.get("/v1/me")\n'
                           "def me():\n    raise HTTPException(404, errors.OTHER_CODE)\n")
    assert checker.run(str(tree)) == []


def test_a_code_named_by_a_helper_of_the_route_counts(checker, tree):
    _unworded(tree)
    _router(tree, "me.py", "router = APIRouter(prefix=\"/v1\")\n\n\ndef _refuse():\n"
                           "    raise HTTPException(409, errors.OTHER_CODE)\n\n\n"
                           '@router.get("/me")\ndef me():\n    _refuse()\n')
    found = checker.run(str(tree))
    assert len(found) == 4 and all("GET /v1/me" in f for f in found), found


def test_a_route_the_consoles_do_not_call_is_not_read(checker, tree):
    _unworded(tree)
    # Another method on the same path (the app's), and another path.
    _router(tree, "me.py", 'router = APIRouter()\n\n\n@router.delete("/v1/me")\n'
                           "def gone():\n    raise HTTPException(409, errors.OTHER_CODE)\n\n\n"
                           '@router.get("/v1/sessions")\n'
                           "def sessions():\n    raise HTTPException(409, errors.OTHER_CODE)\n")
    assert checker.run(str(tree)) == []


def test_a_mounted_roster_route_is_matched_on_its_tail(checker, tree):
    _unworded(tree)
    _router(tree, "roster.py", "def roster_router():\n    router = APIRouter()\n\n"
                               '    @router.post("/seats")\n    def add():\n'
                               "        raise HTTPException(409, errors.OTHER_CODE)\n\n"
                               "    return router\n")
    _router(tree, "institutions.py", "from app.routers.roster import roster_router\n\n"
                                     "router = APIRouter()\nrouter.include_router(roster_router(), "
                                     'prefix="/v1/institutions/licenses/{license_id}")\n')
    _edit(_console(tree, "institution", "page.js"), "export async",
          'export const add = (root) => api(`${root}/seats`, { method: "POST" });\nexport async')
    _one_failure(checker, tree, "institution",
                 "'other_code' can come back from POST /v1/institutions/licenses/{license_id}/seats")


def test_a_row_outcome_code_is_not_a_refusal(checker, tree):
    _edit(tree / "backend" / "app" / "errors.py", 'KNOWN_CODE = "known_code"\n',
          'KNOWN_CODE = "known_code"\nROSTER_ROW_UNPROCESSED = "roster_row_unprocessed"\n')
    _router(tree, "me.py", 'router = APIRouter()\n\n\n@router.get("/v1/me")\n'
                           "def me():\n    return {\"error\": errors.ROSTER_ROW_UNPROCESSED}\n")
    assert checker.run(str(tree)) == []


# ---------------- 9: preloads ----------------
def test_a_module_run_but_not_preloaded_fails(checker, tree):
    _edit(_console(tree, "operator", "index.html"), '  <link rel="modulepreload" href="../config.js" />\n', "")
    _one_failure(checker, tree, "does not preload ../config.js")


def test_a_preload_of_an_on_demand_module_fails(checker, tree):
    _write(_console(tree, "qr.js"), "export const qr = 1;\n")
    _edit(_console(tree, "operator", "page.js"), "return null;", 'return import("../qr.js");')
    _edit(_console(tree, "operator", "index.html"), "</head>", '<link rel="modulepreload" href="../qr.js" />\n</head>')
    _one_failure(checker, tree, "preloads ../qr.js, which is only imported on demand")


def test_a_credentialed_preload_fails(checker, tree):
    _edit(_console(tree, "index.html"), 'href="./config.js" />', 'href="./config.js" crossorigin="use-credentials" />')
    _one_failure(checker, tree, "with credentials")


def test_init_json_preloaded_in_the_wrong_mode_fails(checker, tree):
    _edit(_console(tree, "account", "index.html"), 'as="fetch" crossorigin="anonymous"', 'as="fetch"')
    _one_failure(checker, tree, "/__/firebase/init.json")


def test_every_planted_failure_is_reported_not_just_the_first(checker, tree):
    _edit(_console(tree, "config.js"), "__API_BASE_URL__", "https://api.example.com")
    _edit(_console(tree, "operator", "page.js"), '$("go")', '$("missing")')
    _edit(_console(tree, "page.js"), '"known_code"', '"renamed_code"')
    assert len(checker.run(str(tree))) == 3


def test_a_console_page_not_born_pending_fails(checker, tree):
    _edit(_console(tree, "operator", "index.html"), '<body data-auth="pending">', "<body>")
    _one_failure(checker, tree, "operator", 'lacks data-auth="pending"')


def test_a_status_line_that_is_not_a_live_region_fails(checker, tree):
    _edit(_console(tree, "account", "index.html"), ' aria-live="polite"', "")
    _one_failure(checker, tree, "account", 'lacks aria-live="polite"')


# ---------------- 11: no CSP runs inline script ----------------
def test_a_global_csp_that_allows_inline_script_fails(checker, tree):
    _edit(tree / "firebase-hosting/firebase.json", "script-src 'self'; style-src",
          "script-src 'self' 'unsafe-inline'; style-src")
    _one_failure(checker, tree, "the ** CSP's script-src allows 'unsafe-inline'")


def test_inline_script_on_a_legal_page_fails(checker, tree):
    _edit(tree / "firebase-hosting/public/terms/index.html", "<p>", "<script>track()</script><p>")
    _one_failure(checker, tree, "terms", "inline script, handler or javascript: URL")


def test_an_inline_handler_on_a_legal_page_fails(checker, tree):
    _edit(tree / "firebase-hosting/public/terms/index.html", "<p>", '<p onclick="go()">')
    _one_failure(checker, tree, "terms", "inline script, handler or javascript: URL")


# ---------------- 12: the SDK is pinned in script-src ----------------
_SDK = "https://www.gstatic.com/firebasejs/12.4.0/firebase-app.js"


def _with_sdk(tree: Path, script_src: str) -> None:
    """The shared module imports the Firebase SDK from gstatic (preloaded on
    every page, as check 9 asks), and both console CSPs carry `script_src`."""
    _edit(_console(tree, "shared.js"), "export const $",
          f'import {{ initializeApp }} from "{_SDK}";\nexport const app = initializeApp;\nexport const $')
    for sub in _CONSOLE_DIRS:
        _edit(_console(tree, sub, "index.html"), "</head>",
              f'  <link rel="modulepreload" href="{_SDK}" />\n</head>')
    path = tree / "firebase-hosting/firebase.json"
    _write(path, path.read_bytes().decode("utf-8").replace(
        "script-src 'self';", f"script-src {script_src};"))


def test_a_pinned_sdk_path_passes(checker, tree):
    _with_sdk(tree, "'self' https://www.gstatic.com/firebasejs/12.4.0/")
    assert checker.run(str(tree)) == []


def test_the_whole_gstatic_host_in_script_src_fails(checker, tree):
    _with_sdk(tree, "'self' https://www.gstatic.com")
    found = checker.run(str(tree))
    assert len(found) == 2 and all("whole www.gstatic.com host" in f for f in found), found


def test_a_pin_to_another_sdk_version_fails(checker, tree):
    _with_sdk(tree, "'self' https://www.gstatic.com/firebasejs/12.3.0/")
    found = checker.run(str(tree))
    assert any("12.4.0" in f and "not allowed" in f for f in found), found
    assert any("12.3.0" in f and "no console module imports" in f for f in found), found
