package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.OtherAppBackups
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.net.QuotaDto
import com.sempermechanics.semper.data.net.SessionsResponse
import com.sempermechanics.semper.data.session.SessionRecord.SyncState
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.sessionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * TD-153: a row restored from the other app's backup (ADR-014) is SYNCED, but
 * this app's listing does not show it. The reconcile asks the whole account
 * (`?app=all`) before it repairs, so such a row stays SYNCED and is not
 * uploaded again as a duplicate.
 */
@RunWith(RobolectricTestRunner::class)
class CrossAppReconcileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()
    private val queued = mutableListOf<String>()

    @Before
    fun setUp() {
        CloudSync.queueUpload = { _, id -> queued += id }
        AppRemoteConfig.clear(context)
        api.onGetConfig = { throw IOException("config down") }
        api.onListSessions = { _, _ -> listing(completed("c-own", "own")) }
    }

    @After
    fun tearDown() {
        CloudSync.queueUpload = CloudSync::enqueueUpload
        AppRemoteConfig.clear(context)
    }

    @Test
    fun `a row backed up only by the other app stays synced and is not queued`() {
        store("own", SyncState.SYNCED)
        store("theirs", SyncState.SYNCED)
        val asked = mutableListOf<String>()
        api.onListAppSessions = { _, app ->
            asked += app
            listing(completed("c-own", "own"), completed("c-mt", "theirs"))
        }

        val outcome = reconcile()

        assertEquals(listOf(CloudApi.ALL_APPS), asked)
        assertEquals(CloudSync.Outcome.Ok(cloudCount = 1, quotaUsed = 1, quotaMax = 25, repaired = 0), outcome)
        assertEquals(mapOf("own" to SyncState.SYNCED, "theirs" to SyncState.SYNCED), states())
        assertTrue(queued.isEmpty())
        // Remembered, so the storage budget does not drop a copy this app cannot restore.
        assertEquals(setOf("theirs"), OtherAppBackups.ids(context))
    }

    @Test
    fun `a restored row is matched by the cloud id it stored when its local id differs`() {
        store("restored-c-mt", SyncState.SYNCED, cloudId = "c-mt")
        api.onListAppSessions = { _, _ -> listing(CloudSessionDto(sessionId = "c-mt", status = "COMPLETED")) }

        reconcile()

        assertEquals(SyncState.SYNCED, states()["restored-c-mt"])
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `a row in neither listing is repaired and queued`() {
        store("lost", SyncState.SYNCED)
        // The other app's copy never completed, so it is no backup either.
        api.onListAppSessions = { _, _ ->
            listing(completed("c-own", "own"), CloudSessionDto(sessionId = "c-mt", localSessionId = "lost"))
        }

        val outcome = reconcile()

        assertEquals(1, (outcome as CloudSync.Outcome.Ok).repaired)
        assertEquals(SyncState.PENDING, states()["lost"])
        assertEquals(listOf("lost"), queued)
    }

    @Test
    fun `a failed account listing repairs nothing this pass`() {
        store("lost", SyncState.SYNCED)
        store("waiting", SyncState.PENDING)
        api.onListAppSessions = { _, _ -> throw ApiException(503, "{}") }

        val outcome = reconcile()

        assertEquals(0, (outcome as CloudSync.Outcome.Ok).repaired)
        assertEquals(SyncState.SYNCED, states()["lost"])
        // A row already waiting is still queued: only the repair waits for an answer.
        assertEquals(listOf("waiting"), queued)
    }

    @Test
    fun `a reconcile cancelled during the account listing ends cancelled and repairs nothing`() = runBlocking {
        store("lost", SyncState.SYNCED)
        val inFlight = CompletableDeferred<Unit>()
        api.onListAppSessions = { _, _ ->
            inFlight.complete(Unit)
            awaitCancellation()
        }
        val call = async(Dispatchers.Default) {
            CloudSync.reconcile(
                context,
                reupload = true,
                deep = true,
                api = api,
                tokens = tokens,
                bin = FakeRecycleBinApi.empty(),
            )
        }
        inFlight.await()
        call.cancel()

        val outcome = runCatching { call.await() }

        assertTrue("ends cancelled, got $outcome", outcome.exceptionOrNull() is CancellationException)
        assertEquals(SyncState.SYNCED, states()["lost"])
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `nothing missing asks for no account listing`() {
        store("own", SyncState.SYNCED)
        store("waiting", SyncState.PENDING)

        reconcile()

        assertEquals(listOf("listSessions"), api.calls.filter { it.startsWith("listSessions") })
        assertEquals(listOf("waiting"), queued)
    }

    private fun reconcile() = runBlocking {
        CloudSync.reconcile(
            context,
            reupload = true,
            deep = true,
            api = api,
            tokens = tokens,
            bin = FakeRecycleBinApi.empty(),
        )
    }

    private fun completed(cloudId: String, localId: String) =
        CloudSessionDto(sessionId = cloudId, localSessionId = localId, status = "COMPLETED")

    private fun listing(vararg sessions: CloudSessionDto) =
        SessionsResponse(sessions = sessions.toList(), quota = QuotaDto(used = 1, max = 25))

    private fun states() = SessionStore.list(context).associate { it.id to it.syncState }

    private fun store(id: String, state: SyncState, cloudId: String = "") = assertTrue(
        SessionStore.upsert(
            context,
            sessionRecord(
                id = id,
                refName = "",
                sessionDir = SessionStore.dirFor(context, id).absolutePath,
                syncState = state,
                cloudSessionId = cloudId,
            ),
            allowOverLimit = true,
        ),
    )
}
