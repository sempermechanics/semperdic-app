// Restore parsing: literal buffer sizes and manifest field offsets read clearest
// inline, so MagicNumber is suppressed for this whole file.
@file:Suppress("MagicNumber")

package com.indicvision.semper.data.cloud.restore

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.indicvision.semper.data.DicBundleDownloadWorker
import com.indicvision.semper.data.DicRestoreWorker
import com.indicvision.semper.data.DicUploadWorker
import com.indicvision.semper.data.cloud.CorruptTransferException
import com.indicvision.semper.data.cloud.SessionUploadMetadata
import com.indicvision.semper.data.cloud.TransferLog
import com.indicvision.semper.data.cloud.UploadWorkOutcomes
import com.indicvision.semper.data.net.ArtifactRoles
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.CloudFileDto
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.data.session.SessionHeadline
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.data.session.SessionZip
import com.indicvision.semper.data.session.SkippedNode
import com.indicvision.semper.data.session.ZipDirectory
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.util.AtomicFiles
import com.indicvision.semper.util.Digests
import com.indicvision.semper.util.rethrowIfCallerCancelled
import com.indicvision.semper.util.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/**
 * Rebuilds an analysis on this device from its cloud backup.
 *
 * Because the engine's `.dat` results are uploaded alongside the raw images,
 * a restored session is **fully re-openable** — the results viewer works without
 * re-running the analysis. The session's `metadata/metadata.json` is the
 * blueprint: it carries the engine parameters, ROI, metrics and the ordered
 * frame list (image ↔ dat ↔ csv), so we can reconstruct the [SessionRecord]
 * and the on-disk layout exactly as a local run would have produced it.
 *
 * A restore fetches `Session.zip` (see [SessionZip.isRestoreEssential]) and rebuilds
 * the full local layout — nothing a local run would have produced is missing:
 * ```
 * <sessionDir>/reference.png              the heatmap backdrop
 * <sessionDir>/frame_%04d.dat             the engine results
 * <sessionDir>/raw_deformed/<name>        every deformed original
 * ```
 * The derived deliverables (`csv`, `reports`, `processed`) are the only thing left in
 * the cloud (`Extras.zip`) — they are regenerated on export, so a restore never reads
 * them back.
 */
@Suppress("TooManyFunctions", "LargeClass") // one cohesive restore pipeline: fetch, parse, write, index
object CloudRestore {

    /** Input key for [DicRestoreWorker]: which cloud session to pull down. */
    const val KEY_CLOUD_SESSION_ID = "CLOUD_SESSION_ID"
    const val KEY_TARGET_LOCAL_ID = "TARGET_LOCAL_ID"

    /**
     * Queue a restore. It runs in [DicRestoreWorker] rather than a UI scope so
     * it survives leaving the screen — a restore can be hundreds of megabytes
     * and must not die because the user navigated away.
     */
    fun enqueueRestore(context: Context, cloudSessionId: String, targetLocalId: String): String {
        val name = workName(cloudSessionId)
        val work = OneTimeWorkRequestBuilder<DicRestoreWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(KEY_CLOUD_SESSION_ID, cloudSessionId)
                    .putString(KEY_TARGET_LOCAL_ID, targetLocalId)
                    .build(),
            )
            .addTag("restore")
            .addTag("restore-$cloudSessionId")
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, work)
        SemperAnalytics.event(context, SemperAnalytics.CLOUD_RESTORE_ENQUEUED)
        return name
    }

    /**
     * Queue a Save-to-Files Session.zip download. Same WorkManager rationale as
     * [enqueueRestore]: Analyses data management must not cancel the transfer
     * when the user leaves Settings.
     *
     * [destUri] is a document URI from [android.content.Intent.ACTION_CREATE_DOCUMENT]
     * (persistable write grant taken by the caller before enqueue).
     */
    fun enqueueBundleDownload(
        context: Context,
        cloudSessionId: String,
        displayName: String,
        destUri: String,
        localSessionId: String = "",
    ): String {
        val name = bundleDownloadWorkName(cloudSessionId)
        val work = OneTimeWorkRequestBuilder<DicBundleDownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(KEY_CLOUD_SESSION_ID, cloudSessionId)
                    .putString(DicBundleDownloadWorker.KEY_DISPLAY_NAME, displayName)
                    .putString(DicBundleDownloadWorker.KEY_LOCAL_SESSION_ID, localSessionId)
                    .putString(DicBundleDownloadWorker.KEY_DEST_URI, destUri)
                    .build(),
            )
            .addTag(TAG_BUNDLE_DOWNLOAD)
            .addTag("$TAG_BUNDLE_DOWNLOAD-$cloudSessionId")
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, work)
        return name
    }

    fun cancelBundleDownload(context: Context, cloudSessionId: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(bundleDownloadWorkName(cloudSessionId))
    }

    /** Unique work name for a restore, so the UI can observe its progress. */
    fun workName(cloudSessionId: String): String = "restore-$cloudSessionId"

    /** Unique work name for a Save-to-Files download. */
    fun bundleDownloadWorkName(cloudSessionId: String): String = "download-bundle-$cloudSessionId"

    /** Suggested SAF filename for an analysis Session.zip. */
    fun suggestedBundleFileName(displayName: String): String {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
            .ifBlank { "analysis" }.take(40)
        return "${safe}_Session.zip"
    }

    const val TAG_BUNDLE_DOWNLOAD = "download-bundle"

    fun targetLocalId(cloud: CloudSessionDto): String =
        cloud.localSessionId.ifBlank { "restored-" + cloud.sessionId.take(12) }

    private const val BACKOFF_SECONDS = 30L

    /** Concurrent GETs for legacy per-file restores (matches upload concurrency). */
    private const val LEGACY_DOWNLOAD_CONCURRENCY = 4

    /** ZIP local-file / empty-archive signature prefix (`PK`). */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B)

    /**
     * Tail fetched to read a legacy bundle's central directory. Large enough for a
     * few thousand entries; if the directory does not fit, the parse returns null and
     * the whole archive is downloaded as before.
     */
    private const val CENTRAL_DIRECTORY_TAIL_BYTES = 512L * 1024L

    /** Skip the extra round trip unless the prefix saves at least this fraction. */
    private const val PREFIX_MIN_SAVING_DIVISOR = 20L // 5%

    private const val UNPACK_BUFFER_BYTES = 64 * 1024

    /**
     * Why a restorable-list query failed or is empty — never collapse auth/config
     * failures into a blank "no backups" list.
     */
    sealed class ListResult {
        data class Ready(val sessions: List<CloudSessionDto>) : ListResult()
        data object Empty : ListResult()
        data object NeedSignIn : ListResult()
        data object ApiOff : ListResult()
        data class Failed(val reason: String) : ListResult()
    }

    /**
     * Every COMPLETED cloud backup for this account (no local-presence filter).
     * Settings management uses this so rows that still have a phone stub without
     * `.dat`s can still offer Download when a cloud copy exists.
     */
    suspend fun listCompleted(
        context: Context,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): ListResult = withContext(Dispatchers.IO) {
        fetchCompletedSessions(api, tokens)
    }

    /**
     * One Firestore-backed listing of COMPLETED sessions. Auth/config failures
     * stay distinct from an empty list. Does not filter by local presence.
     */
    private suspend fun fetchCompletedSessions(api: CloudApi, tokens: TokenSource): ListResult {
        val token = tokens.usableIdToken()
        return when {
            !api.enabled -> ListResult.ApiOff
            token == null -> ListResult.NeedSignIn
            else -> try {
                val sessions = api.listSessions(token).sessions
                    .filter { it.status == UploadWorkOutcomes.STATUS_COMPLETED }
                if (sessions.isEmpty()) ListResult.Empty else ListResult.Ready(sessions)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                e.rethrowIfCallerCancelled()
                Timber.e(e, "listCompleted sessions failed")
                ListResult.Failed(e.message ?: e.toString())
            }
        }
    }

    /**
     * Download the cloud [Session.zip] into app cache for the user to save or
     * share. Does **not** unpack into a session directory or touch existing
     * local analysis files.
     *
     * Throws [UnrestorableBackupException] when the backup has no bundle role
     * (legacy per-file backups) and [CorruptTransferException] when the transfer
     * fails attestation.
     */
    @Suppress("LongParameterList") // api and tokens are test seams (ADR-002)
    suspend fun downloadBundleZip(
        context: Context,
        sessionId: String,
        displayName: String,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
        onProgress: suspend (done: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val token = tokens.usableIdToken()
            ?: error("Not signed in")

        val files = completedFiles(api, token, sessionId)
        val bundleEntry = files.firstOrNull { it.role == ArtifactRoles.BUNDLE }
            ?: throw UnrestorableBackupException("backup_no_bundle")

        val outDir = CacheJanitor.shareDir(appContext.cacheDir)
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
            .ifBlank { "analysis" }.take(40)
        val dest = File(outDir, "${safe}_Session.zip")
        dest.delete()
        AtomicFiles.deleteSidecars(dest)

        val expected = bundleEntry.sizeBytes.takeIf { it > 0L } ?: -1L
        val totalForUi = expected.takeIf { it > 0L } ?: 1L
        onProgress(0L, totalForUi)
        api.downloadFile(
            token,
            bundleEntry.fileId,
            dest,
            expectedBytes = expected,
            onBytes = { have ->
                val total = if (expected > 0L) expected else have.coerceAtLeast(1L)
                onProgress(have.coerceAtMost(total), total)
            },
        )
        verifySessionZip(dest, expected, bundleEntry.sha256)
        // Since the payload was split, Session.zip alone is no longer the whole
        // analysis. "Save to Files" is the deliverables use case, so pull Extras.zip
        // too and hand over one merged archive — the same single file as before.
        files.firstOrNull { it.role == ArtifactRoles.EXTRAS }?.let { mergeExtrasInto(api, token, it, dest, outDir) }
        onProgress(totalForUi, totalForUi)
        dest
    }

    /** Fold Extras.zip into [dest] so Save-to-Files still yields one complete archive. */
    private suspend fun mergeExtrasInto(
        api: CloudApi,
        token: String,
        extrasEntry: CloudFileDto,
        dest: File,
        outDir: File,
    ) {
        val extrasTmp = File(outDir, "${dest.name}.extras")
        try {
            val expected = extrasEntry.sizeBytes.takeIf { it > 0L } ?: -1L
            api.downloadFile(token, extrasEntry.fileId, extrasTmp, expectedBytes = expected)
            val expectedSha = extrasEntry.sha256?.lowercase()?.takeIf { it.length == 64 }
                ?: throw CorruptTransferException("extras_zip_sha256_missing")
            val gotSha = Digests.sha256Hex(extrasTmp)
            if (gotSha != expectedSha) {
                throw CorruptTransferException("session_zip_sha256_mismatch")
            }
            SessionZip.merge(listOf(dest, extrasTmp), dest)
        } finally {
            extrasTmp.delete()
            AtomicFiles.deleteSidecars(extrasTmp)
        }
    }

    /**
     * Download an analysis and rebuild it locally. Returns the restored local
     * session id, or throws on failure.
     *
     * @param onProgress cumulative units completed vs total (bytes for bundled
     * Session.zip restores; file counts for legacy per-file backups).
     */
    @Suppress("LongParameterList") // api and tokens are test seams (ADR-002)
    suspend fun restore(
        context: Context,
        sessionId: String,
        targetLocalId: String,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
        onProgress: suspend (done: Long, total: Long) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val token = tokens.usableIdToken()
            ?: error("Not signed in")

        val files = completedFiles(api, token, sessionId)

        // 1. metadata.json first — it's the blueprint for everything else.
        val metaEntry = files.firstOrNull { it.role == ArtifactRoles.METADATA }
            ?: throw UnrestorableBackupException("backup_no_metadata")
        val fetched = fetchMetadata(
            api,
            token,
            metaEntry,
            File(appContext.cacheDir, "restore_${sessionId}_metadata.json"),
        )
        val meta = fetched.json

        // The enqueueing UI already created a row under this id. Never let
        // metadata select a second id and leave an orphan stub behind.
        val localId = targetLocalId
        val existing = SessionStore.get(appContext, localId)
        val sessionDir = SessionStore.dirFor(appContext, localId)
        val rawDeformedDir = File(sessionDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }
        File(sessionDir, "metadata.json").writeBytes(fetched.bytes)

        // 2. Everything else, into the layout a local run would have produced.
        // Three eras, one destination layout (see destFor):
        //  - schema/3+ : Session.zip holds ONLY raw/ + dat/. Fetch it whole; the
        //                Extras.zip alongside it is never downloaded.
        //  - schema<3  : one Session.zip holds everything. Fetch just the raw/+dat/
        //                prefix, falling back to the whole archive if that is not
        //                safely possible.
        //  - pre-bundle: every artifact listed as its own file.
        val layout = Layout(sessionDir, rawDeformedDir)
        val bundleEntry = files.firstOrNull { it.role == ArtifactRoles.BUNDLE }
        val outcome = if (bundleEntry != null) {
            val fetch = BundleFetch(api, token, sessionId, appContext, bundleEntry, layout)
            if (isSplitLayout(meta.optString("schema"))) {
                downloadAndUnpackBundle(fetch, onProgress)
            } else {
                restoreLegacyBundle(fetch, onProgress)
            }
        } else {
            BundleOutcome(restoreLegacyFiles(api, token, files, layout, onProgress), "legacy-per-file")
        }
        val refPath = outcome.refPath

        // 3. Rebuild the index row from the blueprint.
        check(
            SessionStore.upsert(
                appContext,
                recordFrom(
                    meta,
                    RestoreRecordTarget(localId, sessionId, sessionDir, refPath, existing),
                ),
                allowOverLimit = true, // already counted in the cloud quota
            ),
        ) { "Could not update the restored session index" }
        logRestoreSaving(outcome, metaEntry.sizeBytes, files)
        localId
    }

    /** The backup's COMPLETED files; none at all is a backup no retry will fix. */
    private suspend fun completedFiles(api: CloudApi, token: String, sessionId: String): List<CloudFileDto> {
        val files = api.listSessionFiles(token, sessionId).files.filter { it.status == "COMPLETED" }
        if (files.isEmpty()) throw UnrestorableBackupException("backup_no_completed_files")
        return files
    }

    /** `metadata.json` as downloaded (written back verbatim) and parsed. */
    private class FetchedMetadata(val bytes: ByteArray, val json: JSONObject)

    /**
     * Download and check the backup's `metadata.json`.
     *
     * Every attempt starts from nothing: [tmp] and its `.part` / `.full` sidecars are
     * cleared first, since the download resumes from a `.part` beside its destination
     * and one an earlier attempt left could hold an older body of this file (the
     * backend's metadata replace route rewrites it when an analysis is edited after
     * its backup, e.g. a deflection correction). The declared sha256 is checked like
     * the bundle's, when the file
     * list carries one; a mismatch or a body that is not JSON is corrupt (terminal).
     */
    private suspend fun fetchMetadata(
        api: CloudApi,
        token: String,
        entry: CloudFileDto,
        tmp: File,
    ): FetchedMetadata {
        tmp.delete()
        AtomicFiles.deleteSidecars(tmp)
        try {
            api.downloadFile(
                token,
                entry.fileId,
                tmp,
                expectedBytes = entry.sizeBytes.takeIf { it > 0L } ?: -1L,
            )
            val bytes = tmp.readBytes()
            val expectedSha = entry.sha256?.lowercase()?.takeIf { it.length == 64 }
            if (expectedSha != null && Digests.toHex(Digests.sha256(bytes)) != expectedSha) {
                throw CorruptTransferException("metadata_sha256_mismatch")
            }
            val json = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: JSONException) {
                throw CorruptTransferException("metadata_json_invalid", e)
            }
            return FetchedMetadata(bytes, json)
        } finally {
            tmp.delete()
            AtomicFiles.deleteSidecars(tmp)
        }
    }

    /** Log restore completion; structured line is PII-free, Timber line is coarse totals only. */
    private fun logRestoreSaving(
        outcome: BundleOutcome,
        metaBytes: Long,
        files: List<CloudFileDto>,
    ) {
        val downloaded = metaBytes.coerceAtLeast(0L) + outcome.bytesDownloaded
        val backupTotal = files.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        TransferLog.phase(
            TransferLog.PhaseFields(
                phase = "restore",
                outcome = "complete",
                bytes = downloaded,
                count = files.size,
                stage = outcome.mode,
            ),
        )
        Timber.i(
            "Restore complete (%s): %d of %d backup bytes (%d files)",
            outcome.mode,
            downloaded,
            backupTotal,
            files.size,
        )
    }

    private fun verifySessionZip(
        file: File,
        expectedSize: Long,
        sha256: String?,
        requireEntries: Boolean = false,
    ) {
        val err = checkZipSize(file, expectedSize)
            ?: checkZipSha256(file, sha256)
            ?: checkZipMagic(file)
            ?: if (requireEntries) checkZipEntries(file) else null
        if (err != null) throw err
    }

    private fun checkZipSize(file: File, expectedSize: Long): CorruptTransferException? {
        if (expectedSize > 0L && file.length() != expectedSize) {
            return CorruptTransferException("session_zip_size_mismatch")
        }
        return null
    }

    private fun checkZipSha256(file: File, sha256: String?): CorruptTransferException? {
        val expectedSha = sha256?.lowercase()?.takeIf { it.length == 64 }
        return when {
            expectedSha == null -> CorruptTransferException("session_zip_sha256_missing")
            Digests.sha256Hex(file) != expectedSha -> CorruptTransferException("session_zip_sha256_mismatch")
            else -> null
        }
    }

    private fun checkZipMagic(file: File): CorruptTransferException? {
        val magic = ByteArray(ZIP_MAGIC.size)
        val read = file.inputStream().use { it.read(magic) }
        return when {
            read < ZIP_MAGIC.size -> CorruptTransferException("session_zip_too_small")
            !magic.contentEquals(ZIP_MAGIC) -> CorruptTransferException("session_zip_bad_magic")
            else -> null
        }
    }

    private fun checkZipEntries(file: File): CorruptTransferException? {
        return try {
            java.util.zip.ZipFile(file).use { zf ->
                if (zf.size() <= 0) CorruptTransferException("session_zip_no_entries") else null
            }
        } catch (e: java.util.zip.ZipException) {
            CorruptTransferException("session_zip_unreadable", e)
        }
    }

    /** Remove an interrupted restore's files while retaining its cloud-only index row. */
    fun clearPartialArtifacts(context: Context, localId: String) {
        val dir = SessionStore.dirFor(context.applicationContext, localId)
        dir.deleteRecursively()
        check(dir.mkdirs()) { "Could not reset partial restore directory" }
    }

    /** The on-disk shape of a restored session — where artifacts land. */
    @VisibleForTesting
    internal data class Layout(val sessionDir: File, val rawDeformedDir: File)

    /**
     * Whether this backup's `Session.zip` holds only the restore payload.
     *
     * `schema` has been written since the first cloud backups but never read until
     * now, so the parse must be forgiving: anything unrecognised or missing is an
     * older, everything-in-one-zip backup. Being wrong in that direction costs
     * bandwidth; being wrong the other way would skip frames.
     */
    internal fun isSplitLayout(schema: String): Boolean {
        val version = schema.substringAfterLast('/', "").toIntOrNull() ?: return false
        return version >= SessionUploadMetadata.SCHEMA_SPLIT_BUNDLE
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
        fetch: BundleFetch,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): BundleOutcome {
        val size = fetch.entry.sizeBytes
        val plan = if (size > 0L) {
            suspendRunCatching { planPrefixFetch(fetch) }
                .onFailure { Timber.w(it, "Prefix planning failed for %s; downloading whole bundle", fetch.sessionId) }
                .getOrNull()
        } else {
            null
        }
        if (plan == null) return downloadAndUnpackBundle(fetch, onProgress)

        val prefixTmp = File(fetch.appContext.cacheDir, "restore_${fetch.sessionId}_prefix.zip")
        // The central-directory tail counts toward what the ranged restore pulled.
        val tailBytes = minOf(size, CENTRAL_DIRECTORY_TAIL_BYTES)
        return try {
            Timber.i(
                "Legacy bundle %s: fetching %d of %d bytes (%d%%)",
                fetch.sessionId,
                plan.cut,
                size,
                plan.cut * 100 / size.coerceAtLeast(1L),
            )
            onProgress(0L, plan.cut)
            fetch.api.downloadRange(fetch.token, fetch.entry.fileId, prefixTmp, rangeStart = 0L, length = plan.cut)
            if (prefixTmp.length() != plan.cut) {
                throw CorruptTransferException("prefix_size_mismatch")
            }
            onProgress(plan.cut, plan.cut)
            val ref = unpackPrefix(prefixTmp, fetch.layout, plan.crcByName)
            BundleOutcome(ref, "legacy-ranged-prefix", plan.cut + tailBytes)
        } catch (e: IllegalArgumentException) {
            // A bad prefix is not a corrupt backup — fall back to the whole archive
            // rather than failing a restore that would otherwise succeed.
            Timber.w(e, "Prefix restore failed for %s; downloading whole bundle", fetch.sessionId)
            prefixTmp.delete()
            downloadAndUnpackBundle(fetch, onProgress)
        } finally {
            prefixTmp.delete()
            AtomicFiles.deleteSidecars(prefixTmp)
        }
    }

    /** Everything one bundle fetch needs; these always travel together. */
    private data class BundleFetch(
        val api: CloudApi,
        val token: String,
        val sessionId: String,
        val appContext: Context,
        val entry: CloudFileDto,
        val layout: Layout,
    )

    /**
     * Result of restoring the file payload: the reference image path, the bytes
     * actually pulled off the network, and which strategy did it (for telemetry).
     */
    private data class BundleOutcome(
        val refPath: String,
        val mode: String,
        val bytesDownloaded: Long = 0L,
    )

    /** Where the restore payload ends, plus the CRC of every entry inside it. */
    internal data class PrefixPlan(val cut: Long, val crcByName: Map<String, Long>)

    /**
     * Read the archive's central directory over a tail range and work out how much of
     * it is worth downloading. Returns null when a prefix fetch is not clearly safe.
     */
    private suspend fun planPrefixFetch(fetch: BundleFetch): PrefixPlan? {
        val size = fetch.entry.sizeBytes
        val tailLen = minOf(size, CENTRAL_DIRECTORY_TAIL_BYTES)
        val tailTmp = File(fetch.appContext.cacheDir, "restore_${fetch.sessionId}_tail.bin")
        try {
            fetch.api.downloadRange(
                fetch.token,
                fetch.entry.fileId,
                tailTmp,
                rangeStart = size - tailLen,
                length = tailLen,
            )
            return planFromTail(tailTmp.readBytes(), size - tailLen, size)
        } finally {
            tailTmp.delete()
            AtomicFiles.deleteSidecars(tailTmp)
        }
    }

    /**
     * Pure half of [planPrefixFetch]: what the fetched tail says about the archive.
     *
     * Every guard exits to null — "just download the whole archive" — so the return
     * count is the safety property here, not a smell to refactor away.
     */
    @Suppress("ReturnCount")
    internal fun planFromTail(tail: ByteArray, tailStart: Long, size: Long): PrefixPlan? {
        val entries = ZipDirectory.parse(tail, tailStart, size) ?: return null
        val cdOffset = ZipDirectory.centralDirectoryOffset(tail) ?: return null
        val cut = ZipDirectory.prefixCut(entries, SessionZip.RESTORE_ENTRY_PREFIXES, cdOffset) ?: return null
        // Only worth the extra round trip if it saves a meaningful amount.
        if (cut >= size - (size / PREFIX_MIN_SAVING_DIVISOR)) return null
        val crcByName = entries
            .filter { entry -> SessionZip.RESTORE_ENTRY_PREFIXES.any { entry.name.startsWith(it) } }
            .associate { it.name to it.crc32 }
        return PrefixPlan(cut, crcByName)
    }

    /**
     * Stream a downloaded prefix with [ZipInputStream] — [SessionZip.forEachEntry]
     * uses random-access [java.util.zip.ZipFile], which needs the central directory
     * this deliberately did not fetch. Each entry is checked against its
     * central-directory CRC before it counts as restored.
     *
     * Deliberately does **not** go through [SessionZip]'s `DatCodec` decode: this path
     * only runs for `schema < 3` archives (see [isSplitLayout]), which predate the
     * split-bundle feature entirely — and therefore predate `DatCodec` too. Every
     * `.dat` entry a legacy archive can hold is guaranteed raw. A schema this old
     * never gets `DatCodec`-encoded going forward either, since a *new* upload always
     * writes the current schema and goes through [SessionZip.build] /
     * [SessionZip.forEachEntry] instead of this path.
     */
    private fun unpackPrefix(zip: File, layout: Layout, crcByName: Map<String, Long>): String {
        var refPath = ""
        var restored = 0
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            generateSequence { input.nextEntry }
                .filterNot { it.isDirectory }
                .forEach { entry ->
                    val dest = writePrefixEntry(input, entry.name, layout, crcByName[entry.name])
                    if (dest.name == "reference.png") refPath = dest.absolutePath
                    restored++
                }
        }
        requirePrefixComplete(restored, crcByName.size)
        return refPath
    }

    /** Copy one prefix entry into place, verifying it against its declared CRC. */
    private fun writePrefixEntry(
        input: ZipInputStream,
        entryName: String,
        layout: Layout,
        expectedCrc: Long?,
    ): File {
        val role = entryName.substringBefore('/', missingDelimiterValue = "")
        val name = entryName.substringAfter('/', missingDelimiterValue = "")
        if (role.isEmpty() || name.isEmpty()) {
            throw CorruptTransferException("unexpected_zip_entry")
        }
        val dest = destFor(role, name, layout)
        dest.parentFile?.mkdirs()
        val crc = CRC32()
        dest.outputStream().buffered().use { out ->
            val buffer = ByteArray(UNPACK_BUFFER_BYTES)
            var n = input.read(buffer)
            while (n > 0) {
                crc.update(buffer, 0, n)
                out.write(buffer, 0, n)
                n = input.read(buffer)
            }
        }
        if (expectedCrc != null && crc.value != expectedCrc) {
            throw CorruptTransferException("entry_crc_mismatch")
        }
        return dest
    }

    private fun requirePrefixComplete(restored: Int, expected: Int) {
        if (restored != expected) {
            throw CorruptTransferException("prefix_entry_count")
        }
    }

    /**
     * Download Session.zip with size checks, verify zip magic, unpack.
     * Deletes `.part` / `.full` sidecars so a corrupt transfer cannot stick.
     * [onProgress] is byte-based: (bytesOnDisk, declaredSize).
     */
    private suspend fun downloadAndUnpackBundle(
        fetch: BundleFetch,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): BundleOutcome {
        val api = fetch.api
        val token = fetch.token
        val sessionId = fetch.sessionId
        val bundleEntry = fetch.entry
        val layout = fetch.layout
        val appContext = fetch.appContext
        val zipTmp = File(appContext.cacheDir, "restore_${sessionId}_bundle.zip")
        return try {
            val expected = bundleEntry.sizeBytes.takeIf { it > 0L } ?: -1L
            val totalForUi = expected.takeIf { it > 0L } ?: 1L
            onProgress(0L, totalForUi)
            api.downloadFile(
                token,
                bundleEntry.fileId,
                zipTmp,
                expectedBytes = expected,
                onBytes = { have ->
                    val total = if (expected > 0L) expected else have.coerceAtLeast(1L)
                    onProgress(have.coerceAtMost(total), total)
                },
            )
            verifySessionZip(zipTmp, expected, bundleEntry.sha256, requireEntries = true)
            // Download bytes are done; hold 100% through unpack so the row
            // doesn't look stuck again during inflate.
            onProgress(totalForUi, totalForUi)
            BundleOutcome(unpackBundle(zipTmp, layout), "whole-bundle", zipTmp.length())
        } finally {
            zipTmp.delete()
            AtomicFiles.deleteSidecars(zipTmp)
        }
    }

    /** Legacy per-file backups: download each artifact into place. Returns refPath. */
    private suspend fun restoreLegacyFiles(
        api: CloudApi,
        token: String,
        files: List<CloudFileDto>,
        layout: Layout,
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
                        val dest = destFor(f.role, f.name, layout)
                        api.downloadFile(
                            token,
                            f.fileId,
                            dest,
                            expectedBytes = f.sizeBytes.takeIf { it > 0L } ?: -1L,
                        )
                        if (dest.name == "reference.png") refPath.set(dest.absolutePath)
                        onProgress(done.incrementAndGet().toLong(), total)
                    }
                }
            }.awaitAll()
        }
        return refPath.get()
    }

    /**
     * Extract a Session.zip into the layout a local run would have produced.
     * Entries are named `role/name` by [DicUploadWorker]; the mapping must
     * mirror the legacy per-file restore. Returns the reference image's
     * restored path ("" if the bundle somehow lacks one).
     */
    private fun unpackBundle(zip: File, layout: Layout): String {
        var refPath = ""
        SessionZip.forEachEntry(zip) { role, name, input ->
            val dest = destFor(role, name, layout)
            dest.parentFile?.mkdirs()
            dest.outputStream().use { input.copyTo(it) }
            if (dest.name == "reference.png") refPath = dest.absolutePath
        }
        return refPath
    }

    /**
     * Where one artifact lands on disk, by role — the single mapping both
     * restore paths share. Guards against zip-slip: an entry must resolve to a
     * path strictly **inside** the session directory.
     *
     * The containment test compares whole path segments. A plain string-prefix
     * test let `../<id>X/…` through, since a sibling directory whose name merely
     * starts with this session's id shares its path as a prefix. An entry that
     * escapes is a hostile or broken archive, so it fails as a
     * [CorruptTransferException]: terminal, never retried.
     */
    @VisibleForTesting
    internal fun destFor(role: String, name: String, layout: Layout): File {
        val dest = when {
            role == ArtifactRoles.RAW && name == SessionZip.REFERENCE_NAME -> File(layout.sessionDir, "reference.png")
            role == ArtifactRoles.RAW -> File(layout.rawDeformedDir, name)
            // Per-frame reports/heatmaps into their own subfolders — one PDF and
            // five PNGs per frame flat in the session dir would drown the .dat files.
            role == ArtifactRoles.REPORTS -> File(layout.sessionDir, "reports/$name")
            role == ArtifactRoles.PROCESSED -> File(layout.sessionDir, "${SessionPaths.PROCESSED_SUBDIR}/$name")
            // dat lives flat in the session dir; csv is regenerable and kept
            // beside the session for export.
            else -> File(layout.sessionDir, name)
        }
        // rawDeformedDir sits inside sessionDir, so the session dir is the only bound.
        val root = layout.sessionDir.canonicalPath
        if (!dest.canonicalPath.startsWith(root + File.separator)) {
            throw CorruptTransferException(
                "artifact_path_escapes_session",
                IllegalArgumentException("$role/$name"),
            )
        }
        dest.parentFile?.mkdirs()
        return dest
    }

    @VisibleForTesting
    internal data class RestoreRecordTarget(
        val localId: String,
        val cloudSessionId: String,
        val sessionDir: File,
        val refPath: String,
        val existing: SessionRecord?,
    )

    @VisibleForTesting
    internal fun recordFrom(meta: JSONObject, target: RestoreRecordTarget): SessionRecord {
        val engine = meta.optJSONObject("engine") ?: JSONObject()
        val roi = engine.optJSONObject("roi") ?: JSONObject()
        val metrics = meta.optJSONObject("metrics") ?: JSONObject()
        val defNames = restoredFrameNames(meta)
        val stats = restoredEngineStats(engine)
        val now = System.currentTimeMillis()
        val sweep = engine.optJSONObject("sweep")
        val skipped = sweep?.optJSONObject("skipped")
        val skipNodes = SkippedNode.fromMetadata(skipped)
        val legacySkip = SkippedNode.toLegacyLists(skipNodes)
        return SessionRecord(
            id = target.localId,
            name = target.existing?.name?.takeIf { it.isNotBlank() }
                ?: meta.optString("name").ifBlank { meta.optString("specimen", "Restored") },
            createdAt = target.existing?.createdAt ?: now,
            updatedAt = now,
            frameCount = meta.optInt("frameCount", defNames.size),
            subset = engine.optInt("subset", 41),
            step = engine.optInt("step", 5),
            strainWindow = engine.optInt("strainWindow", 15),
            use6x6 = engine.optBoolean("use6x6", false),
            imgW = engine.optInt("imageWidth", 0),
            imgH = engine.optInt("imageHeight", 0),
            roiX = roi.optInt("x", 0),
            roiY = roi.optInt("y", 0),
            roiW = roi.optInt("w", 0),
            roiH = roi.optInt("h", 0),
            refPath = target.refPath,
            refName = meta.optString("specimen", "Reference"),
            sessionDir = target.sessionDir.absolutePath,
            defNames = defNames,
            headline = restoredHeadline(meta, engine, defNames, stats),
            engineStats = stats,
            strainMethod = engine.optString("strainMethod", "VSG"),
            pointsConverged = metrics.optInt("pointsConverged", 0),
            avgIterations = metrics.optDouble("avgIterations", 0.0).toFloat(),
            executionTimeMs = metrics.optInt("executionTimeMs", 0),
            // Absent from backups made before they were written: read as a
            // completed run, which is what those records said too.
            stopCode = metrics.optInt("stopCode", 0),
            plannedFrameCount = metrics.optInt("plannedFrameCount", 0),
            cloudSessionId = target.cloudSessionId,
            // It came from the cloud, so it is by definition backed up.
            syncState = SessionRecord.SyncState.SYNCED,
            sweepSubsets = intList(sweep?.optJSONArray("subsets")),
            sweepSteps = intList(sweep?.optJSONArray("steps")),
            sweepStrainWindows = intList(sweep?.optJSONArray("strainWindows")),
            sweepLabels = stringList(sweep?.optJSONArray("labels")),
            lineCutHorizontal = sweep?.optBoolean("lineCutHorizontal", true) ?: true,
            sweepSkipSubsets = legacySkip.subsets,
            sweepSkipSteps = legacySkip.steps,
            sweepSkipStrainWindows = legacySkip.strainWindows,
            sweepSkipCodes = legacySkip.codes,
            sweepSkippedNodes = skipNodes,
            renamedByUser = target.existing?.renamedByUser ?: false,
        )
    }

    private fun restoredFrameNames(meta: JSONObject): List<String> {
        val frames = meta.optJSONArray("frames") ?: return emptyList()
        return buildList {
            for (i in 0 until frames.length()) add(frames.getJSONObject(i).optString("image"))
        }.filter { it.isNotBlank() }
    }

    private fun restoredEngineStats(engine: JSONObject): List<Float> {
        val stats = engine.optJSONArray("stats") ?: return emptyList()
        return buildList {
            for (i in 0 until stats.length()) add(stats.optDouble(i, 0.0).toFloat())
        }
    }

    /**
     * The Home-list headline for a restored session: for a sweep, the specimen
     * plus solved/total and subset span; otherwise [SessionHeadline]'s first-frame convergence.
     * Skips are counted from the parsed nodes: backups write a `nodes` array,
     * and counting the legacy `subsets` list alone read 0 for every new one.
     */
    internal fun restoredHeadline(
        meta: JSONObject,
        engine: JSONObject,
        defNames: List<String>,
        stats: List<Float>,
    ): String {
        val sweep = engine.optJSONObject("sweep")
            ?: return SessionHeadline.firstFrameConvergence(
                stats.getOrElse(EngineStats.SLOT_CONVERGENCE) { 0f },
                defNames.size,
            )
        val solved = meta.optInt("frameCount", defNames.size)
        val skipCount = SkippedNode.fromMetadata(sweep.optJSONObject("skipped")).size
        val image = defNames.firstOrNull().orEmpty().ifBlank { meta.optString("specimen", "frame") }
        val subsets = intList(sweep.optJSONArray("subsets"))
        val lo = subsets.minOrNull() ?: engine.optInt("subset", 0)
        val hi = subsets.maxOrNull() ?: lo
        return String.format(
            java.util.Locale.US,
            "%s · %d of %d solved · subset %d–%d",
            image,
            solved,
            solved + skipCount,
            lo,
            hi,
        )
    }

    private fun intList(arr: JSONArray?): List<Int> = buildList {
        if (arr == null) return@buildList
        for (i in 0 until arr.length()) add(arr.optInt(i))
    }

    private fun stringList(arr: JSONArray?): List<String> = buildList {
        if (arr == null) return@buildList
        for (i in 0 until arr.length()) add(arr.optString(i))
    }
}
