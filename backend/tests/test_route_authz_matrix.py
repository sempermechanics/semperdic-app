"""Every route's auth tier, asserted as a table, plus cross-user negative tests.

Two different jobs:

1. `test_every_route_has_the_expected_auth_tier` is a structural guard. Adding a
   route without an auth dependency is the classic silent regression — the first
   batch of endpoints gets it right and a later addition quietly does not. This
   fails on any route not listed below, so a new one cannot ship without someone
   writing down what it should be.

2. The rest exercise the boundaries against real requests. Note that
   conftest.py sets DEV_INSECURE_AUTH=1 and deps._DEV_USER is role="admin", so
   every *other* HTTP test in the suite runs as an authenticated admin with auth
   bypassed. These tests turn that off.
"""
import base64
import hashlib

import fake_firestore
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app import audit, deps, drive, firestore_repo as repo
from app.config import settings
from app.deps import (
    admin_user,
    any_status_user,
    attested_or_mfa_admin,
    attested_or_mfa_admin_fresh,
    attested_or_mfa_user,
    current_user,
    verified_device,
)
from app.main import app
from app.routers.institutions import institution_admin_context, institution_admin_stepup
from app.tasks import tasks_caller

NONE, USER, ADMIN, DEVICE, DEVICE_ADMIN = "none", "user", "admin", "device", "device+admin"
# Signed in but not necessarily approved: only the Terms/consent routes, which
# must work at registration time, before an operator has approved the account.
ANY_STATUS = "any-status"
# Not a user tier: authenticated by the OIDC token Cloud Tasks attaches, and
# reachable by nothing else — no ID token or device signature will open it.
TASK = "cloud-task"
# Institution IT self-service: membership in adminEmails plus dashboard MFA
# (institution_admin_stepup). Membership is checked before MFA so a foreign
# licence still 404s. Deliberately distinct from ADMIN/DEVICE_ADMIN.
INSTITUTION_ADMIN = "institution-admin"
INSTITUTION_STEPUP = "institution-stepup"
# Semper staff changing state. Satisfied EITHER by a device attestation (the
# phone admin screen, unchanged) OR by an admin whose ID token records a
# completed second factor and a recent sign-in (the browser console, which
# cannot produce an attestation). Recorded as its own tier rather than folded
# into DEVICE_ADMIN because it is genuinely weaker: a phished live MFA session
# inside the freshness window can do what a stolen token alone could not. The
# table below is where that trade is visible.
ADMIN_STEPUP = "admin-stepup"
# Whole-licence revoke: same machinery, tighter ADMIN_WEB_REVOKE_REAUTH_SECONDS.
ADMIN_STEPUP_FRESH = "admin-stepup-fresh"
# The same step-up one tier down: an account holder acting on their own
# licence, proved by an attested device or by a second factor on a recent
# sign-in. It authorises nothing beyond what the holder already holds — it
# only refuses to take a bare ID token as proof that they are present. Used
# for the operations that are reachable from a browser and worth more than a
# token: changing which device the licence is bound to, and pulling an
# analysis out.
USER_STEPUP = "user-stepup"

# (method, path) -> required tier. Keep in sync deliberately, not automatically:
# the point is that a human decides.
EXPECTED = {
    ("GET", "/healthz"): NONE,                                  # liveness probe
    ("GET", "/readyz"): NONE,                                   # readiness probe
    ("GET", "/v1/me"): USER,
    ("GET", "/v1/config"): USER,
    ("GET", "/v1/me/export"): DEVICE,
    ("POST", "/v1/me/terms"): ANY_STATUS,                       # clickwrap runs before approval
    ("PUT", "/v1/me/consents"): ANY_STATUS,
    ("DELETE", "/v1/me"): DEVICE,
    ("POST", "/v1/devices/register"): USER,                     # bootstrap: no device yet
    ("POST", "/v1/challenge"): USER,                            # bootstrap: mints the nonce
    ("GET", "/v1/sessions"): USER,
    ("POST", "/v1/sessions"): DEVICE,
    ("DELETE", "/v1/sessions/{sid}"): DEVICE,
    ("GET", "/v1/sessions/{sid}/uploads"): DEVICE,              # returns Drive upload URIs
    ("GET", "/v1/sessions/{sid}/files"): USER,
    ("PUT", "/v1/sessions/{sid}/metadata"): DEVICE,          # writes into the Drive folder
    # The whole analysis out through a browser. USER_STEPUP because the
    # device-attested single-file route it stands in for is DEVICE, and the
    # data is the same data — a bare ID token must not be enough to drain an
    # account from anywhere.
    ("GET", "/v1/sessions/{sid}/bundle"): USER_STEPUP,
    ("GET", "/v1/files/{file_id}/content"): DEVICE,
    ("POST", "/v1/files/{file_id}/complete"): DEVICE,
    ("GET", "/v1/admin/users"): ADMIN,                          # read-only: no device needed
    ("POST", "/v1/admin/users/{uid}/approve"): ADMIN_STEPUP,
    ("POST", "/v1/admin/users/{uid}/revoke"): ADMIN_STEPUP,
    ("POST", "/v1/admin/device-releases"): ADMIN_STEPUP,
    ("PATCH", "/v1/admin/users/{uid}/config"): ADMIN_STEPUP,
    ("GET", "/v1/admin/licenses"): ADMIN,
    ("GET", "/v1/admin/licenses/{license_id}"): ADMIN,             # one row of the list
    ("POST", "/v1/admin/licenses"): ADMIN_STEPUP,
    ("PATCH", "/v1/admin/licenses/{license_id}"): ADMIN_STEPUP,
    ("POST", "/v1/admin/licenses/{license_id}/revoke"): ADMIN_STEPUP_FRESH,
    ("POST", "/v1/admin/licenses/{license_id}/convert"): ADMIN_STEPUP,
    ("DELETE", "/v1/admin/licenses/{license_id}"): ADMIN_STEPUP_FRESH,
    ("GET", "/v1/admin/deleted-licenses"): ADMIN,
    ("POST", "/v1/admin/deleted-licenses/{license_id}/restore"): ADMIN_STEPUP,
    # A read, so plain ADMIN like GET /v1/admin/licenses: it changes
    # nothing and the second factor gates state changes.
    ("GET", "/v1/admin/licenses/{license_id}/reconcile"): ADMIN,
    # Staff unbinding one institution seat. The same operation IT has on its
    # own route, at the staff tier, because staff are not in a customer's
    # adminEmails and that route 404s for them.
    ("PATCH", "/v1/admin/licenses/{license_id}/seats/{uid}/device"): ADMIN_STEPUP,
    # Read-only device-move history for support. ADMIN (token) not step-up —
    # same tier as listing licences.
    ("GET", "/v1/admin/licenses/{license_id}/device-history"): ADMIN,
    ("POST", "/v1/licenses/activate"): USER,
    # Lease routes are USER, not INSTITUTION_ADMIN: the member takes their own
    # seat. Eligibility is the seat document, checked inside the transaction —
    # a caller with no seat gets `not_eligible`, so a bare token buys nothing.
    ("POST", "/v1/licenses/checkout"): USER,
    ("POST", "/v1/licenses/release"): USER,
    # The holder's own device change. USER_STEPUP and not USER: a bare token
    # is exactly what a stolen one is, and this decides which device the
    # licence follows.
    ("POST", "/v1/licenses/unbind"): USER_STEPUP,
    # "Which licences do I administer" — USER, not INSTITUTION_ADMIN, because
    # it is the question that finds the licence id every other route here
    # already requires. It cannot be scoped to a licence the caller has not
    # named yet; the handler scopes it to the caller's own verified address
    # instead, and answers an empty list for everybody else.
    ("GET", "/v1/institutions/licenses"): USER,
    ("POST", "/v1/institutions/licenses/{license_id}/seats"): INSTITUTION_STEPUP,
    ("GET", "/v1/institutions/licenses/{license_id}/seats"): INSTITUTION_STEPUP,
    ("PATCH", "/v1/institutions/licenses/{license_id}/seats/{uid}"): INSTITUTION_STEPUP,
    ("DELETE", "/v1/institutions/licenses/{license_id}/seats/{uid}"): INSTITUTION_STEPUP,
    # Withdrawing an unclaimed invite. Same tier as the seat routes: an invite
    # is a roster decision, and it is scoped to one licence by the same
    # adminEmails check — the handler additionally refuses an invite whose
    # licenseId is not this one, so a guessed id reaches nothing.
    ("DELETE", "/v1/institutions/licenses/{license_id}/invites/{invite_key}"): INSTITUTION_STEPUP,
    ("POST", "/v1/tasks/provision-session"): TASK,
}


def _flatten(dependant):
    for sub in dependant.dependencies:
        if sub.call is not None:
            yield sub.call
        yield from _flatten(sub)


def _tier(route) -> str:
    calls = set(_flatten(route.dependant))
    if tasks_caller in calls:
        return TASK
    # Also calls verified_device directly rather than through Depends, so the
    # flattened dependency set shows only admin_user — without this branch the
    # route would silently read as plain ADMIN and the step-up would vanish
    # from the table it is supposed to be visible in.
    if attested_or_mfa_admin_fresh in calls:
        return ADMIN_STEPUP_FRESH
    if attested_or_mfa_admin in calls:
        return ADMIN_STEPUP
    # Same reason as above: the shared step-up calls verified_device directly
    # rather than through Depends, so without this branch a step-up route
    # would read as a plain USER one.
    if attested_or_mfa_user in calls:
        return USER_STEPUP
    # institution_admin_stepup wraps membership then MFA; check before the
    # bare membership dependency used by list_my_licenses routing helpers.
    if institution_admin_stepup in calls:
        return INSTITUTION_STEPUP
    if institution_admin_context in calls:
        return INSTITUTION_ADMIN
    has_device = verified_device in calls
    has_admin = admin_user in calls
    if has_device and has_admin:
        return DEVICE_ADMIN
    if has_device:
        return DEVICE
    if has_admin:
        return ADMIN
    if current_user in calls:
        return USER
    if any_status_user in calls:
        return ANY_STATUS
    return NONE


def _iter_api_routes(routes):
    """Walk `app.routes`, including FastAPI `_IncludedRouter` wrappers.

    `include_router` no longer flattens child APIRoutes onto `app.routes`;
    they live on `original_router.routes`. The auth-tier table still needs
    every user-facing path.
    """
    for route in routes:
        nested = getattr(route, "original_router", None)
        if nested is not None:
            yield from _iter_api_routes(nested.routes)
            continue
        yield route


def _actual_routes():
    out = {}
    for route in _iter_api_routes(app.routes):
        if not hasattr(route, "dependant") or not getattr(route, "methods", None):
            continue
        for method in route.methods:
            if method in ("HEAD", "OPTIONS"):
                continue
            out[(method, route.path)] = _tier(route)
    return out


def test_every_route_has_the_expected_auth_tier():
    actual = _actual_routes()

    undeclared = set(actual) - set(EXPECTED)
    assert not undeclared, (
        f"route(s) added without an auth decision: {sorted(undeclared)}. "
        "Add them to EXPECTED with the tier they should require."
    )
    removed = set(EXPECTED) - set(actual)
    assert not removed, f"EXPECTED lists route(s) that no longer exist: {sorted(removed)}"

    wrong = {k: (EXPECTED[k], actual[k]) for k in EXPECTED if EXPECTED[k] != actual[k]}
    assert not wrong, f"auth tier changed (expected, actual): {wrong}"


def test_only_health_probes_are_unauthenticated():
    """Stated separately so the intent survives an edit to the table above.

    The Cloud Tasks callback is deliberately not in this set: it has no *user*
    dependency, but it is authenticated — see test_task_route_rejects_users.
    """
    unauth = {k for k, v in _actual_routes().items() if v == NONE}
    assert unauth == {("GET", "/healthz"), ("GET", "/readyz")}


# --------------------------------------------------------------- live requests

VICTIM = "victim-uid"
ATTACKER = "attacker-uid"


def _sign(priv, nonce, method, path, body: bytes) -> str:
    msg = (nonce + method + path).encode() + hashlib.sha256(body).digest()
    return base64.b64encode(priv.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()


@pytest.fixture
def attacker(monkeypatch):
    """A fully legitimate, approved, device-attested user — who owns nothing.

    Everything they are denied below is denied on ownership, not on credentials.
    """
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(repo, "consume_nonce", lambda *a, **k: True)

    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()

    profile = {
        "uid": ATTACKER, "email": "attacker@example.com", "role": "user",
        "access_status": "APPROVED", "activeDeviceId": "atk-device",
    }
    monkeypatch.setattr(deps, "verify_id_token", lambda _t: {"sub": ATTACKER})
    monkeypatch.setattr(repo, "get_or_create_user", lambda claims, device_id=None: dict(profile))
    store._data["devices"] = {"atk-device": {
        "uid": ATTACKER, "status": "ACTIVE", "publicKeyPem": pem,
    }}

    # The victim's data, which the attacker will try to reach.
    store._data["sessions"] = {"s-victim": {
        "uid": VICTIM, "status": "UPLOADING", "localSessionId": "lv",
        "driveFolderId": "victim-folder", "fileCount": 1, "completedCount": 0,
    }}
    store._data["files"] = {"f-victim": {
        "uid": VICTIM, "sessionId": "s-victim", "driveFileId": "victim-drive-file",
        "name": "secret.zip", "sizeBytes": 10, "status": "COMPLETED",
    }}

    def signed(method, path, body=b""):
        nonce = "n-attacker"
        return {
            "Authorization": "Bearer ok",
            "X-Device-Id": "atk-device",
            "X-Nonce": nonce,
            "X-Signature": _sign(priv, nonce, method, path, body),
            # Without this the body never parses and the route 422s before it
            # ever reaches the ownership check we are trying to exercise.
            "Content-Type": "application/json",
        }

    store.signed = signed
    store.bearer = {"Authorization": "Bearer ok"}
    return store


@pytest.mark.parametrize("method,path,detail", [
    ("GET", "/v1/sessions/s-victim/uploads", "session_not_found"),
    ("DELETE", "/v1/sessions/s-victim", "session_not_found"),
    ("GET", "/v1/files/f-victim/content", "file_not_found"),
])
async def test_attested_user_cannot_reach_another_users_data(attacker, client, method, path, detail):
    """Valid token, valid device, valid signature — and still denied. Before
    this, only the /files listing had a cross-user test."""
    r = await client.request(method, path, headers=attacker.signed(method, path))
    assert r.status_code == 404, r.text
    assert r.json()["detail"] == detail
    # And nothing was destroyed on the way to being denied.
    assert "s-victim" in attacker._data["sessions"]
    assert "f-victim" in attacker._data["files"]


async def test_attested_user_cannot_complete_another_users_file(attacker, client, monkeypatch):
    monkeypatch.setattr(drive, "get_file_meta", lambda t, fid: {
        "size": 10, "md5": "b" * 32, "parents": ["victim-folder"],
    })
    path = "/v1/files/f-victim/complete"
    body = b'{"sessionId":"s-victim","driveFileId":"d","bytes":10,"md5":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}'
    r = await client.post(path, headers=attacker.signed("POST", path, body), content=body)
    assert r.status_code == 404
    assert attacker._data["files"]["f-victim"]["status"] == "COMPLETED"
    assert attacker._data["sessions"]["s-victim"]["completedCount"] == 0


@pytest.mark.parametrize("method,path", [
    ("GET", "/v1/admin/users"),
    ("POST", "/v1/admin/users/victim-uid/approve"),
    ("POST", "/v1/admin/users/victim-uid/revoke"),
    ("PATCH", "/v1/admin/users/victim-uid/config"),
    ("GET", "/v1/admin/licenses"),
    ("GET", "/v1/admin/licenses/abc"),
    ("POST", "/v1/admin/licenses"),
    ("POST", "/v1/admin/licenses/abc/revoke"),
    ("POST", "/v1/admin/licenses/abc/convert"),
    ("DELETE", "/v1/admin/licenses/abc"),
    ("GET", "/v1/admin/deleted-licenses"),
    ("POST", "/v1/admin/deleted-licenses/abc/restore"),
    ("GET", "/v1/admin/licenses/abc/reconcile"),
])
async def test_non_admin_is_refused_every_admin_route(attacker, client, method, path):
    attacker._data["users"] = {VICTIM: {"email": "v@e.com", "access_status": "PENDING"}}
    body = b'{"maxSessions":9999}' if method == "PATCH" else b""
    r = await client.request(method, path, headers=attacker.signed(method, path, body), content=body)
    assert r.status_code == 403, r.text
    assert r.json()["detail"] == "not_admin"
    assert attacker._data["users"][VICTIM]["access_status"] == "PENDING"


# ---------------------------------------------------------- institution IT routes
#
# Token + APPROVED + verified email in that license's adminEmails — no device
# attestation, no Semper role=admin. Cross-tenant isolation is the critical
# property: institution A's IT contact must not learn anything about institution B's
# seats, not even that the license id exists.

INSTITUTION_A = "license-institution-a"
INSTITUTION_B = "license-institution-b"


@pytest.fixture
def institution_it(monkeypatch):
    """An APPROVED, verified-email user who is IT for INSTITUTION_A only.

    Carries a fresh second-factor claim so own-licence seat routes (now
    institution_admin_stepup) still succeed; foreign-licence tests still
    404 on membership before MFA is consulted.
    """
    import time as _time

    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)

    uid = "it-admin-a"
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {
            "sub": uid,
            "email": "it@university-a.edu",
            "email_verified": True,
            "firebase": {"sign_in_second_factor": "totp"},
            "auth_time": _time.time() - 60,
        },
    )
    profile = {
        "uid": uid, "email": "it@university-a.edu", "role": "user",
        "access_status": "APPROVED", "emailVerified": True,
    }
    monkeypatch.setattr(repo, "get_or_create_user", lambda claims, device_id=None: dict(profile))

    store._data["licenses"] = {
        INSTITUTION_A: {
            "kind": "institution", "plan": "professional", "status": "active",
            "domainLock": "university-a.edu", "adminEmails": ["it@university-a.edu"],
            "keyPrefix": "SEMP-AAAA", "seatsUsed": 1,
        },
        INSTITUTION_B: {
            "kind": "institution", "plan": "professional", "status": "active",
            "domainLock": "university-b.edu", "adminEmails": ["it@university-b.edu"],
            "keyPrefix": "SEMP-BBBB", "seatsUsed": 1,
        },
    }
    store._data[f"licenses/{INSTITUTION_B}/seats"] = {
        "student-b": {
            "uid": "student-b", "email": "student@university-b.edu",
            "deviceIdLock": "dev-b", "status": "active",
        },
    }
    store.bearer = {"Authorization": "Bearer ok"}
    return store


async def test_institution_it_cannot_reach_another_institutions_seats(institution_it, client):
    r = await client.get(f"/v1/institutions/licenses/{INSTITUTION_B}/seats", headers=institution_it.bearer)
    assert r.status_code == 404, r.text
    assert r.json()["detail"] == "license_not_found"
    # Nothing about institution B's roster was disclosed, and nothing was touched.
    assert institution_it._data[f"licenses/{INSTITUTION_B}/seats"]["student-b"]["status"] == "active"


async def test_institution_it_cannot_patch_another_institutions_seat(institution_it, client):
    r = await client.patch(
        f"/v1/institutions/licenses/{INSTITUTION_B}/seats/student-b",
        json={"enabled": False},
        headers=institution_it.bearer,
    )
    assert r.status_code == 404, r.text
    assert institution_it._data[f"licenses/{INSTITUTION_B}/seats"]["student-b"]["status"] == "active"


async def test_institution_it_cannot_revoke_another_institutions_seat(institution_it, client):
    r = await client.delete(
        f"/v1/institutions/licenses/{INSTITUTION_B}/seats/student-b", headers=institution_it.bearer,
    )
    assert r.status_code == 404, r.text
    assert institution_it._data[f"licenses/{INSTITUTION_B}/seats"]["student-b"]["status"] == "active"


async def test_institution_it_can_manage_its_own_institutions_seats(institution_it, client):
    institution_it._data[f"licenses/{INSTITUTION_A}/seats"] = {
        "student-a": {
            "uid": "student-a", "email": "student@university-a.edu",
            "deviceIdLock": "dev-a", "status": "active",
        },
    }
    r = await client.get(f"/v1/institutions/licenses/{INSTITUTION_A}/seats", headers=institution_it.bearer)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["seats"][0]["uid"] == "student-a"
    assert "key" not in body["license"]  # never leaks plaintext or the raw hash-keyed record


async def test_institution_route_rejects_a_bare_token_from_a_non_member(institution_it, client):
    """A verified, approved caller who is simply not on adminEmails for ANY
    license must not learn that INSTITUTION_A even exists."""
    monkeypatch_email = {"uid": "it-admin-a", "email": "outsider@example.com",
                          "role": "user", "access_status": "APPROVED", "emailVerified": True}
    import app.firestore_repo as repo_mod
    orig = repo_mod.get_or_create_user
    repo_mod.get_or_create_user = lambda claims, device_id=None: dict(monkeypatch_email)
    try:
        r = await client.get(f"/v1/institutions/licenses/{INSTITUTION_A}/seats", headers=institution_it.bearer)
        assert r.status_code == 404
    finally:
        repo_mod.get_or_create_user = orig


@pytest.mark.parametrize("method,path", [
    ("GET", "/v1/me/export"),
    ("DELETE", "/v1/me"),
    ("POST", "/v1/sessions"),
    ("GET", "/v1/files/f-victim/content"),
])
async def test_device_routes_reject_a_bare_id_token(attacker, client, method, path):
    """A stolen ID token, with no device key, must not reach these at all.

    /uploads is covered separately below, on a session the caller owns, so a
    bare-token refusal cannot be mistaken for the ownership 404.
    """
    r = await client.request(method, path, headers=attacker.bearer)
    assert r.status_code in (400, 401), f"{method} {path} -> {r.status_code}"
    assert attacker._data["files"], "account data was touched without attestation"


# ----------------------------------------------------------- /uploads attested
#
# The resume list carries Drive capability URLs. An ID-token-only read was
# accepted during the fleet migration (device_or_legacy_reader, behind
# REQUIRE_ATTESTED_UPLOADS); retired 2026-09-26 (TD-45). These pin that the
# route is now plainly device-attested, on a session the caller owns.

def _owned_session(attacker):
    """A session the attacker actually owns, so an attested read reaches 200
    (the cross-user fixture session is owned by the victim and 404s first)."""
    attacker._data["sessions"]["s-mine"] = {
        "uid": ATTACKER, "status": "UPLOADING", "localSessionId": "lm",
        "driveFolderId": "mine-folder", "fileCount": 0, "completedCount": 0,
    }


async def test_uploads_rejects_a_bare_token_even_for_the_owner(attacker, client, caplog):
    """No device headers: refused, and no legacy event is logged any more."""
    _owned_session(attacker)
    with caplog.at_level("WARNING", logger="semper.auth"):
        r = await client.get("/v1/sessions/s-mine/uploads", headers=attacker.bearer)
    assert r.status_code in (400, 401), r.text
    assert "legacy_unattested_uploads" not in caplog.text


async def test_uploads_rejects_a_bad_signature(attacker, client):
    _owned_session(attacker)
    headers = {
        "Authorization": "Bearer ok",
        "X-Device-Id": "atk-device",
        "X-Nonce": "n-attacker",
        "X-Signature": "not-a-valid-signature",
    }
    r = await client.get("/v1/sessions/s-mine/uploads", headers=headers)
    assert r.status_code == 401, r.text


async def test_uploads_accepts_an_attested_read(attacker, client):
    _owned_session(attacker)
    path = "/v1/sessions/s-mine/uploads"
    r = await client.get(path, headers=attacker.signed("GET", path))
    assert r.status_code == 200, r.text
    assert r.json()["sessionId"] == "s-mine"


async def test_download_requires_attestation_not_just_ownership(attacker, client):
    """The route previously had only a reflection-based test that never issued
    a request, so nothing proved the dependency was actually enforced."""
    attacker._data["files"]["f-mine"] = {
        "uid": ATTACKER, "sessionId": "s", "driveFileId": "d", "name": "a.zip",
    }
    unsigned = await client.get("/v1/files/f-mine/content", headers=attacker.bearer)
    assert unsigned.status_code in (400, 401)
