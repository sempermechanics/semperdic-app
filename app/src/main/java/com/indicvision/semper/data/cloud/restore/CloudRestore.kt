package com.indicvision.semper.data.cloud.restore

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.indicvision.semper.data.DicBundleDownloadWorker
import com.indicvision.semper.data.DicRestoreWorker
import com.indicvision.semper.data.cloud.CorruptTransferException
import com.indicvision.semper.data.cloud.SessionMetadataDoc
import com.indicvision.semper.data.cloud.SessionUploadMetadata
import com.indicvision.semper.data.cloud.TransferLog
import com.indicvision.semper.data.cloud.UploadWorkOutcomes
import com.indicvision.semper.data.cloud.WorkTags
import com.indicvision.semper.data.cloud.enqueueUnique
import com.indicvision.semper.data.cloud.oneTimeWork
import com.indicvision.semper.data.net.ArtifactRoles
import com.indicvision.semper.data.net.Authed
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.CloudFileDto
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.net.authed
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.data.session.SessionLayout
import com.indicvision.semper.data.session.SessionNaming
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.data.session.SessionZip
import com.indicvision.semper.diagnostics.SemperAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

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
@Suppress("TooManyFunctions") // the restore's entry points for the screens and workers, plus the steps that order them
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
        val work = oneTimeWork<DicRestoreWorker>(
            tags = listOf(WorkTags.RESTORE, WorkTags.restoreTag(cloudSessionId)),
            input = workDataOf(
                KEY_CLOUD_SESSION_ID to cloudSessionId,
                KEY_TARGET_LOCAL_ID to targetLocalId,
            ),
            expedited = true,
        )
        enqueueUnique(context, name, ExistingWorkPolicy.KEEP, work)
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
        val work = oneTimeWork<DicBundleDownloadWorker>(
            tags = listOf(WorkTags.BUNDLE_DOWNLOAD, WorkTags.bundleDownloadTag(cloudSessionId)),
            input = workDataOf(
                KEY_CLOUD_SESSION_ID to cloudSessionId,
                DicBundleDownloadWorker.KEY_DISPLAY_NAME to displayName,
                DicBundleDownloadWorker.KEY_LOCAL_SESSION_ID to localSessionId,
                DicBundleDownloadWorker.KEY_DEST_URI to destUri,
            ),
            expedited = true,
        )
        enqueueUnique(context, name, ExistingWorkPolicy.KEEP, work)
        return name
    }

    fun cancelBundleDownload(context: Context, cloudSessionId: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(bundleDownloadWorkName(cloudSessionId))
    }

    /** Unique work name for a restore, so the UI can observe its progress. */
    fun workName(cloudSessionId: String): String = WorkTags.restoreName(cloudSessionId)

    /** Unique work name for a Save-to-Files download. */
    fun bundleDownloadWorkName(cloudSessionId: String): String = WorkTags.bundleDownloadName(cloudSessionId)

    /** Suggested SAF filename for an analysis Session.zip. */
    fun suggestedBundleFileName(displayName: String): String = SessionNaming.bundleFileName(displayName)

    const val TAG_BUNDLE_DOWNLOAD = WorkTags.BUNDLE_DOWNLOAD

    /** How much of the cloud id names a row restored from a backup that carries no local id. */
    private const val RESTORED_ID_CHARS = 12

    fun targetLocalId(cloud: CloudSessionDto): String =
        cloud.localSessionId.ifBlank { "restored-" + cloud.sessionId.take(RESTORED_ID_CHARS) }

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
     * `.dat`s can still offer Download when a cloud copy exists. Auth/config
     * failures stay distinct from an empty list.
     */
    suspend fun listCompleted(
        context: Context,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): ListResult = withContext(Dispatchers.IO) {
        when (val listed = api.authed(tokens) { listSessions(it).sessions }) {
            Authed.Disabled -> ListResult.ApiOff
            Authed.NoToken -> ListResult.NeedSignIn
            is Authed.Failed -> {
                val cause = listed.failure.cause
                Timber.e(cause, "listCompleted sessions failed")
                ListResult.Failed(cause.message ?: cause.toString())
            }
            is Authed.Ok -> {
                val sessions = listed.value.filter { it.status == UploadWorkOutcomes.STATUS_COMPLETED }
                if (sessions.isEmpty()) ListResult.Empty else ListResult.Ready(sessions)
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
        val token = tokens.usableIdToken() ?: error("Not signed in")
        val files = completedFiles(api, token, sessionId)
        val bundleEntry = files.firstOrNull { it.role == ArtifactRoles.BUNDLE }
            ?: throw UnrestorableBackupException("backup_no_bundle")

        val outDir = CacheJanitor.shareDir(context.applicationContext.cacheDir)
        val dest = File(outDir, SessionNaming.bundleCacheFileName(displayName, sessionId))
        discard(dest)
        var complete = false
        try {
            val whole = api.downloadReporting(token, bundleEntry, dest, onProgress)
            RestoreZipVerifier.verifySessionZip(dest, bundleEntry.declaredSize, bundleEntry.sha256)
            // Since the payload was split, Session.zip alone is no longer the whole
            // analysis. "Save to Files" is the deliverables use case, so pull Extras.zip
            // too and hand over one merged archive — the same single file as before.
            files.firstOrNull { it.role == ArtifactRoles.EXTRAS }?.let { mergeExtrasInto(api, token, it, dest) }
            onProgress(whole, whole)
            complete = true
        } finally {
            // A failed or cancelled attempt leaves no half-verified archive in the share dir.
            if (!complete) discard(dest)
        }
        dest
    }

    /** Fold Extras.zip into [dest] so Save-to-Files still yields one complete archive. */
    private suspend fun mergeExtrasInto(api: CloudApi, token: String, extrasEntry: CloudFileDto, dest: File) {
        val extrasTmp = File(dest.parentFile, "${dest.name}.extras")
        try {
            api.downloadFile(token, extrasEntry.fileId, extrasTmp, expectedBytes = extrasEntry.declaredSize)
            RestoreZipVerifier.verifyExtrasZip(extrasTmp, extrasEntry.sha256)
            SessionZip.merge(listOf(dest, extrasTmp), dest)
        } finally {
            discard(extrasTmp)
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
        val token = tokens.usableIdToken() ?: error("Not signed in")
        val files = completedFiles(api, token, sessionId)

        // 1. metadata.json first — it's the blueprint for everything else.
        val metaEntry = files.firstOrNull { it.role == ArtifactRoles.METADATA }
            ?: throw UnrestorableBackupException("backup_no_metadata")
        val metadataTmp = scratchFile(appContext.cacheDir, sessionId, "metadata.json")
        val metadata = fetchMetadata(api, token, metaEntry, metadataTmp)

        // The enqueueing UI already created a row under targetLocalId. Never let
        // metadata select a second id and leave an orphan stub behind.
        val existing = SessionStore.get(appContext, targetLocalId)
        val layout = SessionLayout(SessionStore.dirFor(appContext, targetLocalId))
        layout.rawDeformedDir.mkdirs()
        layout.metadataJson.writeBytes(metadata.bytes)

        // 2. Everything else, into the layout a local run would have produced.
        val fetch = RestoreBundleFetcher.PayloadFetch(api, token, sessionId, appContext.cacheDir, layout)
        val outcome = RestoreBundleFetcher.fetchPayload(fetch, files, metadata.doc.isSplitLayout(), onProgress)

        // 3. Rebuild the index row from the blueprint.
        val record = metadata.doc.toRecord(targetLocalId, sessionId, layout.dir, outcome.refPath, existing)
        // allowOverLimit: the analysis already counts against the cloud quota.
        val saved = SessionStore.save(appContext, record, allowOverLimit = true)
        check(saved == SessionStore.UpsertResult.SAVED) { "Could not update the restored session index ($saved)" }
        logRestoreSaving(outcome, metaEntry.sizeBytes, files)
        targetLocalId
    }

    /** The backup's COMPLETED files; none at all is a backup no retry will fix. */
    private suspend fun completedFiles(api: CloudApi, token: String, sessionId: String): List<CloudFileDto> {
        val files = api.listSessionFiles(token, sessionId).files.filter { it.status == "COMPLETED" }
        if (files.isEmpty()) throw UnrestorableBackupException("backup_no_completed_files")
        return files
    }

    /** `metadata.json` as downloaded (written back verbatim) and parsed. */
    private class FetchedMetadata(val bytes: ByteArray, val doc: SessionMetadataDoc)

    /**
     * Download and check the backup's `metadata.json`.
     *
     * Every attempt starts from nothing: [tmp] and its `.part` / `.full` sidecars are
     * cleared first, since the download resumes from a `.part` beside its destination
     * and one an earlier attempt left could hold an older body of this file (the
     * backend's metadata replace route rewrites it when an analysis is edited after
     * its backup, e.g. a deflection correction). The declared sha256 is checked like
     * the bundle's, when the file list carries one; a mismatch, a body that is not
     * a JSON object (or holds a frame that is not one), or one that will not build
     * an index row, is corrupt (terminal).
     */
    private suspend fun fetchMetadata(api: CloudApi, token: String, entry: CloudFileDto, tmp: File): FetchedMetadata {
        discard(tmp)
        try {
            api.downloadFile(token, entry.fileId, tmp, expectedBytes = entry.declaredSize)
            val bytes = tmp.readBytes()
            RestoreZipVerifier.verifyMetadata(bytes, entry.sha256)
            val doc = try {
                SessionMetadataDoc.decode(String(bytes, Charsets.UTF_8)).also { doc ->
                    // Build the index row once now, so a blueprint no restore can turn
                    // into one (legacy skip lists that disagree) fails before the bundle
                    // is downloaded, and as corrupt rather than a retry that downloads it again.
                    doc.toRecord(localId = "", cloudSessionId = "", sessionDir = tmp, refPath = "", existing = null)
                }
            } catch (e: IllegalArgumentException) {
                // SerializationException is one: what org.json's parse used to throw as JSONException.
                throw CorruptTransferException("metadata_json_invalid", e)
            }
            return FetchedMetadata(bytes, doc)
        } finally {
            discard(tmp)
        }
    }

    /** Log restore completion; structured line is PII-free, Timber line is coarse totals only. */
    private fun logRestoreSaving(
        outcome: RestoreBundleFetcher.PayloadOutcome,
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

    /** Remove an interrupted restore's files while retaining its cloud-only index row. */
    fun clearPartialArtifacts(context: Context, localId: String) {
        val dir = SessionStore.dirFor(context.applicationContext, localId)
        dir.deleteRecursively()
        check(dir.mkdirs()) { "Could not reset partial restore directory" }
    }

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
}
