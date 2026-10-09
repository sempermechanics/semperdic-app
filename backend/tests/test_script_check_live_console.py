"""`scripts/check_live_console.py`: the post-deploy read of the live consoles.

`deploy-console.sh` runs it after every Hosting deploy; it is what catches a
deploy that shipped `__API_BASE_URL__` / `__API_ORIGIN__` unfilled (2026-10-07).
Here the network is never touched: `urllib.request.urlopen` is replaced by a
fake site that serves a clean deploy, and each test breaks one thing on it.
"""
from __future__ import annotations

import importlib.util
import sys
import urllib.error
import urllib.request
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "check_live_console.py"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")

_ORIGIN = "https://app.test"
_API = "https://gw.example"


@pytest.fixture(scope="module")
def live():
    spec = importlib.util.spec_from_file_location("check_live_console", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _csp(connect: str = f"'self' {_API} https://identitytoolkit.googleapis.com") -> str:
    return ("default-src 'self'; script-src 'self' https://www.gstatic.com; "
            f"connect-src {connect}; frame-src 'self'; object-src 'none'; base-uri 'self'")


def _config(value: str = _API) -> str:
    return f'/* Deploy-time configuration. */\nexport const API_BASE_URL = "{value}";\n'


class _Response:
    def __init__(self, body: str, headers: dict[str, str], body_error: BaseException | None = None):
        self._body = body.encode("utf-8")
        self.headers = headers
        self._body_error = body_error

    def read(self) -> bytes:
        if self._body_error is not None:
            raise self._body_error
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeSite:
    """What the Hosting origin serves, keyed by path; an exception value is raised."""

    def __init__(self, origin: str = _ORIGIN):
        self.origin = origin
        self.pages: dict[str, object] = {
            "/console/config.js": (_config(), {}),
            "/login": ("<!doctype html>", {"Content-Security-Policy": _csp()}),
            "/account": ("<!doctype html>", {"Content-Security-Policy": _csp()}),
            "/console/": ("<!doctype html>", {"Content-Security-Policy": _csp()}),
        }
        self.requests: list[urllib.request.Request] = []
        self.timeouts: list[float] = []

    def set(self, path: str, value) -> None:
        self.pages[path] = value

    def urls(self) -> list[str]:
        return [r.full_url for r in self.requests]

    def urlopen(self, req, timeout=None):
        self.requests.append(req)
        self.timeouts.append(timeout)
        url = req.full_url
        assert url.startswith(self.origin), f"unexpected host: {url}"
        page = self.pages.get(url[len(self.origin):])
        if page is None:
            raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)
        if isinstance(page, BaseException):
            raise page
        if isinstance(page, _Response):
            return page
        body, headers = page
        return _Response(body, headers)


@pytest.fixture
def site(monkeypatch):
    fake = FakeSite()
    monkeypatch.setattr(urllib.request, "urlopen", fake.urlopen)
    return fake


@pytest.fixture
def sleeps(monkeypatch, live):
    waited: list[float] = []
    monkeypatch.setattr(live.time, "sleep", waited.append)
    return waited


def _main(live, monkeypatch, capsys, *args: str) -> tuple[int, str, str]:
    monkeypatch.setattr(sys, "argv", ["check_live_console.py", *args])
    status = live.main()
    out = capsys.readouterr()
    return status, out.out, out.err


# --- problems(): a clean site, and each thing it checks -----------------------

def test_a_clean_deploy_has_no_problems(live, site):
    assert live.problems(_ORIGIN, _API) == []
    assert site.urls() == [f"{_ORIGIN}{p}" for p in ("/console/config.js", "/login", "/account", "/console/")]


def test_without_api_only_placeholders_are_checked(live, site):
    site.set("/console/config.js", (_config("https://some-other.gateway"), {}))
    site.set("/login", ("", {"Content-Security-Policy": _csp("'self' https://some-other.gateway")}))
    assert live.problems(_ORIGIN, None) == []


@pytest.mark.parametrize("placeholder", ["__API_BASE_URL__", "__API_ORIGIN__"])
def test_a_placeholder_left_in_config_js_fails(live, site, placeholder):
    site.set("/console/config.js", (_config(placeholder), {}))
    for api in (None, _API):
        assert live.problems(_ORIGIN, api) == [f"/console/config.js: API_BASE_URL is still {placeholder}"]


def test_config_js_naming_another_gateway_fails_when_api_is_given(live, site):
    site.set("/console/config.js", (_config("https://stale.example"), {}))
    assert live.problems(_ORIGIN, _API) == [
        f"/console/config.js: API_BASE_URL is https://stale.example, expected {_API}"
    ]


def test_config_js_without_api_base_url_fails(live, site):
    site.set("/console/config.js", ("export const SOMETHING_ELSE = 1;\n", {}))
    assert live.problems(_ORIGIN, _API) == ["/console/config.js: no API_BASE_URL in it"]


@pytest.mark.parametrize("error", [
    urllib.error.URLError("name resolution failed"),
    urllib.error.HTTPError(f"{_ORIGIN}/console/config.js", 503, "Service Unavailable", {}, None),
    TimeoutError("timed out"),
], ids=["url-error", "http-503", "timeout"])
def test_an_unreadable_config_js_is_the_only_problem_reported(live, site, error):
    site.set("/console/config.js", error)
    found = live.problems(_ORIGIN, _API)
    assert len(found) == 1
    assert found[0].startswith("/console/config.js: could not read it (")
    assert site.urls() == [f"{_ORIGIN}/console/config.js"]  # pages are not read after it


def test_a_missing_config_js_is_reported_as_unreadable(live, site):
    del site.pages["/console/config.js"]
    assert live.problems(_ORIGIN, None) == ["/console/config.js: could not read it (HTTP Error 404: Not Found)"]


@pytest.mark.parametrize("path", ["/login", "/account", "/console/"])
def test_a_csp_placeholder_on_any_console_page_fails(live, site, path):
    site.set(path, ("", {"Content-Security-Policy": _csp("'self' __API_ORIGIN__ https://identitytoolkit.googleapis.com")}))
    assert live.problems(_ORIGIN, None) == [f"{path}: CSP still says __API_ORIGIN__"]
    assert live.problems(_ORIGIN, _API) == [
        f"{path}: CSP still says __API_ORIGIN__",
        f"{path}: CSP connect-src does not admit {_API}",
    ]


def test_both_placeholders_in_a_csp_are_named(live, site):
    site.set("/account", ("", {"Content-Security-Policy": _csp("'self' __API_ORIGIN__ __API_BASE_URL__")}))
    assert live.problems(_ORIGIN, None) == ["/account: CSP still says __API_BASE_URL__, __API_ORIGIN__"]


@pytest.mark.parametrize("path", ["/login", "/account", "/console/"])
def test_a_page_without_a_csp_header_fails(live, site, path):
    site.set(path, ("<!doctype html>", {}))
    assert live.problems(_ORIGIN, _API) == [f"{path}: no Content-Security-Policy header"]


@pytest.mark.parametrize("connect", [
    "'self' https://identitytoolkit.googleapis.com",          # gateway absent
    f"'self' {_API}.evil.test",                                # prefix of another host
    f"'self' {_API}/",                                         # not the exact origin token
], ids=["absent", "prefix", "trailing-slash"])
def test_a_connect_src_that_does_not_admit_the_gateway_fails(live, site, connect):
    site.set("/console/", ("", {"Content-Security-Policy": _csp(connect)}))
    assert live.problems(_ORIGIN, _API) == [f"/console/: CSP connect-src does not admit {_API}"]


def test_the_gateway_in_another_directive_does_not_count(live, site):
    csp = f"default-src 'self'; script-src 'self' {_API}; connect-src 'self'"
    site.set("/login", ("", {"Content-Security-Policy": csp}))
    assert live.problems(_ORIGIN, _API) == [f"/login: CSP connect-src does not admit {_API}"]


def test_an_unreadable_page_is_reported_and_the_rest_still_checked(live, site):
    site.set("/login", urllib.error.HTTPError(f"{_ORIGIN}/login", 500, "Internal Server Error", {}, None))
    site.set("/console/", ("", {}))
    assert live.problems(_ORIGIN, _API) == [
        "/login: could not read it (HTTP Error 500: Internal Server Error)",
        "/console/: no Content-Security-Policy header",
    ]


def test_every_problem_is_reported_not_just_the_first(live, site):
    site.set("/console/config.js", (_config("__API_BASE_URL__"), {}))
    site.set("/login", TimeoutError("timed out"))
    site.set("/account", ("", {}))
    site.set("/console/", ("", {"Content-Security-Policy": _csp("'self' __API_ORIGIN__")}))
    assert live.problems(_ORIGIN, _API) == [
        "/console/config.js: API_BASE_URL is still __API_BASE_URL__",
        "/login: could not read it (timed out)",
        "/account: no Content-Security-Policy header",
        "/console/: CSP still says __API_ORIGIN__",
        f"/console/: CSP connect-src does not admit {_API}",
    ]


# --- fetch(): the HTTP layer --------------------------------------------------

def test_fetch_asks_for_an_uncached_copy_with_a_timeout(live, site):
    body, csp = live.fetch(f"{_ORIGIN}/login")
    assert (body, csp) == ("<!doctype html>", _csp())
    request = site.requests[0]
    assert request.get_header("Cache-control") == "no-cache"
    assert site.timeouts == [20]


def test_fetch_returns_an_empty_csp_when_the_header_is_absent(live, site):
    assert live.fetch(f"{_ORIGIN}/console/config.js") == (_config(), "")


def test_fetch_tolerates_a_body_that_is_not_utf8(live, site):
    response = _Response("", {"Content-Security-Policy": "x"})
    response._body = b'API_BASE_URL = "\xff"'
    site.set("/login", response)
    body, csp = live.fetch(f"{_ORIGIN}/login")
    assert body == 'API_BASE_URL = "�"'
    assert csp == "x"


# --- main(): exit status, output, retries -------------------------------------

def test_main_exits_0_on_a_clean_deploy(live, site, sleeps, monkeypatch, capsys):
    status, out, err = _main(live, monkeypatch, capsys, "--origin", _ORIGIN, "--api", _API)
    assert status == 0
    assert out.strip() == f"Live console at {_ORIGIN} has its gateway filled in ({_API})."
    assert err == ""
    assert sleeps == []


def test_main_without_api_says_only_that_it_is_filled(live, site, sleeps, monkeypatch, capsys):
    status, out, _ = _main(live, monkeypatch, capsys, "--origin", _ORIGIN)
    assert status == 0
    assert out.strip() == f"Live console at {_ORIGIN} has its gateway filled in."


def test_main_reads_the_production_origin_by_default(live, sleeps, monkeypatch, capsys):
    prod = FakeSite("https://app.sempermechanics.com")
    monkeypatch.setattr(urllib.request, "urlopen", prod.urlopen)
    status, _, _ = _main(live, monkeypatch, capsys)
    assert status == 0
    assert prod.urls()[0] == "https://app.sempermechanics.com/console/config.js"


def test_main_strips_trailing_slashes_from_origin_and_api(live, site, sleeps, monkeypatch, capsys):
    status, out, _ = _main(live, monkeypatch, capsys, "--origin", f"{_ORIGIN}/", "--api", f"{_API}/")
    assert status == 0
    assert site.urls()[0] == f"{_ORIGIN}/console/config.js"
    assert f"({_API})." in out


def test_main_exits_1_after_every_attempt_fails_and_reports_the_last(live, site, sleeps, monkeypatch, capsys):
    site.set("/console/config.js", (_config("__API_BASE_URL__"), {}))
    status, out, err = _main(live, monkeypatch, capsys,
                             "--origin", _ORIGIN, "--api", _API, "--attempts", "3", "--wait", "0.25")
    assert status == 1
    assert out == ""
    assert sleeps == [0.25, 0.25]  # between attempts, not after the last
    assert site.urls().count(f"{_ORIGIN}/console/config.js") == 3
    assert f"Live console at {_ORIGIN} is not deployed correctly:" in err
    assert "  - /console/config.js: API_BASE_URL is still __API_BASE_URL__" in err
    assert "scripts/deploy-console.sh" in err


def test_a_stale_cdn_read_is_retried_until_the_new_release_serves(live, site, sleeps, monkeypatch, capsys):
    """The CDN can serve the old release for seconds after a deploy."""
    stale = _Response(_config("__API_BASE_URL__"), {})
    fresh_config = (_config(), {})
    served = iter([stale, stale])

    real = site.urlopen

    def urlopen(req, timeout=None):
        if req.full_url.endswith("/console/config.js"):
            site.set("/console/config.js", next(served, fresh_config))
        return real(req, timeout)

    monkeypatch.setattr(urllib.request, "urlopen", urlopen)
    status, out, err = _main(live, monkeypatch, capsys, "--origin", _ORIGIN, "--api", _API, "--wait", "5")
    assert status == 0, err
    assert sleeps == [5.0, 5.0]
    assert "has its gateway filled in" in out


def test_main_with_one_attempt_never_sleeps(live, site, sleeps, monkeypatch, capsys):
    site.set("/console/config.js", urllib.error.URLError("down"))
    status, _, err = _main(live, monkeypatch, capsys, "--origin", _ORIGIN, "--attempts", "1")
    assert status == 1
    assert sleeps == []
    assert "/console/config.js: could not read it (<urlopen error down>)" in err


def test_a_connection_dropped_mid_body_is_reported_not_a_crash(live, site, sleeps, monkeypatch, capsys):
    site.set("/login", _Response("", {}, body_error=ConnectionResetError("connection reset by peer")))
    status, _, err = _main(live, monkeypatch, capsys, "--origin", _ORIGIN, "--attempts", "2")
    assert status == 1
    assert sleeps == [5.0]
    assert "/login: could not read it" in err
