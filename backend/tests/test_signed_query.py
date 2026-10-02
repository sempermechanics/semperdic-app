"""A device signature over a path with a query string.

The Android client signs `path?query` exactly as it sends it (the paged
`GET /v1/sessions/{sid}/uploads?page_token=…`). These pin that the backend
verifies the same bytes: the raw, still-encoded query, appended after `?`.
"""
import pytest
from fastapi import HTTPException
from starlette.requests import Request

from app import deps
from tests import test_device_auth as device_auth

# The device-auth fixtures: a real keypair and verified_device wired to it.
keypair = device_auth.keypair
wired = device_auth.wired
_sign = device_auth._sign

PATH = "/v1/sessions/s1/uploads"
QUERY = "page_token=a+b%26c"


def _request(query: str) -> Request:
    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    scope = {
        "type": "http",
        "method": "GET",
        "path": PATH,
        "headers": [],
        "query_string": query.encode(),
    }
    return Request(scope, receive)


async def _verify(priv, query: str, signed_target: str):
    return await deps.verified_device(
        request=_request(query),
        user={"uid": "u1", "email": "a@b.com", "access_status": "APPROVED"},
        x_device_id="d1",
        x_nonce="n1",
        x_signature=_sign(priv, "n1", "GET", signed_target, b""),
    )


async def test_signature_over_the_encoded_query_is_accepted(wired):
    ctx = await _verify(wired, QUERY, f"{PATH}?{QUERY}")
    assert ctx["device"]["deviceId"] == "d1"


async def test_signature_over_the_bare_path_is_refused_when_a_query_is_sent(wired):
    with pytest.raises(HTTPException) as e:
        await _verify(wired, QUERY, PATH)
    assert e.value.status_code == 401


async def test_signature_over_the_decoded_query_is_refused(wired):
    with pytest.raises(HTTPException) as e:
        await _verify(wired, QUERY, f"{PATH}?page_token=a b&c")
    assert e.value.status_code == 401
