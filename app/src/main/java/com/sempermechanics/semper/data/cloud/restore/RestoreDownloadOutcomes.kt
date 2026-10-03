package com.sempermechanics.semper.data.cloud.restore

import com.sempermechanics.semper.data.cloud.CorruptTransferException
import com.sempermechanics.semper.data.net.HttpStatus
import java.util.zip.ZipException

/**
 * Classify proxied restore download failures for Range-resume vs terminal fail.
 *
 * Session.zip streams through API Gateway → Cloud Run → Drive. A gateway /
 * Cloud Run deadline kill often surfaces as HTTP 5xx with an **empty** body
 * (not FastAPI's `{"detail":…}`). Within one download call those are retried
 * with `Range` from the bytes already on disk — same as a mid-stream
 * `IOException`. After an attempt that ended normally (success, failure or
 * retry), the next WorkManager attempt starts the file over: CloudRestore deletes
 * its temps and their `.part` sidecars on the way out. After process death that
 * cleanup never ran, so the next attempt's download resumes from the leftover
 * `.part`; the bundle's sha256 (and a legacy prefix's per-entry CRCs) catch a bad
 * resume, and `metadata.json` is always fetched fresh.
 */
object RestoreDownloadOutcomes {

    fun isTransientProxyFailure(code: Int): Boolean = when (code) {
        HttpStatus.INTERNAL_ERROR,
        HttpStatus.BAD_GATEWAY,
        HttpStatus.SERVICE_UNAVAILABLE,
        HttpStatus.GATEWAY_TIMEOUT,
        -> true
        else -> false
    }

    /** Whether [DriveTransfer.downloadFile] should Range-resume after this status. */
    fun shouldResumeAfterHttp(code: Int, attempt: Int, maxAttempts: Int): Boolean =
        isTransientProxyFailure(code) && attempt < maxAttempts

    /**
     * Parsed `Content-Range: bytes start-end/total`.
     * [total] is null when the server sends `*`.
     */
    data class ContentRange(val start: Long, val end: Long, val total: Long?)

    /** Parse a Content-Range header, or null if absent/malformed. */
    fun parseContentRange(header: String?): ContentRange? {
        val raw = header?.trim().orEmpty()
        if (!raw.startsWith("bytes ")) return null
        val spec = raw.removePrefix("bytes ").trim()
        val unit = spec.substringBefore('/', missingDelimiterValue = "")
        val totalRaw = spec.substringAfter('/', missingDelimiterValue = "").trim()
        val start = unit.substringBefore('-').trim().toLongOrNull()
        val end = unit.substringAfter('-').trim().toLongOrNull()
        val total = when {
            totalRaw.isEmpty() || totalRaw == "*" -> null
            else -> totalRaw.toLongOrNull()?.takeIf { it >= 0L }
        }
        return when {
            start == null || end == null || start < 0L || end < start -> null
            else -> ContentRange(start, end, total)
        }
    }

    /** `Content-Range: bytes a-b/total` → total length, or null if absent/unparsed. */
    fun parseContentRangeTotal(header: String?): Long? = parseContentRange(header)?.total

    /**
     * Whether [haveBytes] is a complete object of [expectedBytes] / [reportedTotal].
     * Prefer the Firestore-declared size; fall back to Content-Range total.
     */
    fun isComplete(haveBytes: Long, expectedBytes: Long, reportedTotal: Long): Boolean {
        val target = when {
            expectedBytes > 0L -> expectedBytes
            reportedTotal > 0L -> reportedTotal
            else -> return false
        }
        return haveBytes == target
    }

    /**
     * Zip or attestation failure: the bytes are wrong. Never retried, and a bundle
     * download does not paper over it by packing the phone's copy instead.
     */
    fun isTerminalCorruptFailure(error: Throwable): Boolean =
        error is ZipException || error is CorruptTransferException

    /**
     * Whether a restore or bundle-download worker should give up rather than
     * `Result.retry()`: a corrupt payload ([isTerminalCorruptFailure]) or a backup
     * that lacks what the transfer needs ([UnrestorableBackupException]). Network
     * drops, 5xx and a missing sign-in stay retryable.
     */
    fun isTerminalFailure(error: Throwable): Boolean =
        isTerminalCorruptFailure(error) || error is UnrestorableBackupException
}
