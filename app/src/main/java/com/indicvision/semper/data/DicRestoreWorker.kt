package com.indicvision.semper.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.indicvision.semper.R
import com.indicvision.semper.data.account.LicenseErrors
import com.indicvision.semper.data.cloud.TransferNotifications
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.data.cloud.restore.DownloadFailure
import com.indicvision.semper.data.cloud.restore.DownloadProgress
import com.indicvision.semper.data.cloud.restore.RestoreDownloadOutcomes
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.navigation.DicKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Downloads a cloud backup and rebuilds it on this device.
 *
 * This runs in WorkManager rather than an Activity scope on purpose: a restore
 * can be hundreds of megabytes, and a `lifecycleScope` job is cancelled the
 * moment the user leaves the screen — which silently abandoned the download
 * part-way through. As a worker it survives navigation and app death, retries
 * on flaky networks, and reports progress the UI can observe if it's watching.
 */
class DicRestoreWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val restorer: Restorer,
) : CoroutineWorker(context, params) {

    /** The constructor WorkManager instantiates by reflection; keep it public. */
    constructor(context: Context, params: WorkerParameters) : this(context, params, Restorer.Cloud)

    /** What the worker runs; a seam so tests can drive [doWork] without a backend. */
    internal fun interface Restorer {
        suspend fun restore(
            context: Context,
            cloudSessionId: String,
            targetLocalId: String,
            onProgress: suspend (done: Long, total: Long) -> Unit,
        ): String

        companion object {
            val Cloud = Restorer { context, cloudSessionId, targetLocalId, onProgress ->
                CloudRestore.restore(context, cloudSessionId, targetLocalId, onProgress = onProgress)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        TransferNotifications.restoreForeground(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cloudSessionId = inputData.getString(CloudRestore.KEY_CLOUD_SESSION_ID)
            ?: return@withContext Result.failure()
        val targetLocalId = inputData.getString(CloudRestore.KEY_TARGET_LOCAL_ID)
            ?: return@withContext Result.failure()

        try {
            clearPartialArtifacts(targetLocalId)
            publishProgress(targetLocalId, done = 0L, total = 0L)
            val localId = restorer.restore(
                applicationContext,
                cloudSessionId,
                targetLocalId,
            ) { done, total ->
                publishProgress(targetLocalId, done, total)
            }
            Timber.i("Restored %s from cloud session %s", localId, cloudSessionId)
            SemperAnalytics.event(applicationContext, SemperAnalytics.CLOUD_RESTORE_SUCCEEDED)
            Result.success()
        } catch (e: CancellationException) {
            clearPartialArtifacts(targetLocalId)
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            onFailure(DownloadFailure.of(e), cloudSessionId, targetLocalId)
        }
    }

    /**
     * Give up on a backup no retry can fix, clearing what this attempt wrote, or
     * retry from scratch. A retry starts the files over: CloudRestore deletes its
     * cache temps and their `*.part` sidecars on the way out, and the session dir
     * is cleared at the top of [doWork]. Only after process death (no way out)
     * does the next attempt resume the bundle from its leftover `.part` — see
     * [RestoreDownloadOutcomes]; the bundle's sha256 catches a bad resume.
     */
    private fun onFailure(failure: DownloadFailure, cloudSessionId: String, targetLocalId: String): Result =
        when (failure) {
            is DownloadFailure.Rejected -> {
                clearPartialArtifacts(targetLocalId)
                Timber.e(failure.cause, "Restore of %s rejected — giving up", cloudSessionId)
                restoreFailed("rejected")
                failWith(LicenseErrors.restoreMessage(applicationContext, failure.cause.body))
            }
            is DownloadFailure.Unusable -> {
                clearPartialArtifacts(targetLocalId)
                Timber.e(failure.cause, "Restore of %s cannot succeed — giving up (re-upload needed)", cloudSessionId)
                restoreFailed(if (failure.corrupt) "corrupt" else "unusable")
                // The cause's message is a reason code (e.g. session_zip_sha256_mismatch):
                // logged above, never shown.
                failWith(applicationContext.getString(R.string.restore_failed_generic))
            }
            is DownloadFailure.Transient -> {
                Timber.w(failure.cause, "Restore of %s failed; will retry", cloudSessionId)
                Result.retry()
            }
        }

    private fun restoreFailed(reason: String) {
        SemperAnalytics.event(applicationContext, SemperAnalytics.CLOUD_RESTORE_FAILED, mapOf("reason" to reason))
    }

    /**
     * End the work with [DicKeys.DOWNLOAD_ERROR] set to [message].
     *
     * For this worker the value is **display-ready, localised text**: Home and
     * Settings toast it verbatim (falling back to `restore_failed_generic` when it
     * is absent), so a reason code or a raw response body must never go here.
     * [DicBundleDownloadWorker] fills the same key the other way round, with a
     * code its observer translates; see its `fail`.
     */
    private fun failWith(message: String): Result =
        Result.failure(workDataOf(DicKeys.DOWNLOAD_ERROR to message))

    private suspend fun publishProgress(localId: String, done: Long, total: Long) {
        setProgress(DownloadProgress.data(done, total, localId))
    }

    private fun clearPartialArtifacts(localId: String) {
        runCatching { CloudRestore.clearPartialArtifacts(applicationContext, localId) }
            .onFailure { Timber.w(it, "Could not clean partial restore %s", localId) }
    }
}
