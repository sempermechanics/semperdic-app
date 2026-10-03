package com.sempermechanics.semper.ui.common.transfer

import android.app.Activity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.TransferWork
import com.sempermechanics.semper.data.cloud.restore.RestoreFailureLedger
import com.sempermechanics.semper.ui.common.dialog.CrispToast

/**
 * What Home and Settings tell the user when a restore fails. Without it a
 * restore's outcome would be silent: the user taps Restore, sees "continues
 * in background", and is never told it failed (backup gone, not theirs, gave
 * up). A success is silent on purpose, as a backup's is.
 */
object RestoreFailureNotice {

    /**
     * Shows [job]'s failure, if it failed and no screen has shown it yet:
     * once per failure across Home and Settings ([RestoreFailureLedger]), not
     * once per screen open, with the worker's reason or a generic line.
     * True when it was shown.
     */
    fun showIfNew(activity: Activity, job: TransferWorkObserver.Job): Boolean {
        val failed = job.state as? TransferWork.State.Failed ?: return false
        val first = RestoreFailureLedger.claim(activity, job.id)
        if (first) {
            CrispToast.show(
                activity,
                failed.reason ?: activity.getString(R.string.restore_failed_generic),
                long = true,
            )
        }
        return first
    }
}
