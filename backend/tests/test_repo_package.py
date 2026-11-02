"""The `app.repo` package (ADR-001, ADR-021) and the test helper that patches it.

Production code reads `app.repo` plainly: the package re-exports each
module's public names, routers call `repo.<name>`, and no module rebinds
anything on assignment. Tests patch through `repo_view.patch`, which replaces
a name in every module holding it. These pin both halves.
"""
import ast
import pathlib

import pytest

import app.repo as package
import repo_view as repo
from app.repo import (
    _base,
    activation,
    claims,
    devlock,
    entitlement,
    institution_admin,
    user_config,
)

APP_DIR = pathlib.Path(__file__).resolve().parents[1] / "app"
REPO_DIR = APP_DIR / "repo"


def _tree(module):
    return ast.parse(pathlib.Path(module.__file__).read_text(encoding="utf-8"))


def test_a_patch_reaches_callers_inside_the_package():
    """`ensure_entitlement` (entitlement) calls `claim_seat` through its own
    binding, as do `activation` and `institution_admin`. A patch that reached
    only one module would leave those callers on the real function and the
    test that set it would prove nothing."""
    real = repo.claim_seat

    def fake(*a, **k):
        return repo._CONTENDED

    holders = (claims, activation, entitlement, institution_admin, package)
    with pytest.MonkeyPatch.context() as mp:
        repo.patch(mp, "claim_seat", fake)
        assert all(m.claim_seat is fake for m in holders)
        assert repo.claim_seat is fake
    assert all(m.claim_seat is real for m in holders)


def test_a_shared_helper_is_patched_in_every_module_that_imported_it():
    real = repo._now
    with pytest.MonkeyPatch.context() as mp:
        repo.patch(mp, "_now", lambda: "then")
        assert user_config._now() == devlock._now() == _base._now() == "then"
    assert user_config._now is devlock._now is _base._now is real


def test_the_client_lives_in_base_alone():
    """`fake_firestore.install` sets `_DB` and `firestore` on `_base`; the
    other modules read them from there at call time."""
    with pytest.MonkeyPatch.context() as mp:
        repo.patch(mp, "_DB", "client")
        assert _base._DB == "client"
        assert repo._DB == "client"
    assert [m.__name__ for m in package.PACKAGE if "_DB" in vars(m)] == ["app.repo._base"]


def test_every_public_name_is_exported_by_the_package():
    """Routers call `repo.<name>`; the package lists each module's public names."""
    missing = []
    for module in package.PACKAGE:
        for node in _tree(module).body:
            name = getattr(node, "name", None)
            if name and not name.startswith("_") and name not in vars(package):
                missing.append(f"{module.__name__}.{name}")
    assert missing == []


def test_the_package_exports_no_private_name():
    """A private helper is imported from the module that defines it."""
    exported = {
        alias.name for node in ast.parse((REPO_DIR / "__init__.py").read_text()).body
        if isinstance(node, ast.ImportFrom) and node.level == 1 and node.module
        for alias in node.names
    }
    assert sorted(n for n in exported if n.startswith("_")) == []


def test_the_package_imports_run_one_way():
    """Each module imports only modules before it in `PACKAGE`, and none
    imports the package itself (which would make the order load-bearing)."""
    order = [m.__name__.rsplit(".", 1)[1] for m in package.PACKAGE]
    assert sorted(order) == sorted(p.stem for p in REPO_DIR.glob("*.py") if p.stem != "__init__")
    backwards = []
    for i, module in enumerate(package.PACKAGE):
        for node in ast.walk(_tree(module)):
            if isinstance(node, ast.ImportFrom) and node.level == 1 and node.module:
                if order.index(node.module) >= i:
                    backwards.append(f"{order[i]} -> {node.module}")
            if isinstance(node, ast.ImportFrom) and node.level == 2 and any(
                    alias.name == "repo" for alias in node.names):
                backwards.append(f"{order[i]} -> app.repo")
    assert backwards == []


def test_only_base_binds_the_firestore_module():
    """Everything else says `_base.firestore`, so the fake patches one place."""
    binders = [
        module.__name__ for module in package.PACKAGE[1:]
        if "firestore" in vars(module)
    ]
    assert binders == []


def test_nothing_imports_the_retired_facade():
    users = [
        str(p.relative_to(APP_DIR.parent)) for p in APP_DIR.rglob("*.py")
        if "firestore_repo" in p.read_text(encoding="utf-8")
    ]
    assert users == []


#: What each licensing module may import from the package (TD-64). Claims and
#: invites are leaves; `holders` (the one-licence rule) reads through invites;
#: everything else builds on them.
LICENSING_IMPORTS = {
    "claims": {"_base"},
    "invites": {"_base"},
    "holders": {"_base", "invites"},
    "mint": {"_base", "claims", "holders", "invites"},
    "activation": {"_base", "claims", "devlock", "holders", "user_config"},
    "entitlement": {"_base", "claims", "holders", "invites", "mint"},
    "license_admin": {"_base", "claims", "invites", "mint"},
    "upgrade": {"_base", "claims", "invites", "mint", "license_admin"},
    # A restore settles the licence's phones (`_settle_activations`, ADR-033).
    "deletion": {"_base", "activations", "claims", "holders", "invites", "mint",
                 "license_admin"},
    "institution_admin": {"_base", "claims", "holders", "invites", "mint"},
}


def _package_imports(module) -> set[str]:
    return {
        node.module for node in ast.walk(_tree(module))
        if isinstance(node, ast.ImportFrom) and node.level == 1 and node.module
    }


def test_the_licensing_modules_import_only_their_layer():
    by_name = {m.__name__.rsplit(".", 1)[1]: m for m in package.PACKAGE}
    extra = {
        name: sorted(_package_imports(by_name[name]) - allowed)
        for name, allowed in LICENSING_IMPORTS.items()
        if _package_imports(by_name[name]) - allowed
    }
    assert extra == {}


def test_no_module_outside_repo_queries_firestore():
    """Firestore is read and written from `app.repo` alone (ADR-021): a module
    outside it reaches the database through the package's names, never the
    client. `audit.py` queried `audit_logs` itself until the reads moved to
    `repo/audit_log.py`."""
    offenders = []
    for path in sorted(APP_DIR.rglob("*.py")):
        if REPO_DIR in path.parents:
            continue
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module and (
                node.module.endswith("repo._base") or node.module.startswith("google.cloud")
                and any(a.name == "firestore" for a in node.names)
            ):
                offenders.append(f"{path.relative_to(APP_DIR)}:{node.lineno} imports {node.module}")
            if (isinstance(node, ast.ImportFrom) and node.module == "repo"
                    and any(a.name == "_base" for a in node.names)):
                offenders.append(f"{path.relative_to(APP_DIR)}:{node.lineno} imports repo._base")
            if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                    and node.func.attr in {"collection", "collection_group"}):
                offenders.append(f"{path.relative_to(APP_DIR)}:{node.lineno} calls .{node.func.attr}()")
    assert offenders == []
