package com.sempermechanics.semper.data.cloud

import android.content.Context
import androidx.work.WorkManager
import com.sempermechanics.semper.util.suspendRunCatching
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** How long a delete waits for a row's upload work to stop before it goes on regardless. */
internal const val UPLOAD_STOP_WAIT_MS = 10_000L

private const val UPLOAD_STOP_POLL_MS = 250L

/**
 * Cancel row [localId]'s upload work and wait, up to [UPLOAD_STOP_WAIT_MS],
 * until WorkManager has it finished: [SessionDeletes.run]'s step 3 (L5-3).
 * Best effort: a worker still winding down records nothing for a row that is
 * no longer PENDING (`DicUploadWorker`'s guard, `markSyncedIfPending`).
 */
internal suspend fun cancelUploadAndWait(context: Context, localId: String) {
    val name = WorkTags.uploadName(localId)
    val stopped = suspendRunCatching {
        val work = WorkManager.getInstance(context.applicationContext)
        work.cancelUniqueWork(name)
        withTimeoutOrNull(UPLOAD_STOP_WAIT_MS) {
            while (work.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }) {
                delay(UPLOAD_STOP_POLL_MS)
            }
            true
        } ?: false
    }
    stopped.onFailure { Timber.w(it, "Could not cancel the upload of %s before its delete", localId) }
    if (stopped.getOrNull() == false) Timber.w("Upload of %s still running after the wait; deleting on", localId)
}
