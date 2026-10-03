package com.sempermechanics.semper.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.SessionMetadataSync
import com.sempermechanics.semper.navigation.DicKeys

/**
 * Runs [SessionMetadataSync.send] for one session. It waits out an upload
 * still in flight and a failed call with WorkManager's backoff, then stops
 * after [MAX_ATTEMPTS] and leaves the row marked: [CloudSync.reconcile] queues
 * it again later.
 */
class SessionMetadataWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(DicKeys.SESSION_LOCAL_ID) ?: return Result.failure()
        return when (SessionMetadataSync.send(applicationContext, id)) {
            SessionMetadataSync.Outcome.DONE, SessionMetadataSync.Outcome.LATER -> Result.success()
            SessionMetadataSync.Outcome.WAIT, SessionMetadataSync.Outcome.RETRY ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    private companion object {
        // 30 s doubling: the last try is about four hours after the first.
        const val MAX_ATTEMPTS = 9
    }
}
