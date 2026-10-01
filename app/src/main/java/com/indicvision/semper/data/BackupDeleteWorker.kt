package com.indicvision.semper.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.indicvision.semper.data.cloud.SessionDeletes
import timber.log.Timber

/**
 * Runs one queued delete from [SessionDeletes], after the undo window.
 *
 * The delay is the safety net: a confirmed deletion sits in the queue for
 * [SessionDeletes.UNDO_WINDOW_SECONDS] and reaches the backend only if nobody
 * cancels it, so a mis-tapped bin followed by a reflexive confirm is still
 * recoverable.
 *
 * It runs in WorkManager rather than a screen scope for the same reason
 * [DicRestoreWorker] does: the user already confirmed, so leaving the screen —
 * or the app — must not quietly abandon the request, and a delete that fails
 * offline should retry rather than be lost. The class keeps its old name so a
 * delete queued by an earlier build still finds its worker after an update.
 */
class BackupDeleteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val items = SessionDeletes.decode(inputData.getString(SessionDeletes.KEY_ITEMS)) + legacyItem()
        if (items.isEmpty()) return Result.failure()

        setProgress(workDataOf(SessionDeletes.KEY_DONE to 0, SessionDeletes.KEY_TOTAL to items.size))
        val report = SessionDeletes.run(applicationContext, items) { done, total ->
            setProgress(workDataOf(SessionDeletes.KEY_DONE to done, SessionDeletes.KEY_TOTAL to total))
        }

        // Nothing reached the backend (offline, or no usable token): try the
        // whole batch again later. After that, say which ones are still there
        // rather than retrying in silence.
        val retry = report.nothingSent && runAttemptCount < MAX_SILENT_RETRIES
        if (retry) Timber.w("Delete of %d analyses did not reach the cloud; retrying", items.size)
        return if (retry) Result.retry() else Result.success(outcome(items, report))
    }

    private fun outcome(items: List<SessionDeletes.Item>, report: SessionDeletes.Report) = workDataOf(
        SessionDeletes.KEY_DONE to report.done,
        SessionDeletes.KEY_TOTAL to items.size,
        SessionDeletes.KEY_STILL_IN_CLOUD to SessionDeletes.encode(report.stillInCloud),
        SessionDeletes.KEY_CLOUD_ONLY to items.all { it.mode == SessionDeletes.Mode.CLOUD },
    )

    /** A single delete queued by a build from before [SessionDeletes]. */
    private fun legacyItem(): List<SessionDeletes.Item> {
        val cloudId = inputData.getString(KEY_CLOUD_SESSION_ID) ?: return emptyList()
        val mode = if (inputData.getBoolean(KEY_ALSO_LOCAL, false)) {
            SessionDeletes.Mode.EVERYWHERE
        } else {
            SessionDeletes.Mode.CLOUD
        }
        return listOf(SessionDeletes.Item(inputData.getString(KEY_LOCAL_SESSION_ID).orEmpty(), cloudId, mode))
    }

    private companion object {
        const val KEY_CLOUD_SESSION_ID = "cloud_session_id"
        const val KEY_LOCAL_SESSION_ID = "local_session_id"
        const val KEY_ALSO_LOCAL = "also_local"
        const val MAX_SILENT_RETRIES = 3
    }
}
