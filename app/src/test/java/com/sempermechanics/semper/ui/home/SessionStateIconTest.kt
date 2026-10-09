package com.sempermechanics.semper.ui.home

import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.TransferPhase
import com.sempermechanics.semper.data.session.SessionRecord.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

/** [SessionStateIcon]: one glyph, one set of words and one tint per backup state and transfer phase. */
class SessionStateIconTest {

    @Test
    fun `each backup state has its cloud glyph and words`() {
        assertEquals(
            SessionStateIcon(R.drawable.ic_cloud_done, R.string.sync_state_backed_up, R.color.sky_primary),
            SessionStateIcon.forState(SyncState.SYNCED),
        )
        assertEquals(
            SessionStateIcon(R.drawable.ic_cloud_download, R.string.sync_state_cloud_only, R.color.text_secondary),
            SessionStateIcon.forState(SyncState.SYNCED, framesOnPhone = false),
        )
        assertEquals(
            SessionStateIcon(R.drawable.ic_cloud_upload, R.string.sync_state_pending, R.color.text_secondary),
            SessionStateIcon.forState(SyncState.PENDING),
        )
        assertEquals(
            SessionStateIcon(R.drawable.ic_cloud_off, R.string.sync_state_not_backed_up, R.color.text_secondary),
            SessionStateIcon.forState(SyncState.LOCAL_ONLY),
        )
        assertEquals(
            SessionStateIcon(R.drawable.ic_cloud_off, R.string.sync_state_not_backed_up, R.color.semantic_danger),
            SessionStateIcon.forState(SyncState.FAILED),
        )
    }

    @Test
    fun `only a synced backup reads differently off the phone`() {
        for (state in SyncState.entries - SyncState.SYNCED) {
            val offPhone = SessionStateIcon.forState(state, framesOnPhone = false)
            assertEquals(state.name, SessionStateIcon.forState(state), offPhone)
        }
    }

    @Test
    fun `a transfer shows its direction`() {
        assertEquals(R.drawable.ic_cloud_upload, SessionStateIcon.forTransfer(TransferPhase.PREPARE).icon)
        assertEquals(R.drawable.ic_cloud_upload, SessionStateIcon.forTransfer(TransferPhase.UPLOAD).icon)
        assertEquals(R.drawable.ic_cloud_download, SessionStateIcon.forTransfer(TransferPhase.DOWNLOAD).icon)
        assertEquals(R.string.transfer_row_downloading, SessionStateIcon.forTransfer(TransferPhase.DOWNLOAD).label)
    }

    @Test
    fun `Settings' rows use the icon's words`() {
        assertEquals(R.string.sync_state_pending, SessionStateIcon.label(SyncState.PENDING))
        assertEquals(R.string.sync_state_cloud_only, SessionStateIcon.label(SyncState.SYNCED, framesOnPhone = false))
    }
}
