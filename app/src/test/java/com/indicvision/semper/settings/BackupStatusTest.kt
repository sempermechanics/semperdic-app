package com.indicvision.semper.settings

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.session.SessionRecord.SyncState
import com.indicvision.semper.ui.settings.BackupStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings' backup line must not say "up to date" over a backup that failed. */
@RunWith(RobolectricTestRunner::class)
class BackupStatusTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `a failed backup is reported ahead of anything pending`() {
        assertEquals(
            "1 analysis could not be backed up",
            BackupStatus.text(res, listOf(SyncState.SYNCED, SyncState.PENDING, SyncState.FAILED)),
        )
    }

    @Test
    fun `pending uploads read as pending`() {
        assertEquals("upload pending", BackupStatus.text(res, listOf(SyncState.SYNCED, SyncState.PENDING)))
    }

    @Test
    fun `analyses never queued are not up to date`() {
        assertEquals(
            "2 analyses not backed up",
            BackupStatus.text(res, listOf(SyncState.LOCAL_ONLY, SyncState.LOCAL_ONLY, SyncState.SYNCED)),
        )
    }

    @Test
    fun `only when everything is synced is it up to date`() {
        assertEquals("Cloud backups up to date", BackupStatus.text(res, listOf(SyncState.SYNCED)))
        assertEquals("Cloud backups up to date", BackupStatus.text(res, emptyList()))
    }
}
