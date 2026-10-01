"""The status and role vocabularies shared with the Android client.

`test_error_codes.py` pins the `{"detail": …}` codes. These are the other two
wire vocabularies the client branches on, and until now nothing held them
together:

* **Session status** — `UploadWorkOutcomes.resumeKindFor` decides from it
  whether a backup resumes, waits for provisioning, or reports a terminal
  failure. A rename here does not crash anything; it produces a backup that
  never resumes.
* **Artifact role** — the restore path routes each downloaded file by the role
  it was stored under. A rename deploys green on both sides and silently routes
  restored files nowhere.

Both are the same failure shape as a renamed error code, so they get the same
pin.
"""
import re
from functools import lru_cache
from pathlib import Path

import pytest

from app import models, statuses

# tests/ -> backend/ -> repo root
_REPO_ROOT = Path(__file__).resolve().parents[2]
_CLIENT_ROOT = _REPO_ROOT / "app/src/main/java/com/indicvision/semper"
_CLIENT_STATUSES = _CLIENT_ROOT / "data/cloud/UploadWorkOutcomes.kt"
_CLIENT_ROLES = _CLIENT_ROOT / "data/net/ArtifactRoles.kt"

_needs_android = pytest.mark.skipif(
    not _CLIENT_ROOT.is_dir(),
    reason="Android sources not present (backend-only checkout)",
)


@lru_cache(maxsize=None)
def _declared_statuses() -> dict[str, str]:
    """Every public `NAME = "STATUS"` constant in app/statuses.py."""
    return {
        name: value
        for name, value in vars(statuses).items()
        if name.isupper() and isinstance(value, str) and not name.startswith("_")
    }


@lru_cache(maxsize=None)
def _client_statuses() -> frozenset[str]:
    source = _CLIENT_STATUSES.read_text(encoding="utf-8")
    return frozenset(re.findall(r'const val STATUS_[A-Z_]+ = "([A-Z][A-Z0-9_]*)"', source))


@lru_cache(maxsize=None)
def _client_roles() -> frozenset[str]:
    source = _CLIENT_ROLES.read_text(encoding="utf-8")
    return frozenset(re.findall(r'const val [A-Z_]+ = "([a-z][a-z0-9_]*)"', source))


def _backend_roles() -> frozenset[str]:
    """The `Role` Literal's arguments, read off the annotation itself."""
    return frozenset(models.Role.__args__)


def test_statuses_are_stable_tokens():
    for name, value in _declared_statuses().items():
        assert re.fullmatch(r"[A-Z][A-Z0-9_]*", value), f"{name} is not a wire-safe status"


def test_no_two_status_names_share_a_value():
    """Distinct vocabularies may reuse a spelling (a file and a session are both
    COMPLETED); within one vocabulary a collision is a bug, so compare per prefix."""
    for prefix in ("SESSION_", "FILE_", "DEVICE_", "ACCESS_"):
        values = [v for n, v in _declared_statuses().items() if n.startswith(prefix)]
        assert len(values) == len(set(values)), f"duplicate value under {prefix}"


def test_client_branched_statuses_are_declared():
    assert statuses.CLIENT_BRANCHED_SESSION <= set(_declared_statuses().values())


def test_in_flight_statuses_are_declared():
    assert set(statuses.IN_FLIGHT_SESSION_STATUSES) <= set(_declared_statuses().values())


@_needs_android
def test_client_declares_every_branched_status():
    missing = statuses.CLIENT_BRANCHED_SESSION - _client_statuses()
    assert not missing, (
        f"{sorted(missing)} are branched on by this service but absent from "
        f"{_CLIENT_STATUSES.relative_to(_REPO_ROOT)} — rename both sides together"
    )


@_needs_android
def test_client_invents_no_statuses_of_its_own():
    unknown = _client_statuses() - set(_declared_statuses().values())
    assert not unknown, (
        f"{sorted(unknown)} are declared in {_CLIENT_STATUSES.name} but never "
        "written by this service — the client is branching on a status that cannot arrive"
    )


@_needs_android
def test_role_vocabularies_match_exactly():
    backend, client = _backend_roles(), _client_roles()
    assert backend == client, (
        f"role mismatch — only in backend: {sorted(backend - client)}; "
        f"only in {_CLIENT_ROLES.name}: {sorted(client - backend)}"
    )
