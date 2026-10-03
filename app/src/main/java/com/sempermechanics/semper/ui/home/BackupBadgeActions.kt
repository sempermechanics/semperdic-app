package com.sempermechanics.semper.ui.home

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.TransferWork
import com.sempermechanics.semper.data.cloud.WorkTags
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.prefs.DicSettings
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.Feedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A tap on a Home row's sync badge: retry a failed or pending backup, back
 * up a phone-only analysis when cloud backup is on, or else go to Settings
 * ([openSettings]), where backup is turned on.
 */
internal class BackupBadgeActions(
    private val activity: AppCompatActivity,
    private val adapter: SessionListAdapter,
    private val openSettings: () -> Unit,
) {

    /** Retry a failed/pending upload, or back up a local-only session when cloud is on. */
    fun retryOrBackup(record: SessionRecord) {
        when (record.syncState) {
            // A terminal failure: explain why (from the retained WorkInfo) before
            // offering a deliberate retry, instead of silently re-queuing a doomed
            // upload every tap.
            SessionRecord.SyncState.FAILED -> showFailedBackupDialog(record)
            SessionRecord.SyncState.PENDING -> enqueueBackup(record, R.string.cloud_retry_backup)
            SessionRecord.SyncState.LOCAL_ONLY -> if (DicSettings.saveToCloudEnabled(activity)) {
                enqueueBackup(record, R.string.cloud_backup_now)
            } else {
                openSettings()
            }
            SessionRecord.SyncState.SYNCED -> openSettings()
        }
    }

    private fun enqueueBackup(record: SessionRecord, @StringRes toastRes: Int) {
        if (!SemperApi.get(activity).enabled) {
            Feedback.toast(activity, R.string.cloud_backup_no_backend, long = true)
            return
        }
        // The index write is a file read-modify-write, and this runs from a tap.
        // Order is preserved rather than made optimistic: the PENDING stamp has
        // to land before the worker is queued, or an upload that finishes first
        // would have its SYNCED stamp overwritten by this one.
        activity.lifecycleScope.launch {
            SessionStore.setSyncStateOnIo(activity, record.id, SessionRecord.SyncState.PENDING)
            CloudSync.enqueueUpload(activity, record.id)
            adapter.rebindRow(record.id)
            Feedback.toast(activity, toastRes)
        }
    }

    private fun showFailedBackupDialog(record: SessionRecord) {
        activity.lifecycleScope.launch {
            val reason = withContext(Dispatchers.IO) { lastUploadFailureReason(record.id) }
            Dialogs.confirm(
                activity,
                activity.getText(R.string.cloud_backup_failed_title),
                reason ?: activity.getString(R.string.cloud_backup_failed_generic),
                R.string.cloud_backup_retry_action,
            ) { enqueueBackup(record, R.string.cloud_retry_backup) }
        }
    }

    /** The reason attached to the last terminal upload failure for [localId], if still retained. */
    private fun lastUploadFailureReason(localId: String): String? = runCatching {
        WorkManager.getInstance(activity)
            .getWorkInfosForUniqueWork(WorkTags.uploadName(localId))
            .get()
            .map { TransferWork.classify(it, TransferWork.Kind.UPLOAD) }
            .firstNotNullOfOrNull { it as? TransferWork.State.Failed }
            ?.reason
    }.getOrNull()
}
