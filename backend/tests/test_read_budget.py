"""Firestore reads and writes per request, for the calls the app makes.

Firestore bills per document read, and the app calls these routes on every open
(`/v1/config`, `/v1/me`, `GET /v1/sessions`) or once per analysis (create, one
completion per file, erase). This counts what each route costs against the
store double, the way Firestore bills it: a document get is one read, a query
one read per document returned (at least one), a count one read. The budgets
below are what the routes cost today; a change that adds a read fails here, and
one that saves reads updates the number (docs/perf/request-volume.md).

Real device auth, not DEV_INSECURE_AUTH: the account lookup, the licence
re-checks and the nonce claim are part of what every call costs.
"""
import base64
import collections
import hashlib
import itertools
import json
import time

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

import fake_firestore
from app import audit, deps, drive, rate_limit
from app.config import settings

UID = "u1"
DEVICE = "dev-1"
SESSIONS = 20  # already in the cloud when the app lists them
FILES = 3  # a split bundle: Session.zip, Extras.zip, manifest
_SHA = "a" * 64
_MD5 = "b" * 32


class Meter:
    """Counts billable reads and writes by patching the store double's classes."""

    def __init__(self, monkeypatch):
        self.reads = 0
        self.writes = 0
        self.by_kind = collections.Counter()
        m = self

        def counted(cls, name, kind, cost):
            real = getattr(cls, name)

            def wrapper(*a, **k):
                out = real(*a, **k)
                n = cost(out)
                m.by_kind[kind] += n
                if kind.startswith("read"):
                    m.reads += n
                else:
                    m.writes += n
                return out

            monkeypatch.setattr(cls, name, wrapper)

        one = lambda _out: 1  # noqa: E731
        counted(fake_firestore._DocRef, "get", "read:get", one)
        # A query is billed per document returned, and at least one read; a
        # count aggregation is one read per 1000 index entries, so one here.
        real_stream = fake_firestore._Query.stream

        def stream(q, **kw):
            rows = list(real_stream(q, **kw))
            m.by_kind["read:query"] += max(1, len(rows))
            m.reads += max(1, len(rows))
            return iter(rows)

        monkeypatch.setattr(fake_firestore._Query, "stream", stream)
        counted(fake_firestore._Query, "count", "read:count", one)
        for w in ("set", "create", "update", "delete"):
            counted(fake_firestore._DocRef, w, f"write:{w}", one)

    def reset(self):
        self.reads = self.writes = 0
        self.by_kind.clear()


@pytest.fixture
def world(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(deps, "verify_id_token",
                        lambda _t: {"sub": UID, "email": "u1@example.com", "email_verified": True})
    for bucket in vars(rate_limit).values():
        if isinstance(bucket, rate_limit.TokenBucket):
            monkeypatch.setattr(bucket, "allow", lambda key: True)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(drive, "ensure_session_folders", lambda *a, **k: {
        "sessionFolderId": "sf", "userFolderId": "uf", "sessionsFolderId": "usf", "bundle": "sf"})
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    monkeypatch.setattr(drive, "get_file_meta",
                        lambda _t, _id: {"size": 10, "md5": _MD5, "parents": ["sf"]})
    monkeypatch.setattr(drive, "delete_file", lambda *a, **k: None)

    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    store._data["users"] = {UID: {
        "uid": UID, "email": "u1@example.com", "role": "user",
        "access_status": "APPROVED", "activeDeviceId": DEVICE,
    }}
    store._data["devices"] = {DEVICE: {"uid": UID, "status": "ACTIVE", "publicKeyPem": pem}}
    store._data["sessions"] = {
        f"s{i:02d}": {"uid": UID, "status": "COMPLETED", "localSessionId": f"local-{i}",
                      "fileCount": FILES, "completedCount": FILES, "driveFolderId": f"f{i}"}
        for i in range(SESSIONS)
    }
    return priv, Meter(monkeypatch)


_nonces = itertools.count()
KINDS: dict = {}  # per-call breakdown, printed with the table


def _headers(priv, method, path, body=b""):
    nonce = f"t1.{int(time.time())}.{next(_nonces):022d}"
    msg = (nonce + method + path).encode() + hashlib.sha256(body).digest()
    sig = base64.b64encode(priv.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()
    return {"Authorization": "Bearer ok", "X-Device-Id": DEVICE, "X-Nonce": nonce,
            "X-Signature": sig, "Content-Type": "application/json"}


async def _call(client, priv, meter, method, path, payload=None, signed=True):
    body = json.dumps(payload).encode() if payload is not None else b""
    headers = _headers(priv, method, path, body) if signed else {
        "Authorization": "Bearer ok", "X-Device-Id": DEVICE}
    meter.reset()
    r = await client.request(method, path, content=body or None, headers=headers)
    assert r.status_code == 200, f"{method} {path}: {r.status_code} {r.text}"
    KINDS[f"{method} {path.split('?')[0]}"] = dict(meter.by_kind)
    return r.json(), (meter.reads, meter.writes)


async def _measure(client, priv, meter):
    """What one app open and one analysis upload cost, route by route."""
    cost = {}
    # An account's first config call sets up its demo licence; every later one is the app-open cost.
    _, cost["GET /v1/config (first ever)"] = await _call(
        client, priv, meter, "GET", "/v1/config", signed=False)
    _, cost["GET /v1/config"] = await _call(client, priv, meter, "GET", "/v1/config", signed=False)
    _, cost["GET /v1/me"] = await _call(client, priv, meter, "GET", "/v1/me", signed=False)
    listing, cost[f"GET /v1/sessions (S={SESSIONS})"] = await _call(
        client, priv, meter, "GET", "/v1/sessions?page_size=100", signed=False)
    assert len(listing["sessions"]) == SESSIONS

    created, cost[f"POST /v1/sessions (N={FILES})"] = await _call(
        client, priv, meter, "POST", "/v1/sessions", {
            "specimen": "s", "localSessionId": "local-new",
            "files": [{"name": f"f{i}.zip", "role": "bundle", "bytes": 10, "sha256": _SHA}
                      for i in range(FILES)],
        })
    sid = created["sessionId"]
    assert len(created["uploads"]) == FILES
    completes = []
    for up in created["uploads"]:
        _, c = await _call(client, priv, meter, "POST", f"/v1/files/{up['fileId']}/complete",
                           {"sessionId": sid, "driveFileId": f"d{up['fileId'][-6:]}",
                            "bytes": 10, "md5": _MD5})
        completes.append(c)
    cost["POST /v1/files/{id}/complete (each)"] = max(completes)
    _, cost[f"DELETE /v1/sessions/{{id}} (N={FILES})"] = await _call(
        client, priv, meter, "DELETE", f"/v1/sessions/{sid}")
    return cost


#: (reads, writes) per request, measured 2026-09-25. POST /v1/sessions was
#: (14, 11) before the inline path stopped reading back what it had just written.
#: GET and POST /v1/sessions each gained one read the same day (TD-121): the
#: quota count subtracts a second count of PROVISION_FAILED sessions, which
#: store nothing and were being charged as stored analyses. POST /v1/sessions
#: lost one again on 2026-10-05: the retry lookup is one query, not two.
#: It gained two on 2026-10-18 for a demo account (this one): the demo cap
#: also counts the device's stored analyses, total less PROVISION_FAILED.
#: On 2026-10-29 (ADR-033) the quota became per app: Semper's count is the
#: account's less Semper Classroom's, two more counts on GET and POST
#: /v1/sessions, and the first config call also reads Classroom's invite.
#: On 2026-11-01 (ADR-035) POST /v1/sessions gained one read, the recycle-bin
#: lookup, and DELETE bins instead of erasing: the files are flagged, the
#: tombstone written and the session deleted, one write more than before.
BUDGET = {
    "GET /v1/config (first ever)": (5, 3),
    "GET /v1/config": (1, 0),
    "GET /v1/me": (1, 0),
    f"GET /v1/sessions (S={SESSIONS})": (SESSIONS + 5, 0),
    f"POST /v1/sessions (N={FILES})": (14, 11),
    "POST /v1/files/{id}/complete (each)": (6, 4),
    f"DELETE /v1/sessions/{{id}} (N={FILES})": (7, 6),
}


@pytest.mark.asyncio
async def test_firestore_cost_per_request_stays_in_budget(world, client, capsys):
    priv, meter = world
    cost = await _measure(client, priv, meter)
    with capsys.disabled():
        print("\n| Route | reads | writes |\n|---|--:|--:|")
        for route, (r, w) in cost.items():
            print(f"| {route} | {r} | {w} |")
        for call, kinds in KINDS.items():
            print(f"  {call}: {kinds}")
    assert cost == BUDGET


#: Reads for one POST /v1/sessions by manifest size, up to the largest manifest
#: provisioned inline (INLINE_PROVISION_MAX_FILES). Before: 8 + 2N, then 6 + N,
#: then 7 + N with the failed-session count in the quota check (TD-121), then
#: 6 + N once the retry lookup became one `status in` query (2026-10-05),
#: then 8 + N for a demo account with the per-device count (2026-10-18),
#: then 10 + N with the per-app quota (ADR-033, 2026-10-29), then 11 + N
#: with the recycle-bin lookup (ADR-035, 2026-11-01).
CREATE_READS = {1: 12, 3: 14, 8: 19}


@pytest.mark.asyncio
@pytest.mark.parametrize("n", sorted(CREATE_READS))
async def test_create_reads_grow_once_per_file(world, client, n):
    priv, meter = world
    # The account's first call sets up its licence; measure the steady state.
    await _call(client, priv, meter, "GET", "/v1/config", signed=False)
    _, (reads, _writes) = await _call(client, priv, meter, "POST", "/v1/sessions", {
        "specimen": "s", "localSessionId": f"local-n{n}",
        "files": [{"name": f"f{i}.zip", "role": "bundle", "bytes": 10, "sha256": _SHA}
                  for i in range(n)],
    })
    assert reads == CREATE_READS[n]


def _gets_by_collection(monkeypatch) -> collections.Counter:
    """Document gets per collection (`licenses/L1/seats` for a seat)."""
    seen: collections.Counter = collections.Counter()
    real = fake_firestore._DocRef.get

    def get(ref, transaction=None, **kw):
        seen[ref._collection] += 1
        return real(ref, transaction=transaction, **kw)

    monkeypatch.setattr(fake_firestore._DocRef, "get", get)
    return seen


@pytest.mark.asyncio
async def test_signed_request_reads_the_licence_and_seat_once(world, client, monkeypatch):
    """A licensed seat holder's signed call checks its device lock once.

    `current_user` re-validates the lock for the request's X-Device-Id;
    `verified_device` used to repeat the same check with the same inputs, which
    read the licence and the seat twice on every completion, download window
    and erase.
    """
    priv, meter = world
    import repo_view as repo
    data = repo._DB._data
    data["licenses"] = {"L1": {"kind": "institution", "status": "active", "mode": "licensed",
                               "seating": "assigned", "keyPrefix": "SEMP-TEST"}}
    data["licenses/L1/seats"] = {UID: {"uid": UID, "status": "active", "deviceIdLock": DEVICE}}
    data["users"][UID].update({"licenseId": "L1", "licenseKind": "institution",
                               "mode": "licensed", "plan": "professional"})
    gets = _gets_by_collection(monkeypatch)
    await _call(client, priv, meter, "DELETE", "/v1/sessions/s00")
    assert gets["licenses"] == 1
    assert gets["licenses/L1/seats"] == 1


def _queries_by_collection(monkeypatch) -> collections.Counter:
    """Queries run per collection."""
    seen: collections.Counter = collections.Counter()
    real = fake_firestore._Query.stream

    def stream(q, **kw):
        seen[q._collection] += 1
        return real(q, **kw)

    monkeypatch.setattr(fake_firestore._Query, "stream", stream)
    return seen


@pytest.mark.asyncio
async def test_export_reads_the_files_as_one_stream_not_a_query_per_session(
        world, client, monkeypatch):
    """`GET /v1/me/export` used to run one files query per session: an account
    with a thousand analyses made a thousand round trips. The files are now
    one stream ordered by session, merged with the sessions'."""
    priv, meter = world
    import repo_view as repo
    data = repo._DB._data
    data["files"] = {
        f"s{i:02d}-f{j}": {"uid": UID, "sessionId": f"s{i:02d}", "name": f"f{j}.zip",
                           "role": "bundle", "sizeBytes": 10, "status": "COMPLETED"}
        for i in range(SESSIONS) for j in range(FILES)
    }
    # A file whose session is gone, between two that exist, is not listed.
    data["files"]["s05x-orphan"] = {"uid": UID, "sessionId": "s05x", "name": "lost.zip",
                                    "role": "bundle", "sizeBytes": 1}
    # Nor is another account's file in a session of the same name.
    data["files"]["other"] = {"uid": "u2", "sessionId": "s03", "name": "theirs.zip",
                              "role": "bundle", "sizeBytes": 1}
    queries = _queries_by_collection(monkeypatch)
    body, _ = await _call(client, priv, meter, "GET", "/v1/me/export")
    assert queries["files"] == 1
    assert body["complete"] is True and body["sessionCount"] == SESSIONS
    assert [[f["fileId"] for f in s["files"]] for s in body["sessions"]] == [
        [f"s{i:02d}-f{j}" for j in range(FILES)] for i in range(SESSIONS)]
