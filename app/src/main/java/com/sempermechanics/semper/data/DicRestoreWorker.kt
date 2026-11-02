package com.sempermechanics.semper.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseErrors
import com.sempermechanics.semper.data.cloud.TransferNotifications
import com.sempermechanics.semper.data.cloud.TransferProgressUpdates
import com.sempermechanics.semper.data.cloud.TransferResultNotifications
import com.sempermechanics.semper.data.cloud.TransferRetryReceiver
import com.sempermechanics.semper.data.cloud.restore.CloudRestore
import com.sempermechanics.semper.data.cloud.restore.DownloadFailure
import com.sempermechanics.semper.data.cloud.restore.DownloadProgress
import com.sempermechanics.semper.data.cloud.restore.RestoreDownloadOutcomes
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.navigation.IntentKeys
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

    /** The running notification's reading and its once-a-second updates. */
    private val updates = TransferProgressUpdates(applicationContext, TransferNotifications.Kind.RESTORE)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cloudSessionId = inputData.getString(CloudRestore.KEY_CLOUD_SESSION_ID)
            ?: return@withContext Result.failure()
        val targetLocalId = inputData.getString(CloudRestore.KEY_TARGET_LOCAL_ID)
            ?.takeIf { SessionPaths.isSafeId(it) }
            // An id that cannot name a session dir: nothing is cleared or written (L5-1).
            ?: return@withContext Result.failure()
        // Read before the restore: a failed one clears what it wrote.
        val name = SessionStore.get(applicationContext, targetLocalId)?.name.orEmpty()
        restoreInto(cloudSessionId, targetLocalId).also { result ->
            TransferResultNotifications.afterWork(
                applicationContext,
                TransferResultNotifications.Subject(TransferNotifications.Kind.RESTORE, targetLocalId, name),
                result,
                reason = result.outputData.getString(IntentKeys.DOWNLOAD_ERROR),
                retry = TransferRetryReceiver.restoreIntent(applicationContext, cloudSessionId, targetLocalId, name),
            )
        }
    }

    /** The restore itself: success, a terminal failure with its message, or a retry. */
    private suspend fun restoreInto(cloudSessionId: String, targetLocalId: String): Result {
        return try {
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
            clearStoppedRestore(applicationContext, cloudSessionId, targetLocalId, stopReasonNow())
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            onFailure(DownloadFailure.of(e), cloudSessionId, targetLocalId)
        }
    }

    /**
     * Give up on a backup no retry can fix, clearing what this attempt wrote and
     * the bundle's `.part` (nothing will resume it), or retry. A retry unpacks
     * afresh (the session dir is cleared at the top of [doWork]) and refetches
     * every file but the bundle, whose `.part` a [DownloadFailure.Transient]
     * keeps (TD-307), as does a stop WorkManager reruns ([clearStoppedRestore],
     * TD-283) or process death (no way out): the next attempt resumes it — see
     * [RestoreDownloadOutcomes]; the bundle's sha256 catches a bad resume.
     */
    private fun onFailure(failure: DownloadFailure, cloudSessionId: String, targetLocalId: String): Result {
        if (failure !is DownloadFailure.Transient) CloudRestore.discardDownloads(applicationContext, cloudSessionId)
        return when (failure) {
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
            is DownloadFailure.Paused -> {
                // The restore kill switch: not a failure, so no failure event.
                clearPartialArtifacts(targetLocalId)
                Timber.i("Restore of %s paused by the backend", cloudSessionId)
                failWith(LicenseErrors.restoreMessage(applicationContext, failure.cause.body))
            }
            is DownloadFailure.Transient -> {
                Timber.w(failure.cause, "Restore of %s failed; will retry", cloudSessionId)
                Result.retry()
            }
        }
    }

    private fun restoreFailed(reason: String) {
        SemperAnalytics.event(applicationContext, SemperAnalytics.CLOUD_RESTORE_FAILED, mapOf("reason" to reason))
    }

    /**
     * End the work with [IntentKeys.DOWNLOAD_ERROR] set to [message].
     *
     * For this worker the value is **display-ready, localised text**: Home and
     * Settings toast it verbatim (falling back to `restore_failed_generic` when it
     * is absent), so a reason code or a raw response body must never go here.
     * [DicBundleDownloadWorker] fills the same key the other way round, with a
     * code its observer translates; see its `fail`.
     */
    private fun failWith(message: String): Result =
        Result.failure(workDataOf(IntentKeys.DOWNLOAD_ERROR to message))

    private suspend fun publishProgress(localId: String, done: Long, total: Long) {
        val reading = updates.sample(done, total)
        setProgress(DownloadProgress.data(done, total, localId, reading.bytesPerSecond))
        updates.foregroundIfDue(reading)?.let { TransferProgressUpdates.post(this, it) }
    }

    private fun clearPartialArtifacts(localId: String) {
        runCatching { CloudRestore.clearPartialArtifacts(applicationContext, localId) }
            .onFailure { Timber.w(it, "Could not clean partial restore %s", localId) }
    }
}

/**
 * What a restore of [cloudSessionId] into [localId] stopped for [stopReason]
 * leaves behind. The session dir is reset either way (the rerun unpacks into
 * it afresh). The bundle download's `.part` stays for a stop WorkManager runs
 * again — lost connectivity, quota, the system — so the rerun resumes it
 * rather than starting from byte 0; the app's own cancel drops it, as nothing
 * will resume it ([keepsDestinationOnStop], TD-283).
 */
internal fun clearStoppedRestore(context: Context, cloudSessionId: String, localId: String, stopReason: Int) {
    runCatching { CloudRestore.clearPartialArtifacts(context, localId) }
        .onFailure { Timber.w(it, "Could not clean partial restore %s", localId) }
    if (!keepsDestinationOnStop(stopReason)) CloudRestore.discardDownloads(context, cloudSessionId)
}
