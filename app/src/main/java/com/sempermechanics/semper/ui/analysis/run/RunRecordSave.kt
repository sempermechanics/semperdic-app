package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import timber.log.Timber

/**
 * Writes a finished run's [record] to the session index and, once it is
 * there and [cloudEnabled], queues its upload. The result says why a row was
 * not written: [SessionStore.UpsertOutcome.QUOTA_FULL] is the session limit
 * filling between the run's pre-check and this save;
 * [SessionStore.UpsertOutcome.INDEX_UNAVAILABLE] is an index that could not
 * be read or written, which no upgrade or deletion would fix.
 */
@WorkerThread
internal fun saveRunRecord(
    appContext: Context,
    record: SessionRecord,
    cloudEnabled: Boolean,
): SessionStore.UpsertOutcome {
    val result = SessionStore.save(appContext, record)
    if (result == SessionStore.UpsertOutcome.SAVED) {
        if (cloudEnabled) {
            CloudSync.enqueueUpload(appContext, record.id)
        } else {
            Timber.d("Save to cloud is off — session %s stays local only", record.id)
        }
    }
    return result
}
