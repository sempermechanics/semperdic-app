package com.sempermechanics.semper.data.cloud.bin

import com.sempermechanics.semper.data.cloud.bin.BinReconcile.Decision
import com.sempermechanics.semper.data.net.ErasedBackupDto
import com.sempermechanics.semper.data.net.RecycleBinItemDto
import com.sempermechanics.semper.data.net.RecycleBinResponse
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionRecord.SyncState
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the reconcile does with a row given the recycle bin (ADR-035), with no
 * Android: L5-2's re-upload of a backup another phone deleted becomes a row
 * marked binned, or erased, never a repair.
 */
class BinReconcileTest {

    private val purgeAt = 1_764_753_245_123L

    private val bin = BinReconcile.Bin(
        RecycleBinResponse(
            items = listOf(
                RecycleBinItemDto(
                    sessionId = "c-binned",
                    localSessionId = "l-binned",
                    purgeAt = "2025-12-03T09:14:05.123456+00:00",
                ),
            ),
            erased = listOf(ErasedBackupDto(sessionId = "c-erased", localSessionId = "l-erased")),
        ),
    )

    @Suppress("LongParameterList") // the decision's inputs, each defaulted
    private fun decide(
        row: SessionRecord,
        backedUp: Set<String> = emptySet(),
        listed: Set<String> = emptySet(),
        keep: Set<String> = emptySet(),
        bin: BinReconcile.Bin? = this.bin,
        hasLocalData: Boolean = true,
    ) = BinReconcile.decide(row, backedUp, listed, keep, bin) { hasLocalData }

    private fun synced(id: String, cloudId: String = "") =
        sessionRecord(id = id, syncState = SyncState.SYNCED, cloudSessionId = cloudId)

    private fun inBin(id: String, cloudId: String, purge: Long = purgeAt) =
        sessionRecord(id = id).copy(binnedCloudId = cloudId, binPurgeAt = purge)

    @Test
    fun `the purge time reads the backend's microseconds and offset`() {
        assertEquals(purgeAt, RecycleBinItemDto("s", purgeAt = "2025-12-03T09:14:05.123456+00:00").purgeAtMs)
        assertEquals(purgeAt, RecycleBinItemDto("s", purgeAt = "2025-12-03T09:14:05.123Z").purgeAtMs)
        assertEquals(0L, RecycleBinItemDto("s", purgeAt = "soon").purgeAtMs)
        assertEquals(0L, RecycleBinItemDto("s").purgeAtMs)
    }

    @Test
    fun `a missing backup in the bin is binned, matched by the row's cloud id`() {
        assertEquals(Decision.ToBin("c-binned", purgeAt), decide(synced("anything", cloudId = "c-binned")))
    }

    @Test
    fun `a row with no cloud id is matched by its local id`() {
        assertEquals(Decision.ToBin("c-binned", purgeAt), decide(synced("l-binned")))
    }

    @Test
    fun `a row with a cloud id is not matched by its local id`() {
        // The bin holds another backup of the same local id; this row's own is simply lost.
        assertEquals(Decision.Repair, decide(synced("l-binned", cloudId = "c-other")))
    }

    @Test
    fun `an erased backup unlinks the row, and drops a stub with no frames`() {
        assertEquals(Decision.Erased(dropStub = false), decide(synced("x", cloudId = "c-erased")))
        assertEquals(Decision.Erased(dropStub = true), decide(synced("l-erased"), hasLocalData = false))
    }

    @Test
    fun `a backup in neither the bin nor its markers is the old repair`() {
        assertEquals(Decision.Repair, decide(synced("lost", cloudId = "c-lost")))
    }

    @Test
    fun `another app's backup is left alone, whatever the bin says`() {
        assertNull(decide(synced("l-binned"), keep = setOf("l-binned")))
        assertNull(decide(inBin("l-binned", "c-gone"), keep = setOf("l-binned")))
    }

    @Test
    fun `a bin that could not be read repairs and bins nothing`() {
        assertNull(decide(synced("lost", cloudId = "c-lost"), bin = null))
        assertNull(decide(inBin("x", "c-gone"), bin = null))
    }

    @Test
    fun `a listed row and a row not claiming a backup are not the bin's business`() {
        assertNull(decide(synced("ok"), backedUp = setOf("ok")))
        assertNull(decide(sessionRecord(id = "p", syncState = SyncState.PENDING)))
        assertNull(decide(sessionRecord(id = "f", syncState = SyncState.FAILED)))
        assertNull(decide(sessionRecord(id = "l", syncState = SyncState.LOCAL_ONLY)))
    }

    @Test
    fun `a binned row whose backup is listed complete again is back from the bin`() {
        assertEquals(Decision.BackFromBin("c-binned"), decide(inBin("x", "c-binned"), listed = setOf("c-binned")))
        // Even with no bin read: the listing alone says it is back.
        assertEquals(Decision.BackFromBin("c-1"), decide(inBin("x", "c-1"), listed = setOf("c-1"), bin = null))
    }

    @Test
    fun `a binned row still in the bin stays, its purge time refreshed only when it changed`() {
        assertNull(decide(inBin("x", "c-binned")))
        assertEquals(Decision.ToBin("c-binned", purgeAt), decide(inBin("x", "c-binned", purge = 0L)))
    }

    @Test
    fun `a binned row whose backup is neither listed nor binned was erased`() {
        assertEquals(Decision.Erased(dropStub = false), decide(inBin("x", "c-erased")))
        assertEquals(Decision.Erased(dropStub = true), decide(inBin("x", "c-unknown"), hasLocalData = false))
    }

    @Test
    fun `the bin is read only when a backup is missing or a binned row is not listed`() {
        val rows = listOf(synced("a"), inBin("b", "c-b"))

        assertFalse(BinReconcile.needsBin(rows.take(1), missing = emptySet(), listedCloudIds = emptySet()))
        assertTrue(BinReconcile.needsBin(rows.take(1), missing = setOf("a"), listedCloudIds = emptySet()))
        assertTrue(BinReconcile.needsBin(rows, missing = emptySet(), listedCloudIds = emptySet()))
        assertFalse(BinReconcile.needsBin(rows, missing = emptySet(), listedCloudIds = setOf("c-b")))
    }
}
