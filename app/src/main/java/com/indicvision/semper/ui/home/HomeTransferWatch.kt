package com.indicvision.semper.ui.home

import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkManager
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.TransferWork
import com.indicvision.semper.data.cloud.UploadErrors
import com.indicvision.semper.ui.common.dialog.CrispToast
import com.indicvision.semper.ui.common.transfer.RestoreFailureNotice
import com.indicvision.semper.ui.common.transfer.TransferWorkObserver

/**
 * Home's view of the backup and restore jobs while it is alive: live
 * progress on their rows, a list [refresh] when one ends, and what a failure
 * tells the user.
 *
 * @param showsCloudState false for a demo account, which is told nothing about backups.
 */
internal class HomeTransferWatch(
    private val activity: AppCompatActivity,
    private val adapter: SessionListAdapter,
    private val quota: HomeQuotaCard,
    private val showsCloudState: () -> Boolean,
    private val refresh: () -> Unit,
) {
    private var activeUploadProgress: Map<String, TransferWorkObserver.RowProgress> = emptyMap()
    private var activeRestoreProgress: Map<String, TransferWorkObserver.RowProgress> = emptyMap()

    fun observe() {
        observeUploadFailures()
        observeRestoreProgress()
    }

    /**
     * Background uploads run in WorkManager, so a failure would otherwise be
     * silent (only the row badge changed). Watch the upload jobs and, when one
     * ends in a terminal failure carrying a reason, tell the user. Quota-full
     * returns no reason: it opens the persistent limit screen instead.
     */
    private fun observeUploadFailures() {
        TransferWorkObserver(TransferWork.Kind.UPLOAD).observe(activity, WorkManager.getInstance(activity)) { update ->
            // Live per-row progress from every running backup.
            activeUploadProgress = update.rowProgress()
            publishRowProgress()

            update.newlyFinished.forEach { job ->
                when (val state = job.state) {
                    // A finished backup — flip the row's badge to "synced".
                    TransferWork.State.Succeeded -> refresh()
                    is TransferWork.State.Failed -> when {
                        // A refusal at the account's limit, which forces the
                        // stop. Open the limit screen from here so it shows
                        // whether or not the worker also opens it (it is
                        // singleTop, so the two cannot stack). Only the
                        // worker's quota kind says so: other failures carry
                        // no reason either (an analysis deleted before its
                        // backup ran), and the limit can be held from before.
                        job.isQuotaStop() -> quota.openLimitScreenIfReached()
                        state.reason != null -> showUploadFailure(state.reason)
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Whether this failed backup was the worker's quota refusal ([UploadErrors.UPLOAD_FAIL_KIND]). */
    private fun TransferWorkObserver.Job.isQuotaStop(): Boolean =
        info.outputData.getString(UploadErrors.UPLOAD_FAIL_KIND) == UploadErrors.FAIL_KIND_QUOTA

    private fun observeRestoreProgress() {
        TransferWorkObserver(TransferWork.Kind.RESTORE).observe(activity, WorkManager.getInstance(activity)) { update ->
            activeRestoreProgress = update.rowProgress()
            publishRowProgress()

            update.newlyFinished.forEach { job ->
                refresh()
                RestoreFailureNotice.showIfNew(activity, job)
            }
        }
    }

    private fun publishRowProgress() {
        adapter.setUploadProgress(activeUploadProgress + activeRestoreProgress)
    }

    /**
     * Informative only — every reason that reaches here is terminal (device
     * conflict, too large, render OOM), so a one-tap Retry would just re-fail.
     * The badge remains the place to deliberately re-attempt
     * (see [BackupBadgeActions.retryOrBackup]).
     */
    private fun showUploadFailure(reason: String) {
        if (!showsCloudState()) return
        CrispToast.show(
            activity,
            activity.getString(R.string.cloud_backup_failed_fmt, reason),
            long = true,
        )
    }
}
