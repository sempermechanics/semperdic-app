package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.CloudBackfill
import com.sempermechanics.semper.data.cloud.bin.BinOutcome
import com.sempermechanics.semper.data.cloud.bin.RecycleBin
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.RecycleBinRestoreResponse
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord.SyncState
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.markBinned
import com.sempermechanics.semper.data.session.markSyncedIfPending
import com.sempermechanics.semper.fixtures.sessionRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The recycle bin from the phone (ADR-035): each call's answer mapped once
 * (ADR-018), and what a restore or a stamp does to the rows.
 */
@RunWith(RobolectricTestRunner::class)
class RecycleBinTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bin = FakeRecycleBinApi()
    private val tokens = FakeTokens()

    @Test
    fun `each refusal is named once as gone, no room, offline, or failed`() = runBlocking {
        suspend fun restoreFailing(e: Exception): BinOutcome<RecycleBinRestoreResponse> {
            bin.onRestore = { _, _ -> throw e }
            return RecycleBin.restore(context, "c1", bin, tokens)
        }

        assertEquals(BinOutcome.Gone, restoreFailing(ApiException(404, """{"detail":"session_not_found"}""")))
        assertEquals(
            BinOutcome.NoRoom,
            restoreFailing(ApiException(409, """{"detail":"session_quota_exceeded: 25/25"}""")),
        )
        assertEquals(BinOutcome.NoRoom, restoreFailing(ApiException(409, """{"detail":"device_quota_exceeded"}""")))
        assertEquals(BinOutcome.Offline, restoreFailing(IOException("no route")))
        // A bare 404 is a route the gateway does not publish, not an entry gone.
        val bare = restoreFailing(ApiException(404, "Not Found"))
        assertEquals(HttpFailure.Kind.NOT_FOUND, (bare as BinOutcome.Failed).failure.kind)
    }

    @Test
    fun `no token or no backend sends nothing`() = runBlocking {
        tokens.token = null
        assertEquals(BinOutcome.Offline, RecycleBin.list(context, bin, tokens))
        bin.enabled = false
        assertEquals(BinOutcome.Offline, RecycleBin.list(context, bin, tokens))
        assertTrue(bin.calls.isEmpty())
    }

    @Test
    fun `a restore brings the row back synced and linked when the backup is complete`() = runBlocking {
        store("s1")
        SessionStore.markBinned(context, "s1", "c1", purgeAt = 1L)
        bin.onRestore = { _, sid -> RecycleBinRestoreResponse(sid, "s1", status = "COMPLETED", restored = true) }

        assertTrue(RecycleBin.restore(context, "c1", bin, tokens) is BinOutcome.Ok)

        val row = SessionStore.get(context, "s1")!!
        assertEquals(SyncState.SYNCED, row.syncState)
        assertEquals("c1", row.cloudSessionId)
        assertEquals("", row.binnedCloudId)
    }

    @Test
    fun `a restored backup that never finished leaves the row only out of the bin`() = runBlocking {
        store("s1")
        SessionStore.markBinned(context, "s1", "c1", purgeAt = 1L)
        bin.onRestore = { _, sid -> RecycleBinRestoreResponse(sid, "s1", status = "UPLOADING", restored = true) }

        RecycleBin.restore(context, "c1", bin, tokens)

        val row = SessionStore.get(context, "s1")!!
        assertEquals(SyncState.LOCAL_ONLY, row.syncState)
        assertEquals("", row.cloudSessionId)
        assertEquals("", row.binnedCloudId)
    }

    @Test
    fun `marking synced is a compare-and-set on a waiting row`() {
        store("waiting", SyncState.PENDING)
        store("deleted", SyncState.LOCAL_ONLY)
        store("binned", SyncState.PENDING)
        SessionStore.update(context, "binned") { it.copy(binnedCloudId = "c-b") }

        assertTrue(SessionStore.markSyncedIfPending(context, "waiting"))
        assertFalse(SessionStore.markSyncedIfPending(context, "deleted"))
        assertFalse(SessionStore.markSyncedIfPending(context, "binned"))

        assertEquals(SyncState.SYNCED, SessionStore.get(context, "waiting")!!.syncState)
        assertEquals(SyncState.LOCAL_ONLY, SessionStore.get(context, "deleted")!!.syncState)
        assertEquals(SyncState.PENDING, SessionStore.get(context, "binned")!!.syncState)
    }

    @Test
    fun `the backup offer skips a row in the bin and stamps the rest pending before they are queued`() {
        store("phone-only", SyncState.LOCAL_ONLY)
        store("in-bin", SyncState.SYNCED)
        SessionStore.markBinned(context, "in-bin", "c-bin", purgeAt = 1L)
        store("synced", SyncState.SYNCED)

        val offered = CloudBackfill.candidates(context).map { it.id }
        assertEquals(listOf("phone-only"), offered)

        // A row binned between the offer and the tap is not stamped either.
        val stamped = CloudBackfill.stamp(context, listOf("phone-only", "in-bin", "synced"))

        assertEquals(listOf("phone-only"), stamped)
        assertEquals(SyncState.PENDING, SessionStore.get(context, "phone-only")!!.syncState)
        assertEquals(SyncState.LOCAL_ONLY, SessionStore.get(context, "in-bin")!!.syncState)
        assertEquals(SyncState.SYNCED, SessionStore.get(context, "synced")!!.syncState)
    }

    private fun store(id: String, state: SyncState = SyncState.SYNCED) {
        val dir = SessionStore.dirFor(context, id)
        SessionPaths.frameDat(dir, 0).writeBytes(ByteArray(8))
        val record = sessionRecord(id = id, sessionDir = dir.absolutePath, syncState = state)
        assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
    }
}
