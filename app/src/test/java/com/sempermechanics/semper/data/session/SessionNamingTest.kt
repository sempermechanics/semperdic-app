package com.sempermechanics.semper.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SessionNaming]: the auto-name a run gives, and the safe forms names take in files. */
class SessionNamingTest {

    @Test
    fun `the auto-name is the reference's base name, without the date the row already shows`() {
        assertEquals("specimen_A", SessionNaming.defaultSessionName("specimen_A.tif", emptySet()))
        assertEquals("Analysis", SessionNaming.defaultSessionName(".png", emptySet()))
    }

    @Test
    fun `a video analysis is named after its clip, without the extension`() {
        assertEquals("tensile_03", SessionNaming.clipName("tensile_03.mp4"))
        assertEquals("clip", SessionNaming.clipName("clip"))
        assertNull("no name: the caller says Video", SessionNaming.clipName(null))
        assertNull(SessionNaming.clipName(".mp4"))
        // The clip's name is the reference's, so the run's auto-name is the clip's.
        assertEquals("tensile_03 (2)", SessionNaming.runSessionName("tensile_03", "tensile_03", setOf("tensile_03")))
        val dotted = SessionNaming.runSessionName("tensile.v2", "tensile.v2", emptySet())
        assertEquals("a dot in a clip's name stays", "tensile.v2", dotted)
        assertEquals(
            "a reference picked over a video's frame names the run as an image",
            "plate",
            SessionNaming.runSessionName("plate.tif", "tensile_03", emptySet()),
        )
    }

    @Test
    fun `an auto-name another session has gets the first free number`() {
        assertEquals("steel_00 (2)", SessionNaming.defaultSessionName("steel_00.png", setOf("steel_00")))
        assertEquals(
            "steel_00 (4)",
            SessionNaming.defaultSessionName("steel_00.png", setOf("steel_00", "steel_00 (2)", "steel_00 (3)")),
        )
        val gap = setOf("steel_00", "steel_00 (3)")
        assertEquals("a gap is reused", "steel_00 (2)", SessionNaming.uniqueName("steel_00", gap))
        val dated = setOf("steel_00 · Oct 5, 18:09:56")
        assertEquals("an old dated name is no clash", "steel_00", SessionNaming.uniqueName("steel_00", dated))
    }

    @Test
    fun `only an image or video extension is dropped, in any case`() {
        assertEquals("steel_00", SessionNaming.withoutMediaExtension("steel_00.PNG"))
        assertEquals("tensile.v2", SessionNaming.withoutMediaExtension("tensile.v2.mp4"))
        val dotted = SessionNaming.withoutMediaExtension("tensile.v2")
        assertEquals("a dotted tail that is no extension stays", "tensile.v2", dotted)
        assertEquals("frame.0001", SessionNaming.withoutMediaExtension("frame.0001"))
        assertEquals("no dot", SessionNaming.withoutMediaExtension("no dot"))
        assertEquals("tensile.v2", SessionNaming.defaultSessionName("tensile.v2", emptySet()))
        assertEquals("tensile.v2", SessionNaming.defaultSessionName("tensile.v2.tif", emptySet()))
        assertEquals("tensile.v2", SessionNaming.clipName("tensile.v2.mov"))
        assertEquals("tensile.v2", SessionNaming.clipName("tensile.v2"))
    }

    @Test
    fun `a name that already carries a number counts on from its stem`() {
        val taken = setOf("steel_00", "steel_00 (2)")
        assertEquals("steel_00 (3)", SessionNaming.uniqueName("steel_00 (2)", taken))
        assertEquals("a free numbered name stays", "steel_00 (5)", SessionNaming.uniqueName("steel_00 (5)", taken))
        assertEquals("Test (2)", SessionNaming.uniqueName("Test (1)", setOf("Test (1)")))
        assertEquals("a bare number is a name", "(2) (2)", SessionNaming.uniqueName("(2)", setOf("(2)")))
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
