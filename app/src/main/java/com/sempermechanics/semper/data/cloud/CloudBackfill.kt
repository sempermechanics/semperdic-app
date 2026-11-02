package com.sempermechanics.semper.data.cloud

import android.content.Context
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.inRecycleBin

/**
 * Settings' offer, when backup is turned on, to back up what is still
 * phone-only. A row whose backup is in the recycle bin (ADR-035) is not
 * offered: it was deleted from the cloud on purpose, and its upload would be
 * refused.
 */
object CloudBackfill {

    /** The current account's rows the offer names: LOCAL_ONLY and not in the bin. */
    @WorkerThread
    fun candidates(context: Context): List<SessionRecord> =
        SessionStore.list(context).filter(::offered)

    /**
     * Stamps each of [ids] still [offered] PENDING, under the index lock, and
     * returns those, for the caller to queue. The stamp comes first, as for
     * every other backup start: the upload worker uploads PENDING rows only, and
     * an upload that finished first would otherwise have its SYNCED stamp
     * overwritten.
     */
    @WorkerThread
    fun stamp(context: Context, ids: List<String>): List<String> = ids.filter { id ->
        var stamped = false
        SessionStore.update(context, id) {
            if (offered(it)) {
                stamped = true
                it.copy(syncState = SessionRecord.SyncState.PENDING)
            } else {
                it
            }
        }
        stamped && SessionStore.get(context, id) != null
    }

    private fun offered(record: SessionRecord): Boolean =
        record.syncState == SessionRecord.SyncState.LOCAL_ONLY && !record.inRecycleBin
}
