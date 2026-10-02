package com.indicvision.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.cloud.CloudSync.EraseResult
import com.indicvision.semper.data.cloud.SessionDeletes
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.ListSessionsResponse
import com.indicvision.semper.data.prefs.PrefFiles
import com.indicvision.semper.data.prefs.get
import com.indicvision.semper.data.prefs.privatePrefs
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.fixtures.sessionRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * How [CloudSync] and [SessionDeletes] read each kind of backend failure:
 * the reconcile's verdict and wording, the config refresh around it, and the
 * erase results. Pinned before these paths moved onto `CloudApi.authed`.
 */
@RunWith(RobolectricTestRunner::class)
class CloudSyncFailureMappingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()

    @After
    fun tearDown() = AppRemoteConfig.clear(context)

    private fun reconcile(deep: Boolean = true) = runBlocking {
        CloudSync.reconcile(context, reupload = false, deep = deep, api = api, tokens = tokens)
    }

    private fun configDown() {
        api.onGetConfig = { throw IOException("config down") }
    }

    private fun failStreak(): Int = privatePrefs(context, PrefFiles.RemoteConfig.NAME)[PrefFiles.RemoteConfig.FAIL_STREAK]

    // ── reconcile ──────────────────────────────────────────────────────────

    @Test
    fun `an unapproved account is told so in words`() {
        configDown()
        api.onListSessions = { _, _ -> throw IndicApi.NotApprovedException() }

        assertEquals(CloudSync.Outcome.Failed("your account isn't approved for cloud backup"), reconcile())
    }

    @Test
    fun `a backend error names its status`() {
        configDown()
        api.onListSessions = { _, _ -> throw IndicApi.ApiException(503, "{}") }

        assertEquals(CloudSync.Outcome.Failed("server returned HTTP 503"), reconcile())
    }

    @Test
    fun `a device failure without a status reads as offline, like any other IOException`() {
        configDown()
        api.onListSessions = { _, _ -> throw IndicApi.DeviceConflictException("r1") }

        assertEquals(CloudSync.Outcome.Offline, reconcile())
    }

    @Test
    fun `a failure that is not I O is reported, not thrown at the screen`() {
        configDown()
        api.onListSessions = { _, _ -> throw IllegalStateException("bad state") }

        // Home shows the reason, so it carries no class name; the log keeps that.
        assertEquals(CloudSync.Outcome.Failed("unexpected error"), reconcile())
    }

    @Test
    fun `no token is offline and asks the backend nothing`() {
        tokens.token = null

        assertEquals(CloudSync.Outcome.Offline, reconcile())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a config fetch that fails is counted, one that answers is stored`() {
        configDown()
        api.onListSessions = { _, _ -> ListSessionsResponse() }
        reconcile()
        assertEquals(1, failStreak())

        api.onGetConfig = { AppConfigDto(mode = "demo", maxSessions = 12) }
        reconcile()
        assertEquals(12, AppRemoteConfig.maxSessions(context))
        assertEquals(0, failStreak())
    }

    @Test
    fun `a throttled check skips the listing, and the config once it is known`() {
        api.onGetConfig = { AppConfigDto(mode = "demo", maxSessions = 12) }
        api.onListSessions = { _, _ -> ListSessionsResponse() }
        assertTrue(reconcile(deep = false) is CloudSync.Outcome.Ok)
        api.calls.clear()

        assertEquals(CloudSync.Outcome.Skipped, reconcile(deep = false))
        assertEquals(emptyList<String>(), api.calls)
    }

    // ── erase ──────────────────────────────────────────────────────────────

    @Test
    fun `a cloud backup delete reads 429 as rate limited and anything else as unreachable`() = runBlocking {
        store(sessionRecord(id = "s1", syncState = SessionRecord.SyncState.SYNCED, cloudSessionId = "c1"))

        api.onDeleteSession = { _, _ -> throw IndicApi.ApiException(429, "") }
        assertEquals(EraseResult.RATE_LIMITED, CloudSync.eraseCloudBackup(context, "c1", "s1", api, tokens))

        api.onDeleteSession = { _, _ -> throw IndicApi.ApiException(503, "") }
        assertEquals(
            EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE,
            CloudSync.eraseCloudBackup(context, "c1", "s1", api, tokens),
        )

        api.onDeleteSession = { _, _ -> throw IOException("offline") }
        assertEquals(EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE, CloudSync.eraseEverywhere(context, "s1", api, tokens))
        assertNotNull(SessionStore.get(context, "s1"))
    }

    @Test
    fun `a cloud backup delete with no backend or no token sends nothing`() = runBlocking {
        tokens.token = null
        assertEquals(
            EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE,
            CloudSync.eraseCloudBackup(context, "c1", "s1", api, tokens),
        )
        tokens.token = "tok"
        api.enabled = false
        assertEquals(
            EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE,
            CloudSync.eraseCloudBackup(context, "c1", "s1", api, tokens),
        )
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a delete whose link lookup fails offline keeps the row and reports it still in the cloud`() = runBlocking {
        store(sessionRecord(id = "s1", syncState = SessionRecord.SyncState.SYNCED))
        api.onListSessions = { _, _ -> throw IOException("offline") }
        val items = listOf(SessionDeletes.Item("s1", "", SessionDeletes.Mode.EVERYWHERE))

        val report = SessionDeletes.run(context, items, api, tokens, pause = {})

        assertEquals(items, report.stillInCloud)
        assertEquals(listOf("listSessions", "listSessions"), api.calls)
        assertEquals(SessionRecord.SyncState.SYNCED, SessionStore.get(context, "s1")!!.syncState)
    }

    // ── backup lookup ──────────────────────────────────────────────────────

    @Test
    fun `a backup lookup that fails offline is no backup, not a crash`() = runBlocking {
        val record = sessionRecord(id = "s1", syncState = SessionRecord.SyncState.SYNCED)
        api.onListSessions = { _, _ -> throw IOException("offline") }

        assertNull(CloudSync.resolveCloudIdFor(context, record, api, tokens))
        assertEquals(listOf("listSessions"), api.calls)
    }

    @Test
    fun `a backup lookup reads the stored link first, then the listing`() = runBlocking {
        assertEquals("c7", CloudSync.resolveCloudIdFor(context, sessionRecord(cloudSessionId = "c7"), api, tokens))
        assertTrue(api.calls.isEmpty())

        api.onListSessions = { _, _ ->
            ListSessionsResponse(sessions = listOf(CloudSessionDto(sessionId = "c8", localSessionId = "s1")))
        }
        assertEquals("c8", CloudSync.resolveCloudIdFor(context, sessionRecord(id = "s1"), api, tokens))
        tokens.token = null
        assertNull(CloudSync.resolveCloudIdFor(context, sessionRecord(id = "s1"), api, tokens))
    }

    private fun store(record: SessionRecord) = assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
}
