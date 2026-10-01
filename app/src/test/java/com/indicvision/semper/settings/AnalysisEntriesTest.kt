package com.indicvision.semper.settings

import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.fixtures.sessionRecord
import com.indicvision.semper.ui.settings.AnalysisEntries
import com.indicvision.semper.ui.settings.AnalysisLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The settings page shows one row per analysis, joining the local index with
 * the backend's list. These pin the join and — more importantly — what a row
 * claims when the backend could not be reached.
 */
class AnalysisEntriesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A session dir with a `.dat` so [SessionRecord.hasLocalData] is true. */
    private fun sessionDirWithData(id: String): String {
        val dir = tmp.newFolder(id)
        File(dir, "frame_0000.dat").writeBytes(ByteArray(1))
        return dir.absolutePath
    }

    private fun record(
        id: String,
        name: String = id,
        syncState: SessionRecord.SyncState = SessionRecord.SyncState.SYNCED,
        cloudSessionId: String = "",
        withLocalData: Boolean = true,
    ): SessionRecord {
        val dir = if (withLocalData) sessionDirWithData(id) else tmp.newFolder("empty-$id").absolutePath
        return sessionRecord(
            id = id,
            name = name,
            createdAt = 0L,
            refPath = "$dir/ref.png",
            sessionDir = dir,
            cloudSessionId = cloudSessionId,
            syncState = syncState,
        )
    }

    @Test
    fun `a row's place and Restore come from the flag read at merge, not from the disk`() {
        // Rows bind and are tapped on the main thread; listing a session
        // directory there per bind is what the flag replaces.
        val withFrames = record("local-1", withLocalData = true)
        val cloud = CloudSessionDto(sessionId = "cloud-1", localSessionId = "local-1")

        val readAsGone = AnalysisEntries.merge(listOf(withFrames), listOf(cloud), hasLocal = { false }).single()
        assertEquals(AnalysisLocation.CLOUD_ONLY, readAsGone.location)
        assertEquals(true, readAsGone.offersRestore())

        val readAsThere = AnalysisEntries.merge(listOf(withFrames), listOf(cloud), hasLocal = { true }).single()
        assertEquals(AnalysisLocation.PHONE_AND_CLOUD, readAsThere.location)
        assertEquals(false, readAsThere.offersRestore())
    }

    @Test
    fun `cloud row joins its local record by localSessionId`() {
        val entries = AnalysisEntries.merge(
            records = listOf(record("local-1", name = "Steel plate")),
            cloud = listOf(CloudSessionDto(sessionId = "cloud-1", localSessionId = "local-1")),
        )

        assertEquals(1, entries.size)
        assertEquals(AnalysisLocation.PHONE_AND_CLOUD, entries[0].location)
        assertEquals("Steel plate", entries[0].name)
    }

    @Test
    fun `cloud row joins by stored cloud id when localSessionId is blank`() {
        // Sessions uploaded before the localSessionId link existed.
        val entries = AnalysisEntries.merge(
            records = listOf(record("local-1", cloudSessionId = "cloud-1")),
            cloud = listOf(CloudSessionDto(sessionId = "cloud-1", localSessionId = "")),
        )

        assertEquals(1, entries.size)
        assertEquals(AnalysisLocation.PHONE_AND_CLOUD, entries[0].location)
    }

    @Test
    fun `a stub local row without dat files is cloud-only when joined`() {
        val entries = AnalysisEntries.merge(
            records = listOf(record("local-1", withLocalData = false)),
            cloud = listOf(CloudSessionDto(sessionId = "cloud-1", localSessionId = "local-1")),
        )
        assertEquals(AnalysisLocation.CLOUD_ONLY, entries[0].location)
    }

    @Test
    fun `a backup with no local copy becomes a cloud-only row, after the local ones`() {
        val entries = AnalysisEntries.merge(
            records = listOf(record("local-1")),
            cloud = listOf(
                CloudSessionDto(sessionId = "cloud-1", localSessionId = "local-1"),
                CloudSessionDto(sessionId = "cloud-2", localSessionId = "gone", specimen = "Old beam"),
            ),
        )

        assertEquals(2, entries.size)
        assertEquals(AnalysisLocation.PHONE_AND_CLOUD, entries[0].location)
        assertEquals(AnalysisLocation.CLOUD_ONLY, entries[1].location)
        assertEquals("Old beam", entries[1].name)
        assertNull(entries[1].record)
    }

    @Test
    fun `a cloud-only row falls back to its session id when unnamed`() {
        val entries = AnalysisEntries.merge(
            records = emptyList(),
            cloud = listOf(CloudSessionDto(sessionId = "cloud-9", specimen = null)),
        )

        assertEquals("cloud-9", entries[0].name)
    }

    @Test
    fun `local-only analyses report phone only`() {
        val entries = AnalysisEntries.merge(
            records = listOf(record("local-1", syncState = SessionRecord.SyncState.LOCAL_ONLY)),
            cloud = emptyList(),
        )

        assertEquals(AnalysisLocation.PHONE_ONLY, entries[0].location)
    }

    /**
     * The regression that matters: an unreachable backend yields an empty cloud
     * list, and a backed-up analysis must NOT then read as "on phone only" —
     * that tells the user their backup is gone when it was merely unverified.
     */
    @Test
    fun `a synced analysis is not demoted to phone only when the cloud cannot be listed`() {
        val entries = AnalysisEntries.merge(
            records = listOf(record("local-1", syncState = SessionRecord.SyncState.SYNCED)),
            cloud = emptyList(),
        )

        assertEquals(AnalysisLocation.PHONE_SYNC_STATE, entries[0].location)
    }

    @Test
    fun `pending and failed backups keep their own state, not phone only`() {
        val entries = AnalysisEntries.merge(
            records = listOf(
                record("local-1", syncState = SessionRecord.SyncState.PENDING),
                record("local-2", syncState = SessionRecord.SyncState.FAILED),
            ),
            cloud = emptyList(),
        )

        assertEquals(AnalysisLocation.PHONE_SYNC_STATE, entries[0].location)
        assertEquals(AnalysisLocation.PHONE_SYNC_STATE, entries[1].location)
    }

    /**
     * Two records carrying the same stale cloud id must not both show that
     * backup: the row's bin deletes by cloud id, so a duplicate would offer to
     * delete the same thing twice.
     */
    @Test
    fun `one backup is claimed by a single record`() {
        val entries = AnalysisEntries.merge(
            records = listOf(
                record("local-1", cloudSessionId = "cloud-1"),
                record("local-2", cloudSessionId = "cloud-1"),
            ),
            cloud = listOf(CloudSessionDto(sessionId = "cloud-1", localSessionId = "local-1")),
        )

        assertEquals(2, entries.size)
        assertEquals(AnalysisLocation.PHONE_AND_CLOUD, entries[0].location)
        assertEquals("cloud-1", entries[0].cloud?.sessionId)
        assertNull(entries[1].cloud)
    }

    @Test
    fun `Download when cloud listed Restore only when local frames missing`() {
        val phoneAndCloud = AnalysisEntries.merge(
            records = listOf(record("local-match", name = "Steel")),
            cloud = listOf(CloudSessionDto(sessionId = "cloud-1", localSessionId = "local-match")),
        )
        assertEquals(true, phoneAndCloud[0].offersDownload())
        assertEquals(false, phoneAndCloud[0].offersRestore())
        assertEquals(true, phoneAndCloud[0].offersCloudActions())

        val cloudOnly = AnalysisEntries.merge(
            records = emptyList(),
            cloud = listOf(CloudSessionDto(sessionId = "cloud-9", specimen = "Beam")),
        )
        assertEquals(true, cloudOnly[0].offersDownload())
        assertEquals(true, cloudOnly[0].offersRestore())

        val stubNoLocal = AnalysisEntries.merge(
            records = listOf(record("local-stub", withLocalData = false)),
            cloud = listOf(CloudSessionDto(sessionId = "cloud-2", localSessionId = "local-stub")),
        )
        assertEquals(true, stubNoLocal[0].offersDownload())
        assertEquals(true, stubNoLocal[0].offersRestore())

        val offlineSynced = AnalysisEntries.merge(
            records = listOf(
                record(
                    "local-offline",
                    syncState = SessionRecord.SyncState.SYNCED,
                    cloudSessionId = "cloud-1",
                ),
            ),
            cloud = emptyList(),
        )
        assertEquals(false, offlineSynced[0].offersDownload())
        assertEquals(false, offlineSynced[0].offersRestore())
        assertEquals(false, offlineSynced[0].offersCloudActions())
    }
}
