package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.SessionMetadataSync
import com.sempermechanics.semper.data.cloud.SessionMetadataSync.Outcome
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.net.QuotaDto
import com.sempermechanics.semper.data.net.SessionsResponse
import com.sempermechanics.semper.data.session.SessionRecord.SyncState
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.sessionRecord
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * ADR-013: a rename made after the backup reaches the cloud copy's
 * metadata.json, so a restore on another phone brings back the new name.
 */
@RunWith(RobolectricTestRunner::class)
class SessionMetadataSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()
    private val sent = mutableListOf<Pair<String, JSONObject>>()
    private val queued = mutableListOf<String>()

    @Before
    fun setUp() {
        File(context.filesDir, "sessions").deleteRecursively()
        api.onReplaceSessionMetadata = { _, sid, json -> sent += sid to JSONObject(json) }
        CloudSync.queueMetadata = { _, id -> queued += id }
        CloudSync.queueUpload = { _, _ -> }
        AppRemoteConfig.clear(context)
    }

    @After
    fun tearDown() {
        CloudSync.queueMetadata = SessionMetadataSync::enqueue
        CloudSync.queueUpload = CloudSync::enqueueUpload
        AppRemoteConfig.clear(context)
    }

    // ── Marking ─────────────────────────────────────────────────────────────

    @Test
    fun `renaming a backed-up session marks its metadata stale`() {
        store("s1", SyncState.SYNCED, cloudId = "c1")

        SessionStore.rename(context, "s1", "Beam B")

        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
    }

    @Test
    fun `renaming a session whose upload is on its way marks it too`() {
        store("s1", SyncState.PENDING)

        SessionStore.rename(context, "s1", "Beam B")

        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
    }

    @Test
    fun `renaming a session never backed up marks nothing`() {
        store("s1", SyncState.LOCAL_ONLY)

        SessionStore.rename(context, "s1", "Beam B")

        val record = SessionStore.get(context, "s1")!!
        assertEquals("Beam B", record.name)
        assertFalse(record.metadataStale)
    }

    @Test
    fun `renaming to the same name marks nothing`() {
        store("s1", SyncState.SYNCED, cloudId = "c1")

        SessionStore.rename(context, "s1", "s1")

        assertFalse(SessionStore.get(context, "s1")!!.metadataStale)
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    @Test
    fun `a stale backed-up session sends metadata carrying the new name, then clears`() {
        store("s1", SyncState.SYNCED, cloudId = "c1")
        SessionStore.rename(context, "s1", "Beam B")

        assertEquals(Outcome.DONE, send("s1"))

        val (sid, json) = sent.single()
        assertEquals("c1", sid)
        assertEquals("s1", json.getString("localSessionId"))
        assertEquals("Beam B", json.getString("name"))
        assertFalse(SessionStore.get(context, "s1")!!.metadataStale)
    }

    @Test
    fun `a rename made while the send was in flight is sent again`() {
        store("s1", SyncState.SYNCED, cloudId = "c1")
        SessionStore.rename(context, "s1", "Beam B")
        api.onReplaceSessionMetadata = { _, sid, json ->
            sent += sid to JSONObject(json)
            SessionStore.rename(context, "s1", "Beam C")
        }

        assertEquals(Outcome.RETRY, send("s1"))

        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
    }

    @Test
    fun `nothing is sent for a row that is not stale`() {
        store("s1", SyncState.SYNCED, cloudId = "c1")

        assertEquals(Outcome.DONE, send("s1"))

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `an upload still in flight is waited for`() {
        store("s1", SyncState.PENDING, stale = true)

        assertEquals(Outcome.WAIT, send("s1"))

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a row with no cloud copy is left for the next reconcile`() {
        store("s1", SyncState.LOCAL_ONLY, stale = true)

        assertEquals(Outcome.LATER, send("s1"))

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `offline, a busy backend and an unfinished cloud upload are retried, the row still marked`() {
        store("s1", SyncState.SYNCED, cloudId = "c1", stale = true)

        assertEquals(Outcome.RETRY, sendFailing(IOException("offline")))
        assertEquals(Outcome.RETRY, sendFailing(ApiException(503, "{}")))
        assertEquals(
            Outcome.WAIT,
            sendFailing(ApiException(409, """{"detail":"session_not_complete"}""")),
        )

        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
    }

    @Test
    fun `no token is retried without a call`() {
        store("s1", SyncState.SYNCED, cloudId = "c1", stale = true)
        tokens.token = null

        assertEquals(Outcome.RETRY, send("s1"))

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a backend without the route leaves the row for the next reconcile`() {
        store("s1", SyncState.SYNCED, cloudId = "c1", stale = true)
        api.onReplaceSessionMetadata = { _, _, _ -> throw ApiException(404, "") }

        assertEquals(Outcome.LATER, send("s1"))

        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
    }

    // ── Reconcile ───────────────────────────────────────────────────────────

    @Test
    fun `a reconcile queues a send for a backed-up row still marked`() {
        store("s1", SyncState.SYNCED, cloudId = "c1", stale = true)
        store("s2", SyncState.SYNCED, cloudId = "c2")
        api.onGetConfig = { throw IOException("config down") }
        api.onListSessions = { _, _ ->
            SessionsResponse(
                sessions = listOf(
                    CloudSessionDto(sessionId = "c1", localSessionId = "s1", status = "COMPLETED"),
                    CloudSessionDto(sessionId = "c2", localSessionId = "s2", status = "COMPLETED"),
                ),
                quota = QuotaDto(used = 2, max = 25),
            )
        }

        runBlocking { CloudSync.reconcile(context, deep = true, api = api, tokens = tokens) }

        assertEquals(listOf("s1"), queued)
    }

    private fun send(id: String) = runBlocking { SessionMetadataSync.send(context, id, api, tokens) }

    private fun sendFailing(error: Exception): Outcome {
        api.onReplaceSessionMetadata = { _, _, _ -> throw error }
        return send("s1")
    }

    private fun store(id: String, state: SyncState, cloudId: String = "", stale: Boolean = false) = assertTrue(
        SessionStore.upsert(
            context,
            sessionRecord(
                id = id,
                frameCount = 2,
                strainWindow = 9,
                imgW = 640,
                imgH = 480,
                refPath = "/x/ref.png",
                sessionDir = "/x",
                defNames = listOf("d1.png", "d2.png"),
                cloudSessionId = cloudId,
                syncState = state,
            ).copy(metadataStale = stale),
        ),
    )
}
