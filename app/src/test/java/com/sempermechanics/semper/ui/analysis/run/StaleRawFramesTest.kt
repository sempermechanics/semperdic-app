package com.sempermechanics.semper.ui.analysis.run

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The prune after a batch run deletes the images an earlier run left in
 * `raw_deformed/` that the frame list no longer has, and only those. A run
 * that stopped at its first frame in a session an earlier run saved nothing
 * in used to delete every frame it had not reached, though they were still
 * its inputs: the wizard pointed at them, and Re-run's keep then failed on
 * the missing frame and was dropped (RerunKeeper).
 */
class StaleRawFramesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `launched frames already in raw_deformed stay, reached or not`() {
        val raw = temp.newFolder("session", "raw_deformed")
        listOf("a.png", "b.png", "x.png").forEach { File(raw, it).writeBytes(byteArrayOf(1)) }
        val cache = temp.newFolder("cache")
        // c.png came from the import and was kept as c.png; a.png and b.png were
        // moved here by the earlier run; x.png is a frame the list dropped since.
        File(raw, "c.png").writeBytes(byteArrayOf(2))
        val launched = listOf(File(cache, "c.png").path, File(raw, "a.png").path, File(raw, "b.png").path)
        // The run stopped at frame 0: only it was kept this time.
        val persisted = listOf("c.png", "", "")

        val stale = staleRawFrames(raw, persisted, launched)

        assertEquals(listOf("x.png"), stale.map { it.name })
    }

    @Test
    fun `a frame this run wrote under another name stays, and so does nothing else`() {
        val raw = temp.newFolder("raw_deformed")
        listOf("0000_a.png", "old.png").forEach { File(raw, it).writeBytes(byteArrayOf(1)) }
        val launched = listOf(File(temp.root, "cache/a.png").path)

        val stale = staleRawFrames(raw, listOf("0000_a.png"), launched)

        assertEquals(listOf("old.png"), stale.map { it.name })
    }

    @Test
    fun `no folder, nothing to prune`() {
        assertEquals(emptyList<File>(), staleRawFrames(File(temp.root, "none"), listOf("a.png"), listOf("a.png")))
    }
}
