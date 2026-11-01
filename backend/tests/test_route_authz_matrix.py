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
import copy
import hashlib
import re
import time

import fake_firestore
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app import audit, deps, drive
import repo_view as repo
from app.config import settings
from app.deps import (
    admin_user,
    any_status_user,
    current_user,
    token_uid,
    verified_device,
)
from app.main import app
from app.step_up import (
    attested_or_mfa_admin,
    attested_or_mfa_admin_fresh,
    attested_or_mfa_user,
)
from app.routers.institutions import institution_admin_context, institution_admin_stepup
from app.tasks import tasks_caller

NONE, USER, ADMIN, DEVICE, DEVICE_ADMIN = "none", "user", "admin", "device", "device+admin"
# Signed in but not necessarily approved: only the Terms/consent routes, which
# must work at registration time, before an operator has approved the account.
ANY_STATUS = "any-status"
# Not a user tier: authenticated by the OIDC token Cloud Tasks attaches, and
# reachable by nothing else — no ID token or device signature will open it.
TASK = "cloud-task"
# A verified ID token and nothing more: no profile is read or created. Only the
# erasure check, which must ask about an erased account without re-creating it
# (TD-206), and which answers about the caller's own device alone.
TOKEN_ONLY = "token-only"  # noqa: S105 - an auth tier name
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
    ("GET", "/v1/me/erasure"): TOKEN_ONLY,
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
    ("POST", "/v1/admin/licenses/search"): ADMIN,                   # the GET's search, in a body
    ("GET", "/v1/admin/licenses/{license_id}"): ADMIN,             # one row of the list
    ("POST", "/v1/admin/licenses"): ADMIN_STEPUP,
    ("PATCH", "/v1/admin/licenses/{license_id}"): ADMIN_STEPUP,
    ("POST", "/v1/admin/licenses/{license_id}/revoke"): ADMIN_STEPUP_FRESH,
    ("POST", "/v1/admin/licenses/{license_id}/convert"): ADMIN_STEPUP,
    ("POST", "/v1/admin/licenses/{license_id}/upgrade"): ADMIN_STEPUP,
    ("POST", "/v1/admin/licenses/{license_id}/apply-upgrade"): ADMIN_STEPUP,
    ("DELETE", "/v1/admin/licenses/{license_id}"): ADMIN_STEPUP_FRESH,
    ("GET", "/v1/admin/deleted-licenses"): ADMIN,
    ("POST", "/v1/admin/deleted-licenses/{license_id}/restore"): ADMIN_STEPUP,
    # A read, so plain ADMIN like GET /v1/admin/licenses: it changes
    # nothing and the second factor gates state changes.
    ("GET", "/v1/admin/licenses/{license_id}/reconcile"): ADMIN,
    # Any licence's phones at the staff tier: reading is an admin read,
    # adding or deactivating one a staff write.
    ("GET", "/v1/admin/licenses/{license_id}/devices"): ADMIN,
    ("POST", "/v1/admin/licenses/{license_id}/devices/{device_id}"): ADMIN_STEPUP,
    ("DELETE", "/v1/admin/licenses/{license_id}/devices/{device_id}"): ADMIN_STEPUP,
    # Staff unbinding one institution seat. The same operation IT has on its
    # own route, at the staff tier, because staff are not in a customer's
    # adminEmails and that route 404s for them.
    ("PATCH", "/v1/admin/licenses/{license_id}/seats/{uid}/device"): ADMIN_STEPUP,
    # The institution roster at the staff tier (`routers/roster.py`): any
    # institution licence, adminEmails or not. Reading it is an ordinary
    # admin read, like the licence list; changing it is a staff write.
    ("GET", "/v1/admin/licenses/{license_id}/seats"): ADMIN,
    ("POST", "/v1/admin/licenses/{license_id}/seats"): ADMIN_STEPUP,
    ("PATCH", "/v1/admin/licenses/{license_id}/seats/{uid}"): ADMIN_STEPUP,
    ("DELETE", "/v1/admin/licenses/{license_id}/seats/{uid}"): ADMIN_STEPUP,
    ("DELETE", "/v1/admin/licenses/{license_id}/invites/{invite_key}"): ADMIN_STEPUP,
    # The bulk import is a write like the single add it runs per row; the
    # export, summary and audit log are reads, like the listing.
    ("POST", "/v1/admin/licenses/{license_id}/seats/import"): ADMIN_STEPUP,
    ("GET", "/v1/admin/licenses/{license_id}/seats/export"): ADMIN,
    ("GET", "/v1/admin/licenses/{license_id}/summary"): ADMIN,
    ("GET", "/v1/admin/licenses/{license_id}/audit"): ADMIN,
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
    # A licence's phones (ADR-033). Activate is signed by the phone itself;
    # listing your own is a plain read; deactivating one is reachable from a
    # browser, so it takes the same step-up as a device change.
    ("POST", "/v1/me/devices/activate"): DEVICE,
    ("GET", "/v1/me/devices"): USER,
    ("DELETE", "/v1/me/devices/{device_id}"): USER_STEPUP,
    # A claim code redeemed on the web account page. USER_STEPUP: a code is
    # worth a licence, and a stolen bare token must not be able to spend one
    # onto the account (or burn it). The app's own key route above stays USER
    # for installed builds.
    ("POST", "/v1/me/claim"): USER_STEPUP,
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
    # Bulk import, export, seat summary and the licence's audit log: the same
    # membership check and step-up as the roster itself, so a licence that
    # does not name the caller 404s before anything is read.
    ("POST", "/v1/institutions/licenses/{license_id}/seats/import"): INSTITUTION_STEPUP,
    ("GET", "/v1/institutions/licenses/{license_id}/seats/export"): INSTITUTION_STEPUP,
    ("GET", "/v1/institutions/licenses/{license_id}/summary"): INSTITUTION_STEPUP,
    ("GET", "/v1/institutions/licenses/{license_id}/audit"): INSTITUTION_STEPUP,
    ("GET", "/v1/institutions/licenses/{license_id}/devices"): INSTITUTION_STEPUP,
    ("DELETE", "/v1/institutions/licenses/{license_id}/devices/{device_id}"): INSTITUTION_STEPUP,
    ("POST", "/v1/tasks/provision-session"): TASK,
    ("POST", "/v1/tasks/license-fanout"): TASK,
    # Staff test accounts (ADR-027). The list is a read; every change hands
    # over, prolongs or ends a working login, so the revoke tier's fresh
    # step-up, and the expiry job is the Scheduler's OIDC invoker.
    ("GET", "/v1/admin/test-accounts"): ADMIN,
    ("POST", "/v1/admin/test-accounts"): ADMIN_STEPUP_FRESH,
    ("POST", "/v1/admin/test-accounts/{uid}/extend"): ADMIN_STEPUP_FRESH,
    ("POST", "/v1/admin/test-accounts/{uid}/password"): ADMIN_STEPUP_FRESH,
    ("POST", "/v1/admin/test-accounts/{uid}/devices/{device_id}/release"): ADMIN_STEPUP_FRESH,
    ("DELETE", "/v1/admin/test-accounts/{uid}"): ADMIN_STEPUP_FRESH,
    ("POST", "/v1/tasks/test-account-expiry"): TASK,
    # The recycle bin (ADR-035). Listing it is a read like GET /v1/sessions;
    # restoring and erasing for good are device-signed, like the delete that
    # binned the analysis. The daily purge is the Scheduler's OIDC invoker.
    ("GET", "/v1/recycle-bin"): USER,
    ("POST", "/v1/recycle-bin/{sid}/restore"): DEVICE,
    ("DELETE", "/v1/recycle-bin/{sid}"): DEVICE,
    ("POST", "/v1/tasks/recycle-bin-purge"): TASK,
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
    if token_uid in calls:
        return TOKEN_ONLY
    return NONE


def _iter_api_routes(_routes=None):
    """Every route the app serves, nested routers included, with full paths
    (`app.main.served_routes`). Walking only the top level would compare
    against the docs endpoints and pass vacuously."""
    from app.main import served_routes
    yield from served_routes(app)


def _actual_routes():
    out = {}
    for route in _iter_api_routes():
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

    The Cloud Tasks callbacks are deliberately not in this set: they have no
    *user* dependency, but they are authenticated — see
    test_async_provisioning.py::test_task_route_is_unreachable_with_a_user_token
    (every TASK route) and test_every_authenticated_route_refuses_an_anonymous_request.
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
    repo.patch(monkeypatch, "consume_nonce", lambda *a, **k: True)

    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()

    profile = {
        "uid": ATTACKER, "email": "attacker@example.com", "role": "user",
        "access_status": "APPROVED", "activeDeviceId": "atk-device",
    }
    monkeypatch.setattr(deps, "verify_id_token", lambda _t: {"sub": ATTACKER})
    repo.patch(monkeypatch, "get_or_create_user", lambda claims, device_id=None: dict(profile))
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


#: Values for path parameters in the sweeps below. The victim's uid where a
#: route names a user, so "nothing changed" is checked on a real document.
_PATH_VALUES = {"uid": VICTIM, "sid": "s-victim", "file_id": "f-victim",
                "license_id": "abc", "invite_key": "k", "device_id": "d"}


def _fill(path: str) -> str:
    return re.sub(r"\{(\w+)\}", lambda m: _PATH_VALUES.get(m.group(1), "x"), path)


#: Every staff route, from the table, so a new one is covered by being listed
#: there (readiness TE3-002: 13 of 33 had no live non-staff refusal).
_STAFF_TIERS = {ADMIN, ADMIN_STEPUP, ADMIN_STEPUP_FRESH, DEVICE_ADMIN}
STAFF_ROUTES = sorted((m, p) for (m, p), tier in EXPECTED.items() if tier in _STAFF_TIERS)


@pytest.mark.parametrize("method,template", STAFF_ROUTES, ids=lambda v: v)
async def test_non_admin_is_refused_every_admin_route(attacker, client, method, template):
    path = _fill(template)
    attacker._data["users"] = {VICTIM: {"email": "v@e.com", "access_status": "PENDING"}}
    before = copy.deepcopy(attacker._data)
    body = b'{"maxSessions":9999}' if method == "PATCH" else b""
    r = await client.request(method, path, headers=attacker.signed(method, path, body), content=body)
    assert r.status_code == 403, r.text
    assert r.json()["detail"] == "not_admin"
    assert attacker._data["users"][VICTIM]["access_status"] == "PENDING"
    assert attacker._data == before, f"{method} {path} wrote before refusing"


def test_the_staff_sweep_covers_every_staff_route():
    assert len(STAFF_ROUTES) >= 33
    assert ("POST", "/v1/admin/device-releases") in STAFF_ROUTES
    assert ("DELETE", "/v1/admin/test-accounts/{uid}") in STAFF_ROUTES


# ------------------------------------------------------------ anonymous sweep

@pytest.mark.parametrize("method,template",
                         sorted(k for k, tier in EXPECTED.items() if tier != NONE),
                         ids=lambda v: v)
async def test_every_authenticated_route_refuses_an_anonymous_request(store, client, monkeypatch,
                                                                     method, template):
    """No Authorization header at all, on every route but the two probes: 401
    missing_bearer before anything is read or written (readiness TE3-003)."""
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)

    def no_token_check(*_a, **_k):
        raise AssertionError("an anonymous request reached token verification")

    monkeypatch.setattr(deps, "verify_id_token", no_token_check)
    before = copy.deepcopy(store._data)
    r = await client.request(method, _fill(template))
    assert r.status_code == 401, f"{method} {template} -> {r.status_code} {r.text}"
    assert r.json()["detail"] == "missing_bearer"
    assert store._data == before


# ------------------------------------------------------- suspended account
#
# deps._authenticate is the one gate: any status but APPROVED (PENDING only on
# the Terms routes) is 403 not_approved. These send a SUSPENDED account through
# the real auth path, on one route per tier, with everything else in order: a
# valid token with a fresh second factor, an ACTIVE device with a valid
# signature, staff role and institution IT membership (readiness TE3-001).

SUSPENDED_UID = "suspended-uid"
_SUSPENDED_INSTITUTION = "license-suspended-it"


@pytest.fixture
def suspended(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    repo.patch(monkeypatch, "consume_nonce", lambda *a, **k: True)

    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    monkeypatch.setattr(deps, "verify_id_token", lambda _t, **_k: {
        "sub": SUSPENDED_UID, "email": "staff@university.edu", "email_verified": True,
        "firebase": {"sign_in_second_factor": "totp"}, "auth_time": time.time() - 60,
    })
    store._data["users"] = {SUSPENDED_UID: {
        "email": "staff@university.edu", "emailVerified": True, "role": "admin",
        "access_status": "SUSPENDED", "activeDeviceId": "sus-device",
    }}
    store._data["devices"] = {"sus-device": {
        "uid": SUSPENDED_UID, "status": "ACTIVE", "publicKeyPem": pem,
    }}
    store._data["licenses"] = {_SUSPENDED_INSTITUTION: {
        "kind": "institution", "plan": "professional", "status": "active",
        "domainLock": "university.edu", "adminEmails": ["staff@university.edu"],
        "keyPrefix": "SEMP-SSSS", "seatsUsed": 0,
    }}
    store._data["sessions"] = {"s-own": {
        "uid": SUSPENDED_UID, "status": "COMPLETED", "localSessionId": "l",
        "driveFolderId": "own-folder", "fileCount": 1, "completedCount": 1,
    }}
    store._data["files"] = {"f-own": {
        "uid": SUSPENDED_UID, "sessionId": "s-own", "driveFileId": "own-drive-file",
        "name": "a.zip", "sizeBytes": 10, "status": "COMPLETED",
    }}

    def signed(method, path, body=b""):
        return {
            "Authorization": "Bearer ok", "X-Device-Id": "sus-device", "X-Nonce": "n-sus",
            "X-Signature": _sign(priv, "n-sus", method, path, body),
            "Content-Type": "application/json",
        }

    store.signed = signed
    return store


_CREATE_BODY = (b'{"specimen":"s","files":[{"name":"a.zip","role":"bundle","bytes":10,"sha256":"'
                + b"a" * 64 + b'"}]}')


#: Tiers a SUSPENDED account cannot reach by construction: the probes take no
#: account, a Cloud Task carries no ID token, and the erasure check reads no
#: profile on purpose (TD-206).
_NOT_AN_ACCOUNT_TIER = {NONE, TASK, TOKEN_ONLY}
#: Every route of every account tier, from the table, so a new route or a new
#: tier is swept by being listed there (readiness TE3-103: the hand list of ten
#: left ADMIN_STEPUP_FRESH and PUT /v1/me/consents without a SUSPENDED request).
SUSPENDED_SWEEP = sorted(k for k, tier in EXPECTED.items() if tier not in _NOT_AN_ACCOUNT_TIER)
#: A body where the route reads one, so a refusal is not a validation error.
_SUSPENDED_BODIES = {("POST", "/v1/sessions"): _CREATE_BODY}
_SUSPENDED_PATH = {"file_id": "f-own", "sid": "s-own", "uid": SUSPENDED_UID,
                   "license_id": _SUSPENDED_INSTITUTION, "invite_key": "k", "device_id": "d"}


def test_the_suspended_sweep_covers_every_account_tier():
    assert {EXPECTED[k] for k in SUSPENDED_SWEEP} == set(EXPECTED.values()) - _NOT_AN_ACCOUNT_TIER
    assert {ADMIN_STEPUP_FRESH, ANY_STATUS} <= {EXPECTED[k] for k in SUSPENDED_SWEEP}
    assert ("PUT", "/v1/me/consents") in SUSPENDED_SWEEP


@pytest.mark.parametrize("method,template", SUSPENDED_SWEEP, ids=lambda v: v)
async def test_a_suspended_account_is_refused_on_every_tier(suspended, client, method, template):
    """Every route of every account tier (USER, DEVICE, USER_STEPUP, ANY_STATUS,
    ADMIN, ADMIN_STEPUP, ADMIN_STEPUP_FRESH, INSTITUTION_STEPUP). 403
    not_approved, and nothing changed."""
    body = _SUSPENDED_BODIES.get((method, template), b"")
    path = re.sub(r"\{(\w+)\}", lambda m: _SUSPENDED_PATH[m.group(1)], template)
    before = copy.deepcopy(suspended._data)
    r = await client.request(method, path, headers=suspended.signed(method, path, body),
                             content=body)
    assert r.status_code == 403, f"{method} {path} -> {r.status_code} {r.text}"
    assert r.json()["detail"] == "not_approved"
    assert suspended._data["users"][SUSPENDED_UID]["access_status"] == "SUSPENDED"
    for collection in ("sessions", "files", "licenses", "devices"):
        assert suspended._data[collection] == before[collection], collection
    assert not suspended._data.get(f"licenses/{_SUSPENDED_INSTITUTION}/seats")


async def test_a_revoked_licence_is_refused_through_the_real_auth_path(suspended, client):
    """The revoked-licence check end to end, with no dev bypass: the licence
    ended (its fan-out to the account not yet run), the account still reads
    licensed, and the device-lock revalidation on the signed request drops it
    to Demo, so the restore is 403 feature_not_licensed (readiness TE3-001)."""
    from refusals import attempt

    store = suspended
    store._data["users"][SUSPENDED_UID]["access_status"] = "APPROVED"
    minted = repo.create_individual_license(
        email_lock="staff@university.edu", device_id_lock="sus-device", created_by_uid="admin")
    err, _ = attempt(repo.activate_license, SUSPENDED_UID, "staff@university.edu", "sus-device",
                     minted["key"])
    assert err == ""
    assert repo.effective_mode({**store._data["users"][SUSPENDED_UID], "uid": SUSPENDED_UID})         == "licensed"
    store._data["licenses"][minted["license"]["id"]]["status"] = "revoked"

    path = "/v1/files/f-own/content"
    r = await client.get(path, headers=store.signed("GET", path))
    assert r.status_code == 403, r.text
    assert r.json()["detail"].startswith("feature_not_licensed")
    assert repo.effective_mode({**store._data["users"][SUSPENDED_UID], "uid": SUSPENDED_UID})         == "demo"


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
    repo.patch(monkeypatch, "get_or_create_user", lambda claims, device_id=None: dict(profile))

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


async def test_institution_route_rejects_a_bare_token_from_a_non_member(
        institution_it, client, monkeypatch):
    """A verified, approved caller who is simply not on adminEmails for ANY
    license must not learn that INSTITUTION_A even exists."""
    outsider = {"uid": "it-admin-a", "email": "outsider@example.com",
                "role": "user", "access_status": "APPROVED", "emailVerified": True}
    import repo_view as repo_mod
    repo_mod.patch(monkeypatch, "get_or_create_user",
                   lambda claims, device_id=None: dict(outsider))
    r = await client.get(f"/v1/institutions/licenses/{INSTITUTION_A}/seats", headers=institution_it.bearer)
    assert r.status_code == 404


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
