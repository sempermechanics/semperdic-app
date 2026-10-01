package com.indicvision.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.cloud.CloudSync.EraseResult
import com.indicvision.semper.data.cloud.SessionDeletes
import com.indicvision.semper.data.cloud.SessionDeletes.Item
import com.indicvision.semper.data.cloud.SessionDeletes.Mode
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.ListSessionsResponse
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.fixtures.sessionRecord
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

    private suspend fun run(items: List<Item>, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }) =
        SessionDeletes.run(context, items, api, tokens, pause = { pauses += it }, onProgress = onProgress)

    private fun deletes() = api.calls.count { it == "deleteSession" }

    @Test
    fun `a deleted cloud backup is forgotten, so deleting the phone copy later sends nothing`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.SYNCED, cloudId = "c1"))
        api.onDeleteSession = { _, _ -> }

        assertEquals(EraseResult.ERASED_EVERYWHERE, CloudSync.eraseCloudBackup(context, "c1", "s1", api, tokens))
        val kept = SessionStore.get(context, "s1")!!
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, kept.syncState)
        assertEquals("", kept.cloudSessionId)

        assertEquals(EraseResult.ERASED_EVERYWHERE, CloudSync.eraseEverywhere(context, "s1", api, tokens))
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
            if (sent in 4..6 || sent in 9..11) throw IndicApi.ApiException(429, """{"detail":"rate_limited"}""")
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
        api.onDeleteSession = { _, _ -> throw IndicApi.ApiException(429, "") }

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
            ListSessionsResponse(sessions = ids.map { CloudSessionDto(sessionId = "c-$it", localSessionId = it) })
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
        api.onListSessions = { _, _ -> ListSessionsResponse(sessions = emptyList()) }

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
        val items = listOf(Item("a", "c-a", Mode.EVERYWHERE), Item("b", "", Mode.CLOUD))

        assertEquals(items, SessionDeletes.decode(SessionDeletes.encode(items)))
        assertTrue(SessionDeletes.decode(null).isEmpty())
    }

    @Test
    fun `only rows leaving the phone are tagged for hiding`() {
        val tags = setOf(SessionDeletes.TAG, SessionDeletes.rowTag("a"), SessionDeletes.rowTag("b"))

        assertEquals(setOf("a", "b"), SessionDeletes.rowIdsIn(tags))
    }

    private fun record(id: String, state: SessionRecord.SyncState, cloudId: String = "") = sessionRecord(
        id = id,
        refName = "reference.png",
        sessionDir = SessionStore.dirFor(context, id).absolutePath,
        syncState = state,
        cloudSessionId = cloudId,
    )

    private fun store(record: SessionRecord) = assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
}
