"""The recycle bin's flows (ADR-035) behind `routers/recycle_bin.py`, the bin
half of `DELETE /v1/sessions/{sid}`, and the daily purge job.

Binning flags the files, then moves the session (`repo.move_to_bin`); no
Drive call. A restore needs room under the quota, as a new backup does
(`sessions.require_capacity`). A purge, by the job or by the holder's
permanent delete, is `purge_one`: claim, Drive, file docs, erased marker,
audit row, in that order, so a Drive failure leaves the entry binned and due
again.
"""
import logging

from .. import apps, audit, drive, errors, repo
from .. import observability as obs
from ..config import settings
from ..errors import Refusal
from . import sessions as session_service

log = logging.getLogger("semper")

#: How many of the caller's own due entries `GET /v1/recycle-bin` purges on
#: its way, so an entry past its 30 days does not wait for the daily job.
OPPORTUNISTIC_PURGES = 3


# ---------------- answers ----------------
def bin_item(entry: dict) -> dict:
    """One binned analysis as `GET /v1/recycle-bin` and the export list it."""
    return {
        "sessionId": entry["sessionId"],
        "localSessionId": entry.get("localSessionId") or "",
        "name": entry.get("name") or "",
        "specimen": entry.get("specimen"),
        "app": repo.session_app(entry),
        # As it was when it was binned, which a restore puts back.
        "status": entry.get("priorStatus") or "",
        "fileCount": entry.get("fileCount", 0),
        "totalBytes": entry.get("totalBytes", 0),
        "deletedAt": entry.get("deletedAt"),
        "purgeAt": entry.get("purgeAt"),
    }


def erased_item(entry: dict) -> dict:
    """One erased marker: ids alone, so another phone can tell "deleted" from "lost"."""
    return {
        "sessionId": entry["sessionId"],
        "localSessionId": entry.get("localSessionId") or "",
        "erasedAt": entry.get("erasedAt"),
    }


def _delete_answer(sid: str, entry: dict) -> dict:
    """`DELETE /v1/sessions/{sid}`'s answer for a binned session. `deleted`
    and `filesRemoved` keep their names for installed builds; the files are
    binned, not removed."""
    return {"deleted": sid, "filesRemoved": entry.get("filesBinned", 0),
            "binned": True, "purgeAt": entry.get("purgeAt")}


# ---------------- DELETE /v1/sessions/{sid} ----------------
def bin_session(user: dict, session: dict, device_id: str | None, app: str) -> dict:
    """Move one of the caller's sessions into the bin and audit it
    `SESSION_BIN`. The files are flagged first: a crash before the move
    leaves them refusing completion and download, never the reverse."""
    sid = session["sessionId"]
    flagged = repo.flag_session_files(sid, binned=True)
    entry = repo.move_to_bin(sid, user["uid"], device_id=device_id, app=app, files_binned=flagged)
    if entry is None:
        # Moved by a delete that raced this one, or gone altogether.
        return already_binned(sid, user["uid"])
    audit.record(user["uid"], device_id, action="SESSION_BIN",
                 target={"type": "session", "id": sid},
                 detail={"localSessionId": entry.get("localSessionId") or "",
                         "app": repo.session_app(entry), "filesBinned": flagged,
                         "purgeAt": entry.get("purgeAt")})
    log.info("Binned session %s for uid %s (%d files)", sid, user["uid"], flagged)
    return _delete_answer(sid, entry)


def already_binned(sid: str, uid: str) -> dict:
    """A repeated delete of a session already in the caller's bin answers as
    the first did (200); anything else is 404 `session_not_found`, as a
    delete of a session that is gone always was."""
    entry = repo.get_binned_session(sid)
    if not repo.in_recycle_bin(entry) or entry.get("uid") != uid:
        raise Refusal(errors.SESSION_NOT_FOUND)
    return _delete_answer(sid, entry)


# ---------------- POST /v1/sessions ----------------
def refuse_if_binned(uid: str, local_session_id: str) -> None:
    """409 `session_in_recycle_bin: <sid>` when this analysis is in the
    caller's bin: a phone that still holds it as backed up would otherwise
    upload it again behind the holder's back."""
    entry = repo.find_binned_session(uid, local_session_id)
    if entry is not None:
        raise Refusal(errors.SESSION_IN_RECYCLE_BIN, entry["sessionId"])


# ---------------- GET /v1/recycle-bin ----------------
def listing(uid: str, app: str | None, page_size: int, page_token: str | None) -> dict:
    """One page of the caller's bin: binned entries under `items`, erased
    markers under `erased`. Up to `OPPORTUNISTIC_PURGES` of the page's due
    entries are purged on the way and answered as erased."""
    entries, next_token = repo.list_recycle_bin(uid, page_size, page_token, app)
    items, erased = [], []
    purges = 0
    for entry in entries:
        if repo.in_recycle_bin(entry) and purges < OPPORTUNISTIC_PURGES and repo.purge_is_due(entry):
            purges += 1
            if _purge_quietly(entry["sessionId"], uid):
                entry = repo.get_binned_session(entry["sessionId"]) or entry
        if entry.get("state") == repo.STATE_ERASED:
            erased.append(erased_item(entry))
        elif repo.in_recycle_bin(entry):
            items.append(bin_item(entry))
    return {"items": items, "erased": erased, "retentionDays": settings.RECYCLE_BIN_DAYS,
            "page": {"size": page_size, "count": len(entries), "nextPageToken": next_token,
                     "hasMore": bool(next_token)}}


def _purge_quietly(sid: str, uid: str) -> bool:
    """`purge_one` for the listing: a failure is logged and left to the job,
    never the caller's error."""
    try:
        return purge_one(sid, repo.ERASED_EXPIRED, uid=uid) is not None
    except Exception as e:  # noqa: BLE001 - the listing must not fail for a purge
        obs.log_event(log, logging.WARNING, "recycle_bin_purge_failed", outcome="degraded",
                      errorCode=type(e).__name__, dependency="drive", stage="listing")
        return False


# ---------------- POST /v1/recycle-bin/{sid}/restore ----------------
def restore(user: dict, sid: str, device_id: str | None, app: str) -> dict:
    """Put a binned analysis back, if the caller has room for it, and audit
    it `SESSION_BIN_RESTORE`. A repeat of a restore that went through
    answers the same, and unflags the files again in case the first stopped
    before it did."""
    uid = user["uid"]
    entry = repo.get_binned_session(sid)
    if entry is None:
        session = repo.get_session(sid)
        if session and session.get("uid") == uid and apps.same_app(repo.session_app(session), app):
            repo.flag_session_files(sid, binned=False)
            return _restored(sid, session)
        raise Refusal(errors.SESSION_NOT_FOUND)
    if entry.get("state") != repo.STATE_BINNED or entry.get("uid") != uid \
            or not apps.same_app(repo.session_app(entry), app) or repo.purge_is_due(entry):
        raise Refusal(errors.SESSION_NOT_FOUND)
    session_service.require_capacity(user, repo.resolve_user_config(user, app), device_id, app)
    session = repo.restore_from_bin(sid, uid, app)
    repo.flag_session_files(sid, binned=False)
    audit.record(uid, device_id, action="SESSION_BIN_RESTORE",
                 target={"type": "session", "id": sid},
                 detail={"localSessionId": session.get("localSessionId") or "",
                         "app": repo.session_app(session)})
    return _restored(sid, session)


def _restored(sid: str, session: dict) -> dict:
    return {"sessionId": sid, "localSessionId": session.get("localSessionId") or "",
            "status": session.get("status"), "restored": True}


# ---------------- DELETE /v1/recycle-bin/{sid} and the job ----------------
def purge_one(sid: str, reason: str, *, uid: str | None = None,
              device_id: str | None = None) -> dict | None:
    """Erase one bin entry for good. Returns the marker's answer, or None
    when there was nothing for this caller to purge (gone, another account's,
    already erased, or being purged right now).

    1. Claim it (`binned` to `purging`, in a transaction).
    2. Delete its Drive folder. Drive's 404 is gone already
       (`drive.delete_file`). Any failure puts the entry back to `binned`
       and raises: the job finds it due again tomorrow.
    3. Delete its file docs, then 4. write the erased marker.
    5. Audit `SESSION_PURGE`.
    """
    entry = repo.claim_purge(sid, uid)
    if entry is None:
        return None
    folder = entry.get("driveFolderId")
    try:
        if folder:
            drive.delete_file(drive.access_token(), folder)
    except Exception:
        repo.release_purge(sid)
        raise
    removed = repo.erase_binned(sid, entry, reason)
    # The owner's row, whoever purged it: `reason` says whether they did.
    audit.record(entry.get("uid"), device_id, action="SESSION_PURGE",
                 target={"type": "session", "id": sid},
                 detail={"reason": reason, "filesRemoved": removed,
                         "localSessionId": entry.get("localSessionId") or ""})
    log.info("Purged binned session %s (%s, %d files)", sid, reason, removed)
    return {"sessionId": sid, "localSessionId": entry.get("localSessionId") or "",
            "filesRemoved": removed, "erased": True}


def purge_permanently(user: dict, sid: str, device_id: str | None, app: str) -> dict:
    """The holder's "Delete permanently". 404 for an entry that is not the
    caller's binned one, as a restore answers; a repeat after it went answers
    the marker, so the app's retry of a call whose answer it lost succeeds."""
    entry = repo.get_binned_session(sid)
    if not entry or entry.get("uid") != user["uid"] \
            or not apps.same_app(repo.session_app(entry), app):
        raise Refusal(errors.SESSION_NOT_FOUND)
    if entry.get("state") == repo.STATE_ERASED:
        return {"sessionId": sid, "localSessionId": entry.get("localSessionId") or "",
                "filesRemoved": 0, "erased": True}
    done = purge_one(sid, repo.ERASED_BY_USER, uid=user["uid"], device_id=device_id)
    if done is None:
        # Another purge holds it right now; it will be erased in a moment.
        raise Refusal(errors.CLAIM_CONTENDED)
    return done


def purge_due() -> dict:
    """Cloud Scheduler, daily: purge every entry past its `purgeAt`, up to
    `RECYCLE_BIN_PURGE_BATCH`. One failure does not stop the rest; it is
    logged, counted, and due again tomorrow. Answers `{due, purged, failed}`."""
    due = repo.due_for_purge(settings.RECYCLE_BIN_PURGE_BATCH)
    purged, failed = [], []
    for entry in due:
        sid = entry["sessionId"]
        try:
            if purge_one(sid, repo.ERASED_EXPIRED) is not None:
                purged.append(sid)
        except Exception as e:  # noqa: BLE001 - one entry must not stop the run
            obs.log_event(log, logging.ERROR, "recycle_bin_purge_failed", outcome="error",
                          errorCode=type(e).__name__, dependency="drive", stage="job")
            failed.append(sid)
    return {"due": len(due), "purged": purged, "failed": failed}
