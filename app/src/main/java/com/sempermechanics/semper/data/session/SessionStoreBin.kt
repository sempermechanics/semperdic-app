package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.annotation.WorkerThread
import timber.log.Timber

// The recycle bin's writes to a row (ADR-035), beside SessionStore so its file
// keeps one accessor per index query (ADR-015). Each is one SessionStore.update,
// so its check and its write share the index lock.

/**
 * Row [id]'s backup [cloudId] is in the account's recycle bin until [purgeAt]
 * (epoch ms, 0 when unknown): the row becomes LOCAL_ONLY with no live cloud
 * id, so nothing uploads or repairs it ([inRecycleBin]).
 * @return false if the index could not be written.
 */
@WorkerThread
fun SessionStore.markBinned(context: Context, id: String, cloudId: String, purgeAt: Long): Boolean =
    update(context, id) {
        it.copy(
            syncState = SessionRecord.SyncState.LOCAL_ONLY,
            cloudSessionId = "",
            binnedCloudId = cloudId,
            binPurgeAt = purgeAt,
        )
    }

/**
 * Row [id] is out of the bin. With [restoredAs], the backup was restored
 * from it and is the row's again: SYNCED and linked to that cloud id. Without
 * it, the backup was erased: the row stays LOCAL_ONLY, linked to nothing.
 * @return false if the index could not be written.
 */
@WorkerThread
fun SessionStore.clearBinned(context: Context, id: String, restoredAs: String = ""): Boolean =
    update(context, id) {
        val out = it.copy(binnedCloudId = "", binPurgeAt = 0L)
        if (restoredAs.isBlank()) {
            out.copy(syncState = SessionRecord.SyncState.LOCAL_ONLY, cloudSessionId = "")
        } else {
            out.copy(syncState = SessionRecord.SyncState.SYNCED, cloudSessionId = restoredAs)
        }
    }

/**
 * Marks row [id] SYNCED only while it is still PENDING and not in the bin:
 * the check and the write share the index lock (compare-and-set). A delete
 * that stamped the row LOCAL_ONLY while its upload was finishing
 * (`SessionDeletes`) is not undone by the upload's success.
 * @return whether the row is now SYNCED by this call.
 */
@WorkerThread
fun SessionStore.markSyncedIfPending(context: Context, id: String): Boolean {
    var marked = false
    val written = update(context, id) {
        if (it.syncState == SessionRecord.SyncState.PENDING && !it.inRecycleBin) {
            marked = true
            it.copy(syncState = SessionRecord.SyncState.SYNCED)
        } else {
            it
        }
    }
    if (!marked) Timber.i("Upload of %s finished after its row left PENDING; left as it is", id)
    return written && marked
}
