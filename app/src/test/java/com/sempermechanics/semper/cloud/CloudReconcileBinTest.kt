package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.net.ErasedBackupDto
import com.sempermechanics.semper.data.net.QuotaDto
import com.sempermechanics.semper.data.net.RecycleBinItemDto
import com.sempermechanics.semper.data.net.RecycleBinResponse
import com.sempermechanics.semper.data.net.SessionsResponse
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionRecord.SyncState
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.markBinned
import com.sempermechanics.semper.fixtures.sessionRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * L5-2: phone A deletes an analysis; phone B, which still holds it as backed
 * up, finds it missing from the listing. Before the recycle bin (ADR-035) B
 * marked it PENDING and uploaded it again. Now B reads the bin and marks the
 * row binned, or erased, and queues nothing; a restore from the bin brings the
 * row back to SYNCED.
 */
@RunWith(RobolectricTestRunner::class)
class CloudReconcileBinTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val bin = FakeRecycleBinApi()
    private val tokens = FakeTokens()
    private val queued = mutableListOf<String>()

    @Before
    fun setUp() {
        CloudSync.queueUpload = { _, id -> queued += id }
        AppRemoteConfig.clear(context)
        api.onGetConfig = { throw IOException("config down") }
        api.onListSessions = { _, _ -> listing(completed("c-own", "own")) }
        // Nothing here is another app's backup.
        api.onListAppSessions = { _, _ -> listing(completed("c-own", "own")) }
    }

    @After
    fun tearDown() {
        CloudSync.queueUpload = CloudSync::enqueueUpload
        AppRemoteConfig.clear(context)
    }

    @Test
    fun `a backup another phone deleted is marked binned here, not uploaded again`() {
        store("deleted", SyncState.SYNCED, cloudId = "c-del")
        bin.onList = { binWith(item("c-del", "deleted")) }

        val outcome = reconcile()

        assertEquals(CloudSync.Outcome.Ok(1, 1, 25, repaired = 0, binned = 1), outcome)
        val row = row("deleted")
        assertEquals(SyncState.LOCAL_ONLY, row.syncState)
        assertEquals("", row.cloudSessionId)
        assertEquals("c-del", row.binnedCloudId)
        assertEquals(PURGE_AT_MS, row.binPurgeAt)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `a backup erased from the bin leaves the row phone-only, and a stub with no frames goes`() {
        store("kept", SyncState.SYNCED, cloudId = "c-kept", frames = true)
        store("stub", SyncState.SYNCED, frames = false)
        bin.onList = {
            RecycleBinResponse(
                erased = listOf(ErasedBackupDto("c-kept", "kept"), ErasedBackupDto("c-stub", "stub")),
            )
        }

        val outcome = reconcile() as CloudSync.Outcome.Ok

        assertEquals(0, outcome.repaired)
        val kept = row("kept")
        assertEquals(SyncState.LOCAL_ONLY, kept.syncState)
        assertEquals("", kept.cloudSessionId)
        assertEquals("", kept.binnedCloudId)
        assertNull(SessionStore.get(context, "stub"))
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `a row whose backup was restored from the bin is synced and linked again`() {
        store("back", SyncState.SYNCED)
        SessionStore.markBinned(context, "back", "c-back", purgeAt = PURGE_AT_MS)
        api.onListSessions = { _, _ -> listing(completed("c-own", "own"), completed("c-back", "back")) }

        reconcile()

        val row = row("back")
        assertEquals(SyncState.SYNCED, row.syncState)
        assertEquals("c-back", row.cloudSessionId)
        assertEquals("", row.binnedCloudId)
        // Listed again, so the bin was not needed.
        assertTrue(bin.calls.isEmpty())
    }

    @Test
    fun `a binned row whose backup is neither listed nor binned is treated as erased`() {
        store("gone", SyncState.SYNCED, frames = true)
        SessionStore.markBinned(context, "gone", "c-gone", purgeAt = PURGE_AT_MS)
        bin.onList = { RecycleBinResponse() }

        reconcile()

        val row = row("gone")
        assertEquals(SyncState.LOCAL_ONLY, row.syncState)
        assertEquals("", row.binnedCloudId)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `a binned row still in the bin stays binned and is not queued`() {
        store("still", SyncState.SYNCED)
        SessionStore.markBinned(context, "still", "c-still", purgeAt = PURGE_AT_MS)
        bin.onList = { binWith(item("c-still", "still")) }

        reconcile()

        assertEquals("c-still", row("still").binnedCloudId)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `a bin that cannot be read repairs nothing, and a waiting row is still queued`() {
        store("missing", SyncState.SYNCED, cloudId = "c-missing")
        store("waiting", SyncState.PENDING)
        bin.onList = { throw ApiException(503, "{}") }

        val outcome = reconcile() as CloudSync.Outcome.Ok

        assertEquals(0, outcome.repaired)
        assertEquals(SyncState.SYNCED, row("missing").syncState)
        assertEquals(listOf("waiting"), queued)
    }

    @Test
    fun `a missing backup in neither the bin nor its markers is repaired as before`() {
        store("lost", SyncState.SYNCED, cloudId = "c-lost")
        bin.onList = { RecycleBinResponse() }

        val outcome = reconcile() as CloudSync.Outcome.Ok

        assertEquals(1, outcome.repaired)
        assertEquals(SyncState.PENDING, row("lost").syncState)
        assertEquals(listOf("lost"), queued)
    }

    @Test
    fun `nothing missing reads no bin`() {
        store("own", SyncState.SYNCED)

        reconcile()

        assertTrue(bin.calls.isEmpty())
    }

    @Test
    fun `another account's rows on a shared phone are not reconciled against this account's bin`() {
        store("theirs", SyncState.SYNCED, cloudId = "c-theirs", owner = "other-account")
        bin.onList = { binWith(item("c-theirs", "theirs")) }

        reconcile()

        // Hidden from this account, so neither missing nor read for: left as it was.
        assertTrue(bin.calls.isEmpty())
        assertTrue(queued.isEmpty())
    }

    private fun reconcile() = runBlocking {
        CloudSync.reconcile(context, reupload = true, deep = true, api = api, tokens = tokens, bin = bin)
    }

    private fun row(id: String): SessionRecord = checkNotNull(SessionStore.get(context, id))

    private fun completed(cloudId: String, localId: String) =
        CloudSessionDto(sessionId = cloudId, localSessionId = localId, status = "COMPLETED")

    private fun listing(vararg sessions: CloudSessionDto) =
        SessionsResponse(sessions = sessions.toList(), quota = QuotaDto(used = 1, max = 25))

    private fun item(cloudId: String, localId: String) =
        RecycleBinItemDto(sessionId = cloudId, localSessionId = localId, status = "COMPLETED", purgeAt = PURGE_AT)

    private fun binWith(vararg items: RecycleBinItemDto) =
        RecycleBinResponse(items = items.toList(), retentionDays = 30)

    private fun store(
        id: String,
        state: SyncState,
        cloudId: String = "",
        frames: Boolean = true,
        owner: String = "",
    ) {
        val dir = SessionStore.dirFor(context, id)
        if (frames) {
            SessionPaths.frameDat(dir, 0).writeBytes(ByteArray(8))
        } else {
            File(dir, "reference.png").writeText("r")
        }
        val record = sessionRecord(
            id = id,
            refName = "",
            sessionDir = dir.absolutePath,
            syncState = state,
            cloudSessionId = cloudId,
        ).copy(ownerUid = owner)
        assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
    }

    private companion object {
        const val PURGE_AT = "2026-12-03T09:14:05.123456+00:00"
        const val PURGE_AT_MS = 1_796_289_245_123L
    }
}
