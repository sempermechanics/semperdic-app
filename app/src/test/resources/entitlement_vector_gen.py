"""Writes entitlement_vector.json beside this file: an app-side test vector for
EntitlementVerifier (EntitlementVectorTest). TEST ONLY: the keys are generated
fresh on every run and printed into the JSON, and sign nothing real.

    python3 -I app/src/test/resources/entitlement_vector_gen.py

Needs the `cryptography` package. The contract it follows is the backend's
(backend/app entitlement token, `tests/fixtures/entitlement_vector.json`):
compact JWS, header {"alg":"ES256","kid","typ":"semper-ent+jws"}, sorted
compact JSON, base64url without padding, ECDSA P-256 / SHA-256 with the
signature raw r || s (32 + 32 bytes, big-endian). `dkt` is base64url SHA-256
of the device key's SubjectPublicKeyInfo DER.

Besides the good token it signs one token per rejection the app must make,
each with the real signer key, so a rejection is proven to come from the check
named and not from a bad signature.
"""

import base64
import hashlib
import json
import os

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

HERE = os.path.dirname(os.path.abspath(__file__))


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def compact(obj) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":")).encode("utf-8")


def spki(key) -> bytes:
    return key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )


def sign(key, header: dict, payload: dict) -> str:
    signing_input = b64url(compact(header)) + "." + b64url(compact(payload))
    der = key.sign(signing_input.encode("ascii"), ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    return signing_input + "." + b64url(r.to_bytes(32, "big") + s.to_bytes(32, "big"))


def main() -> None:
    signer = ec.generate_private_key(ec.SECP256R1())
    device = ec.generate_private_key(ec.SECP256R1())
    kid = "app-vector-1"
    header = {"alg": "ES256", "kid": kid, "typ": "semper-ent+jws"}
    iat = 1_800_000_000
    payload = {
        "iss": "semper-api",
        "sub": "uid-app-vector",
        "app": "semper",
        "dev": "and-0123456789abcdef",
        "dkt": b64url(hashlib.sha256(spki(device)).digest()),
        "mode": "licensed",
        "features": {"share": True, "backup": True, "maxAnalyses": 500},
        "lic": "lic-app-vector",
        "iat": iat,
        "refreshAfter": iat + 86_400,
        "graceUntil": iat + 7 * 86_400,
        "exp": iat + 30 * 86_400,
    }
    good = sign(signer, header, payload)
    h, p, s = good.split(".")
    tampered_payload = dict(payload, features=dict(payload["features"], maxAnalyses=100_000))
    rejects = {
        "BAD_SIGNATURE": h + "." + b64url(compact(tampered_payload)) + "." + s,
        "BAD_ALG": sign(signer, dict(header, alg="ES384"), payload),
        "BAD_TYP": sign(signer, dict(header, typ="JWT"), payload),
        "UNKNOWN_KID": sign(signer, dict(header, kid="not-pinned"), payload),
        "WRONG_ISSUER": sign(signer, header, dict(payload, iss="someone-else")),
        "WRONG_APP": sign(signer, header, dict(payload, app="materialtesting")),
        "WRONG_DEVICE": sign(signer, header, dict(payload, dev="and-ffffffffffffffff")),
        "WRONG_DEVICE_KEY": sign(signer, header, dict(payload, dkt=b64url(b"\0" * 32))),
        "WRONG_ACCOUNT": sign(signer, header, dict(payload, sub="uid-someone-else")),
        "BAD_CLAIMS": sign(signer, header, {k: v for k, v in payload.items() if k != "graceUntil"}),
        "MALFORMED": h + "." + p,
    }
    vector = {
        "_comment": "TEST ONLY. Written by entitlement_vector_gen.py (fresh keys each run); "
        "the backend's own vector is entitlement_vector_backend.json.",
        "kid": kid,
        "publicKeySpkiB64": base64.b64encode(spki(signer)).decode("ascii"),
        "devicePublicKeySpkiB64": base64.b64encode(spki(device)).decode("ascii"),
        "token": good,
        "payload": payload,
        "rejects": rejects,
    }
    with open(os.path.join(HERE, "entitlement_vector.json"), "w", encoding="utf-8", newline="\n") as out:
        json.dump(vector, out, indent=2)
        out.write("\n")


if __name__ == "__main__":
    main()
