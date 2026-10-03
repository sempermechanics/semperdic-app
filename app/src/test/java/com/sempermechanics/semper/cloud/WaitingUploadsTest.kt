package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.net.QuotaDto
import com.sempermechanics.semper.data.net.SessionsResponse
import com.sempermechanics.semper.data.prefs.DicSettings
import com.sempermechanics.semper.data.session.SessionRecord.SyncState
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.sessionRecord
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
 * Rows that read "upload pending" must have an upload coming. Two cases had
 * none: a build with no backend (its worker returned success without
 * uploading), and an upload deferred while the quota was unknown (nothing
 * queued it again). Both left the badge on "upload pending" for good.
 */
@RunWith(RobolectricTestRunner::class)
class WaitingUploadsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()
    private val queued = mutableListOf<String>()

    @Before
    fun setUp() {
        CloudSync.queueUpload = { _, id -> queued += id }
        AppRemoteConfig.clear(context)
        api.onGetConfig = { throw IOException("config down") }
        api.onListSessions = { _, _ ->
            SessionsResponse(
                sessions = listOf(CloudSessionDto(sessionId = "c2", localSessionId = "s2", status = "COMPLETED")),
                quota = QuotaDto(used = 1, max = 25),
            )
        }
    }

    @After
    fun tearDown() {
        CloudSync.queueUpload = CloudSync::enqueueUpload
        AppRemoteConfig.clear(context)
        DicSettings.setSaveToCloudEnabled(context, true)
    }

    // ── No backend ──────────────────────────────────────────────────────────

    @Test
    fun `with no backend, a waiting row says it is not backed up`() {
        store("s1", SyncState.PENDING)
        store("s2", SyncState.SYNCED)
        store("s3", SyncState.FAILED)

        val outcome = reconcile(FakeCloudApi(enabled = false))

        assertEquals(CloudSync.Outcome.Disabled, outcome)
        assertEquals(
            mapOf("s1" to SyncState.LOCAL_ONLY, "s2" to SyncState.SYNCED, "s3" to SyncState.FAILED),
            states(),
        )
        assertTrue(queued.isEmpty())
    }

    // ── Queued again after a sync ───────────────────────────────────────────

    @Test
    fun `a sync queues the rows still waiting to upload`() {
        store("s1", SyncState.PENDING)
        store("s2", SyncState.SYNCED)
        store("s3", SyncState.LOCAL_ONLY)
        store("s4", SyncState.FAILED)

        reconcile(api)

        assertEquals(listOf("s1"), queued)
    }

    @Test
    fun `a backup gone from the cloud is still queued once`() {
        store("s1", SyncState.PENDING)
        store("s5", SyncState.SYNCED) // not in the cloud listing

        reconcile(api)

        assertEquals(listOf("s1", "s5"), queued.sorted())
        assertEquals(SyncState.PENDING, states()["s5"])
    }

    @Test
    fun `a licensed account with save-to-cloud off keeps its waiting rows waiting`() {
        AppRemoteConfig.apply(context, AppConfigDto(mode = "licensed", cloudBackupEnabled = true))
        DicSettings.setSaveToCloudEnabled(context, false)
        store("s1", SyncState.PENDING)

        reconcile(api)

        assertTrue(queued.isEmpty())
        assertEquals(SyncState.PENDING, states()["s1"])
    }

    @Test
    fun `a sync asked not to upload queues nothing`() {
        store("s1", SyncState.PENDING)

        reconcile(api, reupload = false)

        assertTrue(queued.isEmpty())
    }

    private fun reconcile(api: FakeCloudApi, reupload: Boolean = true) = runBlocking {
        CloudSync.reconcile(context, reupload = reupload, deep = true, api = api, tokens = tokens)
    }

    private fun states() = SessionStore.list(context).associate { it.id to it.syncState }

    private fun store(id: String, state: SyncState) = assertTrue(
        SessionStore.upsert(
            context,
            sessionRecord(
                id = id,
                refName = "",
                sessionDir = SessionStore.dirFor(context, id).absolutePath,
                syncState = state,
            ),
            allowOverLimit = true,
        ),
    )
}
