package com.sempermechanics.semper.data.net

import org.json.JSONException
import org.json.JSONObject

/**
 * The error codes the backend puts in FastAPI's `{"detail": "<code>"}` body.
 *
 * Mirrors `backend/app/errors.py` — the two lists are pinned against each other
 * by `backend/tests/test_error_codes.py`, so renaming a code on one side fails
 * the other side's CI instead of silently changing what a 409 means here.
 *
 * Match with [hasCode] rather than `String.contains`: a substring test also
 * matches a code quoted inside a longer human message, which is how a
 * "device conflict" reading of an unrelated 409 gets into a bug report.
 */
object ApiErrors {

    /** The account exists but is not APPROVED (403). */
    const val NOT_APPROVED = "not_approved"

    /** The caller is not an admin (403 from an admin route). */
    const val NOT_ADMIN = "not_admin"

    /**
     * This build could not be attested (403), while the backend is enforcing
     * App Check. It says nothing about the account — an entitled user on a
     * sideloaded or tampered build lands here — so it is rendered as "update
     * from the Play Store", never as a licence problem.
     */
    const val APP_CHECK_REQUIRED = "app_check_required"

    /** The app tried to accept a Terms version the server no longer serves (409). */
    const val TERMS_VERSION_MISMATCH = "terms_version_mismatch"

    /** This account is already bound to a different device (409). */
    const val DEVICE_CONFLICT = "device_conflict"

    /** This device is already bound to a different account (409). */
    const val DEVICE_IN_USE = "device_in_use"

    /** No ACTIVE device record for this caller — re-register and retry (409). */
    const val DEVICE_NOT_ACTIVE = "device_not_active"

    /** Single-use nonce missing, expired or replayed (401). */
    const val NONCE_INVALID_OR_REPLAYED = "nonce_invalid_or_replayed"

    /** Device signature did not verify (401). */
    const val BAD_SIGNATURE = "bad_signature"

    /** No such cloud session for this account (404). */
    const val SESSION_NOT_FOUND = "session_not_found"

    /**
     * The session's upload has not completed, so its metadata.json cannot be
     * replaced yet (409 from `PUT /v1/sessions/{sid}/metadata`).
     */
    const val SESSION_NOT_COMPLETE = "session_not_complete"

    /** No such cloud file for this account (404). */
    const val FILE_NOT_FOUND = "file_not_found"

    /**
     * The index points at a Drive object that is no longer there (404 on a
     * download, 400 on a completion). Deleted in Drive, or the upload never
     * landed — not an outage, so retrying cannot fix it.
     */
    const val DRIVE_FILE_GONE = "drive_file_gone"

    /**
     * `:complete` found the file record changed or gone (409): the cloud session
     * cannot be finished, so the upload deletes it and recreates it.
     */
    const val SIZE_OR_STATE_MISMATCH = "size_or_state_mismatch"

    /**
     * `:complete` found Drive's object has other bytes (422) — md5 or size. The
     * upload restages and recreates, a bounded number of times.
     */
    const val CHECKSUM_MISMATCH = "checksum_mismatch"

    /** See [CHECKSUM_MISMATCH]. */
    const val SIZE_MISMATCH = "size_mismatch"

    /** Per-instance token bucket or gateway quota rejected the call (429). */
    const val RATE_LIMITED = "rate_limited"

    /** The account is at its stored-analysis cap (409). Carries a count tail. */
    const val SESSION_QUOTA_EXCEEDED = "session_quota_exceeded"

    /**
     * Every seat on the institution's floating licence is in use (409).
     *
     * Not a fault in the account: the caller stays on the roster and gets a
     * seat as soon as a colleague finishes, so this is rendered as "try again",
     * never as an error.
     */
    const val NO_FLOATING_SEAT = "no_floating_seat"

    /** Cloud backup/share refused because the account is on demo (403). */
    const val FEATURE_NOT_LICENSED = "feature_not_licensed"

    /**
     * This device is not the one the licence is bound to (403). Usually means
     * restore was attempted before the lock moved, or on the wrong phone.
     */
    const val LICENSE_DEVICE_MISMATCH = "license_device_mismatch"

    /**
     * The `detail` of an error [body], or the trimmed body when it is not the
     * usual JSON envelope — API Gateway and Cloud Run kills answer with plain
     * text or nothing at all, and those must not read as a code.
     */
    fun detailOf(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return try {
            JSONObject(trimmed).optString("detail", trimmed)
        } catch (_: JSONException) {
            trimmed
        }
    }

    /**
     * Whether an already-extracted [detail] reports exactly [code].
     *
     * A trailing `: …` counts: `session_quota_exceeded` arrives as
     * "session_quota_exceeded: 5/5 analyses stored." so the caller can show the
     * numbers, and the code in front of the colon is still the whole meaning.
     *
     * Testing several codes against one response goes through this, so the body
     * is parsed once rather than once per candidate.
     */
    fun isCode(detail: String, code: String): Boolean =
        detail == code || detail.startsWith("$code:")

    /** [isCode] for a caller that holds the raw body rather than the detail. */
    fun hasCode(body: String, code: String): Boolean = isCode(detailOf(body), code)
}
