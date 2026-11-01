"""The API Gateway spec and the FastAPI app must describe the same surface.

ESPv2 is a strict allowlist. A route that exists in the application but not in
`gateway/openapi.yaml` is simply unreachable in production — the gateway
rejects it before the container is ever consulted, and nothing in the app's
logs says why. That failure has happened twice on this codebase (the
`/v1/campus/*` aliases, then the lease routes), both times surviving code
review because both halves looked right in isolation.

The reverse direction matters less but is still a bug: a path declared at the
gateway with no handler behind it is a 404 that the gateway happily forwards.

Paths are compared with their parameter *names* erased, because the two files
spell them differently by convention — FastAPI takes `{license_id}` from the
Python signature, the gateway spec uses `{licenseId}`. Only the shape and the
methods are the contract.
"""
import pathlib
import re

import yaml

from app.main import app

_HTTP_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE"}

#: Routes FastAPI serves that are deliberately NOT exposed at the gateway.
#: Everything here is reachable only on the container's own port, which no
#: external client can address once the service is private.
_NOT_PUBLISHED = {
    # FastAPI's own docs. Publishing them would hand an unauthenticated caller
    # a map of every route and schema.
    ("GET", "/openapi.json"),
    ("GET", "/docs"),
    ("GET", "/docs/oauth2-redirect"),
    ("GET", "/redoc"),
    # The Cloud Tasks callback. Cloud Tasks posts straight to
    # TASKS_TARGET_BASE_URL (the Cloud Run service URL) carrying an OIDC token
    # for the invoker service account — it never traverses the API Gateway, and
    # publishing it there would expose a route that no user-facing caller may
    # reach. See app/tasks.py.
    ("POST", "/v1/tasks/provision-session"),
    # The other Cloud Tasks callback, for the same reason: it finishes a
    # licence's pending fan-out (ADR-026).
    ("POST", "/v1/tasks/license-fanout"),
    # The Cloud Scheduler job that ends expired test accounts (ADR-027): the
    # same OIDC invoker, straight to the Cloud Run URL, for the same reason.
    ("POST", "/v1/tasks/test-account-expiry"),
    # The Cloud Scheduler job that purges the recycle bin (ADR-035): the same
    # OIDC invoker, straight to the Cloud Run URL, for the same reason.
    ("POST", "/v1/tasks/recycle-bin-purge"),
    # Readiness. It runs a Firestore read and a Drive call per hit, needs no
    # token, and its 503 names the failing dependency — published at the
    # gateway it was a free way to drive Drive quota and to learn which
    # backend is down. The deploy smoke calls the tagged run.app URL directly
    # with the deployer's invoker token; /healthz stays public for probes.
    ("GET", "/readyz"),
}


def _erase_params(path: str) -> str:
    return re.sub(r"\{[^}]+\}", "{}", path)


def _iter_api_routes(_routes=None):
    """Every route the app serves, nested routers included, with full paths
    (`app.main.served_routes`). Walking only the top level would compare
    against the docs endpoints and pass vacuously."""
    from app.main import served_routes
    yield from served_routes(app)


def _app_surface() -> set[tuple[str, str]]:
    out = set()
    for route in _iter_api_routes():
        for method in getattr(route, "methods", None) or ():
            if method in _HTTP_METHODS:
                out.add((method, _erase_params(route.path)))
    return out


def _gateway_surface() -> set[tuple[str, str]]:
    spec_path = pathlib.Path(__file__).resolve().parents[1] / "gateway" / "openapi.yaml"
    spec = yaml.safe_load(spec_path.read_text())
    out = set()
    for path, operations in (spec.get("paths") or {}).items():
        for method in operations:
            if method.upper() in _HTTP_METHODS:
                out.add((method.upper(), _erase_params(path)))
    return out


def test_every_app_route_is_reachable_through_the_gateway():
    missing = _app_surface() - _gateway_surface() - _NOT_PUBLISHED
    assert not missing, (
        "route(s) served by the app but absent from gateway/openapi.yaml, so "
        f"unreachable in production: {sorted(missing)}. Declare them there in "
        "the same commit, or add them to _NOT_PUBLISHED with the reason."
    )


def test_unpublished_routes_stay_off_the_gateway():
    """_NOT_PUBLISHED is only subtracted above, so re-publishing /readyz or a
    Cloud Tasks callback in gateway/openapi.yaml would pass every other check
    here (readiness TE3-009). It must stay absent."""
    published = _gateway_surface() & {(m, _erase_params(p)) for m, p in _NOT_PUBLISHED}
    assert not published, (
        f"route(s) that must not be reachable through the gateway are declared there: "
        f"{sorted(published)}. See _NOT_PUBLISHED for why each is private."
    )


def test_every_gateway_path_has_a_handler():
    orphaned = _gateway_surface() - _app_surface()
    assert not orphaned, (
        "path(s) declared at the gateway with no handler behind them, so a "
        f"caller gets a forwarded 404: {sorted(orphaned)}"
    )


def test_the_retired_campus_aliases_are_gone_from_both_halves():
    """The pre-rename `/v1/campus/*` seat aliases were retired (TD-45) after
    30 days with no request. They have to leave the app and the gateway
    together: left in the spec alone, ESPv2 forwards a call to a 404; left in
    the app alone, the parity tests above fail."""
    for method, path in sorted(_app_surface() | _gateway_surface()):
        assert not path.startswith("/v1/campus/"), f"retired alias still declared: {method} {path}"


# --- spec validity ---------------------------------------------------------
# API Gateway validates the document against the Swagger 2.0 schema and its
# YAML loader rejects duplicate keys. PyYAML's safe_load does neither: it keeps
# the last duplicate and happily splits an unquoted flow-mapping description at
# a comma into a second, nonsense key. Both slipped through once and failed at
# `api-configs create`, which is the worst place to find out.

_RESPONSE_KEYS = {"description", "schema", "headers", "examples", "$ref"}


class _StrictLoader(yaml.SafeLoader):
    def construct_mapping(self, node, deep=False):
        seen = set()
        for key_node, _ in node.value:
            key = self.construct_object(key_node, deep=deep)
            assert key not in seen, f"duplicate key {key!r} at line {key_node.start_mark.line + 1}"
            seen.add(key)
        return super().construct_mapping(node, deep=deep)


def _spec_text() -> str:
    return (pathlib.Path(__file__).resolve().parents[1] / "gateway" / "openapi.yaml").read_text(
        encoding="utf-8"
    )


def test_gateway_spec_has_no_duplicate_keys():
    yaml.load(_spec_text(), Loader=_StrictLoader)  # noqa: S506 - _StrictLoader is a yaml.SafeLoader subclass


def test_gateway_responses_carry_only_swagger_keys():
    spec = yaml.safe_load(_spec_text())
    bad = []
    for path, operations in spec["paths"].items():
        for method, op in operations.items():
            if method.upper() not in _HTTP_METHODS:
                continue
            for status, response in (op.get("responses") or {}).items():
                extra = set(response) - _RESPONSE_KEYS
                if extra:
                    bad.append(f"{method.upper()} {path} {status}: {sorted(extra)}")
    assert not bad, "unquoted description with a comma? " + "; ".join(bad)


def test_the_licence_search_is_its_own_literal_path_ahead_of_the_template():
    """`/v1/admin/licenses/search` also matches `/v1/admin/licenses/{licenseId}`,
    which has no POST, so the console check's pattern match cannot tell it is
    missing. The literal path carries the POST and comes first (TD-244)."""
    spec = yaml.safe_load(_spec_text())["paths"]
    order = list(spec)
    literal, template = "/v1/admin/licenses/search", "/v1/admin/licenses/{licenseId}"
    assert set(spec[literal]) == {"post"}
    assert "post" not in spec[template]
    assert order.index(literal) < order.index(template)
