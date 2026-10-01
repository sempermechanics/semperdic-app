package com.indicvision.semper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class AtomicWritesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `writes through the part sidecar and promotes it`() {
        val dest = File(tmp.root, "metadata.json")
        var seen: File? = null

        val result = AtomicFiles.writeVia(dest) { part ->
            seen = part
            assertFalse("dest must not exist mid-write", dest.exists())
            part.writeText("{}")
            42
        }

        assertEquals(42, result)
        assertEquals(AtomicFiles.partOf(dest), seen)
        assertEquals("{}", dest.readText())
        assertFalse(AtomicFiles.partOf(dest).exists())
    }

    @Test
    fun `an existing destination is replaced, not deleted first, by default`() {
        val dest = tmp.newFile("index.json").apply { writeText("old") }

        AtomicFiles.writeVia(dest, tmp = File(tmp.root, "index.json.tmp")) { t ->
            // The old file stays readable until the rename replaces it.
            assertEquals("old", dest.readText())
            t.writeText("new")
        }

        assertEquals("new", dest.readText())
        assertFalse(File(tmp.root, "index.json.tmp").exists())
    }

    @Test
    fun `clearDest deletes the destination just before the promote`() {
        val dest = tmp.newFile("Session.zip").apply { writeText("old") }
        val sidecar = File(tmp.root, "Session.zip.tmp")

        AtomicFiles.writeVia(dest, tmp = sidecar, clearDest = true) { t ->
            assertTrue(dest.exists())
            t.writeText("zip")
        }

        assertEquals("zip", dest.readText())
        assertFalse(sidecar.exists())
    }

    @Test
    fun `a failed write removes the sidecar and keeps the old destination`() {
        val dest = tmp.newFile("export.zip").apply { writeText("previous") }
        val boom = IOException("disk full")

        try {
            AtomicFiles.writeVia(dest) { part ->
                part.writeText("half")
                throw boom
            }
            fail("expected the write's exception")
        } catch (e: IOException) {
            assertSame(boom, e)
        }

        assertFalse(AtomicFiles.partOf(dest).exists())
        assertEquals("previous", dest.readText())
    }

    @Test
    fun `cancellation is rethrown unchanged after cleanup`() {
        val dest = File(tmp.root, "ranges.bin")
        val cancel = CancellationException("stopped")

        try {
            AtomicFiles.writeVia(dest) { part ->
                part.writeText("partial")
                throw cancel
            }
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertSame(cancel, e)
        }

        assertFalse(AtomicFiles.partOf(dest).exists())
        assertFalse(dest.exists())
    }

    // A non-local `return` out of the write does not compile (`write` is
    // crossinline). Inlined, it skipped writeVia's finally and left the .part
    // behind. An early exit is a labelled return, which promotes, or a throw,
    // which cleans up.

    @Test
    fun `a labelled early return from the write still promotes what it wrote`() {
        val dest = tmp.newFile("bundle.zip").apply { writeText("previous") }

        val result = AtomicFiles.writeVia(dest) { part ->
            part.writeText("header")
            if (part.length() > 0L) return@writeVia "early"
            part.appendText(" and the rest")
            "full"
        }

        assertEquals("early", result)
        assertEquals("header", dest.readText())
        assertFalse(AtomicFiles.partOf(dest).exists())
    }

    @Test
    fun `a throw out of the promote removes the sidecar too`() {
        val dest = tmp.newFolder("occupied")
        File(dest, "child").writeText("keeps the directory from being replaced")
        val sidecar = File(tmp.root, "occupied.tmp")

        try {
            AtomicFiles.writeVia(dest, tmp = sidecar) { t -> t.writeText("zip") }
            fail("expected the promote to fail")
        } catch (_: IOException) {
            // The rename is refused and copyTo will not overwrite a non-empty directory.
        }

        assertFalse(sidecar.exists())
        assertTrue(dest.isDirectory)
    }
}
