package com.sempermechanics.semper.ui.home

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.TransferPhase
import com.sempermechanics.semper.data.session.SessionRecord

/**
 * What a Home row's end icon shows: where the analysis's backup stands, or
 * the transfer running for it. [label] is the icon's description, and the
 * words Settings' analysis rows use for the same state.
 */
internal data class SessionStateIcon(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    @ColorRes val tint: Int,
) {
    companion object {
        /**
         * The idle icon for a backup in [state]; a synced one whose frames are
         * not on this phone is "Only in cloud".
         */
        fun forState(state: SessionRecord.SyncState, framesOnPhone: Boolean = true): SessionStateIcon = when (state) {
            SessionRecord.SyncState.SYNCED -> if (framesOnPhone) {
                SessionStateIcon(R.drawable.ic_cloud_done, R.string.sync_state_backed_up, R.color.sky_primary)
            } else {
                SessionStateIcon(R.drawable.ic_cloud_download, R.string.sync_state_cloud_only, R.color.text_secondary)
            }
            SessionRecord.SyncState.PENDING ->
                SessionStateIcon(R.drawable.ic_cloud_upload, R.string.sync_state_pending, R.color.text_secondary)
            SessionRecord.SyncState.LOCAL_ONLY ->
                SessionStateIcon(R.drawable.ic_cloud_off, R.string.sync_state_not_backed_up, R.color.text_secondary)
            // Refused for a reason retrying cannot fix: the one state painted as a danger.
            SessionRecord.SyncState.FAILED ->
                SessionStateIcon(R.drawable.ic_cloud_off, R.string.sync_state_not_backed_up, R.color.semantic_danger)
        }

        /** The icon while a transfer in [phase] runs for the row. */
        fun forTransfer(phase: TransferPhase): SessionStateIcon = when (phase) {
            TransferPhase.PREPARE ->
                SessionStateIcon(R.drawable.ic_cloud_upload, R.string.transfer_row_preparing, R.color.sky_primary)
            TransferPhase.UPLOAD ->
                SessionStateIcon(R.drawable.ic_cloud_upload, R.string.transfer_row_uploading, R.color.sky_primary)
            TransferPhase.DOWNLOAD ->
                SessionStateIcon(R.drawable.ic_cloud_download, R.string.transfer_row_downloading, R.color.sky_primary)
        }

        /** The words for a backup in [state], on Home's icon and Settings' analysis rows alike. */
        @StringRes
        fun label(state: SessionRecord.SyncState, framesOnPhone: Boolean = true): Int =
            forState(state, framesOnPhone).label
    }
}
