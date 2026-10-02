package com.indicvision.semper.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** [SessionNaming]: the auto-name a run gives, and the safe forms names take in files. */
class SessionNamingTest {

    @Test
    fun `the auto-name is the reference's base name and the creation time`() {
        val createdAt = 1_700_000_000_000L
        val stamp = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(createdAt))

        assertEquals("specimen_A · $stamp", SessionNaming.defaultSessionName("specimen_A.tif", createdAt))
        assertEquals("Analysis · $stamp", SessionNaming.defaultSessionName(".png", createdAt))
    }

    @Test
    fun `a file-safe name collapses unsafe runs, trims, falls back and caps`() {
        assertEquals("Plate_A-1.v2", SessionNaming.fileSafe("  Plate A-1.v2 ", "x"))
        assertEquals("x", SessionNaming.fileSafe("///", "x"))
        assertEquals(40, SessionNaming.fileSafe("a".repeat(90), "x").length)
    }

    @Test
    fun `the Save-to-Files and export names wrap the file-safe name`() {
        assertEquals("My_Sample_Session.zip", SessionNaming.bundleFileName("My Sample"))
        assertEquals("analysis_Session.zip", SessionNaming.bundleFileName("!!!"))
        assertEquals("session_abcdefgh", SessionNaming.exportEntryName("", "abcdefghij"))
    }

    @Test
    fun `two backups with one display name get their own cache file`() {
        val a = SessionNaming.bundleCacheFileName("My Sample", "cloud-a")
        val b = SessionNaming.bundleCacheFileName("My Sample", "cloud-b")

        assertTrue(a != b)
        assertEquals(a, SessionNaming.bundleCacheFileName("My Sample", "cloud-a"))
        assertTrue(a, Regex("[0-9a-f]{12}_My_Sample_Session\\.zip").matches(a))
    }
}
