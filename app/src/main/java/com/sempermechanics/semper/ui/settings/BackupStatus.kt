package com.sempermechanics.semper.ui.settings

import android.content.res.Resources
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionRecord

/**
 * The one-line cloud backup status in Settings, worst news first.
 *
 * It used to count only [SessionRecord.SyncState.PENDING], so an analysis
 * whose upload had failed, or that was never queued, read as "Cloud backups
 * up to date".
 */
internal object BackupStatus {

    fun text(resources: Resources, states: Collection<SessionRecord.SyncState>): String {
        val failed = states.count { it == SessionRecord.SyncState.FAILED }
        val pending = states.count { it == SessionRecord.SyncState.PENDING }
        val local = states.count { it == SessionRecord.SyncState.LOCAL_ONLY }
        return when {
            failed > 0 -> resources.getQuantityString(R.plurals.sync_status_failed_fmt, failed, failed)
            pending > 0 -> resources.getString(R.string.badge_pending)
            local > 0 -> resources.getQuantityString(R.plurals.sync_status_local_fmt, local, local)
            else -> resources.getString(R.string.sync_status_up_to_date)
        }
    }
}
