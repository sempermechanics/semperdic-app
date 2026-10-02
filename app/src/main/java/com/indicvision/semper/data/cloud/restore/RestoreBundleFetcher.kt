package com.indicvision.semper.data.cloud.restore

import com.indicvision.semper.data.cloud.CorruptTransferException
import com.indicvision.semper.data.net.ArtifactRoles
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.CloudFileDto
import com.indicvision.semper.data.session.SessionLayout
import com.indicvision.semper.data.session.SessionZip
import com.indicvision.semper.data.session.ZipDirectory
import com.indicvision.semper.util.AtomicFiles
import com.indicvision.semper.util.suspendRunCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches a restore's payload — everything but `metadata.json` — into the
 * session's layout, by the cheapest safe strategy the backup allows.
 */
internal object RestoreBundleFetcher {

    /** Concurrent GETs for legacy per-file restores (matches upload concurrency). */
    private const val LEGACY_DOWNLOAD_CONCURRENCY = 4

    /**
     * Tail fetched to read a legacy bundle's central directory. Large enough for a
     * few thousand entries; if the directory does not fit, the parse returns null and
     * the whole archive is downloaded as before.
     */
    private const val CENTRAL_DIRECTORY_TAIL_BYTES = 512L * 1024L

    /** Skip the extra round trip unless the prefix saves at least this fraction. */
    private const val PREFIX_MIN_SAVING_DIVISOR = 20L // 5%

    private const val PERCENT = 100L

    /** One restore's source and destination: everything a payload fetch needs, travelling together. */
    class PayloadFetch(
        val api: CloudApi,
        val token: String,
        val sessionId: String,
        val cacheDir: File,
        val layout: SessionLayout,
    ) {
        fun scratch(suffix: String): File = scratchFile(cacheDir, sessionId, suffix)
    }

    /**
     * Result of restoring the file payload: the reference image path, the bytes
     * actually pulled off the network, and which strategy did it (for telemetry).
     */
    data class PayloadOutcome(
        val refPath: String,
        val mode: String,
        val bytesDownloaded: Long = 0L,
    )

    /** Where the restore payload ends, plus the CRC of every entry inside it. */
    data class PrefixPlan(val cut: Long, val crcByName: Map<String, Long>)

    /**
     * Everything but `metadata.json`, into [PayloadFetch.layout]. Three eras, one
     * destination layout (see [RestoreUnpacker.destFor]):
     *  - schema/3+ ([split]): `Session.zip` holds only raw/ + dat/. Fetch it whole;
     *    the `Extras.zip` beside it is never downloaded.
     *  - schema < 3: one `Session.zip` holds everything. Fetch just the raw/ + dat/
     *    prefix, falling back to the whole archive if that is not safely possible.
     *  - pre-bundle: every artifact listed as its own file.
     */
    suspend fun fetchPayload(
        fetch: PayloadFetch,
        files: List<CloudFileDto>,
        split: Boolean,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): PayloadOutcome {
        val bundle = files.firstOrNull { it.role == ArtifactRoles.BUNDLE }
        return when {
            bundle == null -> PayloadOutcome(restoreLegacyFiles(fetch, files, onProgress), "legacy-per-file")
            split -> downloadAndUnpackBundle(fetch, bundle, onProgress)
            else -> restoreLegacyBundle(fetch, bundle, onProgress)
        }
    }

    /**
     * Download Session.zip with size checks, verify zip magic, unpack.
     * Deletes `.part` / `.full` sidecars so a corrupt transfer cannot stick.
     * [onProgress] is byte-based: (bytesOnDisk, declaredSize).
     */
    private suspend fun downloadAndUnpackBundle(
        fetch: PayloadFetch,
        bundle: CloudFileDto,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): PayloadOutcome {
        val zipTmp = fetch.scratch("bundle.zip")
        try {
            val whole = fetch.api.downloadReporting(fetch.token, bundle, zipTmp, onProgress)
            RestoreZipVerifier.verifySessionZip(zipTmp, bundle.declaredSize, bundle.sha256, requireEntries = true)
            // Download bytes are done; hold 100% through unpack so the row
            // doesn't look stuck again during inflate.
            onProgress(whole, whole)
            return PayloadOutcome(RestoreUnpacker.unpackBundle(zipTmp, fetch.layout), "whole-bundle", zipTmp.length())
        } finally {
            discard(zipTmp)
        }
    }

    /**
     * Legacy backup: one `Session.zip` holding raw/, dat/, csv/, reports/ and
     * processed/. Only raw/ + dat/ are needed, and the uploader wrote them first, so
     * they are a contiguous prefix — read the central directory, then fetch just that
     * prefix instead of the whole archive.
     *
     * Whole-file sha256 cannot apply to a partial fetch, so entries are verified by
     * their central-directory CRC32 instead. Anything unexpected — an unreadable
     * directory, an interleaved layout, a CRC mismatch — falls back to downloading
     * the entire archive, which is exactly today's behaviour.
     */
    private suspend fun restoreLegacyBundle(
        fetch: PayloadFetch,
        bundle: CloudFileDto,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): PayloadOutcome {
        val size = bundle.sizeBytes
        val plan = if (size > 0L) {
            suspendRunCatching { planPrefixFetch(fetch, bundle) }
                .onFailure { Timber.w(it, "Prefix planning failed for %s; downloading whole bundle", fetch.sessionId) }
                .getOrNull()
        } else {
            null
        }
        if (plan == null) return downloadAndUnpackBundle(fetch, bundle, onProgress)

        val prefixTmp = fetch.scratch("prefix.zip")
        // The central-directory tail counts toward what the ranged restore pulled.
        val tailBytes = minOf(size, CENTRAL_DIRECTORY_TAIL_BYTES)
        return try {
            val percent = plan.cut * PERCENT / size.coerceAtLeast(1L)
            Timber.i("Legacy bundle %s: fetching %d of %d bytes (%d%%)", fetch.sessionId, plan.cut, size, percent)
            onProgress(0L, plan.cut)
            fetch.api.downloadRange(fetch.token, bundle.fileId, prefixTmp, rangeStart = 0L, length = plan.cut)
            if (prefixTmp.length() != plan.cut) throw CorruptTransferException("prefix_size_mismatch")
            onProgress(plan.cut, plan.cut)
            val ref = RestoreUnpacker.unpackPrefix(prefixTmp, fetch.layout, plan.crcByName)
            PayloadOutcome(ref, "legacy-ranged-prefix", plan.cut + tailBytes)
        } catch (e: IllegalArgumentException) {
            // A bad prefix is not a corrupt backup — fall back to the whole archive
            // rather than failing a restore that would otherwise succeed.
            Timber.w(e, "Prefix restore failed for %s; downloading whole bundle", fetch.sessionId)
            prefixTmp.delete()
            downloadAndUnpackBundle(fetch, bundle, onProgress)
        } finally {
            discard(prefixTmp)
        }
    }

    /**
     * Read the archive's central directory over a tail range and work out how much of
     * it is worth downloading. Returns null when a prefix fetch is not clearly safe.
     */
    private suspend fun planPrefixFetch(fetch: PayloadFetch, bundle: CloudFileDto): PrefixPlan? {
        val size = bundle.sizeBytes
        val tailLen = minOf(size, CENTRAL_DIRECTORY_TAIL_BYTES)
        val tailTmp = fetch.scratch("tail.bin")
        try {
            fetch.api.downloadRange(fetch.token, bundle.fileId, tailTmp, rangeStart = size - tailLen, length = tailLen)
            return planFromTail(tailTmp.readBytes(), size - tailLen, size)
        } finally {
            discard(tailTmp)
        }
    }

    /**
     * Pure half of [planPrefixFetch]: what the fetched tail says about the archive.
     * Null — "just download the whole archive" — unless the directory parses, the
     * payload is a clean prefix, and skipping the rest saves a meaningful amount.
     */
    fun planFromTail(tail: ByteArray, tailStart: Long, size: Long): PrefixPlan? {
        val prefixes = SessionZip.RESTORE_ENTRY_PREFIXES
        val directory = ZipDirectory.parse(tail, tailStart, size)
        val cut = directory?.prefixCut(prefixes)
            ?.takeIf { it < size - (size / PREFIX_MIN_SAVING_DIVISOR) }
            ?: return null
        val crcByName = directory.entries
            .filter { entry -> prefixes.any { entry.name.startsWith(it) } }
            .associate { it.name to it.crc32 }
        return PrefixPlan(cut, crcByName)
    }

    /** Legacy per-file backups: download each artifact into place. Returns refPath. */
    private suspend fun restoreLegacyFiles(
        fetch: PayloadFetch,
        files: List<CloudFileDto>,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): String {
        val rest = files.filter { it.role != ArtifactRoles.METADATA }
        val done = AtomicInteger(0)
        val refPath = AtomicReference("")
        val total = rest.size.toLong().coerceAtLeast(1L)
        onProgress(0L, total)
        // A few GETs in flight fill the link the way parallel Drive uploads do;
        // one failure cancels siblings via coroutineScope (same as before: abort).
        coroutineScope {
            val gate = Semaphore(LEGACY_DOWNLOAD_CONCURRENCY)
            rest.map { f ->
                async {
                    gate.withPermit {
                        val dest = RestoreUnpacker.destFor(f.role, f.name, fetch.layout)
                        fetch.api.downloadFile(fetch.token, f.fileId, dest, expectedBytes = f.declaredSize)
                        if (dest == fetch.layout.referencePng) refPath.set(dest.absolutePath)
                        onProgress(done.incrementAndGet().toLong(), total)
                    }
                }
            }.awaitAll()
        }
        return refPath.get()
    }
}

/** A restore's scratch file in [cacheDir]; `CacheJanitor` reclaims the `restore_` prefix if one is left. */
internal fun scratchFile(cacheDir: File, sessionId: String, suffix: String): File =
    File(cacheDir, "restore_${sessionId}_$suffix")

/** Deletes [file] and whatever an interrupted download into it left beside it. */
internal fun discard(file: File) {
    file.delete()
    AtomicFiles.deleteSidecars(file)
}

/** The size the backup declared for this file, or -1 when it declared none (what `downloadFile` takes). */
internal val CloudFileDto.declaredSize: Long get() = sizeBytes.takeIf { it > 0L } ?: -1L

/**
 * Downloads [entry] to [dest], reporting (bytes on disk, total) to [onProgress]
 * from (0, total) on. The total is the declared size, or what has arrived while
 * none was declared. Returns the total to report as done once the caller is
 * finished with the file.
 */
internal suspend fun CloudApi.downloadReporting(
    token: String,
    entry: CloudFileDto,
    dest: File,
    onProgress: suspend (done: Long, total: Long) -> Unit,
): Long {
    val expected = entry.declaredSize
    val whole = expected.takeIf { it > 0L } ?: 1L
    onProgress(0L, whole)
    downloadFile(token, entry.fileId, dest, expectedBytes = expected) { have ->
        val total = if (expected > 0L) expected else have.coerceAtLeast(1L)
        onProgress(have.coerceAtMost(total), total)
    }
    return whole
}
