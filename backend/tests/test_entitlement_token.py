"""The signed entitlement on GET /v1/config (A4.1 / A4.3): the token contract
the Android verifier implements, and when the route issues one.

`fixtures/entitlement_vector.json` is the shared test vector: the app's unit
tests verify the same token with the same public key. Regenerate it only with
the contract, `UPDATE_ENTITLEMENT_VECTOR=1 pytest tests/test_entitlement_token.py`,
and copy it to the app in the same change (`test_the_app_copy_is_this_vector`
fails until the copy matches).
"""
from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

import fake_firestore
import repo_view as repo
from app import apps, deps, entitlement_token as ent
from app.config import settings
from key_helpers import public_pem, seeded_p256_key

VECTOR_PATH = Path(__file__).parent / "fixtures" / "entitlement_vector.json"
_APP_TREE = Path(__file__).resolve().parents[2] / "app"
APP_VECTOR_PATH = _APP_TREE / "src" / "test" / "resources" / "entitlement_vector_backend.json"
SIGNER_SEED = "semper-entitlement-vector-v1/signer TEST ONLY"
DEVICE_SEED = "semper-entitlement-vector-v1/device TEST ONLY"
KID = "test-ent-1"
IAT = 1792281600  # 2026-10-18T00:00:00Z

SIGNER = seeded_p256_key(SIGNER_SEED)
DEVICE_KEY = seeded_p256_key(DEVICE_SEED)
DEVICE_PEM = public_pem(DEVICE_KEY)
PINNED = {KID: public_pem(SIGNER)}


def _licensed_config(**over) -> dict:
    return {
        "mode": "licensed", "shareEnabled": True, "cloudBackupEnabled": True,
        "maxSessions": 999, "licenseSeating": "assigned",
        "licenseGraceEndsAt": None, "leaseExpiresAt": None, **over,
    }


def _claims(config=None, *, iat=IAT, grace_days=14, license_id="lic-1") -> dict:
    return ent.build_claims(
        uid="uid-1", app="semper", device_id="and-1", device_public_key_pem=DEVICE_PEM,
        config=config or _licensed_config(), license_id=license_id, iat=iat,
        offline_grace_days=grace_days,
    )


def _parts(token: str) -> list[str]:
    return token.split(".")


def _forge(header: dict, payload: dict, key=SIGNER, *, raw: bool = True) -> str:
    """A token signed by `key` over an arbitrary header, for the refusals."""
    h = ent.b64url(json.dumps(header, separators=(",", ":"), sort_keys=True).encode())
    p = ent.b64url(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode())
    der = key.sign(f"{h}.{p}".encode(), ec.ECDSA(hashes.SHA256()))
    if raw:
        from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

        r, s = decode_dss_signature(der)
        sig = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    else:
        sig = der
    return f"{h}.{p}.{ent.b64url(sig)}"


# ---------------- the encoding ----------------
def test_sign_and_verify_round_trip():
    claims = _claims()
    token = ent.sign(claims, SIGNER, KID)
    assert ent.verify(token, PINNED, now=IAT) == claims


def test_the_header_is_compact_sorted_json_and_names_the_kid():
    token = ent.sign(_claims(), SIGNER, KID)
    header = ent.b64url_decode(_parts(token)[0])
    assert header == b'{"alg":"ES256","kid":"test-ent-1","typ":"semper-ent+jws"}'


def test_no_part_is_padded_and_the_signature_is_raw_r_s():
    token = ent.sign(_claims(), SIGNER, KID)
    assert "=" not in token
    sig = ent.b64url_decode(_parts(token)[2])
    assert len(sig) == 64
    # Not DER: a DER ECDSA signature starts with a SEQUENCE tag.
    assert not (sig[0] == 0x30 and sig[1] in (0x44, 0x45, 0x46))


def test_the_signature_verifies_with_a_plain_ecdsa_verifier():
    """What the app does with java.security: rebuild DER from r||s and verify
    over the ASCII signing input with SHA256withECDSA."""
    from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

    token = ent.sign(_claims(), SIGNER, KID)
    h, p, s = _parts(token)
    raw = ent.b64url_decode(s)
    der = encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big"))
    SIGNER.public_key().verify(der, f"{h}.{p}".encode("ascii"), ec.ECDSA(hashes.SHA256()))


def test_a_rotated_key_is_accepted_beside_the_old_one():
    new_key = seeded_p256_key("rotation TEST ONLY")
    token = ent.sign(_claims(), new_key, "ent-2")
    pinned = {**PINNED, "ent-2": new_key.public_key()}
    assert ent.verify(token, pinned, now=IAT)["sub"] == "uid-1"


# ---------------- the refusals ----------------
def _refused(token: str, match: str, pinned=PINNED, now=IAT):
    with pytest.raises(ent.InvalidEntitlement, match=match):
        ent.verify(token, pinned, now=now)


def test_a_tampered_payload_is_refused():
    h, _, s = _parts(ent.sign(_claims(), SIGNER, KID))
    forged = ent.b64url(json.dumps({**_claims(), "mode": "licensed", "sub": "someone-else"}).encode())
    _refused(f"{h}.{forged}.{s}", "bad signature")


def test_a_tampered_signature_is_refused():
    h, p, s = _parts(ent.sign(_claims(), SIGNER, KID))
    raw = bytearray(ent.b64url_decode(s))
    raw[-1] ^= 0x01
    _refused(f"{h}.{p}.{ent.b64url(bytes(raw))}", "bad signature")


def test_an_unknown_kid_is_refused():
    _refused(ent.sign(_claims(), SIGNER, "ent-unknown"), "unknown kid")


def test_a_key_not_pinned_is_refused_under_a_known_kid():
    impostor = seeded_p256_key("impostor TEST ONLY")
    _refused(ent.sign(_claims(), impostor, KID), "bad signature")


def test_an_expired_token_is_refused():
    claims = _claims()
    token = ent.sign(claims, SIGNER, KID)
    _refused(token, "expired", now=claims["exp"])
    assert ent.verify(token, PINNED, now=claims["exp"] - 1)


@pytest.mark.parametrize("alg", ["none", "ES384", "HS256", "EdDSA"])
def test_any_other_alg_is_refused(alg):
    _refused(_forge({"alg": alg, "kid": KID, "typ": ent.TYP}, _claims()), "alg")


def test_a_wrong_typ_is_refused():
    _refused(_forge({"alg": "ES256", "kid": KID, "typ": "JWT"}, _claims()), "typ")


def test_a_der_signature_is_refused():
    _refused(_forge({"alg": "ES256", "kid": KID, "typ": ent.TYP}, _claims(), raw=False),
             "raw r")


def test_a_foreign_issuer_is_refused():
    _refused(_forge({"alg": "ES256", "kid": KID, "typ": ent.TYP}, {**_claims(), "iss": "x"}),
             "iss")


@pytest.mark.parametrize("exp", [None, "1792281600", True, 1.5])
def test_an_exp_that_is_not_an_int_is_refused(exp):
    payload = {**_claims(), "exp": exp}
    _refused(_forge({"alg": "ES256", "kid": KID, "typ": ent.TYP}, payload), "exp")


@pytest.mark.parametrize("token", ["", "a.b", "a.b.c.d", "..", "a..c"])
def test_a_token_that_is_not_three_parts_is_refused(token):
    _refused(token, "compact JWS")


def test_padding_and_junk_are_refused():
    h, p, s = _parts(ent.sign(_claims(), SIGNER, KID))
    _refused(f"{h}=.{p}.{s}", "padded")
    _refused(f"{h}.{p}.{s[:-1]}*", "base64url|raw r")


def test_a_header_or_payload_that_is_not_an_object_is_refused():
    arr = ent.b64url(b"[1]")
    _refused(f"{arr}.{arr}.{arr}", "header is not an object")
    _refused(f"{ent.b64url(b'{')}.x.y", "header is not JSON")
    header = ent.b64url(b'{"alg":"ES256","kid":"test-ent-1","typ":"semper-ent+jws"}')
    der = SIGNER.sign(f"{header}.{arr}".encode(), ec.ECDSA(hashes.SHA256()))
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

    r, s = decode_dss_signature(der)
    sig = ent.b64url(r.to_bytes(32, "big") + s.to_bytes(32, "big"))
    _refused(f"{header}.{arr}.{sig}", "payload is not an object")
    bad = ent.b64url(b"{nope")
    der = SIGNER.sign(f"{header}.{bad}".encode(), ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    sig = ent.b64url(r.to_bytes(32, "big") + s.to_bytes(32, "big"))
    _refused(f"{header}.{bad}.{sig}", "payload is not JSON")


def test_a_pinned_key_on_another_curve_is_refused():
    p384 = ec.generate_private_key(ec.SECP384R1())
    _refused(ent.sign(_claims(), SIGNER, KID), "P-256", pinned={KID: p384.public_key()})


def test_signing_with_another_curve_is_refused():
    with pytest.raises(ValueError, match="P-256"):
        ent.sign(_claims(), ec.generate_private_key(ec.SECP384R1()), KID)


# ---------------- the claims ----------------
def test_the_claims_carry_the_contract_fields():
    claims = _claims()
    assert set(claims) == {
        "iss", "sub", "app", "dev", "dkt", "mode", "features", "lic",
        "iat", "refreshAfter", "graceUntil", "exp",
    }
    assert claims["iss"] == "semper-api"
    assert claims["features"] == {"share": True, "backup": True, "maxAnalyses": 999}
    assert claims["refreshAfter"] == IAT + 86400
    assert claims["graceUntil"] == claims["exp"] == IAT + 14 * 86400
    assert claims["lic"] == "lic-1"


def test_the_device_thumbprint_is_sha256_of_the_spki_der():
    der = DEVICE_KEY.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    expected = base64.urlsafe_b64encode(hashlib.sha256(der).digest()).rstrip(b"=").decode()
    assert ent.device_key_thumbprint(DEVICE_PEM) == expected
    # The PEM's line breaks are not part of the key.
    assert ent.device_key_thumbprint(DEVICE_PEM.strip() + "\n\n") == expected


def test_a_timed_licence_caps_the_offline_grace_at_its_end_plus_grace():
    ends = datetime.fromtimestamp(IAT, tz=timezone.utc) + timedelta(days=5)
    claims = _claims(_licensed_config(licenseGraceEndsAt=ends))
    assert claims["graceUntil"] == claims["exp"] == int(ends.timestamp())


def test_a_licence_ending_after_the_offline_window_does_not_extend_it():
    ends = datetime.fromtimestamp(IAT, tz=timezone.utc) + timedelta(days=400)
    assert _claims(_licensed_config(licenseGraceEndsAt=ends))["exp"] == IAT + 14 * 86400


def test_a_floating_seat_caps_the_offline_grace_at_its_lease():
    lease = datetime.fromtimestamp(IAT, tz=timezone.utc) + timedelta(hours=8)
    config = _licensed_config(licenseSeating="floating", leaseExpiresAt=lease)
    assert _claims(config)["exp"] == IAT + 8 * 3600


def test_a_demo_token_is_not_capped_and_names_no_licence():
    past = datetime.fromtimestamp(IAT, tz=timezone.utc) - timedelta(days=1)
    config = {"mode": "demo", "shareEnabled": False, "cloudBackupEnabled": False,
              "maxSessions": 25, "licenseGraceEndsAt": past}
    claims = _claims(config, grace_days=3)
    assert claims["mode"] == "demo"
    assert claims["lic"] is None
    assert claims["features"] == {"share": False, "backup": False, "maxAnalyses": 25}
    assert claims["exp"] == IAT + 3 * 86400


# ---------------- the configured key ----------------
def _p256_pem(key=SIGNER) -> str:
    return key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()


def test_the_signing_key_loads_from_pem_and_from_escaped_newlines(monkeypatch):
    pem = _p256_pem()
    monkeypatch.setattr(settings, "ENTITLEMENT_SIGNING_KEY", pem)
    assert ent.signing_key().private_numbers() == SIGNER.private_numbers()
    monkeypatch.setattr(settings, "ENTITLEMENT_SIGNING_KEY", pem.replace("\n", "\\n"))
    assert ent.signing_key().private_numbers() == SIGNER.private_numbers()
    monkeypatch.setattr(settings, "ENTITLEMENT_SIGNING_KEY", "")
    assert ent.signing_key() is None


def test_a_bad_signing_key_issues_nothing_and_says_so(monkeypatch, caplog):
    p384 = ec.generate_private_key(ec.SECP384R1())
    for bad in ("not a key", _p256_pem(p384)):
        monkeypatch.setattr(settings, "ENTITLEMENT_SIGNING_KEY", bad)
        with caplog.at_level(logging.ERROR, logger="semper.entitlement"):
            assert ent.signing_key() is None
    assert "PEM private key" in caplog.text and "P-256" in caplog.text


def test_issue_asks_for_an_active_device_of_this_account(monkeypatch):
    monkeypatch.setattr(settings, "ENTITLEMENT_SIGNING_KEY", _p256_pem())
    user = {"uid": "uid-1", "licenseId": "lic-1"}
    device = {"uid": "uid-1", "status": "ACTIVE", "publicKeyPem": DEVICE_PEM}
    config = _licensed_config()
    assert ent.issue(user, config, app="semper", device_id="and-1", device=device, now=IAT)
    for other in ({**device, "uid": "uid-2"}, {**device, "status": "REVOKED"},
                  {**device, "publicKeyPem": ""}, {**device, "publicKeyPem": None}, None):
        assert ent.issue(user, config, app="semper", device_id="and-1", device=other) is None
    assert ent.issue(user, config, app="semper", device_id="", device=device) is None
    monkeypatch.setattr(settings, "ENTITLEMENT_SIGNING_KEY", "")
    assert ent.issue(user, config, app="semper", device_id="and-1", device=device) is None


# ---------------- the shared vector ----------------
def _vector() -> dict:
    ends = datetime.fromtimestamp(IAT, tz=timezone.utc) + timedelta(days=5)
    claims = ent.build_claims(
        uid="uid-test-0001", app="semper", device_id="and-7f3e9a1c2b4d6e8f",
        device_public_key_pem=DEVICE_PEM,
        config=_licensed_config(licenseGraceEndsAt=ends),
        license_id=hashlib.sha256(b"SEMP-TEST-ONLY-VECTOR").hexdigest(),
        iat=IAT, offline_grace_days=14,
    )
    token = ent.sign(claims, SIGNER, KID)
    h, _, s = _parts(token)
    tampered = ent.b64url(json.dumps({**claims, "mode": "licensed", "features": {
        **claims["features"], "maxAnalyses": 100000}}, separators=(",", ":"),
        sort_keys=True).encode())
    return {
        "_comment": (
            "TEST ONLY. Entitlement token test vector shared by the backend "
            "(tests/test_entitlement_token.py) and the Android verifier. The keys "
            "are derived from the public seeds below (tests/key_helpers.seeded_p256_key: "
            "sha256(seed) mod (n-1) + 1 on P-256) and must never sign anything real."
        ),
        "signerKeySeed": SIGNER_SEED,
        "deviceKeySeed": DEVICE_SEED,
        "kid": KID,
        "publicKeyPem": PINNED[KID],
        "devicePublicKeyPem": DEVICE_PEM,
        "token": token,
        "header": json.loads(ent.b64url_decode(h)),
        "payload": claims,
        "verifyAt": IAT,
        "expiredAt": claims["exp"],
        "tamperedToken": f"{h}.{tampered}.{s}",
    }


@pytest.mark.skipif(not os.environ.get("UPDATE_ENTITLEMENT_VECTOR"), reason="regenerates the vector")
def test_regenerate_the_vector():
    VECTOR_PATH.parent.mkdir(exist_ok=True)
    VECTOR_PATH.write_text(json.dumps(_vector(), indent=2) + "\n", encoding="utf-8", newline="\n")


def test_the_committed_vector_verifies_and_decodes_to_its_payload():
    vector = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))
    assert vector["publicKeyPem"] == PINNED[vector["kid"]]
    assert vector["devicePublicKeyPem"] == DEVICE_PEM
    payload = ent.verify(vector["token"], {vector["kid"]: vector["publicKeyPem"]},
                         now=vector["verifyAt"])
    assert payload == vector["payload"]
    assert json.loads(ent.b64url_decode(_parts(vector["token"])[0])) == vector["header"]
    assert payload["dkt"] == ent.device_key_thumbprint(vector["devicePublicKeyPem"])
    # The licence ends five days after issue, so the grace is capped there.
    assert payload["exp"] == payload["graceUntil"] == vector["verifyAt"] + 5 * 86400
    assert len(ent.b64url_decode(_parts(vector["token"])[2])) == 64
    with pytest.raises(ent.InvalidEntitlement, match="expired"):
        ent.verify(vector["token"], {vector["kid"]: vector["publicKeyPem"]}, now=vector["expiredAt"])
    with pytest.raises(ent.InvalidEntitlement, match="bad signature"):
        ent.verify(vector["tamperedToken"], {vector["kid"]: vector["publicKeyPem"]},
                   now=vector["verifyAt"])


def test_the_vector_is_what_this_code_would_build():
    """Everything but the signature (ECDSA is randomised) is reproducible."""
    vector, built = json.loads(VECTOR_PATH.read_text(encoding="utf-8")), _vector()
    for key in ("kid", "publicKeyPem", "devicePublicKeyPem", "header", "payload", "verifyAt"):
        assert vector[key] == built[key], key
    assert _parts(vector["token"])[:2] == _parts(built["token"])[:2]


@pytest.mark.skipif(not _APP_TREE.is_dir(), reason="app/ not present (backend-only checkout)")
def test_the_app_copy_is_this_vector():
    """The app's EntitlementVectorTest reads its own copy of the vector, so a
    vector regenerated here and left uncopied would keep both suites green."""
    assert APP_VECTOR_PATH.is_file(), f"{APP_VECTOR_PATH} is missing"
    assert APP_VECTOR_PATH.read_bytes() == VECTOR_PATH.read_bytes(), (
        "app/src/test/resources/entitlement_vector_backend.json differs from "
        "backend/tests/fixtures/entitlement_vector.json: copy the backend file over it"
    )


# ---------------- over HTTP ----------------
@pytest.fixture
def issuing(monkeypatch):
    """The dev caller with a registered device and a signing key configured."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(settings, "ENTITLEMENT_SIGNING_KEY", _p256_pem())
    monkeypatch.setattr(settings, "ENTITLEMENT_KID", KID)
    store._data["users"] = {"dev-user": {"email": "dev@local", "access_status": "APPROVED"}}
    store._data["devices"] = {
        "dev-device": {"uid": "dev-user", "status": "ACTIVE", "publicKeyPem": DEVICE_PEM},
        "other-device": {"uid": "someone", "status": "ACTIVE", "publicKeyPem": DEVICE_PEM},
    }
    return store


async def test_config_carries_a_token_that_agrees_with_the_plain_json(issuing, client):
    r = await client.get("/v1/config", headers={"X-Device-Id": "dev-device",
                                                "X-App-Id": "com.sempermechanics.semper"})
    assert r.status_code == 200, r.text
    body = r.json()
    payload = ent.verify(body["entitlement"], PINNED)
    assert payload["sub"] == "dev-user"
    assert payload["dev"] == "dev-device"
    assert payload["app"] == "semper"
    assert payload["dkt"] == ent.device_key_thumbprint(DEVICE_PEM)
    assert payload["mode"] == body["mode"]
    assert payload["features"] == {
        "share": body["shareEnabled"], "backup": body["cloudBackupEnabled"],
        "maxAnalyses": body["maxSessions"],
    }
    assert payload["exp"] - payload["iat"] == settings.ENTITLEMENT_OFFLINE_GRACE_DAYS * 86400


@pytest.mark.parametrize(("app_id", "claim"), [
    ("com.sempermechanics.semperclassroom", "semperclassroom"),
    # A build from before the rename pins the old name (ADR-030,
    # `EntitlementPins.kt`) and refuses any other as WRONG_APP.
    ("com.sempermechanics.materialtesting", "materialtesting"),
    ("com.indicvision.semper.materialtesting", "materialtesting"),
    ("com.sempermechanics.semper", "semper"),
    ("", "semper"),
])
async def test_config_names_the_app_that_asked(issuing, client, app_id, claim):
    headers = {"X-Device-Id": "dev-device"}
    if app_id:
        headers["X-App-Id"] = app_id
    r = await client.get("/v1/config", headers=headers)
    assert ent.verify(r.json()["entitlement"], PINNED)["app"] == claim


async def test_a_demo_account_gets_a_demo_token(issuing, client, monkeypatch):
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "mode": "demo", "plan": "demo"})
    body = (await client.get("/v1/config", headers={"X-Device-Id": "dev-device"})).json()
    payload = ent.verify(body["entitlement"], PINNED)
    assert (payload["mode"], payload["lic"]) == ("demo", None)
    assert payload["features"]["maxAnalyses"] == body["maxSessions"] == settings.DEMO_MAX_ANALYSES


@pytest.mark.parametrize("headers", [
    {},  # a browser: no device
    {"X-Device-Id": "unregistered"},
    {"X-Device-Id": "other-device"},  # someone else's phone
    {"X-Device-Id": "bad id with spaces"},  # malformed, logged and omitted
])
async def test_config_omits_the_token_when_it_cannot_be_issued(issuing, client, headers):
    r = await client.get("/v1/config", headers=headers)
    assert r.status_code == 200
    assert "entitlement" not in r.json()


async def test_config_omits_the_token_without_a_signing_key(issuing, client, monkeypatch):
    monkeypatch.setattr(settings, "ENTITLEMENT_SIGNING_KEY", "")
    r = await client.get("/v1/config", headers={"X-Device-Id": "dev-device"})
    assert r.status_code == 200
    assert "entitlement" not in r.json()


async def test_a_failed_device_lookup_omits_the_token_and_logs(issuing, client, monkeypatch, caplog):
    def down(_device_id):
        raise RuntimeError("firestore down")

    repo.patch(monkeypatch, "get_device", down)
    with caplog.at_level(logging.WARNING, logger="semper"):
        r = await client.get("/v1/config", headers={"X-Device-Id": "dev-device"})
    assert r.status_code == 200
    assert "entitlement" not in r.json()
    events = [json.loads(rec.message) for rec in caplog.records
              if rec.message.startswith("{") and '"entitlement_not_issued"' in rec.message]
    assert events and events[0]["errorCode"] == "RuntimeError"


def test_the_lic_claim_is_the_calling_apps_own_licence(monkeypatch):
    """`lic` used to be the unsuffixed (Semper) `licenseId` whichever app
    asked, so a Classroom token named Semper's licence (L1-6 / L10-3)."""
    monkeypatch.setattr(settings, "ENTITLEMENT_SIGNING_KEY", _p256_pem())
    user = {"uid": "uid-1", "licenseId": "lic-semper", "licenseApp": "semper",
            **apps.written_fields("licenseId", apps.SEMPER_CLASSROOM, "lic-classroom")}
    device = {"uid": "uid-1", "status": "ACTIVE", "publicKeyPem": DEVICE_PEM}

    def lic(app: str):
        token = ent.issue(user, _licensed_config(), app=app, device_id="and-1", device=device, now=IAT)
        payload = _parts(token)[1]
        return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))["lic"]

    assert lic("semper") == "lic-semper"
    assert lic("semperclassroom") == "lic-classroom"
    # A build from before the rename names the app by its old id (ADR-030).
    assert lic("materialtesting") == "lic-classroom"
    # A Classroom caller with no licence of its own, on a split account, names none.
    for name in apps.written_fields("licenseId", apps.SEMPER_CLASSROOM, None):
        del user[name]
    assert lic("semperclassroom") is None
