"""Every Firestore query the backend runs has the index it needs.

A query that needs a composite index fails only in production, with
FAILED_PRECONDITION, on the first request that runs it: neither the fake store
nor the Firestore emulator enforces indexes. This module holds each query's
shape — collection, filters, order — and checks it against
`backend/firestore.indexes.json`.

The shapes are declared below rather than read from the code: queries are
built up across helpers (`_scan` / `_cursor_page` add the order, `list_licenses`
adds its filters one `if` at a time), which no static reading follows reliably.
What keeps the declarations honest is the AST walk: every function in `app/`
that calls `.where(` / `.order_by(` / `_scan(` / `_cursor_page(` must have an
entry, and the literal fields and operators it uses must be the ones declared.
A new query, or a changed field, fails here until its shape is written down.

Rules (https://firebase.google.com/docs/firestore/query-data/index-overview):

* One field, filtered and/or ordered: the automatic single-field index serves it.
* Ordering by the document id (`__name__`) is implicit in every index.
* Several `==` filters and no order: the automatic indexes are merged.
  Conservatively, only `==` counts here; an `in` or `array_contains` beside
  another field is held to a composite index.
* Anything else over two or more fields needs a composite index: the equality
  fields first (in any order), then the inequality field, then the order fields
  with their directions.
* A collection-group query (`collection_group(name)`) is served by no automatic
  index: even on one field it needs that field's single-field index enabled at
  COLLECTION_GROUP scope, a `fieldOverrides` entry in the indexes file.
"""
from __future__ import annotations

import ast
import json
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import pytest

_BACKEND = Path(__file__).resolve().parents[1]
_APP = _BACKEND / "app"
_INDEXES = _BACKEND / "firestore.indexes.json"

ASC, DESC = "ASCENDING", "DESCENDING"
GROUP = "COLLECTION_GROUP"
_EQUALITY_OPS = {"==", "in", "array_contains", "array_contains_any"}
_RANGE_OPS = {"<", "<=", ">", ">=", "!=", "not-in"}


@dataclass(frozen=True)
class Shape:
    """One query as Firestore receives it."""

    collection: str
    filters: tuple[tuple[str, str], ...] = ()
    order: tuple[tuple[str, str], ...] = ()
    note: str = ""
    scope: str = "COLLECTION"

    def describe(self) -> str:
        opener = "collection_group" if self.scope == GROUP else "collection"
        parts = [f"{opener}({self.collection!r})"]
        parts += [f".where({f!r}, {op!r}, ...)" for f, op in self.filters]
        parts += [f".order_by({f!r}, {d})" for f, d in self.order]
        return "".join(parts)


def _q(collection, *filters, order=(), note="", scope="COLLECTION"):
    return Shape(collection, tuple(filters), tuple(order), note, scope)


_BY_ID = (("__name__", ASC),)

#: Every query in `app/`, keyed by `<path under backend/>::<function>`. A
#: function whose filters depend on its arguments lists one shape per
#: combination it can send.
QUERY_SHAPES: dict[str, list[Shape]] = {
    "app/repo/audit_log.py::list_license_device_history": [
        _q("audit_logs", ("target.id", "==")),
        _q("audit_logs", ("target.id", ">="), ("target.id", "<")),
    ],
    # One licence's audit log, newest first, optionally within dates.
    "app/repo/audit_log.py::page_license_audit": [
        _q("audit_logs", ("licenseId", "=="), order=(("ts", DESC),), note="no range"),
        _q("audit_logs", ("licenseId", "=="), ("ts", ">="), order=(("ts", DESC),)),
        _q("audit_logs", ("licenseId", "=="), ("ts", "<"), order=(("ts", DESC),)),
        _q("audit_logs", ("licenseId", "=="), ("ts", ">="), ("ts", "<"), order=(("ts", DESC),)),
    ],
    "app/repo/account.py::list_user_devices": [_q("devices", ("uid", "=="))],
    # A licence's phones (ADR-033): its own subcollection, by holder or by
    # status; and the holder's registered phones for the phones screen.
    "app/repo/activations.py::_holder_activations": [_q("activations", ("uid", "=="))],
    "app/repo/activations.py::licence_devices": [_q("activations", ("status", "=="))],
    "app/repo/activations.py::account_devices": [_q("devices", ("uid", "=="))],
    # Every seat an erased account held, on any licence (TD-243).
    "app/repo/account.py::_seats_of": [
        _q("seats", ("uid", "=="), scope=GROUP),
        _q("deleted_seats", ("uid", "=="), scope=GROUP),
    ],
    "app/repo/account.py::delete_all_user_data": [
        _q("files", ("uid", "==")),
        _q("sessions", ("uid", "==")),
        _q("devices", ("uid", "==")),
        _q("auth_links", ("uid", "==")),
        _q("deleted_sessions", ("uid", "==")),
    ],
    "app/repo/claims.py::_drop_superseded_demos": [_q("licenses", ("redeemedByUid", "in"))],
    "app/repo/deletion.py::list_deleted_licenses": [
        _q("deleted_licenses", order=(("deletedAt", DESC),)),
    ],
    "app/repo/holders.py::licence_held_by": [_q("licenses", ("emailLock", "=="))],
    "app/repo/institution_admin.py::list_licenses_administered_by": [
        _q("licenses", ("adminEmails", "array_contains")),
    ],
    "app/repo/institution_admin.py::page_institution_seats": [_q("seats", order=_BY_ID)],
    "app/repo/invites.py::find_user_by_email": [_q("users", ("email", "=="))],
    "app/repo/invites.py::_delete_license_invites": [_q("licenseInvites", ("licenseId", "=="))],
    "app/repo/invites.py::list_institution_invites": [_q("licenseInvites", ("licenseId", "=="))],
    "app/repo/leases.py::_sweep_expired_leases": [_q("seats", ("leaseExpiresAt", "<="))],
    "app/repo/license_admin.py::list_licenses": [
        _q("licenses", order=(("createdAt", DESC),), note="Demo and revoked shown"),
        _q("licenses", ("mode", "=="), order=(("createdAt", DESC),), note="revoked shown"),
        _q("licenses", ("status", "in"), order=(("createdAt", DESC),), note="Demo shown"),
        _q("licenses", ("mode", "=="), ("status", "in"), order=(("createdAt", DESC),),
           note="the desk's default"),
    ],
    "app/repo/license_admin.py::_search_licenses": [
        _q("licenses", ("emailLock", "==")),
        _q("licenses", ("domainLock", "==")),
        _q("licenses", ("keyPrefix", "==")),
    ],
    "app/repo/sessions.py::delete_session": [_q("files", ("sessionId", "=="))],
    "app/repo/sessions.py::_session_file_docs": [_q("files", ("sessionId", "=="), order=_BY_ID)],
    "app/repo/sessions.py::_page_session_files": [_q("files", ("sessionId", "=="), order=_BY_ID)],
    "app/repo/sessions.py::list_user_sessions": [_q("sessions", ("uid", "=="), order=_BY_ID)],
    "app/repo/sessions.py::iter_sessions_with_files": [
        _q("files", ("uid", "=="), order=(("sessionId", ASC),)),
    ],
    "app/repo/sessions.py::iter_user_sessions": [_q("sessions", ("uid", "=="))],
    # Per app (ADR-033): one app's sessions by each name it is stored under.
    "app/repo/sessions.py::count_user_sessions": [
        _q("sessions", ("uid", "==")),
        _q("sessions", ("uid", "=="), ("app", "=="), note="equality only: merged"),
    ],
    # Its stored-only half, narrowing any of the queries above, all of them
    # equality only (uid, app, status): merged.
    "app/repo/sessions.py::_stored_count": [_q("sessions", ("status", "=="))],
    # The demo cap's per-device half: any account's sessions from one device.
    "app/repo/sessions.py::count_device_sessions": [
        _q("sessions", ("deviceId", "==")),
        _q("sessions", ("deviceId", "=="), ("status", "=="), note="equality only: merged"),
    ],
    # A staff or IT phone release: the account's sessions from that phone.
    "app/repo/devices.py::forget_device_sessions": [
        _q("sessions", ("uid", "=="), ("deviceId", "=="), note="equality only: merged"),
        _q("deleted_sessions", ("uid", "=="), ("deviceId", "=="), note="equality only: merged"),
    ],
    "app/repo/sessions.py::find_incomplete_session": [
        _q("sessions", ("uid", "=="), ("localSessionId", "=="), ("status", "in")),
    ],
    # The recycle bin (ADR-035). Each is one field, or equality on two
    # (merged), or one field ranged and ordered: the automatic indexes.
    "app/repo/recycle_bin.py::flag_session_files": [_q("files", ("sessionId", "=="), order=_BY_ID)],
    "app/repo/recycle_bin.py::binned_files": [_q("files", ("sessionId", "=="), order=_BY_ID)],
    "app/repo/recycle_bin.py::erase_binned": [_q("files", ("sessionId", "=="))],
    # POST /v1/sessions's guard: is this analysis in the account's bin?
    "app/repo/recycle_bin.py::find_binned_session": [
        _q("deleted_sessions", ("uid", "=="), ("localSessionId", "=="), note="equality only: merged"),
    ],
    "app/repo/recycle_bin.py::list_recycle_bin": [_q("deleted_sessions", ("uid", "=="), order=_BY_ID)],
    "app/repo/recycle_bin.py::iter_recycle_bin": [_q("deleted_sessions", ("uid", "=="), order=_BY_ID)],
    # The daily purge: entries past their date, oldest first.
    "app/repo/recycle_bin.py::due_for_purge": [
        _q("deleted_sessions", ("purgeAt", "<="), order=(("purgeAt", ASC),)),
    ],
    "app/repo/users.py::_user_for_device": [_q("users", ("claimedDeviceId", "=="))],
    "app/repo/users.py::list_users": [
        _q("users", order=_BY_ID),
        _q("users", ("access_status", "=="), order=_BY_ID),
    ],
    "app/repo/users.py::set_user_status": [_q("devices", ("uid", "=="))],
    "app/repo/users.py::sign_in_uids": [_q("auth_links", ("uid", "=="))],
    # Test accounts (ADR-027): one field each, so the automatic indexes.
    "app/repo/_base.py::_active_devices": [_q("devices", ("uid", "=="))],
    "app/repo/devices.py::_oldest_active_devices": [_q("devices", ("uid", "=="))],
    "app/repo/testaccounts.py::active_test_devices": [_q("devices", ("uid", "=="))],
    "app/repo/testaccounts.py::licences_of_test_account":[_q("licenses", ("emailLock", "=="))],
    "app/repo/testaccounts.py::list_test_accounts": [_q("users", ("testAccount.purpose", "in"))],
    "app/repo/testaccounts.py::expired_test_accounts": [
        _q("users", ("testAccount.expiresAt", "<")),
    ],
}

#: Functions that order a query their caller built; the caller declares the shape.
_HELPERS = {"app/repo/_base.py::_scan", "app/repo/_base.py::_cursor_page"}

#: Query shapes known to lack their index, with why it is not fixed here.
#: Adding an index is a deploy (`scripts/deploy-firestore.sh indexes`), so a
#: missing one is reported and marked, never added by a test change.
KNOWN_MISSING: dict[str, str] = {}


# ---------------- index rules ----------------
def required_index(shape: Shape) -> tuple[frozenset[str], tuple[tuple[str, str], ...]] | None:
    """The composite index `shape` needs: (equality fields, ordered tail), or
    None when the automatic single-field indexes serve it."""
    equality = [(f, op) for f, op in shape.filters if op in _EQUALITY_OPS]
    ranges = {f for f, op in shape.filters if op in _RANGE_OPS}
    unknown = [op for _, op in shape.filters if op not in _EQUALITY_OPS | _RANGE_OPS]
    assert not unknown, f"{shape.describe()}: unknown operator(s) {unknown}"
    assert len(ranges) <= 1, f"{shape.describe()}: inequalities on two fields need a review"
    order = [(f, d) for f, d in shape.order if f != "__name__"]
    if ranges:
        (field,) = ranges
        if not order or order[0][0] != field:
            # Firestore orders by the inequality field first.
            order.insert(0, (field, ASC))
    eq_fields = frozenset(f for f, _ in equality) - {f for f, _ in order}
    if len(eq_fields | {f for f, _ in order}) <= 1:
        return None
    if not order and all(op == "==" for _, op in equality):
        return None
    return eq_fields, tuple(order)


def _index_fields(index: dict) -> list[tuple[str, str]]:
    return [
        (f["fieldPath"], f.get("order") or f.get("arrayConfig", ""))
        for f in index["fields"]
        if f["fieldPath"] != "__name__"
    ]


def serves(index: dict, shape: Shape, need) -> bool:
    if index.get("collectionGroup") != shape.collection:
        return False
    if index.get("queryScope", "COLLECTION") != shape.scope:
        return False
    eq_fields, order = need
    fields = _index_fields(index)
    if len(fields) != len(eq_fields) + len(order):
        return False
    head, tail = fields[: len(eq_fields)], fields[len(eq_fields):]
    return {f for f, _ in head} == eq_fields and tuple(tail) == order


def index_to_add(shape: Shape, need) -> str:
    eq_fields, order = need
    ops = dict(shape.filters)
    fields = [
        {"fieldPath": f, "arrayConfig": "CONTAINS"} if ops.get(f, "").startswith("array_contains")
        else {"fieldPath": f, "order": ASC}
        for f in sorted(eq_fields)
    ] + [{"fieldPath": f, "order": d} for f, d in order]
    return json.dumps(
        {"collectionGroup": shape.collection, "queryScope": "COLLECTION", "fields": fields},
        indent=2,
    )


def _composites() -> list[dict]:
    return json.loads(_INDEXES.read_text(encoding="utf-8"))["indexes"]


# ---------------- the code's queries, read from the AST ----------------
_QUERY_CALLS = {"where", "order_by", "_scan", "_cursor_page"}


def _call_name(node: ast.Call) -> str | None:
    if isinstance(node.func, ast.Attribute):
        return node.func.attr
    if isinstance(node.func, ast.Name):
        return node.func.id
    return None


def _const(node) -> str | None:
    return node.value if isinstance(node, ast.Constant) and isinstance(node.value, str) else None


@dataclass
class _Found:
    filters: set
    orders: set
    collections: set
    variable_filter: bool = False


def _module_constants(tree: ast.Module) -> dict[str, str]:
    """`NAME = "literal"` at module level, so `collection(_INVITES)` resolves."""
    out = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and _const(node.value) is not None:
            out.update({t.id: node.value.value for t in node.targets if isinstance(t, ast.Name)})
    return out


@lru_cache(maxsize=1)
def _queries_in_code() -> dict[str, _Found]:
    out: dict[str, _Found] = {}
    for path in sorted(_APP.rglob("*.py")):
        rel = path.relative_to(_BACKEND).as_posix()
        tree = ast.parse(path.read_text(encoding="utf-8"))
        constants = _module_constants(tree)
        for fn in ast.walk(tree):
            if not isinstance(fn, ast.FunctionDef | ast.AsyncFunctionDef):
                continue
            found = _Found(set(), set(), set())
            hit = False
            for node in ast.walk(fn):
                if not isinstance(node, ast.Call):
                    continue
                name = _call_name(node)
                if name in ("collection", "collection_group") and node.args:
                    arg = node.args[0]
                    coll = _const(arg) or (constants.get(arg.id) if isinstance(arg, ast.Name) else None)
                    if coll:
                        found.collections.add(coll)
                if name not in _QUERY_CALLS:
                    continue
                hit = True
                if name == "where" and len(node.args) >= 2:
                    field, op = _const(node.args[0]), _const(node.args[1])
                    if field is None:
                        found.variable_filter = True
                    else:
                        found.filters.add((field, op))
                elif name == "order_by" and node.args and _const(node.args[0]):
                    found.orders.add(_const(node.args[0]))
                for kw in node.keywords:
                    if kw.arg == "order_field" and _const(kw.value):
                        found.orders.add(_const(kw.value))
            # Nested functions are walked twice; the outermost entry wins.
            if hit:
                out.setdefault(f"{rel}::{fn.name}", found)
    return out


# ---------------- tests ----------------
def test_every_query_in_the_code_has_a_declared_shape():
    code = _queries_in_code()
    undeclared = sorted(set(code) - set(QUERY_SHAPES) - _HELPERS)
    assert not undeclared, (
        "These functions query Firestore but have no entry in QUERY_SHAPES "
        f"(tests/test_firestore_query_indexes.py): {undeclared}. Declare each "
        "query's collection, filters and order so its index can be checked."
    )
    stale = sorted(set(QUERY_SHAPES) - set(code))
    assert not stale, f"QUERY_SHAPES names functions that no longer query: {stale}"


@pytest.mark.parametrize("where", sorted(QUERY_SHAPES))
def test_declared_shape_matches_the_code(where):
    found = _queries_in_code()[where]
    shapes = QUERY_SHAPES[where]
    declared_filters = {flt for s in shapes for flt in s.filters}
    declared_orders = {f for s in shapes for f, _ in s.order if f != "__name__"}
    missing = found.filters - declared_filters
    assert not missing, f"{where} filters on {sorted(missing)}, which its QUERY_SHAPES entry omits"
    extra = declared_filters - found.filters
    if not found.variable_filter:
        assert not extra, f"{where}: QUERY_SHAPES declares {sorted(extra)}, which the code no longer filters on"
    assert found.orders - {"__name__"} == declared_orders, (
        f"{where} orders by {sorted(found.orders)}, QUERY_SHAPES says {sorted(declared_orders)}"
    )
    # A helper handed its collection by the caller opens none itself.
    unknown = ({s.collection for s in shapes} - found.collections) if found.collections else set()
    assert not unknown, (
        f"{where}: QUERY_SHAPES names collection(s) {sorted(unknown)}, but the function "
        f"opens only {sorted(found.collections)}"
    )


_CASES = [
    pytest.param(
        where, shape,
        id=f"{where.split('::')[1]}[{i}]",
        marks=[pytest.mark.xfail(strict=True, reason=KNOWN_MISSING[f"{where}[{i}]"])]
        if f"{where}[{i}]" in KNOWN_MISSING else [],
    )
    for where, shapes in sorted(QUERY_SHAPES.items())
    for i, shape in enumerate(shapes)
]


@pytest.mark.parametrize(("where", "shape"), _CASES)
def test_query_has_its_composite_index(where, shape):
    need = required_index(shape)
    if need is None:
        return
    assert any(serves(ix, shape, need) for ix in _composites()), (
        f"{where} runs {shape.describe()}, which needs a composite index that "
        f"backend/firestore.indexes.json does not declare. Add to its \"indexes\":\n"
        f"{index_to_add(shape, need)}\n"
        "then deploy it (scripts/deploy-firestore.sh indexes) and wait for the build "
        "before the backend that queries it."
    )


def _group_single_field_indexes() -> set[tuple[str, str]]:
    """(collection, field) pairs whose single-field index is enabled at
    COLLECTION_GROUP scope by a `fieldOverrides` entry."""
    overrides = json.loads(_INDEXES.read_text(encoding="utf-8")).get("fieldOverrides", [])
    return {
        (o["collectionGroup"], o["fieldPath"])
        for o in overrides
        for ix in o.get("indexes", [])
        if ix.get("queryScope") == GROUP and ix.get("order") == ASC
    }


@pytest.mark.parametrize(("where", "shape"), [
    (where, shape) for where, shapes in sorted(QUERY_SHAPES.items())
    for shape in shapes if shape.scope == GROUP
])
def test_a_collection_group_query_has_its_group_scope_index(where, shape):
    fields = {f for f, _ in shape.filters} | {f for f, _ in shape.order if f != "__name__"}
    assert len(fields) == 1, f"{where}: a composite collection-group query needs a review"
    (field,) = fields
    assert (shape.collection, field) in _group_single_field_indexes(), (
        f"{where} runs {shape.describe()}, which needs {shape.collection}.{field} indexed "
        "at COLLECTION_GROUP scope: a fieldOverrides entry in backend/firestore.indexes.json "
        "that also restates the field's COLLECTION indexes. Deploy it "
        "(scripts/deploy-firestore.sh indexes) before the backend that queries it."
    )


def test_every_group_scope_override_is_used_by_some_query():
    used = {
        (s.collection, f) for ss in QUERY_SHAPES.values() for s in ss if s.scope == GROUP
        for f, _ in s.filters
    }
    assert _group_single_field_indexes() <= used


def test_every_composite_index_is_used_by_some_query():
    """An index nothing queries costs a write on every document and hides a
    renamed field: the query moved on, the index did not."""
    shapes = [s for ss in QUERY_SHAPES.values() for s in ss]
    unused = [
        ix for ix in _composites()
        if not any((need := required_index(s)) and serves(ix, s, need) for s in shapes)
    ]
    assert not unused, f"Composite indexes no declared query uses: {json.dumps(unused, indent=2)}"


# ---------------- the rules themselves ----------------
@pytest.mark.parametrize(
    ("shape", "needs"),
    [
        (_q("c", ("a", "==")), False),
        (_q("c", ("a", "=="), order=_BY_ID), False),
        (_q("c", order=(("a", DESC),)), False),
        (_q("c", ("a", ">="), ("a", "<")), False),
        (_q("c", ("a", "=="), ("b", "==")), False),
        (_q("c", ("a", "=="), ("b", "in")), True),
        (_q("c", ("a", "=="), order=(("b", ASC),)), True),
        (_q("c", ("a", "=="), ("b", "<")), True),
        (_q("c", ("a", "array_contains"), ("b", "==")), True),
    ],
)
def test_required_index_rules(shape, needs):
    assert (required_index(shape) is not None) is needs


def test_a_missing_index_is_reported_with_the_index_to_add():
    shape = _q("licenses", ("mode", "=="), order=(("expiresAt", ASC),))
    need = required_index(shape)
    assert not any(serves(ix, shape, need) for ix in _composites())
    added = json.loads(index_to_add(shape, need))
    assert added["fields"] == [
        {"fieldPath": "mode", "order": ASC},
        {"fieldPath": "expiresAt", "order": ASC},
    ]
    assert serves(added, shape, need)


def test_direction_and_extra_fields_matter():
    shape = _q("licenses", ("mode", "=="), order=(("createdAt", DESC),))
    need = required_index(shape)
    asc = {"collectionGroup": "licenses", "fields": [
        {"fieldPath": "mode", "order": ASC}, {"fieldPath": "createdAt", "order": ASC}]}
    wider = {"collectionGroup": "licenses", "fields": [
        {"fieldPath": "mode", "order": ASC}, {"fieldPath": "status", "order": ASC},
        {"fieldPath": "createdAt", "order": DESC}]}
    other = {"collectionGroup": "sessions", "fields": [
        {"fieldPath": "mode", "order": ASC}, {"fieldPath": "createdAt", "order": DESC}]}
    assert not serves(asc, shape, need)
    assert not serves(wider, shape, need)
    assert not serves(other, shape, need)
