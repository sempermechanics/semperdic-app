package com.sempermechanics.semper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `promote replaces an existing destination and removes the temp file`() {
        val dest = tmp.newFile("index.json").apply { writeText("old") }
        val part = AtomicFiles.partOf(dest).apply { writeText("new") }

        AtomicFiles.promote(part, dest)

        assertEquals("new", dest.readText())
        assertFalse(part.exists())
    }

    @Test
    fun `promote copies when the rename is refused`() {
        val dest = tmp.newFile("export.json")
        // A temp folder is one volume, so the rename always succeeds; refuse
        // it by hand to reach the cross-volume branch.
        val src = tmp.newFile("export.json.part").apply { writeText("body") }
        val blocked = object : java.io.File(src.path) {
            override fun renameTo(dest: java.io.File): Boolean = false
        }

        AtomicFiles.promote(blocked, dest)

        assertEquals("body", dest.readText())
        assertFalse(src.exists())
    }

    @Test
    fun `sidecar names sit beside the destination`() {
        val dest = java.io.File(tmp.root, "restore_s1_bundle.zip")
        assertEquals("restore_s1_bundle.zip.part", AtomicFiles.partOf(dest).name)
        assertEquals("restore_s1_bundle.zip.full", AtomicFiles.fullOf(dest).name)
        assertEquals(tmp.root, AtomicFiles.partOf(dest).parentFile)

        AtomicFiles.partOf(dest).writeText("x")
        AtomicFiles.fullOf(dest).writeText("y")
        AtomicFiles.deleteSidecars(dest)
        assertFalse(AtomicFiles.partOf(dest).exists())
        assertFalse(AtomicFiles.fullOf(dest).exists())
    }
}
