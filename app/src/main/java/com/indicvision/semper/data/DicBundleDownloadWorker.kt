package com.indicvision.semper.data

import android.content.Context
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.indicvision.semper.data.cloud.TransferLog
import com.indicvision.semper.data.cloud.TransferNotifications
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.data.cloud.restore.DownloadFailure
import com.indicvision.semper.data.cloud.restore.DownloadProgress
import com.indicvision.semper.data.cloud.restore.RestoreDownloadOutcomes
import com.indicvision.semper.data.cloud.restore.SafDestination
import com.indicvision.semper.data.net.ApiException
import com.indicvision.semper.data.session.SessionEverythingExporter
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Downloads a cloud Session.zip (or packs a local session) into a user-chosen
 * SAF document URI.
 *
 * Runs in WorkManager rather than an Activity lifecycle scope: leaving
 * Analyses data management must not cancel mid-download. The destination URI
 * is picked **before** enqueue so this worker only writes to that location —
 * no Save/Share sheet afterward.
 */
class DicBundleDownloadWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val source: BundleSource,
) : CoroutineWorker(context, params) {

    /** The constructor WorkManager instantiates by reflection; keep it public. */
    constructor(context: Context, params: WorkerParameters) : this(context, params, BundleSource.Cloud)

    /** Where the cloud Session.zip comes from; a seam so tests can drive [doWork] without a backend. */
    internal fun interface BundleSource {
        suspend fun download(
            context: Context,
            cloudSessionId: String,
            displayName: String,
            onProgress: suspend (done: Long, total: Long) -> Unit,
        ): File

        companion object {
            val Cloud = BundleSource { context, cloudSessionId, displayName, onProgress ->
                CloudRestore.downloadBundleZip(context, cloudSessionId, displayName, onProgress = onProgress)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        TransferNotifications.downloadForeground(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cloudSessionId = inputData.getString(CloudRestore.KEY_CLOUD_SESSION_ID)
            ?: return@withContext Result.failure()
        val displayName = inputData.getString(KEY_DISPLAY_NAME).orEmpty()
        val localSessionId = inputData.getString(KEY_LOCAL_SESSION_ID).orEmpty()
        val dest = inputData.getString(KEY_DEST_URI)?.let { SafDestination(applicationContext, it.toUri()) }
            ?: return@withContext fail("no_dest")

        var staged: File? = null
        // A retry writes to the same document, so it keeps the grant.
        var releaseGrant = true
        try {
            publishProgress(done = 0L, total = 0L)
            SemperAnalytics.event(applicationContext, SemperAnalytics.EXPORT_STARTED, mapOf("kind" to KIND))
            TransferLog.phase(TransferLog.PhaseFields(PHASE, "start"))
            staged = downloadOrPack(cloudSessionId, displayName, localSessionId)
            deliver(staged, dest, cloudSessionId)
        } catch (e: CancellationException) {
            dest.delete()
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            val failure = DownloadFailure.of(e)
            releaseGrant = failure !is DownloadFailure.Transient
            onFailure(failure, dest)
        } finally {
            staged?.delete()
            if (releaseGrant) dest.releaseGrant()
        }
    }

    /** Copies [staged] into [dest]; an empty archive or a failed write removes the document instead. */
    private fun deliver(staged: File, dest: SafDestination, cloudSessionId: String): Result {
        val problem = when {
            !staged.exists() || staged.length() <= 0L -> "empty"
            !dest.write(staged) -> "write"
            else -> null
        }
        if (problem != null) {
            exportFailed(problem)
            dest.delete()
            return fail(problem)
        }
        SemperAnalytics.event(applicationContext, SemperAnalytics.EXPORT_COMPLETED, mapOf("kind" to KIND))
        TransferLog.phase(TransferLog.PhaseFields(PHASE, "complete"))
        return Result.success(workDataOf(CloudRestore.KEY_CLOUD_SESSION_ID to cloudSessionId))
    }

    /** Give up, removing the empty document, or retry into the same document. */
    private fun onFailure(failure: DownloadFailure, dest: SafDestination): Result = when (failure) {
        is DownloadFailure.Rejected -> {
            val e = failure.cause
            Timber.e(e, "Bundle download rejected — giving up")
            TransferLog.phase(
                TransferLog.PhaseFields(PHASE, "rejected", httpStatus = e.code, requestId = e.requestId),
            )
            exportFailed("rejected")
            dest.delete()
            // The body, not the message: LicenseErrors parses the `detail` code
            // out of it to say *why* (demo mode) instead of "check your connection".
            fail(e.body)
        }
        is DownloadFailure.Unusable -> {
            // Corrupt bytes, or a backup with nothing to download and no copy on
            // this phone to pack instead (see downloadOrPack): no retry can help.
            val reason = if (failure.corrupt) "corrupt" else "unusable"
            Timber.e(failure.cause, "Bundle download cannot succeed (%s) — giving up", reason)
            TransferLog.phase(TransferLog.PhaseFields(PHASE, reason))
            exportFailed(reason)
            dest.delete()
            fail(failure.cause.message ?: failure.cause.javaClass.simpleName)
        }
        is DownloadFailure.Transient -> {
            Timber.w(failure.cause, "Bundle download failed; will retry")
            val api = failure.api
            TransferLog.phase(
                TransferLog.PhaseFields(PHASE, "retry", httpStatus = api?.code, requestId = api?.requestId),
            )
            Result.retry()
        }
    }

    private fun exportFailed(reason: String) {
        SemperAnalytics.event(
            applicationContext,
            SemperAnalytics.EXPORT_FAILED,
            mapOf("kind" to KIND, "reason" to reason),
        )
    }

    /**
     * End the work with [DicKeys.DOWNLOAD_ERROR] set to [reason].
     *
     * For this worker the value is a **reason code or the backend's raw response
     * body**, never prose: Settings passes it through `LicenseErrors.downloadMessage`,
     * which picks the licence / device / gone messages out of a backend `detail`
     * and shows the generic download failure for anything else. A localised
     * sentence here would always read as that generic failure.
     * [DicRestoreWorker] fills the same key the other way round, with display-ready
     * text its observers toast verbatim; see its `failWith`.
     */
    private fun fail(reason: String): Result = Result.failure(workDataOf(DicKeys.DOWNLOAD_ERROR to reason))

    /**
     * Prefer the cloud Session.zip; if that backup has no bundle (legacy) or
     * the transfer fails for a non-transient reason that local packing can
     * cover, fall back to packing the on-device session. A corrupt download is
     * the exception: it is reported, not hidden behind the phone's copy.
     */
    private suspend fun downloadOrPack(
        cloudSessionId: String,
        displayName: String,
        localSessionId: String,
    ): File = suspendRunCatching {
        source.download(applicationContext, cloudSessionId, displayName) { done, total -> publishProgress(done, total) }
    }.getOrElse { e ->
        val packed = if (mayPackInstead(e)) packLocalFallback(localSessionId) else null
        packed?.also { Timber.i(e, "Cloud zip unavailable; packed local session fallback") } ?: throw e
    }

    /**
     * Whether the phone's copy may stand in for the cloud zip after [e]: not
     * for a backend answer (it says why) nor corrupt bytes (they are reported,
     * not hidden).
     */
    private fun mayPackInstead(e: Throwable): Boolean =
        e is Exception && e !is ApiException && !RestoreDownloadOutcomes.isTerminalCorruptFailure(e)

    private suspend fun packLocalFallback(localSessionId: String): File? {
        val record = localSessionId.takeIf { it.isNotBlank() }
            ?.let { SessionStore.get(applicationContext, it) }
            ?.takeIf { it.hasLocalData() }
            ?: return null
        return SessionEverythingExporter.exportSessionZip(applicationContext, record)
    }

    private suspend fun publishProgress(done: Long, total: Long) {
        setProgress(DownloadProgress.data(done, total))
    }

    companion object {
        /** The `kind` this worker's analytics events carry. */
        private const val KIND = "bundle_download"

        /** The `TransferLog` phase this worker logs under. */
        private const val PHASE = "bundle_download"

        const val KEY_DISPLAY_NAME = "DISPLAY_NAME"
        const val KEY_LOCAL_SESSION_ID = "LOCAL_SESSION_ID"
        const val KEY_DEST_URI = "DEST_URI"
    }
}
