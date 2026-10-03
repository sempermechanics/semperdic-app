package com.sempermechanics.semper.data.prefs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [WizardDraft]'s part writes: atomic, and leaving no sidecar behind when one fails. */
class WizardDraftTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: File get() = File(tmp.root, WizardDraft.DIR_NAME)

    @Test
    fun `parts read back what was written, and null deletes one`() {
        val draft = WizardDraft(dir)

        draft.writeReference(byteArrayOf(1, 2, 3))
        draft.writeFrames("""["a","b"]""")

        assertArrayEquals(byteArrayOf(1, 2, 3), draft.readReference())
        assertEquals("""["a","b"]""", draft.readFrames())
        assertTrue(WizardDraft.isLive(tmp.root))

        draft.writeReference(null)
        assertNull(draft.readReference())
        assertEquals(listOf("frames.json", "live"), dir.list()!!.sorted())
    }

    @Test
    fun `a part that cannot be promoted leaves no tmp file`() {
        // A non-empty directory where the part goes: the tmp file is written,
        // then neither the rename nor the copy can replace it.
        File(dir, "reference.bin/blocker").apply { parentFile!!.mkdirs() }.writeText("x")
        val draft = WizardDraft(dir)

        draft.writeReference(byteArrayOf(9))

        assertFalse(File(dir, "reference.bin.tmp").exists())
    }
}
