import logging
import time
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .. import audit, drive, errors, firestore_repo as repo, legal
from .. import rate_limit
from ..deps import any_status_user, current_user, rate_limited, verified_device
from ._shared import json_dumps

log = logging.getLogger("semper")
router = APIRouter()


def _terms_block(user: dict) -> dict:
    """What the app compares to decide whether to show the acceptance screen."""
    accepted = user.get("termsAccepted") or {}
    return {
        "required_version": legal.TERMS_VERSION,
        "accepted_version": accepted.get("version"),
        "terms_url": legal.TERMS_URL,
        "privacy_url": legal.PRIVACY_URL,
    }


def _improvement_consent(user: dict) -> bool | None:
    """None until the user has answered; the app treats None as "not asked"."""
    consent = user.get("improvementConsent")
    return None if not consent else bool(consent.get("granted"))


@router.get("/v1/me")
def me(user=Depends(current_user)):
    """Who the caller is, and what their license currently grants.

    The `license` block answers "am I entitled, and for how much longer" in the
    same round trip as identity, so the app can warn about an approaching
    expiry instead of only discovering it when `mode` silently flips to demo.
    It costs no extra Firestore read — `current_user` already returns the whole
    user document, and the summary is pure over it.

    `/v1/config` remains the source of truth for limits and feature flags; this
    is deliberately the smaller answer.
    """
    summary = repo.license_summary(user)
    return {
        "uid": user["uid"],
        "email": user.get("email"),
        "role": user.get("role"),
        "access_status": user["access_status"],
        "license": {
            "mode": summary["mode"],
            "kind": summary["licenseKind"],
            "prefix": summary["prefix"],
            "duration": summary["duration"],
            # Both null when perpetual. inGrace is past expiry but still fully
            # entitled — a warning, not a restriction.
            "expiresAt": summary["expiresAt"],
            "graceEndsAt": summary["graceEndsAt"],
            "inGrace": summary["inGrace"],
            # Seating and the lease are here so a page that renders "you hold
            # a floating seat until 14:20" needs this call alone. `/v1/config`
            # carries them too, but it is the larger answer and a browser
            # asking "what am I?" should not have to fetch limits to find out.
            "seating": summary["seating"],
            "leaseExpiresAt": summary["leaseExpiresAt"],
            "held": summary["held"],
        },
        "terms": _terms_block(user),
        "improvement_consent": _improvement_consent(user),
    }


class TermsAcceptance(BaseModel):
    version: str = Field(min_length=1, max_length=32)


class ConsentUpdate(BaseModel):
    improvement: bool


def _consent_source(x_device_id: str) -> str:
    return "app" if x_device_id else "console"


@router.post("/v1/me/terms")
def accept_terms(
    body: TermsAcceptance,
    user=Depends(any_status_user),
    x_device_id: str = Header(default=""),
):
    """Record clickwrap acceptance of the current Terms of Service.

    PENDING accounts may call this — the gate runs at registration, before
    approval. The version is checked against the server's, not trusted from the
    client: an app that ships an older document must not be able to record
    agreement to terms the user never saw.
    """
    if body.version != legal.TERMS_VERSION:
        raise HTTPException(409, errors.TERMS_VERSION_MISMATCH)
    uid = user["uid"]
    record = repo.record_terms_acceptance(
        uid, body.version, x_device_id or None, _consent_source(x_device_id),
    )
    audit.record(uid, x_device_id or None, action="TERMS_ACCEPTED",
                 target={"type": "user", "id": uid}, detail={"version": body.version})
    return {"terms": {**_terms_block({"termsAccepted": record}),
                      "accepted_at": record["acceptedAt"]}}


@router.put("/v1/me/consents")
def set_consents(
    body: ConsentUpdate,
    user=Depends(any_status_user),
    x_device_id: str = Header(default=""),
):
    """Grant or withdraw the optional product-improvement consent.

    Kept apart from Terms acceptance on purpose: consent to secondary use of the
    user's content has to be a separate, freely revocable choice, not a
    condition of the service.
    """
    uid = user["uid"]
    record = repo.record_improvement_consent(
        uid, body.improvement, legal.TERMS_VERSION, x_device_id or None,
        _consent_source(x_device_id),
    )
    audit.record(uid, x_device_id or None, action="CONSENT_CHANGED",
                 target={"type": "user", "id": uid},
                 detail={"improvement": record["granted"], "version": record["version"]})
    return {"improvement_consent": record["granted"], "updated_at": record["at"]}


@router.get("/v1/config")
def app_config(user=Depends(current_user)):
    """Resolved plan, entitlements, and product limits for the caller."""
    return repo.resolve_user_config(user)


@router.get("/v1/me/export", dependencies=[rate_limited(rate_limit.export_bucket)])
def export_account(ctx=Depends(verified_device)):
    """GDPR data portability (Art. 20): everything we hold about the caller, as JSON.

    Structured and machine-readable: the profile, registered devices, and every
    analysis with its full file manifest. The binary artifacts themselves stay
    downloadable via /v1/files/{id}/content (each file's `fileId` is included),
    which is also what the app's Restore screen uses.

    Device-signed (like DELETE /v1/me): a full-account export is high-consequence
    enough that a stolen ID token alone must not be able to trigger it.
    """
    user = ctx["user"]
    uid = user["uid"]
    profile = repo.get_user(uid) or {}

    audit.record(uid, action="DATA_EXPORT", target={"type": "user", "id": uid})
    header = {
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
                    "using the fileId values above, or via the app's Restore screen.",
        },
    }

    def stream():
        """Emit the document incrementally.

        The previous version built the whole account in memory first — every
        session, plus a files query per session — before writing a byte. For a
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
        for s in repo.iter_all_user_sessions(uid):
            s = dict(s)
            s["files"] = repo.list_session_files_all(s["sessionId"])
            yield (b"" if first else b",") + json_dumps(s).encode()
            first = False
            count += 1
        yield b'],"sessionCount":' + str(count).encode()
        yield b',"complete":true}'

    return StreamingResponse(
        stream(),
        media_type="application/json",
        headers={
            "Content-Disposition": 'attachment; filename="semper-account-export.json"',
            # A full-account dump of personal data must not sit in a shared cache.
            "Cache-Control": "no-store",
        },
    )


@router.delete("/v1/me", dependencies=[rate_limited(rate_limit.erase_bucket)])
def delete_account(ctx=Depends(verified_device)):
    """Erase the account and ALL of its data (GDPR right to erasure).

    Deletes the user's entire Drive subtree in one shot (every analysis, plus
    anything orphaned by a failed sync), then every Firestore record: sessions,
    file pointers, device registrations and the user profile.

    Only the append-only audit trail survives — it records that actions
    (including this erasure) happened, and holds no analysis content. The caller
    must sign out afterwards: any further authenticated call would create a
    fresh, empty profile.
    """
    user, device = ctx["user"], ctx["device"]
    uid = user["uid"]
    started = time.monotonic()
    token = drive.access_token()

    # 1. The session folders live inside the user folder, so deleting that one
    #    removes the whole subtree in a single call. The per-session loop below
    #    is the fallback for accounts predating the stored pointer — walking N
    #    sessions is N sequential Drive round-trips, which is what made deleting
    #    a busy account take the best part of a minute.
    user_folder = user.get("driveFolderId")
    folders_deleted = 0
    if user_folder:
        drive.delete_file(token, user_folder)  # raises → we abort before Firestore
    else:
        # No stored pointer: delete each session's folder via its OWN stored id
        # rather than trusting a lookup by name. If a name lookup missed we would
        # silently skip Drive and still wipe the metadata, stranding the blobs
        # with nothing left pointing at them.
        sessions = repo.iter_user_sessions(uid)
        for s in sessions:
            folder = s.get("driveFolderId")
            if folder:
                drive.delete_file(token, folder)
                folders_deleted += 1
        # Then the scaffolding itself, and anything a failed sync orphaned.
        user_folder = drive.find_user_folder(token, uid)
        if user_folder:
            drive.delete_file(token, user_folder)
        else:
            log.warning("No Drive folder found for uid %s — nothing to purge there", uid)

    drive_ms = int((time.monotonic() - started) * 1000)

    # 3. Only once the blobs are gone: erase the metadata.
    counts = repo.delete_all_user_data(uid)
    total_ms = int((time.monotonic() - started) * 1000)
    # Timed per phase: erasure is the one call a user waits on with nothing to
    # look at, so when it feels slow the log should say which half was slow.
    detail = {
        **counts,
        "driveFolders": folders_deleted,
        "userFolderFound": bool(user_folder),
        "driveMs": drive_ms,
        "firestoreMs": total_ms - drive_ms,
        "totalMs": total_ms,
    }
    audit.record(uid, device.get("deviceId"), action="ACCOUNT_DELETE",
                 target={"type": "user", "id": uid}, detail=detail)
    log.info("Erased account %s: %s", uid, detail)
    return {"deleted": uid, **detail}
