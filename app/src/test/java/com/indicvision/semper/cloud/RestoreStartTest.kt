package com.indicvision.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.restore.RestoreFailureLedger
import com.indicvision.semper.data.cloud.restore.RestoreStart
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.fixtures.sessionRecord
import com.indicvision.semper.ui.home.RestoreSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/**
 * Restore's shared entry point. Home and Settings both start restores through
 * [RestoreStart], so the row a restore fills is written the same way from
 * either screen; and [RestoreFailureLedger] announces each failed restore once,
 * however many times either screen opens.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreStartTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ── The row a restore fills ─────────────────────────────────────────────

    @Test
    fun `an existing row keeps its id and name and gains the cloud link`() {
        val existing = record("s1", name = "Beam test", cloudId = "")

        val row = RestoreStart.restoredRow(existing, "c1", "cloud name", now = 42L)

        assertEquals("s1", row.id)
        assertEquals("Beam test", row.name)
        assertEquals("c1", row.cloudSessionId)
        assertEquals(SessionRecord.SyncState.SYNCED, row.syncState)
        assertEquals(42L, row.updatedAt)
        assertEquals("the original creation time stays", 1L, row.createdAt)
    }

    @Test
    fun `a new row takes the cloud name and an empty frame count`() {
        val row = RestoreStart.newRow(context, "c1", "restored-c1", "Beam test", now = 42L)

        assertEquals("restored-c1", row.id)
        assertEquals("Beam test", row.name)
        assertEquals("c1", row.cloudSessionId)
        assertEquals(SessionRecord.SyncState.SYNCED, row.syncState)
        assertEquals(0, row.frameCount)
        assertEquals(SessionStore.dirFor(context, "restored-c1").absolutePath, row.sessionDir)
    }

    // ── What start refuses ──────────────────────────────────────────────────

    @Test
    fun `no cloud id means nothing to restore and nothing written`() {
        assertEquals(RestoreStart.Result.FAILED, RestoreStart.start(context, "", "s1", "Beam test"))
        assertNull(SessionStore.get(context, "s1"))
    }

    @Test
    fun `a row that already has its frames is left alone`() {
        val row = record("s1", cloudId = "")
        File(row.sessionDir, "frame_0000.dat").writeBytes(ByteArray(8))
        store(row)

        assertEquals(RestoreStart.Result.FAILED, RestoreStart.start(context, "c1", "s1", "Beam test"))
        assertEquals("", SessionStore.get(context, "s1")!!.cloudSessionId)
    }

    @Test
    fun `a restore that cannot be queued puts the row back as it was`() {
        // No WorkManager in this harness, so the enqueue throws after the row is written.
        store(record("s1", name = "Beam test", cloudId = ""))

        assertEquals(RestoreStart.Result.FAILED, RestoreStart.start(context, "c1", "s1", "cloud name"))

        val kept = SessionStore.get(context, "s1")!!
        assertEquals("", kept.cloudSessionId)
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, kept.syncState)
    }

    @Test
    fun `a new row that cannot be queued is removed again`() {
        assertEquals(RestoreStart.Result.FAILED, RestoreStart.start(context, "c1", "restored-c1", "Beam test"))
        assertNull(SessionStore.get(context, "restored-c1"))
    }

    // ── Failure announced once ──────────────────────────────────────────────

    @Test
    fun `a failed restore is announced once, whichever screen sees it first`() {
        val id = UUID.randomUUID()

        assertTrue("Home sees it first", RestoreFailureLedger.claim(context, id))
        assertFalse("Settings, later", RestoreFailureLedger.claim(context, id))
        assertFalse("Home, reopened", RestoreFailureLedger.claim(context, id))
        assertTrue("another failure is still announced", RestoreFailureLedger.claim(context, UUID.randomUUID()))
    }

    @Test
    fun `the ledger forgets the oldest ids rather than growing without bound`() {
        val first = UUID.randomUUID()
        RestoreFailureLedger.claim(context, first)
        repeat(64) { RestoreFailureLedger.claim(context, UUID.randomUUID()) }

        assertTrue(RestoreFailureLedger.claim(context, first))
    }

    // ── Home's message after queueing ───────────────────────────────────────

    @Test
    fun `Home's message says what actually happened`() {
        val res = context.resources
        fun text(total: Int, started: Int, running: Int) = RestoreSummary.of(res, total, started, running).text

        assertEquals(context.getString(R.string.restore_background_note), text(1, 1, 0))
        assertEquals(context.getString(R.string.download_analysis_already), text(1, 0, 1))
        assertEquals(context.getString(R.string.download_analysis_failed), text(1, 0, 0))
        assertEquals("Restoring 3 analyses. This continues if you leave the app.", text(3, 2, 1))
        assertEquals(
            "Restoring 2. 1 analysis couldn't be restored. Check your connection and try again.",
            text(3, 2, 0),
        )
        assertEquals("3 analyses couldn't be restored. Check your connection and try again.", text(3, 0, 0))
        assertTrue(RestoreSummary.of(res, 3, 2, 0).failed)
        assertFalse(RestoreSummary.of(res, 3, 3, 0).failed)
    }

    @Test
    fun `restoring is called restore, and download means saving a file`() {
        assertEquals("Restore", context.getString(R.string.restore_action))
        assertTrue(context.getString(R.string.delete_device_only_done).contains("restore"))
        assertFalse(context.getString(R.string.delete_device_only_done).contains("download"))
    }

    private fun record(id: String, name: String = id, cloudId: String) = sessionRecord(
        id = id,
        name = name,
        refName = "reference.png",
        sessionDir = SessionStore.dirFor(context, id).absolutePath,
        syncState = SessionRecord.SyncState.LOCAL_ONLY,
        cloudSessionId = cloudId,
    )

    private fun store(record: SessionRecord) = assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
}
