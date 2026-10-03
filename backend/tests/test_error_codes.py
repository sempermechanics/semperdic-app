"""The error-code contract between this service and the Android client.

`app/errors.py` names the strings that go out as `{"detail": …}`. The client
branches on a subset of them to tell, inside one status code, a device conflict
from a quota rejection. Nothing else pins the two lists together, so a rename on
either side would compile, deploy, and quietly change what the app believes a
409 means. These tests are that pin.
"""
import re
from functools import lru_cache
from pathlib import Path

import pytest

from app import errors

# tests/ -> backend/ -> repo root
_REPO_ROOT = Path(__file__).resolve().parents[2]
_CLIENT_ERRORS = (
    _REPO_ROOT
    / "app/src/main/java/com/sempermechanics/semper/data/net/ApiErrors.kt"
)


@lru_cache(maxsize=None)
def _declared_codes() -> dict[str, str]:
    """Every public `NAME = "code"` constant in app/errors.py."""
    return {
        name: value
        for name, value in vars(errors).items()
        if name.isupper() and isinstance(value, str) and not name.startswith("_")
    }


@lru_cache(maxsize=None)
def _client_codes() -> frozenset[str]:
    """The code strings the Kotlin client declares.

    Same character class as test_codes_are_stable_tokens, so a code carrying a
    digit cannot slip past test_client_invents_no_codes_of_its_own.
    """
    source = _CLIENT_ERRORS.read_text(encoding="utf-8")
    return frozenset(re.findall(r'const val [A-Z_]+ = "([a-z][a-z0-9_]*)"', source))


def test_codes_are_stable_tokens():
    for name, value in _declared_codes().items():
        assert re.fullmatch(r"[a-z][a-z0-9_]*", value), f"{name} is not a wire-safe token"


def test_no_two_names_share_a_code():
    values = list(_declared_codes().values())
    assert len(values) == len(set(values))


def test_client_branched_codes_are_declared():
    declared = set(_declared_codes().values())
    assert errors.CLIENT_BRANCHED <= declared


@pytest.mark.skipif(
    not _CLIENT_ERRORS.is_file(),
    reason="Android sources not present (backend-only checkout)",
)
def test_client_declares_every_branched_code():
    missing = errors.CLIENT_BRANCHED - _client_codes()
    assert not missing, (
        f"{sorted(missing)} are branched on by the backend contract but absent "
        f"from {_CLIENT_ERRORS.relative_to(_REPO_ROOT)} — rename both sides together"
    )


@pytest.mark.skipif(
    not _CLIENT_ERRORS.is_file(),
    reason="Android sources not present (backend-only checkout)",
)
def test_client_invents_no_codes_of_its_own():
    unknown = _client_codes() - set(_declared_codes().values())
    assert not unknown, (
        f"{sorted(unknown)} are declared in ApiErrors.kt but no longer returned "
        "by this service — the client is branching on a code that cannot arrive"
    )
