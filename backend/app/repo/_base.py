"""The Firestore client, the transaction helper, and the helpers every repo module shares.

Other repo modules read `_base.firestore` by attribute and call `db()`, which
reads `_base._DB`, so `tests/fake_firestore.install` patches this module only.
"""
import logging
from datetime import datetime, timezone
from typing import Any, Callable, TypeVar

from google.api_core.exceptions import Aborted
from google.cloud import firestore

from .. import errors
from ..config import settings
from ..licenses import (
    MODES,
    grace_ends_at,
    legacy_plan,
    normalize_mode,
)
from ..observability import DependencyError


log = logging.getLogger("semper.firestore")


_DB = None

# Every server-owned document carries this integer. Migrations must be
# idempotent and advance documents only after an export/restore checkpoint.
SCHEMA_VERSION = 2

T = TypeVar("T")

# Transaction retries on the hot single-document paths (nonce consumption, file
# completion). The client default is 5; contention there is expected rather than
# exceptional, and every lost race costs a legitimate caller a round trip.
_TX_ATTEMPTS = 10

# Firestore caps a write batch at 500 operations.
_BATCH_LIMIT = 400


def _lost_to_contention(exc: BaseException) -> bool:
    """True when a transaction failed only because it kept losing the race.

    Every transaction here is opened with `max_attempts=_TX_ATTEMPTS` (10), so
    the client retries an ABORTED commit ten times and then raises
    ValueError("Failed to commit transaction in 10 attempts") chained from the
    last Aborted. Two callers hammering one hot document — the same nonce
    replayed, the same file completed twice — is an expected condition on these
    paths, not a server fault, so it must resolve to the normal deny/idempotent
    answer instead of a 500.
    """
    if isinstance(exc, Aborted):
        return True
    return isinstance(exc, ValueError) and isinstance(exc.__cause__, Aborted)


def _run_tx(body: Callable[[Any], T], *, on_contended: Callable[[], T]) -> T:
    """Run a `@firestore.transactional` body in a fresh transaction.

    If every attempt lost to contention, answer `on_contended()` instead — each
    caller decides what losing means (deny, re-read, fail closed). Anything
    else propagates. One copy of the try/except the ten transactions used to
    repeat (TD-53); the transaction is created here so its attempt budget is
    `_TX_ATTEMPTS` everywhere.
    """
    try:
        return body(db().transaction(max_attempts=_TX_ATTEMPTS))
    except Exception as exc:  # noqa: BLE001
        if not _lost_to_contention(exc):
            raise
        return on_contended()


#: What the claim transactions answer when they only lost the race. Private
#: to this module: `errors.py` names wire codes, and this one never reaches the
#: wire. It exists so contention stops being indistinguishable in the logs from
#: a licence that genuinely has no room left. Callers that hand a code to a
#: route put it through `_public_claim_error` first, which answers
#: `claim_contended` (503) — never `license_seats_exhausted`, which sent IT
#: looking for a seat problem that a retry would have solved.
_CONTENDED = "_contended"


def db() -> firestore.Client:
    """Process-wide Firestore singleton.

    Cloud Run concurrency (see deploy flags) shares this client across requests
    in one instance. The google-cloud-firestore sync client is thread-safe for
    ordinary reads/writes; do not create per-request clients.
    """
    global _DB
    if _DB is None:
        _DB = firestore.Client(project=settings.GCP_PROJECT or None)
    return _DB


def ping() -> None:
    """Cheap Firestore reachability probe for readiness."""
    try:
        # A missing document is still a successful round-trip.
        # Doc ids matching __.*__ are reserved by Firestore and raise locally
        # (misreported as unreachable); use a plain probe id.
        db().collection("users").document("readyz_ping").get()
    except Exception as e:  # noqa: BLE001
        log.exception("firestore ping failed: %s", e)
        raise DependencyError(errors.FIRESTORE_UNREACHABLE, "firestore") from e


def _now():
    return datetime.now(timezone.utc)


def _load_user(uid: str):
    snap = db().collection("users").document(uid).get()
    if not snap.exists:
        return None
    return {**snap.to_dict(), "uid": uid}


def _mode_patch(mode: str) -> dict:
    """The user-document fields that record an entitlement mode.

    Writes `mode` and the pre-rename `plan` mirror together. Cloud Run rolls
    traffic, so an instance running the previous revision can read a document
    this one just wrote; it looks at `plan`. Drop the mirror only after the
    fleet and every deployed revision read `mode` (see licenses.legacy_plan).
    """
    return {"mode": mode, "plan": legacy_plan(mode)}


def _license_mode(data: dict) -> str:
    """The mode a license grants, reading `mode` then the pre-rename `plan`."""
    raw = data.get("mode")
    if not (isinstance(raw, str) and raw.strip().lower() in MODES):
        raw = data.get("plan")
    return normalize_mode(raw)


def _seat_ref(license_id: str, uid: str):
    return db().collection("licenses").document(license_id).collection("seats").document(uid)


# ---------------- floating-seat leases ----------------
# A floating license separates the roster from the count: every member may use
# the license, but only `maxSeats` hold a live lease at once. The lease lives
# on the seat document — `check_device_lock` already reads that document on
# every institution request, so consulting it costs nothing extra — and its
# expiry is mirrored onto the user so `effective_mode` stays a pure function.

def _lease_clear_patch() -> dict:
    """Seat fields that record no lease. Written on release, hold and revoke."""
    return {
        "leaseExpiresAt": firestore.DELETE_FIELD,
        "leaseDeviceId": firestore.DELETE_FIELD,
        "lastHeartbeatAt": firestore.DELETE_FIELD,
    }


def _seat_lease_counted(seat: dict) -> bool:
    """Whether this seat's lease is still counted in `leasesActive`.

    Not whether the lease is live. Checkout counts a lease once,
    and only the sweep, a release or a revoke uncounts it — so a lease that has
    run out but not been swept yet is still in the count. Clearing one of
    those without decrementing takes it out of the sweep's reach too, and the
    slot is lost for good: the pool reads full with nobody on it.
    """
    return seat.get("leaseExpiresAt") is not None


def _license_past_grace(lic: dict) -> bool:
    """True once a license grants nothing, grace included.

    Reads the license document rather than the user mirror, because at
    activation there is no mirror yet. Fails open on a malformed timestamp,
    matching _expiry_state.
    """
    ends = grace_ends_at(lic.get("expiresAt"), lic.get("graceDays") or 0)
    if ends is None:
        return False
    try:
        return ends <= _now()
    except TypeError:
        return False


def get_license(license_id: str) -> dict | None:
    snap = db().collection("licenses").document(license_id).get()
    return snap.to_dict() if snap.exists else None


def _delete_query_until_empty(query) -> int:
    """Delete a query result in bounded batches, repeating until empty."""
    deleted = 0
    while True:
        docs = list(query.limit(_BATCH_LIMIT).stream())
        if not docs:
            return deleted
        _delete_refs([doc.reference for doc in docs])
        deleted += len(docs)


def _delete_refs(refs: list) -> None:
    """Deletes every reference, chunked to Firestore's per-batch write cap."""
    for start in range(0, len(refs), _BATCH_LIMIT):
        batch = db().batch()
        for ref in refs[start:start + _BATCH_LIMIT]:
            batch.delete(ref)
        batch.commit()


def _update_refs(updates: list) -> None:
    """Applies every `(reference, patch)`, chunked to the per-batch write cap.

    For fan-outs that used to write one document per round trip. A batch is
    atomic per chunk, not across chunks; callers only use it for writes that
    are safe to repeat, so a failure part-way is repaired by running it again.
    """
    for start in range(0, len(updates), _BATCH_LIMIT):
        batch = db().batch()
        for ref, patch in updates[start:start + _BATCH_LIMIT]:
            batch.update(ref, patch)
        batch.commit()


def _get_all(refs: list) -> list:
    """Snapshots for every reference, in `_BATCH_LIMIT` chunks, one call each.

    `get_all` answers in no particular order, so callers key the result by
    `snap.id` or `snap.reference`, never by position.
    """
    out = []
    for start in range(0, len(refs), _BATCH_LIMIT):
        out.extend(db().get_all(refs[start:start + _BATCH_LIMIT]))
    return out


def _cursor_page(col, query, limit: int, page_token: str | None,
                 order_field: str = "__name__", descending: bool = False):
    """One page of [query]. Returns (docs, next_token).

    Ordered by document id unless `order_field` is given; the token is always
    the last document's id, and the cursor is that document's snapshot, which
    carries the order field's value. Reads one document past [limit] so the
    token is only issued when there really is a next page. A token naming a
    document that has since been deleted restarts from the beginning rather
    than failing.
    """
    if order_field == "__name__":
        query = query.order_by("__name__")
    else:
        query = query.order_by(
            order_field,
            direction=firestore.Query.DESCENDING if descending else firestore.Query.ASCENDING,
        )
    query = query.limit(limit + 1)
    if page_token:
        cursor = col.document(page_token).get()
        if cursor.exists:
            query = query.start_after(cursor)
    docs = list(query.stream())
    if len(docs) > limit:
        docs = docs[:limit]
        return docs, docs[-1].id
    return docs, None
