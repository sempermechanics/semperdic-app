package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import timber.log.Timber

/**
 * Writes a finished run's [record] to the session index and, once it is
 * there and [cloudEnabled], queues its upload. [admitted] is a run the quota
 * let start as a new session (`RunAdmission.Admitted`): its row is saved even
 * if the cap filled while it ran, since only starting is blocked. The result
 * says why a row was not written: [SessionStore.UpsertOutcome.QUOTA_FULL] is a
 * new row with no admission at the limit (a re-run whose row was deleted);
 * [SessionStore.UpsertOutcome.INDEX_UNAVAILABLE] is an index that could not
 * be read or written, which no upgrade or deletion would fix.
 */
@WorkerThread
internal fun saveRunRecord(
    appContext: Context,
    record: SessionRecord,
    cloudEnabled: Boolean,
    admitted: Boolean,
): SessionStore.UpsertOutcome {
    val result = SessionStore.save(appContext, record, allowOverLimit = admitted)
    if (result == SessionStore.UpsertOutcome.SAVED) {
        if (cloudEnabled) {
            CloudSync.enqueueUpload(appContext, record.id)
        } else {
            Timber.d("Save to cloud is off — session %s stays local only", record.id)
        }
    }
    return result
}
