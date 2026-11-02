"""HTTP authz tests with DEV_INSECURE_AUTH off — 401/403 trust boundaries."""
import base64
import hashlib
import io
import zipfile

import fake_firestore
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app import audit, deps, drive, statuses
import repo_view as repo
from app.config import settings


@pytest.fixture
def secure(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "ADMIN_EMAILS", {"admin@sempermechanics.com"})
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    return store


def _pem_and_priv():
    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    return priv, pem


def _sign(priv, nonce, method, path, body: bytes) -> str:
    msg = (nonce + method + path).encode() + hashlib.sha256(body).digest()
    return base64.b64encode(priv.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()


def _approved_user(**extra):
    return {
        "uid": "u-approved",
        "email": "user@example.com",
        "role": "user",
        "access_status": "APPROVED",
        "activeDeviceId": "dev-1",
        **extra,
    }


@pytest.mark.asyncio
async def test_missing_bearer_is_401(secure, client, monkeypatch):
    monkeypatch.setattr(deps, "verify_id_token", lambda *_: (_ for _ in ()).throw(AssertionError()))
    r = await client.get("/v1/me")
    assert r.status_code == 401
    assert r.json()["detail"] == "missing_bearer"


@pytest.mark.asyncio
async def test_invalid_token_is_401(secure, client, monkeypatch):
    def boom(_token):
        raise ValueError("bad token")

    monkeypatch.setattr(deps, "verify_id_token", boom)
    r = await client.get("/v1/me", headers={"Authorization": "Bearer not-a-jwt"})
    assert r.status_code == 401
    assert r.json()["detail"] == "invalid_token"


@pytest.mark.asyncio
async def test_pending_user_is_403(secure, client, monkeypatch):
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": "u-pending", "email": "p@e.com", "email_verified": True},
    )
    secure._data["users"] = {
        "u-pending": {
            "email": "p@e.com", "role": "user", "access_status": "PENDING",
        },
    }
    repo.patch(monkeypatch, "get_or_create_user",
        lambda claims, device_id=None: {**secure._data["users"]["u-pending"], "uid": "u-pending"},
    )
    r = await client.get("/v1/me", headers={"Authorization": "Bearer ok"})
    assert r.status_code == 403
    assert r.json()["detail"] == "not_approved"


@pytest.mark.asyncio
async def test_non_admin_cannot_list_users(secure, client, monkeypatch):
    monkeypatch.setattr(deps, "verify_id_token", lambda _t: {"sub": "u1", "email": "u@e.com"})
    repo.patch(monkeypatch, "get_or_create_user", lambda claims, device_id=None: _approved_user())
    r = await client.get("/v1/admin/users", headers={"Authorization": "Bearer ok"})
    assert r.status_code == 403
    assert r.json()["detail"] == "not_admin"


@pytest.mark.asyncio
async def test_cross_user_session_is_404(secure, client, monkeypatch):
    monkeypatch.setattr(deps, "verify_id_token", lambda _t: {"sub": "u-approved", "email": "u@e.com"})
    repo.patch(monkeypatch, "get_or_create_user", lambda claims, device_id=None: _approved_user())
    secure._data["sessions"] = {
        "s-other": {"uid": "someone-else", "status": "COMPLETED", "localSessionId": "x"},
    }
    r = await client.get("/v1/sessions/s-other/files", headers={"Authorization": "Bearer ok"})
    assert r.status_code == 404
    assert r.json()["detail"] == "session_not_found"


def _admin_token(secure, monkeypatch, claims: dict):
    """An admin signing in with exactly these extra token claims."""
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": "admin-1", "email": "admin@sempermechanics.com",
                    "email_verified": True, **claims},
    )
    repo.patch(monkeypatch, "get_or_create_user",
        lambda c, device_id=None: {
            "uid": "admin-1", "email": "admin@sempermechanics.com",
            "role": "admin", "access_status": "APPROVED", "activeDeviceId": "adev",
        },
    )
    secure._data["users"] = {
        "admin-1": {
            "email": "admin@sempermechanics.com", "role": "admin", "access_status": "APPROVED",
        },
        "target": {"email": "t@e.com", "role": "user", "access_status": "PENDING"},
    }


@pytest.mark.asyncio
async def test_admin_mutation_rejects_a_bare_id_token(secure, client, monkeypatch):
    """The property that must survive the console: a token on its own is not
    enough to change anything, however it was obtained.

    The staff console replaced device attestation with a second factor for
    browser callers, so the *code* changed — but a password-only session, which
    is what a stolen or replayed token usually is, still gets nowhere.
    """
    _admin_token(secure, monkeypatch, {})
    r = await client.post(
        "/v1/admin/users/target/approve",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code == 403
    assert r.json()["detail"] == "mfa_required"
    assert secure._data["users"]["target"]["access_status"] == "PENDING"


@pytest.mark.asyncio
async def test_admin_mutation_rejects_a_stale_second_factor(secure, client, monkeypatch):
    """Enrolling in MFA once does not buy authority for the token's whole
    lifetime. The freshness window is what bounds a leaked token."""
    import time as _time

    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    _admin_token(secure, monkeypatch, {
        "firebase": {"sign_in_second_factor": "phone"},
        "auth_time": _time.time() - 3600,
    })
    r = await client.post(
        "/v1/admin/users/target/approve",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code == 403
    assert r.json()["detail"] == "reauth_required"
    assert secure._data["users"]["target"]["access_status"] == "PENDING"


@pytest.mark.asyncio
async def test_admin_mutation_rejects_a_token_that_will_not_state_its_age(
    secure, client, monkeypatch,
):
    """No auth_time is treated as too old, not as fresh — a token that will not
    say when it was minted cannot satisfy a freshness requirement."""
    _admin_token(secure, monkeypatch, {"firebase": {"sign_in_second_factor": "phone"}})
    r = await client.post(
        "/v1/admin/users/target/approve",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code == 403
    assert r.json()["detail"] == "reauth_required"
    assert secure._data["users"]["target"]["access_status"] == "PENDING"


@pytest.mark.asyncio
async def test_admin_mutation_accepts_a_fresh_second_factor(secure, client, monkeypatch):
    """The console path itself: 2FA plus a recent sign-in, from a browser that
    can produce no device signature at all."""
    import time as _time

    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    _admin_token(secure, monkeypatch, {
        "firebase": {"sign_in_second_factor": "phone"},
        "auth_time": _time.time() - 60,
    })
    r = await client.post(
        "/v1/admin/users/target/approve",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code == 200, r.text
    assert secure._data["users"]["target"]["access_status"] == "APPROVED"


@pytest.mark.asyncio
async def test_the_browser_path_can_be_withdrawn_entirely(secure, client, monkeypatch):
    """ADMIN_WEB_MFA_ENABLED=0 restores attestation-only admin, so a deployment
    that does not use the console is not carrying its weaker tier."""
    import time as _time

    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", False)
    _admin_token(secure, monkeypatch, {
        "firebase": {"sign_in_second_factor": "phone"},
        "auth_time": _time.time(),
    })
    r = await client.post(
        "/v1/admin/users/target/approve",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code == 400
    assert secure._data["users"]["target"]["access_status"] == "PENDING"


@pytest.mark.asyncio
async def test_admin_mutation_with_valid_device_attestation(secure, client, monkeypatch):
    priv, pem = _pem_and_priv()
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": "admin-1", "email": "admin@sempermechanics.com", "email_verified": True},
    )
    repo.patch(monkeypatch, "get_or_create_user",
        lambda claims, device_id=None: {
            "uid": "admin-1", "email": "admin@sempermechanics.com",
            "role": "admin", "access_status": "APPROVED", "activeDeviceId": "adev",
        },
    )
    secure._data["users"] = {
        "admin-1": {
            "email": "admin@sempermechanics.com", "role": "admin", "access_status": "APPROVED",
        },
        "target": {"email": "t@e.com", "role": "user", "access_status": "PENDING"},
    }
    secure._data["devices"] = {
        # Registered from a second-factor sign-in: only such a device steps
        # up for staff (ADR-010, device trust).
        "adev": {"uid": "admin-1", "status": "ACTIVE", "publicKeyPem": pem,
                 "stepUpEligible": True},
    }
    repo.patch(monkeypatch, "consume_nonce", lambda *a, **k: True)

    path = "/v1/admin/users/target/approve"
    body = b""
    nonce = "nonce-admin-1"
    sig = _sign(priv, nonce, "POST", path, body)
    r = await client.post(
        path,
        headers={
            "Authorization": "Bearer ok",
            "X-Device-Id": "adev",
            "X-Nonce": nonce,
            "X-Signature": sig,
        },
        content=body,
    )
    assert r.status_code == 200
    assert secure._data["users"]["target"]["access_status"] == "APPROVED"


# --- the same step-up, one tier down -----------------------------------------
# Changing which device a licence is bound to is the holder's own operation,
# not staff work, and it is reachable from a browser. It therefore sits on
# `attested_or_mfa_user`: the step-up machinery of the admin cases above with
# `current_user` beneath it instead of `admin_user`.


def _holder_token(secure, monkeypatch, claims: dict) -> str:
    """An ordinary licence holder signing in with exactly these extra claims.

    Returns the id of the individual licence they hold, bound to `old-phone`.
    """
    secure._data["users"] = {
        "holder-1": {"uid": "holder-1", "email": "holder@lab.org",
                     "access_status": "APPROVED", "emailVerified": True},
    }
    minted = repo.create_individual_license(
        email_lock="holder@lab.org", created_by_uid="admin",
    )
    license_id = minted["license"]["id"]
    user = repo.ensure_entitlement(dict(secure._data["users"]["holder-1"]), None)
    repo.revalidate_device_lock(user, "old-phone")
    stored = dict(secure._data["users"]["holder-1"])
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": "holder-1", "email": "holder@lab.org",
                    "email_verified": True, **claims},
    )
    repo.patch(monkeypatch, "get_or_create_user", lambda c, device_id=None: stored)
    return license_id


@pytest.mark.asyncio
async def test_device_change_rejects_a_bare_id_token(secure, client, monkeypatch):
    """A stolen token must not be able to move a licence onto the thief's
    device — which is the whole reason this route is not plain USER tier."""
    license_id = _holder_token(secure, monkeypatch, {})

    r = await client.post("/v1/licenses/unbind", headers={"Authorization": "Bearer ok"})

    assert r.status_code == 403
    assert r.json()["detail"] == "mfa_required"
    assert secure._data["licenses"][license_id]["deviceIdLock"] == "old-phone"


@pytest.mark.asyncio
async def test_device_change_rejects_a_stale_second_factor(secure, client, monkeypatch):
    import time as _time

    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    license_id = _holder_token(secure, monkeypatch, {
        "firebase": {"sign_in_second_factor": "totp"},
        "auth_time": _time.time() - 3600,
    })

    r = await client.post("/v1/licenses/unbind", headers={"Authorization": "Bearer ok"})

    assert r.status_code == 403
    assert r.json()["detail"] == "reauth_required"
    assert secure._data["licenses"][license_id]["deviceIdLock"] == "old-phone"


@pytest.mark.asyncio
async def test_device_change_accepts_a_fresh_second_factor(secure, client, monkeypatch):
    """The account dashboard's path: 2FA plus a recent sign-in, from a browser
    that can produce no device signature at all."""
    import time as _time

    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    license_id = _holder_token(secure, monkeypatch, {
        "firebase": {"sign_in_second_factor": "totp"},
        "auth_time": _time.time() - 60,
    })

    r = await client.post("/v1/licenses/unbind", headers={"Authorization": "Bearer ok"})

    assert r.status_code == 200, r.text
    assert r.json()["previousDeviceId"] == "old-phone"
    assert secure._data["licenses"][license_id]["deviceIdLock"] == ""


@pytest.mark.asyncio
async def test_the_holder_step_up_authorises_nothing_staff_can_do(secure, client, monkeypatch):
    """The tier below the step-up is what differs, and it still binds: a
    holder who has proved a second factor is still not an operator."""
    import time as _time

    license_id = _holder_token(secure, monkeypatch, {
        "firebase": {"sign_in_second_factor": "totp"},
        "auth_time": _time.time() - 60,
    })

    r = await client.patch(
        f"/v1/admin/licenses/{license_id}",
        json={"clearDeviceLock": True},
        headers={"Authorization": "Bearer ok"},
    )

    assert r.status_code == 403
    assert r.json()["detail"] == "not_admin"
    assert secure._data["licenses"][license_id]["deviceIdLock"] == "old-phone"


# --- the second thing that tier exists for -----------------------------------
# Pulling an analysis out through a browser. `GET /v1/files/{id}/content` is
# device-attested and stays that way; the bundle route is the same bytes at
# the step-up tier, so the pair below is what stops a bare token draining an
# account from anywhere.


def _holder_with_an_analysis(secure, monkeypatch, claims: dict) -> str:
    """The holder above, plus one uploaded analysis they own."""
    _holder_token(secure, monkeypatch, claims)
    sid = "sess-h1"
    secure._data["sessions"] = {sid: {"uid": "holder-1", "specimen": "coupon-1",
                                      "status": statuses.SESSION_COMPLETED}}
    secure._data["files"] = {f"{sid}_bundle_Session.zip": {
        "sessionId": sid, "uid": "holder-1", "role": "bundle", "name": "Session.zip",
        "sizeBytes": 4, "sha256": "aa", "status": statuses.FILE_COMPLETED,
        "driveFileId": "drive-1",
    }}
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(drive, "open_download",
                        lambda token, fid, **kw: _Chunks(b"DATA"))
    return sid


class _Chunks:
    def __init__(self, payload):
        self._payload = payload

    def iter_chunks(self, chunk_size: int = 0):
        yield self._payload


@pytest.mark.asyncio
async def test_a_bundle_is_refused_to_a_bare_id_token(secure, client, monkeypatch):
    sid = _holder_with_an_analysis(secure, monkeypatch, {})

    r = await client.get(f"/v1/sessions/{sid}/bundle",
                         headers={"Authorization": "Bearer ok"})

    assert r.status_code == 403
    assert r.json()["detail"] == "mfa_required"


@pytest.mark.asyncio
async def test_a_bundle_reaches_a_browser_that_proved_a_second_factor(
    secure, client, monkeypatch,
):
    """No device signature is possible here at all — that is the point."""
    import time as _time

    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    sid = _holder_with_an_analysis(secure, monkeypatch, {
        "firebase": {"sign_in_second_factor": "totp"},
        "auth_time": _time.time() - 60,
    })

    r = await client.get(f"/v1/sessions/{sid}/bundle",
                         headers={"Authorization": "Bearer ok"})

    assert r.status_code == 200, r.text
    assert zipfile.ZipFile(io.BytesIO(r.content)).read("bundle/Session.zip") == b"DATA"


# --- whole-licence revoke: tighter freshness window --------------------------
# Ordinary admin mutations accept ADMIN_WEB_REAUTH_SECONDS; revoke uses
# ADMIN_WEB_REVOKE_REAUTH_SECONDS so a long-lived MFA session cannot wipe a
# customer without a fresh password/Google re-auth plus TOTP.


def _license_for_revoke(secure, monkeypatch, claims: dict) -> str:
    _admin_token(secure, monkeypatch, claims)
    minted = repo.create_individual_license(
        email_lock="revoke-target@lab.org", created_by_uid="admin-1",
    )
    return minted["license"]["id"]


@pytest.mark.asyncio
async def test_license_revoke_rejects_a_bare_id_token(secure, client, monkeypatch):
    license_id = _license_for_revoke(secure, monkeypatch, {})
    r = await client.post(
        f"/v1/admin/licenses/{license_id}/revoke",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code == 403
    assert r.json()["detail"] == "mfa_required"
    assert secure._data["licenses"][license_id]["status"] != "revoked"


@pytest.mark.asyncio
async def test_license_revoke_rejects_mfa_that_is_only_dashboard_fresh(
    secure, client, monkeypatch,
):
    """Five minutes old is fine for mint/approve; revoke demands a tighter
    window so the console's password+TOTP step-up is not optional."""
    import time as _time

    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    monkeypatch.setattr(settings, "ADMIN_WEB_REVOKE_REAUTH_SECONDS", 120)
    license_id = _license_for_revoke(secure, monkeypatch, {
        "firebase": {"sign_in_second_factor": "totp"},
        "auth_time": _time.time() - 300,
    })
    r = await client.post(
        f"/v1/admin/licenses/{license_id}/revoke",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code == 403
    assert r.json()["detail"] == "reauth_required"
    assert secure._data["licenses"][license_id]["status"] != "revoked"


@pytest.mark.asyncio
async def test_license_revoke_accepts_a_fresh_second_factor(secure, client, monkeypatch):
    import time as _time

    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(settings, "ADMIN_WEB_REVOKE_REAUTH_SECONDS", 120)
    license_id = _license_for_revoke(secure, monkeypatch, {
        "firebase": {"sign_in_second_factor": "totp"},
        "auth_time": _time.time() - 30,
    })
    r = await client.post(
        f"/v1/admin/licenses/{license_id}/revoke",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code == 200, r.text
    assert secure._data["licenses"][license_id]["status"] == "revoked"


# --- institution IT console: same MFA as other dashboards --------------------


def _institution_it_token(secure, monkeypatch, claims: dict) -> str:
    """IT for one institution licence; returns that licence id."""
    license_id = "lic-it-mfa"
    secure._data["licenses"] = {
        license_id: {
            "kind": "institution", "plan": "professional", "status": "active",
            "domainLock": "university.edu", "adminEmails": ["it@university.edu"],
            "keyPrefix": "SEMP-IT01", "seatsUsed": 0, "maxSeats": 5,
        },
    }
    secure._data[f"licenses/{license_id}/seats"] = {}
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {
            "sub": "it-1", "email": "it@university.edu", "email_verified": True,
            **claims,
        },
    )
    repo.patch(monkeypatch, "get_or_create_user",
        lambda c, device_id=None: {
            "uid": "it-1", "email": "it@university.edu", "role": "user",
            "access_status": "APPROVED", "emailVerified": True,
        },
    )
    return license_id


@pytest.mark.asyncio
async def test_institution_seats_reject_a_bare_id_token(secure, client, monkeypatch):
    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    license_id = _institution_it_token(secure, monkeypatch, {})
    r = await client.get(
        f"/v1/institutions/licenses/{license_id}/seats",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code == 403
    assert r.json()["detail"] == "mfa_required"


@pytest.mark.asyncio
async def test_institution_seats_accept_a_fresh_second_factor(secure, client, monkeypatch):
    import time as _time

    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    license_id = _institution_it_token(secure, monkeypatch, {
        "firebase": {"sign_in_second_factor": "totp"},
        "auth_time": _time.time() - 60,
    })
    r = await client.get(
        f"/v1/institutions/licenses/{license_id}/seats",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code == 200, r.text
    assert r.json()["seats"] == []


@pytest.mark.parametrize("eligible, status", [(False, 403), (True, 200)])
@pytest.mark.asyncio
async def test_institution_seats_take_only_a_device_registered_with_a_second_factor(
    secure, client, monkeypatch, eligible, status,
):
    """IT is a staff-like tier: its phone path needs a device registered from
    a second-factor sign-in, as the operator's does (ADR-010, device trust)."""
    import time as _time

    license_id = _institution_it_token(secure, monkeypatch, {})
    repo.patch(monkeypatch, "get_or_create_user",
        lambda c, device_id=None: {
            "uid": "it-1", "email": "it@university.edu", "role": "user",
            "access_status": "APPROVED", "emailVerified": True, "activeDeviceId": "it-phone",
        },
    )
    priv, pem = _pem_and_priv()
    secure._data["devices"] = {"it-phone": {"uid": "it-1", "status": "ACTIVE",
                                            "publicKeyPem": pem, "stepUpEligible": eligible}}
    path = f"/v1/institutions/licenses/{license_id}/seats"
    nonce = f"t1.{int(_time.time())}.{'C' * 30}"
    r = await client.get(path, headers={
        "Authorization": "Bearer ok", "X-Device-Id": "it-phone", "X-Nonce": nonce,
        "X-Signature": _sign(priv, nonce, "GET", path, b"")})

    assert r.status_code == status, r.text
    if not eligible:
        assert r.json()["detail"] == "mfa_required"


@pytest.mark.asyncio
async def test_admin_user_requires_verified_admin_email(secure, client, monkeypatch):
    """An address in ADMIN_EMAILS is staff only once proved: an approved
    account that typed a staff address and never verified it is not admin."""
    monkeypatch.setattr(settings, "ADMIN_EMAILS", {"boss@sempermechanics.com"})
    secure._data["users"] = {"squatter": {
        "email": "boss@sempermechanics.com", "emailVerified": False,
        "role": "user", "access_status": "APPROVED"}}
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": "squatter", "email": "boss@sempermechanics.com",
                    "email_verified": False},
    )

    r = await client.get("/v1/admin/users", headers={"Authorization": "Bearer ok"})

    assert r.status_code == 403
    assert r.json()["detail"] == "not_admin"


@pytest.mark.asyncio
async def test_a_verified_admin_email_is_admin(secure, client, monkeypatch):
    monkeypatch.setattr(settings, "ADMIN_EMAILS", {"boss@sempermechanics.com"})
    secure._data["users"] = {"boss": {
        "email": "boss@sempermechanics.com", "emailVerified": True,
        "role": "user", "access_status": "APPROVED"}}
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": "boss", "email": "boss@sempermechanics.com",
                    "email_verified": True},
    )

    r = await client.get("/v1/admin/users", headers={"Authorization": "Bearer ok"})

    assert r.status_code == 200, r.text
