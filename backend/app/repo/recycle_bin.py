"""The recycle bin (ADR-035): a deleted cloud backup, held 30 days.

`DELETE /v1/sessions/{sid}` no longer erases. It moves the session document
to `deleted_sessions/{sid}`, a tombstone in its own collection, as ADR-007
holds a deleted licence in `deleted_licenses`. A status on the session
document would not hold: provisioning and file completion write the session
document from paths that do not know the bin, and every listing, count and
ownership check would need a filter. A session that is not in `sessions` is
absent from all of them at once.

A tombstone is in one of three states:

* `binned`: the live session's fields, plus `priorStatus`, `deletedAt`,
  `deletedByDeviceId`, `deletedByApp`, `filesBinned` and `purgeAt`.
* `purging`: the same, while `purge_one` deletes its Drive folder (`purgingAt`
  says since when, so a purge that died is taken over after `PURGE_LEASE`).
* `erased`: replaced, not patched, by a marker of ids alone: `uid`, `app`,
  `localSessionId`, `erasedAt`, `erasedReason` and `expireAt`. The Firestore
  TTL policy on `deleted_sessions.expireAt` deletes the marker. `purgeAt` is
  not a TTL field: the purge job deletes the Drive folder first.

The session's file documents stay in `files`, flagged `binnedAt`. They are
flagged before the move and unflagged after a restore, so a crash between the
two steps leaves files that refuse completion and download (fail closed).
The Drive bytes stay where they are; binning makes no Drive call.
"""
from datetime import timedelta

from .. import apps, errors, statuses
from ..config import settings
from ..errors import Refusal
from ..licenses import as_utc

from . import _base
from ._base import (
    _BATCH_LIMIT,
    SCHEMA_VERSION,
    _delete_query_until_empty,
    _get,
    _now,
    _run_tx,
    _scan,
    _set_doc,
    _stream,
    _update_doc,
    _update_refs,
    db,
)
from .sessions import _file_view, _MANIFEST_FIELDS, session_app


DELETED_SESSIONS = "deleted_sessions"

STATE_BINNED = "binned"
STATE_PURGING = "purging"
STATE_ERASED = "erased"

#: Why an entry was erased: the holder deleted it for good, or its 30 days ran out.
ERASED_BY_USER = "user"
ERASED_EXPIRED = "expired"

#: How long a purge may hold an entry before another may take it over: past
#: the Cloud Run request timeout (300 s) with room, so a live purge is never
#: run twice and a dead one is not stuck for good.
PURGE_LEASE = timedelta(minutes=15)

#: What a tombstone adds to the session it holds; stripped on restore.
TOMBSTONE_FIELDS = ("state", "priorStatus", "deletedAt", "deletedByDeviceId", "deletedByApp",
                    "filesBinned", "purgeAt", "purgingAt")

def _bin():
    return db().collection(DELETED_SESSIONS)


def _tomb_view(sid: str, data: dict) -> dict:
    return {**data, "sessionId": sid}


def get_binned_session(sid: str) -> dict | None:
    """The tombstone `deleted_sessions/{sid}` in any state, or None."""
    snap = _get(_bin().document(sid))
    return _tomb_view(sid, snap.to_dict() or {}) if snap.exists else None


def in_recycle_bin(entry: dict | None) -> bool:
    """Whether `entry` is still in the bin: binned, or being purged."""
    return bool(entry) and entry.get("state") in (STATE_BINNED, STATE_PURGING)


def purge_is_due(entry: dict | None) -> bool:
    """Whether a bin entry's 30 days have run out (`purgeAt` passed)."""
    purge_at = as_utc((entry or {}).get("purgeAt"))
    return purge_at is not None and purge_at <= _now()


def flag_session_files(sid: str, binned: bool) -> int:
    """Stamp `binnedAt` on every file document of `sid`, or clear it. Returns
    how many there were. Batched like `set_file_upload_urls`, and safe to
    repeat: a crash part way is repaired by running it again."""
    col = db().collection("files")
    refs = [d.reference for d in _scan(col, col.where("sessionId", "==", sid))]
    value = _base.firestore.SERVER_TIMESTAMP if binned else _base.firestore.DELETE_FIELD
    _update_refs([(ref, {"binnedAt": value}) for ref in refs])
    return len(refs)


def move_to_bin(sid: str, uid: str, *, device_id: str | None, app: str,
                files_binned: int) -> dict | None:
    """Move `sessions/{sid}` into the bin, in one transaction: the tombstone
    is written and the session deleted together. Returns the tombstone as
    written (with `purgeAt` as a datetime), or None when the session is not
    there (or not `uid`'s), and the caller reads the bin instead."""
    session_ref = db().collection("sessions").document(sid)
    tomb_ref = _bin().document(sid)
    purge_at = _now() + timedelta(days=settings.RECYCLE_BIN_DAYS)

    @_base.firestore.transactional
    def _move(tx) -> dict | None:
        snap = _get(session_ref, tx)
        if not snap.exists:
            return None
        session = snap.to_dict() or {}
        if session.get("uid") != uid:
            return None
        tomb = {
            **session,
            "state": STATE_BINNED,
            "priorStatus": session.get("status") or "",
            "deletedAt": _base.firestore.SERVER_TIMESTAMP,
            "deletedByDeviceId": device_id or "",
            "deletedByApp": apps.stored_name(app),
            "filesBinned": files_binned,
            "purgeAt": purge_at,
            "schemaVersion": SCHEMA_VERSION,
        }
        tx.set(tomb_ref, tomb)
        tx.delete(session_ref)
        return _tomb_view(sid, {**tomb, "deletedAt": _now()})

    def _contended() -> dict | None:
        # Every attempt lost: the session is being written right now (a file
        # completing). Answer what the bin holds; the app retries a None.
        tomb = get_binned_session(sid)
        return tomb if in_recycle_bin(tomb) and tomb.get("uid") == uid else None

    return _run_tx(_move, on_contended=_contended)


def restore_from_bin(sid: str, uid: str, app: str) -> dict:
    """Put a binned session back in `sessions`, in one transaction, with its
    status as it was when it was binned. Returns the restored session.

    Refuses 404 `session_not_found` for an entry that is not there, not the
    caller's, another app's, being purged, erased, or past its `purgeAt`
    (the purge job can run a day late; the entry is gone by then as far as
    the holder is concerned). The files are unflagged after, by the caller."""
    session_ref = db().collection("sessions").document(sid)
    tomb_ref = _bin().document(sid)

    @_base.firestore.transactional
    def _restore(tx) -> dict:
        snap = _get(tomb_ref, tx)
        tomb = (snap.to_dict() or {}) if snap.exists else {}
        purge_at = as_utc(tomb.get("purgeAt"))
        if (tomb.get("state") != STATE_BINNED or tomb.get("uid") != uid
                or not apps.same_app(session_app(tomb), app)
                or purge_at is None or purge_is_due(tomb)):
            raise Refusal(errors.SESSION_NOT_FOUND)
        session = {k: v for k, v in tomb.items() if k not in TOMBSTONE_FIELDS}
        session.update({
            "status": tomb.get("priorStatus") or statuses.SESSION_COMPLETED,
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            "schemaVersion": SCHEMA_VERSION,
        })
        tx.set(session_ref, session)
        tx.delete(tomb_ref)
        return {**session, "sessionId": sid}

    def _contended() -> dict:
        raise Refusal(errors.CLAIM_CONTENDED)

    return _run_tx(_restore, on_contended=_contended)


def find_binned_session(uid: str, local_session_id: str) -> dict | None:
    """The account's binned (or purging) entry for this analysis, if any:
    what `POST /v1/sessions` refuses a re-upload of. An erased marker does not
    count: a permanent delete leaves the holder free to back it up again.
    Two equality filters, so the automatic indexes serve it."""
    if not local_session_id:
        return None
    q = (_bin().where("uid", "==", uid)
         .where("localSessionId", "==", local_session_id))
    for d in _stream(q):
        entry = _tomb_view(d.id, d.to_dict() or {})
        if in_recycle_bin(entry):
            return entry
    return None


def list_recycle_bin(uid: str, limit: int, page_token: str | None,
                     app: str | None) -> tuple[list[dict], str | None]:
    """One page of the account's bin, binned, purging and erased alike, in
    id order. `app` keeps that app's entries alone, filtered here over the
    one `uid` query as `list_user_sessions` filters (an entry from before the
    `app` tag is Semper's); None keeps all. Returns (entries, next_token)."""
    col = _bin()
    out: list[dict] = []
    scan = _scan(col, col.where("uid", "==", uid), page_token, chunk=limit + 1)
    for d in scan:
        entry = _tomb_view(d.id, d.to_dict() or {})
        if app is not None and not apps.same_app(session_app(entry), app):
            continue
        out.append(entry)
        if len(out) == limit:
            return out, (d.id if next(scan, None) is not None else None)
    return out, None


def iter_recycle_bin(uid: str):
    """Every entry of the account's bin, any state, for the export and the
    erasure's Drive fallback."""
    col = _bin()
    for d in _scan(col, col.where("uid", "==", uid)):
        yield _tomb_view(d.id, d.to_dict() or {})


def binned_files(sid: str) -> list[dict]:
    """A binned session's file manifest, as the export lists a live one's."""
    col = db().collection("files")
    return [_file_view(d.id, d.to_dict() or {}, *_MANIFEST_FIELDS)
            for d in _scan(col, col.where("sessionId", "==", sid), chunk=_BATCH_LIMIT)]


def due_for_purge(limit: int) -> list[dict]:
    """Entries whose `purgeAt` has passed, oldest first, at most `limit`.
    An erased marker has no `purgeAt`, so the range leaves it out. One field,
    ranged and ordered: the automatic index serves it."""
    q = (_bin().where("purgeAt", "<=", _now())
         .order_by("purgeAt").limit(limit))
    return [_tomb_view(d.id, d.to_dict() or {}) for d in _stream(q)]


def claim_purge(sid: str, uid: str | None = None) -> dict | None:
    """Move a binned entry to `purging`, in a transaction, and return it; None
    when there is nothing for this caller to purge: no entry, another
    account's (`uid` given), already erased, or purged by someone else right
    now (a `purging` entry within `PURGE_LEASE`). One that has sat in
    `purging` past the lease is a purge that died, and is taken over."""
    ref = _bin().document(sid)

    @_base.firestore.transactional
    def _claim(tx) -> dict | None:
        snap = _get(ref, tx)
        if not snap.exists:
            return None
        tomb = snap.to_dict() or {}
        if uid is not None and tomb.get("uid") != uid:
            return None
        state = tomb.get("state")
        if state == STATE_PURGING:
            since = as_utc(tomb.get("purgingAt"))
            if since is not None and since > _now() - PURGE_LEASE:
                return None
        elif state != STATE_BINNED:
            return None
        patch = {"state": STATE_PURGING, "purgingAt": _now()}
        tx.update(ref, patch)
        return _tomb_view(sid, {**tomb, **patch})

    return _run_tx(_claim, on_contended=lambda: None)


def release_purge(sid: str) -> None:
    """A purge that failed puts its entry back to `binned`, due again."""
    _update_doc(_bin().document(sid), {"state": STATE_BINNED,
                                       "purgingAt": _base.firestore.DELETE_FIELD})


def erase_binned(sid: str, entry: dict, reason: str) -> int:
    """The last steps of a purge, once its Drive folder is gone: delete the
    session's file documents, then replace the entry with the erased marker.
    Returns how many file documents went."""
    removed = _delete_query_until_empty(
        db().collection("files").where("sessionId", "==", sid))
    erased_at = _now()
    _set_doc(_bin().document(sid), {
        "state": STATE_ERASED,
        "uid": entry.get("uid"),
        "app": entry.get("app") or apps.stored_name(apps.SEMPER),
        "localSessionId": entry.get("localSessionId") or "",
        "erasedAt": erased_at,
        "erasedReason": reason,
        "expireAt": erased_at + timedelta(days=settings.ERASED_MARKER_DAYS),
    })
    return removed
