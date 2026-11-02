package com.sempermechanics.semper.data.cloud.sync

import com.sempermechanics.semper.data.cloud.CloudBackupListing
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the sync from other phones decides: when it runs, and which backups it
 * restores. A backup is restored only when no row on this phone claims it,
 * so an analysis removed from this phone ("Only in cloud") is never pulled
 * back down on its own.
 */
class PhoneSyncTest {

    // ── Whether a run goes ahead ────────────────────────────────────────────

    @Test
    fun `a run goes ahead only with the switch on, restore open and a readable index`() {
        assertNull(PhoneSync.skip(switchOn = true, restoreEnabled = true, indexReadable = true))
        assertEquals(
            PhoneSync.Skip.SWITCHED_OFF,
            PhoneSync.skip(switchOn = false, restoreEnabled = true, indexReadable = true),
        )
        assertEquals(
            PhoneSync.Skip.RESTORE_OFF,
            PhoneSync.skip(switchOn = true, restoreEnabled = false, indexReadable = true),
        )
        assertEquals(
            PhoneSync.Skip.INDEX_UNREADABLE,
            PhoneSync.skip(switchOn = true, restoreEnabled = true, indexReadable = false),
        )
    }

    @Test
    fun `the switch is checked first, then the licence`() {
        assertEquals(
            PhoneSync.Skip.SWITCHED_OFF,
            PhoneSync.skip(switchOn = false, restoreEnabled = false, indexReadable = false),
        )
        assertEquals(
            PhoneSync.Skip.RESTORE_OFF,
            PhoneSync.skip(switchOn = true, restoreEnabled = false, indexReadable = false),
        )
    }

    // ── Which backups are restored ──────────────────────────────────────────

    @Test
    fun `a phone with no rows restores every backup`() {
        val listed = listOf(backup("c1", "s1"), backup("c2", "s2"))

        assertEquals(listOf("c1", "c2"), ids(PhoneSync.toRestore(listed, emptyList(), none, none, none)))
    }

    @Test
    fun `a backup made on this phone is not restored over its own row`() {
        val listed = listOf(backup("c1", "s1"), backup("c2", "other-phone"))
        val records = listOf(row("s1", cloudId = "c1"))

        assertEquals(listOf("c2"), ids(PhoneSync.toRestore(listed, records, none, none, none)))
    }

    @Test
    fun `an analysis removed from this phone and kept in the cloud stays as it is`() {
        // "Removed from this phone · in the cloud": the row stays, its frames are gone.
        val freed = row("s1", cloudId = "c1", frames = 0)

        assertEquals(
            emptyList<String>(),
            ids(PhoneSync.toRestore(listOf(backup("c1", "s1")), listOf(freed), none, none, none)),
        )
    }

    @Test
    fun `a row that claims a backup only by its stored cloud id still claims it`() {
        // Uploaded before the local id was sent: the backup's local id is blank.
        val listed = listOf(backup("c1", localId = ""))

        assertEquals(
            emptyList<String>(),
            ids(PhoneSync.toRestore(listed, listOf(row("s9", cloudId = "c1")), none, none, none)),
        )
    }

    @Test
    fun `hidden, tried and restoring backups are left alone`() {
        val listed = listOf(backup("c1", "a"), backup("c2", "b"), backup("c3", "c"), backup("c4", "d"))

        val picked = PhoneSync.toRestore(
            listed,
            records = emptyList(),
            hidden = setOf("c1"),
            tried = setOf("c2"),
            restoring = setOf("c3"),
        )

        assertEquals(listOf("c4"), ids(picked))
    }

    @Test
    fun `a backup listed twice or without an id is asked for at most once`() {
        val listed = listOf(backup("c1", "a"), backup("c1", "a"), backup("", "b"))

        assertEquals(listOf("c1"), ids(PhoneSync.toRestore(listed, emptyList(), none, none, none)))
    }

    // ── What is remembered as tried ─────────────────────────────────────────

    @Test
    fun `a backup deleted on another phone is not restored, the bin keeping it out of the listing`() {
        // `GET /v1/sessions` leaves a binned backup out (ADR-035), so a phone
        // with no row for it is not offered it either.
        val listed = listOf(backup("c-kept", "kept"))

        assertEquals(listOf("c-kept"), ids(PhoneSync.toRestore(listed, emptyList(), none, none, none)))
    }

    @Test
    fun `a row in the bin claims its binned backup, so its restore from the bin is not doubled here`() {
        val inBin = sessionRecord(id = "restored-c1").copy(binnedCloudId = "c1")

        val picked = PhoneSync.toRestore(listOf(backup("c1", "l1")), listOf(inBin), none, none, none)

        assertEquals(emptyList<String>(), ids(picked))
    }

    @Test
    fun `a backup restored from the bin is restored again on a phone without it, as intended`() {
        // Binned: absent from the listing, so the run forgets it was tried.
        val whileBinned = PhoneSync.triedAfter(setOf("c1"), emptyList(), emptyList())
        assertEquals(none, whileBinned)

        // Restored from the bin: listed again, and this phone has no row for it.
        val back = listOf(backup("c1", "l1"))
        assertEquals(listOf("c1"), ids(PhoneSync.toRestore(back, emptyList(), none, whileBinned, none)))
    }

    @Test
    fun `tried keeps what is still listed and adds what was started`() {
        val listed = listOf(backup("c1", "a"), backup("c3", "c"))

        assertEquals(
            setOf("c1", "c3"),
            PhoneSync.triedAfter(tried = setOf("c1", "c2"), listed = listed, started = listOf("c3")),
        )
    }

    @Test
    fun `a backup deleted from the cloud is forgotten from tried`() {
        assertEquals(emptySet<String>(), PhoneSync.triedAfter(setOf("c1"), emptyList(), emptyList()))
    }

    private val none = emptySet<String>()

    private fun ids(backups: List<CloudBackupListing.Backup>) = backups.map { it.cloudId }

    private fun backup(cloudId: String, localId: String) = CloudBackupListing.Backup(cloudId, localId, "", 0L)

    private fun row(id: String, cloudId: String, frames: Int = 1) = sessionRecord(
        id = id,
        frameCount = frames,
        cloudSessionId = cloudId,
        syncState = SessionRecord.SyncState.SYNCED,
    )
}
