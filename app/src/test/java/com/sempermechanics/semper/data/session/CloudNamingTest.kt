package com.sempermechanics.semper.data.session

import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [CloudNaming]: restored names, backup labels and the repair of old names, all in today's scheme. */
class CloudNamingTest {

    // ── The name a backup restores under ────────────────────────────────────

    @Test
    fun `a stored name is kept`() {
        assertEquals("steel_24 sweep", CloudNaming.restoredName("steel_24 sweep", "steel_24.png"))
        assertEquals("Beam A", CloudNaming.restoredName("Beam A", "steel_00.png"))
        val typed = CloudNaming.restoredName("x.png", "y.png")
        assertEquals("a name the user typed with an extension stays", "x.png", typed)
    }

    @Test
    fun `an old auto-name reads as today's`() {
        assertEquals("steel_00", CloudNaming.restoredName("steel_00.png", "steel_00.png"))
        assertEquals("steel_00", CloudNaming.restoredName("steel_00 · Oct 9, 17:57:31", "steel_00.png"))
        assertEquals("video @ 0:00", CloudNaming.restoredName("video @ 0:00 · Oct 8, 18:19:40", "video @ 0:00"))
        assertEquals(
            "a date on another name is the user's",
            "Beam · Oct 9, 17:57:31",
            CloudNaming.restoredName("Beam · Oct 9, 17:57:31", "steel_00.png"),
        )
        assertEquals("tensile.v2", CloudNaming.restoredName("tensile.v2", "tensile.v2"))
    }

    @Test
    fun `no name falls back to the specimen's, then Restored`() {
        assertEquals("pmma_00", CloudNaming.restoredName(null, "pmma_00.png"))
        assertEquals("pmma_00", CloudNaming.restoredName("  ", "pmma_00.png"))
        assertEquals("Restored", CloudNaming.restoredName("", ""))
        assertEquals("Restored", CloudNaming.restoredName(null, null))
    }

    // ── Labels in a list of backups ─────────────────────────────────────────

    @Test
    fun `backup labels drop the extension and never read alike`() {
        val labels = CloudNaming.backupNames(
            listOf("steel_00.png", "pmma_00.png", "pmma_00.png", "pmma_00.png", "concrete_00.jpg", "tensile.v2"),
            taken = setOf("steel_00", "pmma_00 (2)"),
        )

        assertEquals(
            listOf("steel_00 (2)", "pmma_00", "pmma_00 (3)", "pmma_00 (4)", "concrete_00", "tensile.v2"),
            labels,
        )
    }

    @Test
    fun `a backup with no specimen keeps a blank label for the caller's wording`() {
        assertEquals(listOf("", "", "a"), CloudNaming.backupNames(listOf(null, " ", "a.png"), emptySet()))
    }

    // ── Repair of names an earlier build left ───────────────────────────────

    @Test
    fun `nothing to repair is null`() {
        val rows = listOf(sessionRecord("a", name = "steel_00"), sessionRecord("b", name = "steel_00 (2)"))
        assertNull(CloudNaming.repaired(rows) { error("no metadata is read") })
        assertNull("blank names are not clashes", CloudNaming.repaired(listOf(row("a", ""), row("b", ""))) { null })
    }

    @Test
    fun `a clash keeps the oldest row's name and numbers the rest`() {
        val rows = listOf(
            row("new", "pmma_00", createdAt = 30L),
            row("old", "pmma_00", createdAt = 10L),
            row("mid", "pmma_00", createdAt = 20L),
            row("taken", "pmma_00 (2)", createdAt = 40L),
        )

        val names = CloudNaming.repaired(rows) { null }!!.associate { it.id to it.name }

        assertEquals(
            mapOf("old" to "pmma_00", "mid" to "pmma_00 (3)", "new" to "pmma_00 (4)", "taken" to "pmma_00 (2)"),
            names,
        )
    }

    @Test
    fun `a row named after its reference's file takes its metadata's name, else drops the extension`() {
        val rows = listOf(
            row("sweep", "steel_24.png", refName = "steel_24.png"),
            row("plain", "pmma_32.png", refName = "pmma_32.png"),
            row("typed", "x.png", refName = "x.png").copy(renamedByUser = true),
            row("same", "steel_00", refName = "steel_00.png"),
        )

        val repaired = CloudNaming.repaired(rows) { if (it.id == "sweep") "steel_24 sweep" else null }!!

        assertEquals(listOf("steel_24 sweep", "pmma_32", "x.png", "steel_00"), repaired.map { it.name })
        assertEquals("ids and everything else stay", rows.map { it.id }, repaired.map { it.id })
        assertEquals(rows.map { it.refName }, repaired.map { it.refName })
    }

    @Test
    fun `a repaired name that meets another row's is numbered`() {
        val rows = listOf(
            row("local", "pmma_00", createdAt = 1L),
            row("restored", "pmma_00.png", refName = "pmma_00.png", createdAt = 2L),
        )

        val repaired = CloudNaming.repaired(rows) { null }!!

        assertEquals(listOf("pmma_00", "pmma_00 (2)"), repaired.map { it.name })
        assertNull("a second pass finds nothing", CloudNaming.repaired(repaired) { null })
    }

    private fun row(id: String, name: String, refName: String = "ref.png", createdAt: Long = 1L) =
        sessionRecord(id = id, name = name, refName = refName, createdAt = createdAt)
}
