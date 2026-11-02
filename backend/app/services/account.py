"""The account flows behind `routers/account.py` (TD-293): the streamed data
export and the erasure, in the order each step has to run."""
import logging
import time
from datetime import datetime, timezone

from .. import audit, drive, google_auth, repo
from ._shared import json_dumps

log = logging.getLogger("semper")


# ---------------- GET /v1/me/export ----------------
def export_header(uid: str) -> dict:
    """Everything in the export but the analyses: the profile, the contract
    and consent records, the registered devices, and how to fetch the bytes.
    Audits the export (`DATA_EXPORT`) before any of it is read out."""
    profile = repo.get_user(uid) or {}
    audit.record(uid, action="DATA_EXPORT", target={"type": "user", "id": uid})
    return {
        "exportedAtUtc": datetime.now(timezone.utc).isoformat(),
        "schema": "indic.account.export/1",
        "profile": {
            "uid": uid,
            "email": profile.get("email"),
            "displayName": profile.get("displayName"),
            "hostedDomain": profile.get("hd"),
            "role": profile.get("role"),
            "accessStatus": profile.get("access_status"),
            "createdAt": str(profile.get("createdAt")),
            "lastSeenAt": str(profile.get("lastSeenAt")),
        },
        # Contract and consent records are personal data too (right of access).
        "termsAccepted": profile.get("termsAccepted"),
        "improvementConsent": profile.get("improvementConsent"),
        "devices": repo.list_user_devices(uid),
        "artifactDownload": {
            "endpoint": "/v1/files/{fileId}/content",
            "note": "Images, .dat results, CSVs and reports are downloadable per file "
                    "using the fileId values above, or restore them in the app: "
                    "Settings → Analyses data management.",
        },
    }


def export_stream(uid: str, header: dict):
    """Emit the document incrementally.

    The previous version built the whole account in memory first — every
    session, plus a files query per session — before writing a byte. The
    files are now one ordered stream beside the sessions'. For a
    busy account that is an unbounded allocation and a long silence before
    the first byte, on a request with a 60s budget. Streaming keeps memory
    flat and starts the response immediately; `"complete": true` is written
    last, so a truncated transfer is detectable rather than looking like a
    smaller-but-valid export.
    """
    prefix = json_dumps(header)
    yield prefix[:-1].encode()  # drop the closing brace; we continue the object
    yield b',"sessions":['
    first = True
    count = 0
    for s in repo.iter_sessions_with_files(uid):
        yield (b"" if first else b",") + json_dumps(s).encode()
        first = False
        count += 1
    yield b'],"sessionCount":' + str(count).encode()
    yield b',"complete":true}'


# ---------------- DELETE /v1/me ----------------
def _purge_drive(token: str, user: dict) -> tuple[list[str], int]:
    """Delete the account's Drive subtree; `(user folders deleted, session
    folders deleted one by one)`. Raises on a Drive failure, so the erasure
    stops before Firestore.

    Every folder named {uid}, not only the stored pointer: two first uploads
    at once, or a folder create Drive made but answered 5xx, can leave two,
    and the user doc points at one. Each goes in one call with its whole
    subtree. Then each session's own stored `driveFolderId`: any still there
    (a tree the name walk did not reach, or one that predates the pointer) is
    deleted with a bounded fan-out (`drive.delete_files`). They are probed
    first, since after the user folders nearly all are gone, and a probe is
    one round trip where a delete of a missing folder is three.
    """
    uid = user["uid"]
    test = {"test": True} if repo.test_account_of(user) else {}
    user_folders = list(dict.fromkeys(
        f for f in (user.get("driveFolderId"), *drive.find_user_folders(token, uid, **test)) if f
    ))
    for folder in user_folders:
        drive.delete_file(token, folder)  # raises → we abort before Firestore
    if not user_folders:
        log.warning("No Drive user folder for uid %s; deleting its sessions' folders alone", uid)
    # Each session's folder by its OWN stored id rather than trusting a lookup
    # by name: if a name lookup missed we would silently skip Drive and still
    # wipe the metadata, stranding the blobs with nothing left pointing at
    # them. A probe that cannot tell (UNKNOWN) is deleted too, so its failure
    # stops the erasure.
    stored = [s.get("driveFolderId") for s in repo.iter_user_sessions(uid)]
    states = drive.probe_files(token, stored)
    folders_deleted = drive.delete_files(
        token, [fid for fid, state in states.items() if state != drive.MISSING],
    )
    return user_folders, folders_deleted


def erase_account(user: dict, device_id: str | None) -> dict:
    """Erase the account: Drive, then Firestore, then the Firebase Auth users,
    then the audit row. Returns the row's detail, which is also the answer."""
    uid = user["uid"]
    detail = erase_data(user)
    audit.record(uid, device_id, action="ACCOUNT_DELETE",
                 target={"type": "user", "id": uid}, detail=detail)
    log.info("Erased account %s: %s", uid, detail)
    return detail


def erase_data(user: dict) -> dict:
    """The erasure's steps, without its audit row: Drive, then Firestore, then
    the Firebase Auth users. Returns what was erased, timed per phase.

    Shared by the holder's own erasure (`erase_account`) and staff ending a
    test account (`services.testaccounts`), which audits it as its own."""
    uid = user["uid"]
    started = time.monotonic()
    token = drive.access_token()

    # 1. The blobs first, so a failure leaves the metadata pointing at them.
    user_folders, folders_deleted = _purge_drive(token, user)
    drive_ms = int((time.monotonic() - started) * 1000)

    # 2. Only once the blobs are gone: erase the metadata. The sign-ins are
    #    read first, since the erasure deletes the links that name them.
    auth_uids = repo.sign_in_uids(uid)
    counts = repo.delete_all_user_data(uid)
    firestore_ms = int((time.monotonic() - started) * 1000) - drive_ms
    # 3. Then the sign-ins: the account's own Auth user and every one linked
    #    to it (`linkedAuthUids`), each holding the address and the name.
    #    After the data, so a failure here leaves an empty account rather
    #    than data nobody can sign in to reach.
    auth_deleted = sum(1 for sub in auth_uids if google_auth.delete_auth_user(sub))
    total_ms = int((time.monotonic() - started) * 1000)
    # Timed per phase: erasure is the one call a user waits on with nothing to
    # look at, so when it feels slow the log should say which half was slow.
    detail = {
        **counts,
        "driveFolders": folders_deleted,
        "userFolderFound": bool(user_folders),
        "userFolders": len(user_folders),
        "authUserDeleted": auth_deleted == len(auth_uids),
        "authUsers": len(auth_uids),
        "authUsersDeleted": auth_deleted,
        "driveMs": drive_ms,
        "firestoreMs": firestore_ms,
        "totalMs": total_ms,
    }
    return detail
