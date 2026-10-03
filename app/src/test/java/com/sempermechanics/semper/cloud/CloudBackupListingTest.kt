package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.CloudBackupListing
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.net.QuotaDto
import com.sempermechanics.semper.data.net.SessionsResponse
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.sessionRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Which backups Home offers to restore: the ones the last reconcile listed
 * that no row on this phone claims. A new phone used to open on "No analyses
 * yet" while the account had backups that only Settings listed.
 */
@RunWith(RobolectricTestRunner::class)
class CloudBackupListingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()

    // ── Which backups are offered ───────────────────────────────────────────

    @Test
    fun `only finished backups are kept`() {
        CloudBackupListing.record(
            context,
            listOf(
                dto("c1", "s1", status = "COMPLETED", name = "Beam", bytes = 21L),
                dto("c2", "s2", status = "UPLOADING"),
                dto("c3", "s3", status = null),
            ),
        )

        assertEquals(
            listOf(CloudBackupListing.Backup("c1", "s1", "Beam", 21L)),
            CloudBackupListing.read(context),
        )
    }

    @Test
    fun `a backup a phone row claims is not offered`() {
        val backups = listOf(
            backup("c1", localId = "s1"),
            backup("c2", localId = "other-phone"),
            backup("c3", localId = ""),
        )
        val records = listOf(
            record("s1", cloudId = ""),
            // Uploaded before the local id was sent: claimed by the stored cloud id.
            record("s9", cloudId = "c2"),
        )

        assertEquals(listOf("c3"), CloudBackupListing.notOnPhone(backups, records).map { it.cloudId })
    }

    @Test
    fun `a fresh phone is offered every backup`() {
        CloudBackupListing.record(context, listOf(dto("c1", "s1"), dto("c2", "s2")))

        assertEquals(listOf("c1", "c2"), CloudBackupListing.offered(context).map { it.cloudId })
    }

    @Test
    fun `a restore in progress stops the offer, because its row is written first`() {
        CloudBackupListing.record(context, listOf(dto("c1", "s1")))
        store(record("s1", cloudId = "c1", frames = 0))

        assertEquals(emptyList<String>(), CloudBackupListing.offered(context).map { it.cloudId })
    }

    // ── Hide ────────────────────────────────────────────────────────────────

    @Test
    fun `hidden backups are not offered, but a new one is`() {
        CloudBackupListing.record(context, listOf(dto("c1", "s1")))
        CloudBackupListing.hide(context, listOf("c1"))
        CloudBackupListing.record(context, listOf(dto("c1", "s1"), dto("c2", "s2")))

        assertEquals(listOf("c2"), CloudBackupListing.offered(context).map { it.cloudId })
    }

    @Test
    fun `a hidden backup that leaves the cloud is forgotten, so its return is offered`() {
        CloudBackupListing.record(context, listOf(dto("c1", "s1")))
        CloudBackupListing.hide(context, listOf("c1"))
        CloudBackupListing.record(context, emptyList())
        CloudBackupListing.record(context, listOf(dto("c1", "s1")))

        assertEquals(listOf("c1"), CloudBackupListing.offered(context).map { it.cloudId })
    }

    // ── Kept honest between reconciles ──────────────────────────────────────

    @Test
    fun `a successful reconcile saves the listing`() {
        api.onGetConfig = { throw IOException("config down") }
        api.onListSessions = { _, _ ->
            SessionsResponse(sessions = listOf(dto("c1", "s1")), quota = QuotaDto(used = 1, max = 25))
        }

        runBlocking { CloudSync.reconcile(context, reupload = false, deep = true, api = api, tokens = tokens) }

        assertEquals(listOf("c1"), CloudBackupListing.read(context).map { it.cloudId })
    }

    @Test
    fun `a failed reconcile keeps the last listing`() {
        CloudBackupListing.record(context, listOf(dto("c1", "s1")))
        api.onGetConfig = { throw IOException("config down") }
        api.onListSessions = { _, _ -> throw IOException("no route") }

        runBlocking { CloudSync.reconcile(context, reupload = false, deep = true, api = api, tokens = tokens) }

        assertEquals(listOf("c1"), CloudBackupListing.read(context).map { it.cloudId })
    }

    @Test
    fun `deleting a backup stops offering it at once`() = runBlocking {
        CloudBackupListing.record(context, listOf(dto("c1", "s1"), dto("c2", "s2")))
        api.onDeleteSession = { _, _ -> }

        CloudSync.eraseCloudBackup(context, "c1", "", api, tokens)

        assertEquals(listOf("c2"), CloudBackupListing.read(context).map { it.cloudId })
    }

    @Test
    fun `deleting everywhere stops offering it too`() = runBlocking {
        CloudBackupListing.record(context, listOf(dto("c1", "s1")))
        store(record("s1", cloudId = "c1"))
        api.onDeleteSession = { _, _ -> }

        CloudSync.eraseEverywhere(context, "s1", api, tokens)

        assertTrue(CloudBackupListing.read(context).isEmpty())
    }

    @Test
    fun `a refused delete keeps offering it`() = runBlocking {
        CloudBackupListing.record(context, listOf(dto("c1", "s1")))
        api.onDeleteSession = { _, _ -> throw IOException("no route") }

        CloudSync.eraseCloudBackup(context, "c1", "", api, tokens)

        assertEquals(listOf("c1"), CloudBackupListing.read(context).map { it.cloudId })
    }

    @Test
    fun `another account starts with nothing offered`() {
        CloudBackupListing.record(context, listOf(dto("c1", "s1")))
        CloudBackupListing.hide(context, listOf("c1"))

        CloudBackupListing.clear(context)

        assertTrue(CloudBackupListing.read(context).isEmpty())
        CloudBackupListing.record(context, listOf(dto("c1", "s1")))
        // Nothing stays hidden either.
        assertEquals(listOf("c1"), CloudBackupListing.offered(context).map { it.cloudId })
    }

    @Test
    fun `an unreadable listing offers nothing rather than crashing`() {
        context.getSharedPreferences("semper_cloud_listing", Context.MODE_PRIVATE)
            .edit().putString("backups", "{not json").commit()

        assertTrue(CloudBackupListing.read(context).isEmpty())
    }

    // ── Queueing a batch ────────────────────────────────────────────────────

    @Test
    fun `a batch that cannot be queued counts nothing as started`() {
        // No WorkManager in this harness, so each enqueue fails and is rolled back.
        val counts = RestoreStart.startAll(
            context,
            listOf(RestoreStart.Target("c1", "s1", "Beam"), RestoreStart.Target("", "s2", "Blank id")),
        )

        assertEquals(RestoreStart.Counts(started = 0, alreadyRunning = 0), counts)
        assertTrue(SessionStore.list(context).isEmpty())
    }

    private fun dto(
        cloudId: String,
        localId: String,
        status: String? = "COMPLETED",
        name: String? = null,
        bytes: Long = 0L,
    ) = CloudSessionDto(
        sessionId = cloudId,
        localSessionId = localId,
        specimen = name,
        status = status,
        totalBytes = bytes,
    )

    private fun backup(cloudId: String, localId: String) = CloudBackupListing.Backup(cloudId, localId, "", 0L)

    private fun record(id: String, cloudId: String, frames: Int = 1) = sessionRecord(
        id = id,
        frameCount = frames,
        refName = "",
        sessionDir = SessionStore.dirFor(context, id).absolutePath,
        syncState = SessionRecord.SyncState.SYNCED,
        cloudSessionId = cloudId,
    )

    private fun store(record: SessionRecord) = assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
}
