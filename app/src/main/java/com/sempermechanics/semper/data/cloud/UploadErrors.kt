package com.sempermechanics.semper.data.cloud

import com.sempermechanics.semper.data.DicUploadWorker
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.HttpStatus
import com.sempermechanics.semper.data.session.SessionLayout
import java.io.File

/**
 * What an HTTP error means for a [DicUploadWorker] backup: which ones end it,
 * which mean the cloud session must be rebuilt, and which are only a bad
 * moment to retry. Pure, like [UploadWorkOutcomes], so the decisions are pinned
 * without WorkManager.
 */
internal object UploadErrors {

    /** What an HTTP error that ended an upload run means for the backup. */
    enum class Kind {
        /**
         * The account's analysis quota is full (409 `session_quota_exceeded`),
         * or for demo the device's (409 `device_quota_exceeded`). Terminal.
         */
        QUOTA,

        /** Too many files for one analysis (413). Terminal. */
        TOO_LARGE,

        /**
         * The analysis is in the account's recycle bin (409
         * `session_in_recycle_bin: <sid>`, ADR-035): deleted on this or another
         * phone. Terminal, and not a failure: the row is marked binned.
         */
        IN_RECYCLE_BIN,

        /**
         * The cloud session can no longer be finished: Drive refused the
         * resumable PUT (400), `:complete` found Drive's object gone (400
         * `drive_file_gone`), the file record no longer matches (409
         * `size_or_state_mismatch`) or is gone (404 `file_not_found`). Delete
         * it and recreate from the same staging, a bounded number of times
         * ([RebuildBudget.STALE_SESSION]).
         */
        STALE_SESSION,

        /**
         * Drive holds different bytes than the staged file (422
         * `checksum_mismatch` / `size_mismatch` at `:complete`). Completing
         * the same object again fails the same way forever: delete the session
         * and restage, a bounded number of times ([RebuildBudget.INTEGRITY]).
         */
        INTEGRITY,

        /** Any other 409: refused for a reason retrying will not fix. Terminal. */
        REJECTED,

        /**
         * The upload or backup kill switch is on (503 `feature_paused`): a
         * pause, not a failure. Keep staging and retry with WorkManager's
         * backoff, however long it lasts; never marked failed.
         */
        PAUSED,

        /** 5xx, 429, a bare 404 (route not reachable), anything else: keep staging and retry. */
        TRANSIENT,
    }

    /**
     * Classify an upload-ending HTTP [code] by the `detail` code in [body]. A
     * pause (`feature_paused`) is told apart first, whatever its status.
     */
    fun classify(code: Int, body: String): Kind {
        val detail = ApiErrors.detailOf(body)
        return when {
            ApiErrors.isCode(detail, ApiErrors.FEATURE_PAUSED) -> Kind.PAUSED
            // Before any other 409: an older build read it as REJECTED (ADR-035).
            code == HttpStatus.CONFLICT && ApiErrors.isCode(detail, ApiErrors.SESSION_IN_RECYCLE_BIN) ->
                Kind.IN_RECYCLE_BIN
            else -> refusal(code, detail)
        }
    }

    /** [classify] for anything but a pause or the recycle bin. */
    private fun refusal(code: Int, detail: String): Kind {
        fun detailIs(vararg codes: String) = codes.any { ApiErrors.isCode(detail, it) }
        return when {
            code == HttpStatus.CONFLICT &&
                detailIs(ApiErrors.SESSION_QUOTA_EXCEEDED, ApiErrors.DEVICE_QUOTA_EXCEEDED) -> Kind.QUOTA
            code == HttpStatus.PAYLOAD_TOO_LARGE -> Kind.TOO_LARGE
            code == HttpStatus.BAD_REQUEST -> Kind.STALE_SESSION
            code == HttpStatus.CONFLICT && detailIs(ApiErrors.SIZE_OR_STATE_MISMATCH) -> Kind.STALE_SESSION
            // Only when OUR backend says so — a bare 404 is an unreachable route.
            code == HttpStatus.NOT_FOUND && detailIs(ApiErrors.FILE_NOT_FOUND, ApiErrors.SESSION_NOT_FOUND) ->
                Kind.STALE_SESSION
            code == HttpStatus.UNPROCESSABLE && detailIs(ApiErrors.CHECKSUM_MISMATCH, ApiErrors.SIZE_MISMATCH) ->
                Kind.INTEGRITY
            code == HttpStatus.CONFLICT -> Kind.REJECTED
            else -> Kind.TRANSIENT
        }
    }

    /**
     * Whether a failed `GET /v1/sessions/{sid}/uploads` proves the session is
     * gone, so it may be rebuilt. Only our backend's `session_not_found` (or a
     * 410) does: a 5xx, a 429 or a bare 404 says nothing about the session, and
     * rebuilding on one deleted a half-uploaded backup during an outage.
     */
    fun isSessionGone(code: Int, body: String): Boolean =
        code == HttpStatus.GONE ||
            (code == HttpStatus.NOT_FOUND && ApiErrors.hasCode(body, ApiErrors.SESSION_NOT_FOUND))

    /**
     * Upload work output key naming the kind of a terminal failure, for a caller
     * that reacts to it (Home opening the limit screen) rather than showing
     * [com.sempermechanics.semper.navigation.IntentKeys.UPLOAD_FAIL_REASON]. Named like IntentKeys.
     */
    const val UPLOAD_FAIL_KIND = "UPLOAD_FAIL_KIND"

    /** [UPLOAD_FAIL_KIND] for an account whose analysis quota is full. */
    const val FAIL_KIND_QUOTA = "quota"

    /** [UPLOAD_FAIL_KIND] for an analysis in the recycle bin ([Kind.IN_RECYCLE_BIN]). */
    const val FAIL_KIND_IN_RECYCLE_BIN = "in_recycle_bin"

    /**
     * The binned session's id a `session_in_recycle_bin: <sid>` refusal in
     * [body] names, or "" when it names none.
     */
    fun binnedSessionId(body: String): String {
        val detail = ApiErrors.detailOf(body)
        return if (detail.contains(':')) detail.substringAfter(':').trim() else ""
    }
}

/** Integrity rebuilds allowed before the backup fails instead. */
private const val MAX_INTEGRITY_REBUILDS = 2

/** Link-expired rebuilds allowed before the backup fails instead. */
private const val MAX_LINK_EXPIRED_REBUILDS = 3

/** Stale-session rebuilds allowed before the backup fails instead. */
private const val MAX_STALE_SESSION_REBUILDS = 3

/**
 * How many times a backup may throw its cloud session away and start again
 * since its last good upload, by why. Each rebuild uploads every byte again,
 * so a cause that survives [max] fresh sessions fails the backup instead of
 * looping on WorkManager's backoff (on a metered network too).
 *
 * Each count is a file in the session dir ([marker]), not `upload_staging/`,
 * because an integrity rebuild deletes the staging.
 */
internal enum class RebuildBudget(val marker: String, val max: Int) {
    /** Drive held other bytes than the staged file ([UploadErrors.Kind.INTEGRITY]): restage. */
    INTEGRITY(SessionLayout.INTEGRITY_REBUILDS_MARKER, MAX_INTEGRITY_REBUILDS),

    /** Drive no longer knew an upload link (`UploadLinkExpiredException`): recreate from the same staging. */
    LINK_EXPIRED("link_expired_rebuilds", MAX_LINK_EXPIRED_REBUILDS),

    /** The session could no longer be finished ([UploadErrors.Kind.STALE_SESSION]): recreate from the same staging. */
    STALE_SESSION("upload_stale_session_rebuilds", MAX_STALE_SESSION_REBUILDS),
    ;

    /**
     * Count one more rebuild for [sessionDir]. True while the count is within
     * [max]; false once it is past it, and the backup should fail. An
     * unreadable count restarts rather than failing the upload.
     */
    fun spend(sessionDir: File): Boolean {
        val file = File(sessionDir, marker)
        val count = (runCatching { file.readText().trim().toInt() }.getOrNull() ?: 0) + 1
        runCatching { file.writeText(count.toString()) }
        return count <= max
    }

    companion object {
        /** A finished upload, or a backup given up, starts every count afresh. */
        fun clear(sessionDir: File) {
            entries.forEach { File(sessionDir, it.marker).delete() }
        }
    }
}
