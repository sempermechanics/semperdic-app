package com.sempermechanics.semper.ui.home

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.TransferWork
import com.sempermechanics.semper.data.cloud.WorkTags
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.inRecycleBin
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.dialog.ReopenAfterRecreate
import com.sempermechanics.semper.ui.recyclebin.RecycleBinActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A tap on a Home row's sync badge: retry a failed or pending backup, back
 * up a phone-only analysis when cloud backup is on, open the recycle bin
 * ([openRecycleBin]) for a row whose backup is in it (ADR-035), or else go to
 * Settings ([openSettings]), where backup is turned on.
 *
 * Built in `onCreate`, after `super.onCreate`: a failed backup's question that
 * the screen this one replaces had open is asked again, with its reason
 * (readiness AE1-111).
 */
internal class BackupBadgeActions(
    private val activity: AppCompatActivity,
    private val adapter: SessionListAdapter,
    private val openRecycleBin: () -> Unit = { RecycleBinActivity.open(activity) },
    private val openSettings: () -> Unit,
) {
    private val reopenFailed = ReopenAfterRecreate(activity, "home.failedBackup")

    init {
        reopenFailed.restored()?.let { state ->
            askRetry(state.getString(ROW_ID).orEmpty(), state.getString(REASON).orEmpty())
        }
    }

    /** Retry a failed/pending upload, or back up a local-only session when cloud is on. */
    fun retryOrBackup(record: SessionRecord) {
        // Backing it up again would be refused (409 session_in_recycle_bin):
        // the bin is where it comes back from.
        if (record.inRecycleBin) {
            openRecycleBin()
            return
        }
        when (record.syncState) {
            // A terminal failure: explain why (from the retained WorkInfo) before
            // offering a deliberate retry, instead of silently re-queuing a doomed
            // upload every tap.
            SessionRecord.SyncState.FAILED -> showFailedBackupDialog(record)
            SessionRecord.SyncState.PENDING -> enqueueBackup(record.id, R.string.cloud_retry_backup)
            SessionRecord.SyncState.LOCAL_ONLY -> if (AppSettings.saveToCloudEnabled(activity)) {
                enqueueBackup(record.id, R.string.cloud_backup_now)
            } else {
                openSettings()
            }
            SessionRecord.SyncState.SYNCED -> openSettings()
        }
    }

    private fun enqueueBackup(localId: String, @StringRes toastRes: Int) {
        if (!SemperApi.get(activity).enabled) {
            Feedback.toast(activity, R.string.cloud_backup_no_backend, long = true)
            return
        }
        // The index write is a file read-modify-write, and this runs from a tap.
        // Order is preserved rather than made optimistic: the PENDING stamp has
        // to land before the worker is queued, or an upload that finishes first
        // would have its SYNCED stamp overwritten by this one.
        activity.lifecycleScope.launch {
            SessionStore.setSyncStateOnIo(activity, localId, SessionRecord.SyncState.PENDING)
            CloudSync.enqueueUpload(activity, localId)
            adapter.rebindRow(localId)
            Feedback.toast(activity, toastRes)
        }
    }

    private fun showFailedBackupDialog(record: SessionRecord) {
        activity.lifecycleScope.launch {
            val reason = withContext(Dispatchers.IO) { lastUploadFailureReason(record.id) }
            askRetry(record.id, reason ?: activity.getString(R.string.cloud_backup_failed_generic))
        }
    }

    /** "Backup failed", with [reason], offering a retry of row [localId]. */
    private fun askRetry(localId: String, reason: String) {
        val dialog = Dialogs.confirm(
            activity,
            activity.getText(R.string.cloud_backup_failed_title),
            reason,
            R.string.cloud_backup_retry_action,
        ) { enqueueBackup(localId, R.string.cloud_retry_backup) }
        reopenFailed.keep(dialog) { bundleOf(ROW_ID to localId, REASON to reason) }
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

private const val ROW_ID = "rowId"
private const val REASON = "reason"
