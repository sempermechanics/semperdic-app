package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.CloudSync.EraseOutcome
import com.sempermechanics.semper.data.cloud.SessionDeletes
import com.sempermechanics.semper.data.cloud.SessionDeletes.Item
import com.sempermechanics.semper.data.cloud.SessionDeletes.Mode
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.net.RecycleBinPurgeResponse
import com.sempermechanics.semper.data.net.SessionDeleteResponse
import com.sempermechanics.semper.data.net.SessionsResponse
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.inRecycleBin
import com.sempermechanics.semper.data.session.markBinned
import com.sempermechanics.semper.fixtures.sessionRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The delete queue's contract, driven through [FakeCloudApi].
 *
 * On 2026-09-25 ten analyses took 61 DELETE requests over 100 s in production:
 * 10 real deletes, 13 re-sent for backups already gone, and 38 refused by the
 * rate limit. These pin the three fixes: a deleted backup is forgotten, a 429
 * waits instead of failing, and the rows go one at a time.
 */
@RunWith(RobolectricTestRunner::class)
class SessionDeletesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()
    private val pauses = mutableListOf<Long>()
    private val bin = FakeRecycleBinApi()

    /** Each cancelled upload, with the row's state when it was cancelled. */
    private val cancelled = mutableListOf<Pair<String, SessionRecord.SyncState?>>()

    /** What the upload does while it is being cancelled: by default, nothing. */
    private var duringCancel: (String) -> Unit = {}

    private suspend fun run(items: List<Item>, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }) =
        SessionDeletes.run(
            context,
            items,
            api,
            tokens,
            bin = bin,
            cancelUpload = { _, id ->
                cancelled += id to SessionStore.get(context, id)?.syncState
                duringCancel(id)
            },
            pause = { pauses += it },
            onProgress = onProgress,
        )

    private fun deletes() = api.calls.count { it == "deleteSession" }

    @Test
    fun `a cloud delete leaves the row in the recycle bin until the backend erases it`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.SYNCED, cloudId = "c1"))
        api.onDeleteSession = { _, _ -> }

        run(listOf(Item("s1", "c1", Mode.CLOUD)))

        val kept = SessionStore.get(context, "s1")!!
        assertTrue(kept.inRecycleBin)
        assertEquals("c1", kept.binnedCloudId)
        assertEquals(1_796_289_245_123L, kept.binPurgeAt)
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, kept.syncState)
    }

    @Test
    fun `a backend from before the bin leaves the row only unlinked`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.SYNCED, cloudId = "c1"))
        api.onDeleteSession = { _, _ -> }
        api.deleteAnswer = { SessionDeleteResponse(deleted = it, filesRemoved = 3) }

        run(listOf(Item("s1", "c1", Mode.CLOUD)))

        assertFalse(SessionStore.get(context, "s1")!!.inRecycleBin)
    }

    @Test
    fun `a waiting upload is stamped phone-only before it is cancelled, and the session it made is deleted`() =
        runBlocking {
            store(record("s1", SessionRecord.SyncState.PENDING))
            // L5-3: the upload creates its cloud session while the delete waits for it to stop.
            duringCancel = { id -> SessionStore.setCloudSessionId(context, id, "c-made") }
            val deleted = mutableListOf<String>()
            api.onDeleteSession = { _, sid -> deleted += sid }

            val report = run(listOf(Item("s1", "", Mode.EVERYWHERE)))

            assertEquals(listOf("s1" to SessionRecord.SyncState.LOCAL_ONLY), cancelled)
            // Read again after the cancel: the new session is deleted with no lookup.
            assertEquals(listOf("c-made"), deleted)
            assertEquals(0, api.calls.count { it == "listSessions" })
            assertEquals(1, report.done)
            assertNull(SessionStore.get(context, "s1"))
        }

    @Test
    fun `a waiting row with no stored id is still looked up, judged by its state before the stamp`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.FAILED))
        api.onListSessions = { _, _ ->
            SessionsResponse(sessions = listOf(CloudSessionDto(sessionId = "c-s1", localSessionId = "s1")))
        }
        val deleted = mutableListOf<String>()
        api.onDeleteSession = { _, sid -> deleted += sid }

        run(listOf(Item("s1", "", Mode.CLOUD)))

        assertEquals(listOf("s1" to SessionRecord.SyncState.LOCAL_ONLY), cancelled)
        assertEquals(listOf("c-s1"), deleted)
        assertTrue(SessionStore.get(context, "s1")!!.inRecycleBin)
    }

    @Test
    fun `a waiting row whose lookup fails is kept as failed and reported still in the cloud`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.PENDING))
        api.onListSessions = { _, _ -> throw IOException("no route") }

        val report = run(listOf(Item("s1", "", Mode.CLOUD)))

        assertEquals(listOf(Item("s1", "", Mode.CLOUD)), report.stillInCloud)
        assertTrue(report.nothingSent)
        assertEquals(0, deletes())
        // Not LOCAL_ONLY: a later delete must still look for its backup, and nothing requeues it.
        assertEquals(SessionRecord.SyncState.FAILED, SessionStore.get(context, "s1")!!.syncState)
    }

    @Test
    fun `a backed-up row has no upload to cancel`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.SYNCED, cloudId = "c1"))
        api.onDeleteSession = { _, _ -> }

        run(listOf(Item("s1", "c1", Mode.EVERYWHERE)))

        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `a permanent delete erases the binned backup and takes the row out of the bin`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.SYNCED, frames = true))
        SessionStore.markBinned(context, "s1", "c1", purgeAt = 1L)
        bin.onPurge = { _, sid -> RecycleBinPurgeResponse(sessionId = sid, localSessionId = "s1", erased = true) }

        val report = run(listOf(Item("s1", "c1", Mode.PURGE)))

        assertEquals(1, report.done)
        assertEquals(listOf("purgeFromRecycleBin(c1)"), bin.calls)
        assertTrue(api.calls.isEmpty())
        assertTrue(cancelled.isEmpty())
        val row = SessionStore.get(context, "s1")!!
        assertFalse(row.inRecycleBin)
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, row.syncState)
    }

    @Test
    fun `a permanent delete of what is gone already counts as done, and a stub with no frames goes`() =
        runBlocking {
            store(record("s1", SessionRecord.SyncState.SYNCED, frames = false))
            SessionStore.markBinned(context, "s1", "c1", purgeAt = 1L)
            bin.onPurge = { _, _ -> throw ApiException(404, """{"detail":"session_not_found"}""") }

            val report = run(listOf(Item("s1", "c1", Mode.PURGE)))

            assertEquals(1, report.done)
            assertNull(SessionStore.get(context, "s1"))
        }

    @Test
    fun `a permanent delete waits out a 429 and reports a failure as still in the bin`() = runBlocking {
        var sent = 0
        bin.onPurge = { _, sid ->
            sent++
            if (sent == 1) throw ApiException(429, "")
            if (sid == "c2") throw ApiException(502, """{"detail":"drive_write_failed"}""")
            RecycleBinPurgeResponse(sessionId = sid, erased = true)
        }

        val report = run(listOf(Item("", "c1", Mode.PURGE), Item("", "c2", Mode.PURGE)))

        assertEquals(1, report.done)
        assertEquals(listOf(Item("", "c2", Mode.PURGE)), report.stillInCloud)
        assertEquals(1, pauses.size)
    }

    @Test
    fun `a deleted cloud backup is forgotten, so deleting the phone copy later sends nothing`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.SYNCED, cloudId = "c1"))
        api.onDeleteSession = { _, _ -> }

        assertEquals(EraseOutcome.ERASED_EVERYWHERE, CloudSync.eraseCloudBackup(context, "c1", "s1", api, tokens))
        val kept = SessionStore.get(context, "s1")!!
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, kept.syncState)
        assertEquals("", kept.cloudSessionId)

        assertEquals(EraseOutcome.ERASED_EVERYWHERE, CloudSync.eraseEverywhere(context, "s1", api, tokens))
        assertEquals(1, deletes())
        assertNull(SessionStore.get(context, "s1"))
    }

    @Test
    fun `ten rows all go, waiting out 429s rather than reporting them as still in the cloud`() = runBlocking {
        val ids = (1..10).map { "s$it" }
        ids.forEach { store(record(it, SessionRecord.SyncState.SYNCED, cloudId = "c-$it")) }
        var sent = 0
        api.onDeleteSession = { _, _ ->
            sent++
            // Three refusals in a row on two rows, as when two deletes race for the bucket.
            if (sent in 4..6 || sent in 9..11) throw ApiException(429, """{"detail":"rate_limited"}""")
        }
        val progress = mutableListOf<Pair<Int, Int>>()

        val report = run(ids.map { Item(it, "c-$it", Mode.EVERYWHERE) }) { done, total -> progress += done to total }

        assertEquals(10, report.done)
        assertTrue(report.stillInCloud.isEmpty())
        assertFalse(report.nothingSent)
        assertEquals(16, deletes())
        assertEquals(6, pauses.size)
        assertEquals((1..10).map { it to 10 }, progress)
        ids.forEach { assertNull(SessionStore.get(context, it)) }
    }

    @Test
    fun `a limit that never lifts gives up on that row after six tries`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.SYNCED, cloudId = "c1"))
        api.onDeleteSession = { _, _ -> throw ApiException(429, "") }

        val report = run(listOf(Item("s1", "c1", Mode.EVERYWHERE)))

        assertEquals(6, deletes())
        assertEquals(listOf(Item("s1", "c1", Mode.EVERYWHERE)), report.stillInCloud)
        assertNotNull(SessionStore.get(context, "s1"))
    }

    @Test
    fun `rows with no stored link are looked up with one listing, not one each`() = runBlocking {
        val ids = (1..10).map { "s$it" }
        ids.forEach { store(record(it, SessionRecord.SyncState.SYNCED)) }
        api.onListSessions = { _, _ ->
            SessionsResponse(sessions = ids.map { CloudSessionDto(sessionId = "c-$it", localSessionId = it) })
        }
        val deleted = mutableListOf<String>()
        api.onDeleteSession = { _, sid -> deleted += sid }

        val report = run(ids.map { Item(it, "", Mode.EVERYWHERE) })

        assertEquals(10, report.done)
        assertEquals(1, api.calls.count { it == "listSessions" })
        assertEquals(ids.map { "c-$it" }, deleted)
    }

    @Test
    fun `a row the listing does not know is only unlinked, not deleted`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.SYNCED))
        api.onListSessions = { _, _ -> SessionsResponse(sessions = emptyList()) }

        val report = run(listOf(Item("s1", "", Mode.CLOUD)))

        assertEquals(1, report.done)
        assertEquals(0, deletes())
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, SessionStore.get(context, "s1")!!.syncState)
    }

    @Test
    fun `a cloud-only delete keeps the phone copy`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.SYNCED, cloudId = "c1"))
        api.onDeleteSession = { _, _ -> }

        run(listOf(Item("s1", "c1", Mode.CLOUD)))

        val kept = SessionStore.get(context, "s1")!!
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, kept.syncState)
        assertEquals("", kept.cloudSessionId)
    }

    @Test
    fun `a partial failure names exactly the rows left, with their mode`() = runBlocking {
        listOf("s1", "s2", "s3").forEach { store(record(it, SessionRecord.SyncState.SYNCED, cloudId = "c-$it")) }
        api.onDeleteSession = { _, sid -> if (sid == "c-s2") throw IOException("no route") }
        val items = listOf(
            Item("s1", "c-s1", Mode.EVERYWHERE),
            Item("s2", "c-s2", Mode.CLOUD),
            Item("s3", "c-s3", Mode.EVERYWHERE),
        )

        val report = run(items)

        assertEquals(2, report.done)
        assertEquals(listOf(items[1]), report.stillInCloud)
        assertTrue(pauses.isEmpty())
        assertEquals("c-s2", SessionStore.get(context, "s2")!!.cloudSessionId)
    }

    @Test
    fun `no usable token sends nothing and says so`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.SYNCED, cloudId = "c1"))
        tokens.token = null

        val report = run(listOf(Item("s1", "c1", Mode.EVERYWHERE)))

        assertTrue(report.nothingSent)
        assertTrue(api.calls.isEmpty())
        assertNotNull(SessionStore.get(context, "s1"))
    }

    @Test
    fun `the queued items survive the trip through work data`() {
        val items = listOf(Item("a", "c-a", Mode.EVERYWHERE), Item("b", "", Mode.CLOUD), Item("", "c-p", Mode.PURGE))

        assertEquals(items, SessionDeletes.decode(SessionDeletes.encode(items)))
        assertTrue(SessionDeletes.decode(null).isEmpty())
    }

    @Test
    fun `only rows leaving the phone are tagged for hiding`() {
        val tags = setOf(SessionDeletes.TAG, SessionDeletes.rowTag("a"), SessionDeletes.rowTag("b"))

        assertEquals(setOf("a", "b"), SessionDeletes.rowIdsIn(tags))
    }

    private fun record(
        id: String,
        state: SessionRecord.SyncState,
        cloudId: String = "",
        frames: Boolean = false,
    ) = sessionRecord(
        id = id,
        refName = "reference.png",
        sessionDir = SessionStore.dirFor(context, id).also {
            if (frames) SessionPaths.frameDat(it, 0).writeBytes(ByteArray(8))
        }.absolutePath,
        syncState = state,
        cloudSessionId = cloudId,
    )

    private fun store(record: SessionRecord) = assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
}
