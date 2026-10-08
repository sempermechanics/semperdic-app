"""Real Firebase ID token verification, not a monkeypatched stand-in.

Every other test replaces `verify_id_token`, so nothing showed that a token
is checked at all: its signature, audience, issuer, expiry and subject. Here
the tokens are real RS256 JWTs shaped like Firebase's, signed with a key made
for the test, and the only thing replaced is where the public certificates
come from: `google.oauth2.id_token._fetch_certs` hands back this key's
certificate instead of fetching Google's. Everything after that is the code
production runs: `google_auth.verify_id_token` → firebase-admin's claim checks
→ google-auth's signature and time checks, and over HTTP, `deps`.
"""
import base64
import datetime
import json
import time

import fake_firestore
import google.auth
import google.auth.credentials
import google.oauth2.id_token
import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from firebase_admin import _token_gen
from firebase_admin import auth as fb_auth
from google.auth import crypt
from google.auth import jwt as google_jwt

from app import audit, google_auth
import repo_view as repo
from app.config import settings

PROJECT = settings.FIREBASE_PROJECT_ID  # "test-project" under conftest.py
ISSUER = f"https://securetoken.google.com/{PROJECT}"
KID = "test-key-1"


def _key_and_cert():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "securetoken.test")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name).issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=1))
        .sign(key, hashes.SHA256())
    )
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()
    return pem, cert.public_bytes(serialization.Encoding.PEM).decode()


SIGNING_KEY, CERT = _key_and_cert()
OTHER_KEY, _ = _key_and_cert()


@pytest.fixture
def certs(monkeypatch):
    """Google's certificate set, as this test's one key. Records what was asked for."""
    monkeypatch.delenv("FIREBASE_AUTH_EMULATOR_HOST", raising=False)  # emulated = unsigned
    # firebase-admin builds its auth client from Application Default
    # Credentials, which CI does not have. Verifying a token needs none (only
    # the public certificates), so anonymous ones for the test project do.
    monkeypatch.setattr(
        google.auth, "default",
        lambda *_a, **_k: (google.auth.credentials.AnonymousCredentials(), PROJECT),
    )
    asked = []

    def fetch(_request, url):
        asked.append(url)
        return {KID: CERT}

    monkeypatch.setattr(google.oauth2.id_token, "_fetch_certs", fetch)
    return asked


def _claims(**over):
    now = int(time.time())
    claims = {
        "iss": ISSUER, "aud": PROJECT, "sub": "u-real", "auth_time": now - 60,
        "iat": now - 60, "exp": now + 3600,
        "email": "real@example.com", "email_verified": True,
        "firebase": {"sign_in_provider": "password", "identities": {}},
    }
    claims.update(over)
    return {k: v for k, v in claims.items() if v is not None}


def _token(key=SIGNING_KEY, kid=KID, **over) -> str:
    signer = crypt.RSASigner.from_string(key, key_id=kid)
    return google_jwt.encode(signer, _claims(**over)).decode()


def _b64(data: dict) -> str:
    return base64.urlsafe_b64encode(json.dumps(data).encode()).rstrip(b"=").decode()


# ---------------- google_auth.verify_id_token ----------------
def test_a_genuine_token_verifies_against_googles_securetoken_certificates(certs):
    claims = google_auth.verify_id_token(_token())
    assert claims["uid"] == claims["sub"] == "u-real"
    assert claims["email"] == "real@example.com"
    assert certs == [_token_gen.ID_TOKEN_CERT_URI]


def test_a_token_signed_by_another_key_is_refused(certs):
    with pytest.raises(fb_auth.InvalidIdTokenError):
        google_auth.verify_id_token(_token(key=OTHER_KEY))


def test_a_token_whose_claims_were_changed_after_signing_is_refused(certs):
    header, _, signature = _token().split(".")
    forged = f"{header}.{_b64(_claims(sub='u-admin'))}.{signature}"
    with pytest.raises(fb_auth.InvalidIdTokenError):
        google_auth.verify_id_token(forged)


def test_an_expired_token_is_refused_as_expired(certs):
    now = int(time.time())
    with pytest.raises(fb_auth.ExpiredIdTokenError):
        google_auth.verify_id_token(_token(iat=now - 7200, auth_time=now - 7200, exp=now - 3600))


def test_a_token_issued_in_the_future_is_refused(certs):
    now = int(time.time())
    with pytest.raises(fb_auth.InvalidIdTokenError):
        google_auth.verify_id_token(_token(iat=now + 600, exp=now + 4200))


@pytest.mark.parametrize("claim, value", [
    ("aud", "another-project"),
    ("iss", "https://securetoken.google.com/another-project"),
    ("iss", "https://accounts.google.com"),
])
def test_a_token_for_another_project_or_issuer_is_refused(certs, claim, value):
    with pytest.raises(fb_auth.InvalidIdTokenError):
        google_auth.verify_id_token(_token(**{claim: value}))


@pytest.mark.parametrize("sub", [None, "", "x" * 129])
def test_a_token_without_a_usable_subject_is_refused(certs, sub):
    with pytest.raises(fb_auth.InvalidIdTokenError):
        google_auth.verify_id_token(_token(sub=sub))


def test_a_key_id_google_does_not_publish_is_refused(certs):
    with pytest.raises(fb_auth.InvalidIdTokenError):
        google_auth.verify_id_token(_token(kid="not-published"))


@pytest.mark.parametrize("alg", ["none", "HS256"])
def test_an_unsigned_or_symmetric_token_is_refused(certs, alg):
    token = f"{_b64({'alg': alg, 'kid': KID, 'typ': 'JWT'})}.{_b64(_claims())}."
    with pytest.raises(fb_auth.InvalidIdTokenError):
        google_auth.verify_id_token(token)


def test_garbage_is_refused(certs):
    with pytest.raises(fb_auth.InvalidIdTokenError):
        google_auth.verify_id_token("not-a-jwt")


# ---------------- over HTTP, through deps ----------------
@pytest.fixture
def secure(monkeypatch, certs):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    store._data["users"] = {
        "u-real": {"email": "real@example.com", "role": "user", "access_status": "APPROVED"},
    }
    return store


@pytest.mark.asyncio
async def test_a_genuine_token_signs_its_holder_in(secure, client):
    r = await client.get("/v1/me", headers={"Authorization": f"Bearer {_token()}"})
    assert r.status_code == 200, r.text
    assert r.json()["email"] == "real@example.com"


@pytest.mark.asyncio
@pytest.mark.parametrize("token", [
    lambda: _token(key=OTHER_KEY),
    lambda: _token(aud="another-project"),
    lambda: _token(exp=int(time.time()) - 60, iat=int(time.time()) - 3660),
])
async def test_a_forged_foreign_or_expired_token_is_401(secure, client, token):
    r = await client.get("/v1/me", headers={"Authorization": f"Bearer {token()}"})
    assert r.status_code == 401
    assert r.json()["detail"] == "invalid_token"
