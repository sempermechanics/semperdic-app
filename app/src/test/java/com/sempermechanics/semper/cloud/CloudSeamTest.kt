package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.account.SeatLease
import com.sempermechanics.semper.data.cloud.CloudAccountExport
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.CloudSync.EraseResult
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.net.ListSessionsResponse
import com.sempermechanics.semper.data.net.QuotaDto
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.sessionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The cloud decisions that sit on top of [com.sempermechanics.semper.data.net.CloudApi],
 * driven through [FakeCloudApi] (ADR-002): the background seat refresh, the
 * reconcile verdicts, the erase order, and the account export's cancel.
 */
@RunWith(RobolectricTestRunner::class)
class CloudSeamTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()

    private fun floatingSeat() = AppConfigDto(mode = "licensed", licenseSeating = "floating")

    // --------------------------------------------------------- seat refresh

    @Test
    fun `a failed config read does not renew the seat`() = runBlocking {
        AppRemoteConfig.apply(context, floatingSeat())
        api.onGetConfig = { throw IOException("no route") }

        SeatLease.refreshConfigAndSeatBestEffort(context, api, tokens)

        assertEquals(listOf("getConfig"), api.calls)
    }

    @Test
    fun `a still-floating licence renews its seat after the refresh`() = runBlocking {
        api.onGetConfig = { floatingSeat() }
        api.onCheckoutLease = { floatingSeat() }

        SeatLease.refreshConfigAndSeatBestEffort(context, api, tokens)

        assertEquals(listOf("getConfig", "checkoutLease"), api.calls)
    }

    @Test
    fun `a revoke learned in the refresh stops the renewal`() = runBlocking {
        AppRemoteConfig.apply(context, floatingSeat())
        api.onGetConfig = { AppConfigDto(mode = "demo") }

        SeatLease.refreshConfigAndSeatBestEffort(context, api, tokens)

        assertEquals(listOf("getConfig"), api.calls)
    }

    @Test
    fun `no backend means no seat traffic`() = runBlocking {
        api.enabled = false

        SeatLease.refreshConfigAndSeatBestEffort(context, api, tokens)

        assertEquals(emptyList<String>(), api.calls)
        assertEquals(0, tokens.asked)
    }

    @Test
    fun `sign-out returns a held floating seat and applies the config the release answers`() = runBlocking {
        AppRemoteConfig.apply(context, floatingSeat())
        api.onReleaseLease = { AppConfigDto(maxSessions = 25, mode = "demo") }

        SeatLease.releaseBestEffort(context, api, tokens)

        assertEquals(listOf("releaseLease"), api.calls)
        assertEquals("demo", AppRemoteConfig.mode(context))
        assertEquals(25, AppRemoteConfig.maxSessions(context))
    }

    // ------------------------------------------------------------ reconcile

    private fun reconcile() = runBlocking {
        CloudSync.reconcile(context, reupload = false, deep = true, api = api, tokens = tokens)
    }

    private fun configDown() {
        api.onGetConfig = { throw IOException("config down") }
    }

    @Test
    fun `a server that answers with an error is a failure the user sees`() {
        configDown()
        api.onListSessions = { _, _ -> throw SemperApi.ApiException(404, "") }

        val outcome = reconcile()

        assertTrue("got $outcome", outcome is CloudSync.Outcome.Failed)
    }

    @Test
    fun `no network is quiet`() {
        configDown()
        api.onListSessions = { _, _ -> throw IOException("no route") }

        assertEquals(CloudSync.Outcome.Offline, reconcile())
    }

    @Test
    fun `an unapproved account is told why`() {
        configDown()
        api.onListSessions = { _, _ -> throw SemperApi.NotApprovedException() }

        assertTrue(reconcile() is CloudSync.Outcome.Failed)
    }

    @Test
    fun `a session that claims a backup the cloud lacks goes back to pending`() {
        configDown()
        store(record("kept", SessionRecord.SyncState.SYNCED))
        store(record("lost", SessionRecord.SyncState.SYNCED))
        api.onListSessions = { _, verify ->
            assertTrue("a deep check verifies the blobs", verify)
            ListSessionsResponse(
                sessions = listOf(CloudSessionDto(sessionId = "c1", localSessionId = "kept", status = "COMPLETED")),
                quota = QuotaDto(used = 1, max = 25),
            )
        }

        val outcome = reconcile()

        assertEquals(CloudSync.Outcome.Ok(cloudCount = 1, quotaUsed = 1, quotaMax = 25, repaired = 1), outcome)
        assertEquals(SessionRecord.SyncState.SYNCED, SessionStore.get(context, "kept")?.syncState)
        assertEquals(SessionRecord.SyncState.PENDING, SessionStore.get(context, "lost")?.syncState)
    }

    // ---------------------------------------------------------------- erase

    @Test
    fun `an erase the cloud refused keeps the local copy`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.SYNCED, cloudId = "c1"))
        api.onDeleteSession = { _, _ -> throw IOException("no route") }

        val result = CloudSync.eraseEverywhere(context, "s1", api, tokens)

        assertEquals(EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE, result)
        assertTrue(SessionStore.get(context, "s1") != null)
    }

    @Test
    fun `the cloud copy goes before the local one`() = runBlocking {
        store(record("s1", SessionRecord.SyncState.SYNCED))
        val order = mutableListOf<String>()
        api.onListSessions = { _, _ ->
            ListSessionsResponse(sessions = listOf(CloudSessionDto(sessionId = "c9", localSessionId = "s1")))
        }
        api.onDeleteSession = { _, sid ->
            order += "cloud:$sid"
            order += "local:" + (SessionStore.get(context, "s1") != null)
        }

        val result = CloudSync.eraseEverywhere(context, "s1", api, tokens)

        assertEquals(EraseResult.ERASED_EVERYWHERE, result)
        // Found by its local id, deleted while the local row still existed.
        assertEquals(listOf("cloud:c9", "local:true"), order)
        assertNull(SessionStore.get(context, "s1"))
    }

    // --------------------------------------------------------------- export

    @Test
    fun `the account export lands in the cache`() = runBlocking {
        api.onExportAccount = { _, dest -> dest.writeText("{}") }

        val file = CloudAccountExport.download(context.cacheDir, api, tokens)

        assertEquals("{}", file?.readText())
    }

    @Test
    fun `a failed account export is null, so the caller reports it`() = runBlocking {
        api.onExportAccount = { _, _ -> throw IOException("no route") }

        assertNull(CloudAccountExport.download(context.cacheDir, api, tokens))
    }

    @Test
    fun `a cancelled account export is not a failure`() = runBlocking {
        api.onExportAccount = { _, _ -> throw CancellationException("banner Cancel") }

        try {
            CloudAccountExport.download(context.cacheDir, api, tokens)
            fail("a cancel must propagate, not turn into a failed export")
        } catch (expected: CancellationException) {
            assertEquals("banner Cancel", expected.message)
        }
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
