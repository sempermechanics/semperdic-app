package com.indicvision.semper.analysis

import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.ui.analysis.frames.DeformedFrame
import com.indicvision.semper.ui.analysis.frames.FrameOrderDirection
import com.indicvision.semper.ui.analysis.frames.FrameOrderHelper
import com.indicvision.semper.ui.analysis.frames.FrameOrderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Frame ordering in [FrameOrderHelper.reorder].
 *
 * Deformed frames are correlated in list order, so a wrong permutation silently
 * produces a wrong strain history rather than an error. Each frame carries its
 * name, date and size, which must move with it. Pure Kotlin — no Robolectric needed.
 */
class FrameOrderHelperTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val frames = listOf(
        DeformedFrame("/f/2.png", "charlie.png", 300L, ImageSize(10, 10)),
        DeformedFrame("/f/0.png", "Alpha.png", 100L, ImageSize(20, 20)),
        DeformedFrame("/f/1.png", "bravo.png", 200L, ImageSize(30, 30)),
    )
    private val paths = frames.map { it.path }

    private fun reorder(
        mode: FrameOrderMode,
        direction: FrameOrderDirection = FrameOrderDirection.ASCENDING,
        manualOrder: List<Int>? = null,
    ) = FrameOrderHelper.reorder(frames, mode, direction, manualOrder)

    @Test
    fun `an empty batch reorders to an empty batch`() {
        assertTrue(FrameOrderHelper.reorder(emptyList(), FrameOrderMode.NAME).isEmpty())
    }

    @Test
    fun `picker mode preserves the import order exactly`() {
        assertEquals(frames, reorder(FrameOrderMode.PICKER))
    }

    @Test
    fun `name ascending sorts case-insensitively`() {
        val out = reorder(FrameOrderMode.NAME)
        assertEquals(listOf("Alpha.png", "bravo.png", "charlie.png"), out.map { it.name })
        assertEquals(listOf("/f/0.png", "/f/1.png", "/f/2.png"), out.map { it.path })
    }

    @Test
    fun `name descending is the exact reverse of ascending`() {
        val ascending = reorder(FrameOrderMode.NAME, FrameOrderDirection.ASCENDING)
        val descending = reorder(FrameOrderMode.NAME, FrameOrderDirection.DESCENDING)
        assertEquals(ascending.reversed(), descending)
        assertEquals(listOf("charlie.png", "bravo.png", "Alpha.png"), descending.map { it.name })
    }

    @Test
    fun `date ascending orders by capture time, not by name`() {
        val out = reorder(FrameOrderMode.DATE)
        assertEquals(listOf(100L, 200L, 300L), out.map { it.date })
        assertEquals(listOf("/f/0.png", "/f/1.png", "/f/2.png"), out.map { it.path })
    }

    @Test
    fun `date descending puts the newest frame first`() {
        val out = reorder(FrameOrderMode.DATE, FrameOrderDirection.DESCENDING)
        assertEquals(listOf(300L, 200L, 100L), out.map { it.date })
    }

    @Test
    fun `each frame keeps its own name, date and size after a sort`() {
        val out = reorder(FrameOrderMode.DATE)
        assertEquals(frames.toSet(), out.toSet())
    }

    @Test
    fun `manual mode applies the requested permutation`() {
        val out = reorder(FrameOrderMode.MANUAL, manualOrder = listOf(2, 0, 1))
        assertEquals(listOf("/f/1.png", "/f/2.png", "/f/0.png"), out.map { it.path })
        assertEquals(listOf("bravo.png", "charlie.png", "Alpha.png"), out.map { it.name })
    }

    @Test
    fun `a manual order of the wrong length falls back to the current order`() {
        assertEquals(paths, reorder(FrameOrderMode.MANUAL, manualOrder = listOf(1, 0)).map { it.path })
    }

    @Test
    fun `a null manual order falls back to the current order`() {
        assertEquals(paths, reorder(FrameOrderMode.MANUAL, manualOrder = null).map { it.path })
    }

    @Test
    fun `unknown dates sort by name instead of throwing`() {
        val undated = frames.map { it.copy(date = DeformedFrame.UNKNOWN_DATE) }
        val out = FrameOrderHelper.reorder(undated, FrameOrderMode.DATE)
        // All dates compare equal, so the name tiebreaker decides.
        assertEquals(listOf("Alpha.png", "bravo.png", "charlie.png"), out.map { it.name })
    }

    @Test
    fun `reprefixing renames the files into list order and keeps each frame's details`() {
        val dir = temp.newFolder("temp_deformed")
        val staged = listOf("0000_b.png" to "b.png", "0001_a.png" to "a.png").map { (file, name) ->
            File(dir, file).writeText(name)
            DeformedFrame(File(dir, file).path, name, 5L, ImageSize(4, 3))
        }
        val reordered = FrameOrderHelper.reorder(staged, FrameOrderMode.NAME)

        val out = FrameOrderHelper.reprefixTempFiles(reordered)

        assertEquals(listOf("0000_a.png", "0001_b.png"), out.map { File(it.path).name })
        assertEquals(listOf("a.png", "b.png"), out.map { File(it.path).readText() })
        assertEquals(listOf("a.png", "b.png"), out.map { it.name })
        assertTrue(out.all { it.date == 5L && it.size == ImageSize(4, 3) })
    }
}
