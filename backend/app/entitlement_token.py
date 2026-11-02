"""Signed entitlement tokens: what this account may do, provable offline (A4.1 / A4.3).

`GET /v1/config` adds `entitlement`, a compact JWS the app verifies against
public keys pinned in the build, so it can keep running its licence while
offline for a bounded time. A verified token only ever narrows what the
cached `/v1/config` grants (ADR-023 decision 7), so an edited response
cannot widen past a token the app holds. A token that is absent or does not
verify on the device (a copied one names another device's key, `dkt`) is no
bound at all: the cache decides. With no signing key configured, no token is
issued (TD-215), and the app's offline licence and share rest on the
TLS-delivered, unsigned cache alone.

The contract the Android verifier implements; change it only together with it:

* Three base64url parts without padding, `header.payload.signature`.
* Header `{"alg":"ES256","kid":"<kid>","typ":"semper-ent+jws"}`, serialised as
  compact JSON with sorted keys (the payload is serialised the same way, but
  a verifier must not depend on it).
* Signature: ECDSA P-256 with SHA-256 over the ASCII bytes of
  `b64(header) + "." + b64(payload)`, encoded JOSE-style as raw `r || s`,
  32 bytes each, big-endian (64 bytes), not DER.
* Payload claims:

  ============  ===========================================================
  iss           "semper-api"
  sub           the account's Firebase uid
  app           the app the request came from, as that build pins it
                (`apps.token_claim`): `semper`, `semperclassroom`, or
                `materialtesting` for a Material Testing build from before
                the rename (ADR-030)
  dev           the device id (`X-Device-Id`)
  dkt           base64url(SHA-256(DER SubjectPublicKeyInfo of the device's
                registered public key)), so a token copied to another
                device fails the thumbprint check there
  mode          "demo" | "licensed"
  features      {"share": bool, "backup": bool, "maxAnalyses": int}
  lic           the licence id while the account is licensed, else null
  iat           server time, int seconds
  refreshAfter  iat + 86400: when the app should fetch a new one if online
  graceUntil    how long the app may rely on it offline: iat +
                ENTITLEMENT_OFFLINE_GRACE_DAYS, capped for a licensed account
                at the licence's end plus its grace (timed licence) and at
                the lease's end (floating seat)
  exp           = graceUntil
  ============  ===========================================================

`mode` and `features` are read off the same resolved config the plain JSON
returns, so the token can never say more than the response it rides in.

No token is issued — and nothing fails — when there is no signing key, no
device id, or no active device registered to this account with a key.
"""
from __future__ import annotations

import base64
import binascii
import functools
import hashlib
import json
import logging
import time
from collections.abc import Mapping
from datetime import datetime

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import (
    decode_dss_signature,
    encode_dss_signature,
)

from . import apps, statuses
from .config import settings

log = logging.getLogger("semper.entitlement")

ALG = "ES256"
TYP = "semper-ent+jws"
ISSUER = "semper-api"
REFRESH_AFTER_SECONDS = 86_400
_COORD_BYTES = 32
_DAY = 86_400


class InvalidEntitlement(ValueError):
    """A token `verify` refuses. The message says which check failed."""


# ---------------- encoding ----------------
def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64url_decode(text: str) -> bytes:
    """Strict base64url without padding: anything outside the alphabet, or
    padding, is refused rather than skipped."""
    if "=" in text:
        raise InvalidEntitlement("padded base64url")
    try:
        return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))
    except (binascii.Error, ValueError) as e:
        raise InvalidEntitlement("bad base64url") from e


def _compact_json(obj: dict) -> bytes:
    return json.dumps(obj, separators=(",", ":"), sort_keys=True).encode("utf-8")


def device_key_thumbprint(public_key_pem: str) -> str:
    """`dkt`: base64url(SHA-256(DER SubjectPublicKeyInfo)) of a device's
    registered public key. Re-encoded rather than hashed from the PEM text, so
    line breaks or a trailing newline do not change it; the app hashes its
    Keystore key's `getEncoded()`, which is the same DER."""
    key = serialization.load_pem_public_key(public_key_pem.encode("ascii"))
    der = key.public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return b64url(hashlib.sha256(der).digest())


# ---------------- sign / verify ----------------
def sign(claims: Mapping, private_key: ec.EllipticCurvePrivateKey, kid: str) -> str:
    """The compact JWS for `claims` under `private_key` (P-256), named `kid`."""
    if not isinstance(private_key.curve, ec.SECP256R1):
        raise ValueError("entitlement signing key must be P-256")
    header = {"alg": ALG, "kid": kid, "typ": TYP}
    signing_input = b64url(_compact_json(header)) + "." + b64url(_compact_json(dict(claims)))
    der = private_key.sign(signing_input.encode("ascii"), ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    raw = r.to_bytes(_COORD_BYTES, "big") + s.to_bytes(_COORD_BYTES, "big")
    return signing_input + "." + b64url(raw)


def _public_key(key) -> ec.EllipticCurvePublicKey:
    if isinstance(key, str):
        key = serialization.load_pem_public_key(key.encode("ascii"))
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise InvalidEntitlement("pinned key is not P-256")
    return key


def verify(token: str, public_keys_by_kid: Mapping[str, object], *, now: int | None = None) -> dict:
    """The payload of `token`, or `InvalidEntitlement`.

    What the app does offline, here for the tests and for anyone checking a
    token by hand: the header must be ES256 and this `typ`, its `kid` one of
    `public_keys_by_kid` (a PEM string or a loaded public key), the signature
    64 raw bytes that verify, `iss` ours and `exp` still ahead of `now`.
    """
    parts = token.split(".")
    if len(parts) != 3 or not all(parts):
        raise InvalidEntitlement("not a compact JWS")
    header_bytes = b64url_decode(parts[0])
    try:
        header = json.loads(header_bytes)
    except ValueError as e:
        raise InvalidEntitlement("header is not JSON") from e
    if not isinstance(header, dict):
        raise InvalidEntitlement("header is not an object")
    if header.get("alg") != ALG:
        raise InvalidEntitlement("alg is not ES256")
    if header.get("typ") != TYP:
        raise InvalidEntitlement("wrong typ")
    kid = header.get("kid")
    if not isinstance(kid, str) or kid not in public_keys_by_kid:
        raise InvalidEntitlement("unknown kid")
    key = _public_key(public_keys_by_kid[kid])
    raw = b64url_decode(parts[2])
    if len(raw) != 2 * _COORD_BYTES:
        raise InvalidEntitlement("signature is not raw r||s")
    der = encode_dss_signature(
        int.from_bytes(raw[:_COORD_BYTES], "big"), int.from_bytes(raw[_COORD_BYTES:], "big"),
    )
    try:
        key.verify(der, (parts[0] + "." + parts[1]).encode("ascii"), ec.ECDSA(hashes.SHA256()))
    except InvalidSignature as e:
        raise InvalidEntitlement("bad signature") from e
    payload_bytes = b64url_decode(parts[1])
    try:
        payload = json.loads(payload_bytes)
    except ValueError as e:
        raise InvalidEntitlement("payload is not JSON") from e
    if not isinstance(payload, dict):
        raise InvalidEntitlement("payload is not an object")
    if payload.get("iss") != ISSUER:
        raise InvalidEntitlement("wrong iss")
    exp = payload.get("exp")
    if not isinstance(exp, int) or isinstance(exp, bool):
        raise InvalidEntitlement("exp is not an int")
    if (int(time.time()) if now is None else now) >= exp:
        raise InvalidEntitlement("expired")
    return payload


# ---------------- claims ----------------
def _epoch(value) -> int | None:
    return int(value.timestamp()) if isinstance(value, datetime) else None


def grace_until(config: Mapping, iat: int, offline_grace_days: int) -> int:
    """How long a token issued at `iat` may be relied on offline.

    A licensed account's token never outlives what it is licensed for: a
    timed licence caps it at its end plus the licence's own grace
    (`licenseGraceEndsAt`), a floating seat at its lease (`leaseExpiresAt`;
    once the lease lapses the seat may be someone else's). A demo token has
    nothing to cap."""
    until = iat + max(0, offline_grace_days) * _DAY
    if config.get("mode") != "licensed":
        return until
    caps = [_epoch(config.get("licenseGraceEndsAt"))]
    if config.get("licenseSeating") == "floating":
        caps.append(_epoch(config.get("leaseExpiresAt")))
    return min([until, *(c for c in caps if c is not None)])


def build_claims(
    *,
    uid: str,
    app: str,
    device_id: str,
    device_public_key_pem: str,
    config: Mapping,
    license_id: str | None,
    iat: int,
    offline_grace_days: int,
) -> dict:
    """The payload for this account on this device, from its resolved
    `/v1/config` (`repo.resolve_user_config`)."""
    mode = str(config.get("mode") or "demo")
    until = grace_until(config, iat, offline_grace_days)
    return {
        "iss": ISSUER,
        "sub": uid,
        "app": app,
        "dev": device_id,
        "dkt": device_key_thumbprint(device_public_key_pem),
        "mode": mode,
        "features": {
            "share": bool(config.get("shareEnabled")),
            "backup": bool(config.get("cloudBackupEnabled")),
            "maxAnalyses": int(config.get("maxSessions") or 0),
        },
        "lic": (license_id or None) if mode == "licensed" else None,
        "iat": iat,
        "refreshAfter": iat + REFRESH_AFTER_SECONDS,
        "graceUntil": until,
        "exp": until,
    }


# ---------------- issuing ----------------
@functools.lru_cache(maxsize=2)
def _load_signing_key(pem: str) -> ec.EllipticCurvePrivateKey | None:
    """The configured key, or None (logged once per value) when it is not a
    P-256 private key. An env var set by hand may carry `\\n` for newlines."""
    text = pem.replace("\\n", "\n").strip()
    try:
        key = serialization.load_pem_private_key(text.encode("ascii"), password=None)
    except (ValueError, TypeError, UnicodeEncodeError) as e:
        log.error("ENTITLEMENT_SIGNING_KEY is not a PEM private key (%s); no entitlement "
                  "tokens will be issued", type(e).__name__)
        return None
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        log.error("ENTITLEMENT_SIGNING_KEY is not an EC P-256 key; no entitlement tokens "
                  "will be issued")
        return None
    return key


def signing_key() -> ec.EllipticCurvePrivateKey | None:
    pem = settings.ENTITLEMENT_SIGNING_KEY
    return _load_signing_key(pem) if pem else None


def issue(
    user: Mapping,
    config: Mapping,
    *,
    app: str,
    device_id: str,
    device: Mapping | None,
    now: int | None = None,
) -> str | None:
    """The token for this caller, or None when one cannot be issued: no
    signing key, no device id, or `device` is not an active device of this
    account with a registered key."""
    key = signing_key()
    if key is None or not device_id or not device:
        return None
    pem = device.get("publicKeyPem")
    if (
        device.get("uid") != user.get("uid")
        or device.get("status") != statuses.DEVICE_ACTIVE
        or not isinstance(pem, str)
        or not pem
    ):
        return None
    claims = build_claims(
        uid=str(user.get("uid")),
        app=app,
        device_id=device_id,
        device_public_key_pem=pem,
        config=config,
        # The calling app's own licence (ADR-033), not Semper's mirror.
        license_id=apps.licence_view(dict(user), app).get("licenseId"),
        iat=int(time.time()) if now is None else now,
        offline_grace_days=settings.ENTITLEMENT_OFFLINE_GRACE_DAYS,
    )
    return sign(claims, key, settings.ENTITLEMENT_KID)
