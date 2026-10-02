package com.indicvision.semper.data.cloud

import com.indicvision.semper.data.DicUploadWorker
import com.indicvision.semper.data.net.ApiErrors
import com.indicvision.semper.data.net.HttpStatus
import com.indicvision.semper.data.session.SessionLayout
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
        /** The account's analysis quota is full (409 `session_quota_exceeded`). Terminal. */
        QUOTA,

        /** Too many files for one analysis (413). Terminal. */
        TOO_LARGE,

        /**
         * The cloud session can no longer be finished: Drive refused the
         * resumable PUT (400), `:complete` found Drive's object gone (400
         * `drive_file_gone`), the file record no longer matches (409
         * `size_or_state_mismatch`) or is gone (404 `file_not_found`). Delete
         * it and recreate from the same staging.
         */
        STALE_SESSION,

        /**
         * Drive holds different bytes than the staged file (422
         * `checksum_mismatch` / `size_mismatch` at `:complete`). Completing
         * the same object again fails the same way forever: delete the session
         * and restage, a bounded number of times ([MAX_INTEGRITY_REBUILDS]).
         */
        INTEGRITY,

        /** Any other 409: refused for a reason retrying will not fix. Terminal. */
        REJECTED,

        /** 5xx, 429, a bare 404 (route not reachable), anything else: keep staging and retry. */
        TRANSIENT,
    }

    /** Classify an upload-ending HTTP [code] by the `detail` code in [body]. */
    fun classify(code: Int, body: String): Kind {
        val detail = ApiErrors.detailOf(body)
        fun detailIs(vararg codes: String) = codes.any { ApiErrors.isCode(detail, it) }
        return when {
            code == HttpStatus.CONFLICT && detailIs(ApiErrors.SESSION_QUOTA_EXCEEDED) -> Kind.QUOTA
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
     * [com.indicvision.semper.navigation.DicKeys.UPLOAD_FAIL_REASON]. Named like DicKeys.
     */
    const val UPLOAD_FAIL_KIND = "UPLOAD_FAIL_KIND"

    /** [UPLOAD_FAIL_KIND] for an account whose analysis quota is full. */
    const val FAIL_KIND_QUOTA = "quota"

    /**
     * Session-dir file counting the integrity rebuilds ([Kind.INTEGRITY]) since
     * the last successful upload. In the session dir, not `upload_staging/`,
     * because a rebuild deletes the staging.
     */
    const val INTEGRITY_REBUILDS_MARKER = SessionLayout.INTEGRITY_REBUILDS_MARKER

    /** Integrity rebuilds allowed before the backup fails instead. */
    const val MAX_INTEGRITY_REBUILDS = 2

    /**
     * Session-dir file counting the sessions rebuilt because Drive no longer
     * knew an upload link (`IndicApi.UploadLinkExpiredException`) since the last
     * successful upload. Beside [INTEGRITY_REBUILDS_MARKER], for the same reason.
     */
    const val LINK_EXPIRED_REBUILDS_MARKER = "link_expired_rebuilds"

    /** Link-expired rebuilds allowed before the backup fails instead. */
    const val MAX_LINK_EXPIRED_REBUILDS = 3

    /** Count one more integrity rebuild for [sessionDir]; returns the new total. */
    fun recordIntegrityRebuild(sessionDir: File): Int = bump(SessionLayout(sessionDir).integrityRebuildsMarker)

    /** Count one more link-expired rebuild for [sessionDir]; returns the new total. */
    fun recordLinkExpiredRebuild(sessionDir: File): Int = bump(File(sessionDir, LINK_EXPIRED_REBUILDS_MARKER))

    /** A finished upload, or a backup given up, starts both rebuild counts afresh. */
    fun clearRebuildCounts(sessionDir: File) {
        SessionLayout(sessionDir).integrityRebuildsMarker.delete()
        File(sessionDir, LINK_EXPIRED_REBUILDS_MARKER).delete()
    }

    private fun bump(marker: File): Int {
        val count = (runCatching { marker.readText().trim().toInt() }.getOrNull() ?: 0) + 1
        runCatching { marker.writeText(count.toString()) }
        return count
    }
}
