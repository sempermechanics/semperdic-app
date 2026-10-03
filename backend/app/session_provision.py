import logging
import time
from concurrent.futures import ThreadPoolExecutor

from . import drive, firestore_repo as repo, statuses
from . import observability as obs
from .config import settings

log = logging.getLogger("semper")


def purge_session(sid: str) -> None:
    """Delete a session's Drive folder AND its Firestore docs.

    repo.delete_session is Firestore-only, so using it alone as a rollback left
    the Drive subtree (and any resumable sessions already opened inside it)
    orphaned, with nothing left pointing at them.
    """
    session = repo.get_session(sid) or {}
    folder = session.get("driveFolderId")
    if folder:
        try:
            drive.delete_file(drive.access_token(), folder)
        except Exception as e:  # noqa: BLE001
            # Best effort: the Firestore rollback below still has to happen, or
            # the user is charged quota for a session they cannot use.
            log.warning("rollback could not delete Drive folder %s: %s", folder, e)
    repo.delete_session(sid)


def provision_session(sid: str, *, purge_on_failure: bool = False,
                      session: dict | None = None) -> dict:
    """Open a Drive resumable session for every file that still lacks one.

    Idempotent and resumable: it only looks at files with no uploadUrl, so a
    retried task never mints a second upload URI for a file that already has
    one. Runs in the Cloud Tasks worker, or inline when no queue is configured.

    `purge_on_failure` is for the inline path, where no retry is coming: the
    session is rolled back completely. The queued path instead leaves it
    PROVISION_FAILED so Cloud Tasks can retry and a polling client is told to
    stop waiting.

    The inline caller passes the `session` doc it has just written so it is not
    read again, and answers the client from the returned `uploads` (the
    targets opened here) instead of listing them back. The user doc is still
    read here: the folder pointers must come from the stored doc, not from
    whatever the auth layer handed the route.
    """
    session = session or repo.get_session(sid)
    if not session:
        return {"sessionId": sid, "provisioned": 0, "status": "gone", "uploads": []}
    if session.get("status") == statuses.SESSION_COMPLETED:
        # A retry that arrives after every file landed has nothing to open,
        # and must not report — or write — the session as uploading again.
        # `set_session_status` also refuses to leave COMPLETED, for a
        # completion that lands while this runs.
        return {"sessionId": sid, "provisioned": 0,
                "status": statuses.SESSION_COMPLETED, "uploads": []}
    uid = session["uid"]
    started = time.monotonic()

    try:
        token = drive.access_token()
        pending = list(repo.iter_unprovisioned_files(sid))
        roles = {f["role"] for f in pending}
        if roles:
            # Only create the Drive subfolders this manifest actually uses (a
            # bundle upload needs none — Session.zip and metadata.json sit at
            # the session root).
            user = repo.get_user(uid) or {}
            cached = {"userFolderId": user.get("driveFolderId"),
                      "sessionsFolderId": user.get("driveSessionsFolderId")}
            folders = drive.ensure_session_folders(
                token, uid, sid, roles=roles, cached=cached,
                session_folder_id=session.get("driveFolderId"))
            # Write only when the pointers changed — the common upload reuses them.
            if (folders["userFolderId"] != cached["userFolderId"]
                    or folders.get("sessionsFolderId") != cached["sessionsFolderId"]):
                repo.remember_user_folder(uid, folders["userFolderId"],
                                          folders.get("sessionsFolderId"))
            repo.set_session_folder(sid, folders["sessionFolderId"])
            folder_ms = (time.monotonic() - started) * 1000

            def open_one(f):
                uri = drive.init_resumable(token, folders[f["role"]], f["name"], f["sizeBytes"])
                repo.set_file_upload_url(f["fileId"], uri)
                return repo.upload_target(f["fileId"], uri, f)

            # Bounded fan-out rather than a serial loop — same pattern as
            # drive.probe_files. Serially this was the whole problem.
            # pool.map keeps the input order, which is the listing's (document id).
            workers = max(1, min(settings.TASKS_PROVISION_WORKERS, len(pending)))
            with ThreadPoolExecutor(max_workers=workers) as pool:
                uploads = list(pool.map(open_one, pending))
            provisioned = len(pending)
        else:
            uploads = []
            provisioned = 0
            folder_ms = 0.0
    except Exception as e:  # noqa: BLE001
        obs.log_event(log, logging.ERROR, "session_provision_failed",
                      outcome="error", errorCode="drive_provision_failed", dependency="drive")
        log.error("provisioning session %s failed: %s", sid, e)
        if purge_on_failure:
            # Nothing will retry, so leave nothing behind — including the Drive
            # subtree and any resumable sessions already opened inside it.
            purge_session(sid)
        else:
            # A retry is coming. Keep the session so the task can resume, and
            # mark it so a polling client stops waiting and rebuilds instead.
            repo.set_session_status(sid, statuses.SESSION_PROVISION_FAILED,
                                    error_code="drive_provision_failed")
        raise

    # Inline, the upload targets are still in this response, so nothing can
    # have completed yet and the COMPLETED guard (one more read) is skipped.
    repo.set_session_status(sid, statuses.SESSION_UPLOADING,
                            keep_completed=not purge_on_failure)
    obs.log_event(log, logging.INFO, "session_provisioned", outcome="ok",
                  count=provisioned, latencyMs=round((time.monotonic() - started) * 1000, 1),
                  folderMs=round(folder_ms, 1))
    return {"sessionId": sid, "provisioned": provisioned, "status": statuses.SESSION_UPLOADING,
            "uploads": uploads}
