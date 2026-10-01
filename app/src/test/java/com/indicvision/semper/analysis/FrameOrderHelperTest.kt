package com.indicvision.semper.analysis

import com.indicvision.semper.ui.analysis.frames.FrameOrderDirection
import com.indicvision.semper.ui.analysis.frames.FrameOrderHelper
import com.indicvision.semper.ui.analysis.frames.FrameOrderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame ordering in [FrameOrderHelper.reorder].
 *
 * Deformed frames are correlated in list order, so a wrong permutation silently
 * produces a wrong strain history rather than an error. [reorder] moves four
 * parallel collections at once, which is exactly where an off-by-one hides.
 * Pure Kotlin — no Robolectric needed.
 */
class FrameOrderHelperTest {

    private val paths = listOf("/f/2.png", "/f/0.png", "/f/1.png")
    private val names = listOf("charlie.png", "Alpha.png", "bravo.png")
    private val dates = listOf(300L, 100L, 200L)
    private val sizes = mapOf(
        "/f/2.png" to (10 to 10),
        "/f/0.png" to (20 to 20),
        "/f/1.png" to (30 to 30),
    )

    private fun reorder(
        mode: FrameOrderMode,
        direction: FrameOrderDirection = FrameOrderDirection.ASCENDING,
        manualOrder: List<Int>? = null,
    ) = FrameOrderHelper.reorder(
        paths = paths,
        names = names,
        dates = dates,
        sizes = sizes,
        mode = mode,
        direction = direction,
        manualOrder = manualOrder,
    )

    @Test
    fun `an empty batch reorders to an empty batch`() {
        val out = FrameOrderHelper.reorder(
            paths = emptyList(),
            names = emptyList(),
            dates = emptyList(),
            sizes = emptyMap(),
            mode = FrameOrderMode.NAME,
        )
        assertTrue(out.paths.isEmpty())
        assertTrue(out.names.isEmpty())
        assertTrue(out.dates.isEmpty())
        assertTrue(out.sizes.isEmpty())
    }

    @Test
    fun `picker mode preserves the import order exactly`() {
        val out = reorder(FrameOrderMode.PICKER)
        assertEquals(paths, out.paths)
        assertEquals(names, out.names)
        assertEquals(dates, out.dates)
    }

    @Test
    fun `name ascending sorts case-insensitively`() {
        val out = reorder(FrameOrderMode.NAME)
        assertEquals(listOf("Alpha.png", "bravo.png", "charlie.png"), out.names)
        assertEquals(listOf("/f/0.png", "/f/1.png", "/f/2.png"), out.paths)
    }

    @Test
    fun `name descending is the exact reverse of ascending`() {
        val ascending = reorder(FrameOrderMode.NAME, FrameOrderDirection.ASCENDING)
        val descending = reorder(FrameOrderMode.NAME, FrameOrderDirection.DESCENDING)
        assertEquals(ascending.paths.reversed(), descending.paths)
        assertEquals(listOf("charlie.png", "bravo.png", "Alpha.png"), descending.names)
    }

    @Test
    fun `date ascending orders by capture time, not by name`() {
        val out = reorder(FrameOrderMode.DATE)
        assertEquals(listOf(100L, 200L, 300L), out.dates)
        assertEquals(listOf("/f/0.png", "/f/1.png", "/f/2.png"), out.paths)
    }

    @Test
    fun `date descending puts the newest frame first`() {
        val out = reorder(FrameOrderMode.DATE, FrameOrderDirection.DESCENDING)
        assertEquals(listOf(300L, 200L, 100L), out.dates)
    }

    @Test
    fun `the parallel lists stay aligned after a sort`() {
        val out = reorder(FrameOrderMode.DATE)
        // Each frame must keep its own name and date after the permutation.
        out.paths.forEachIndexed { i, path ->
            val original = paths.indexOf(path)
            assertEquals(names[original], out.names[i])
            assertEquals(dates[original], out.dates[i])
        }
    }

    @Test
    fun `manual mode applies the requested permutation`() {
        val out = reorder(FrameOrderMode.MANUAL, manualOrder = listOf(2, 0, 1))
        assertEquals(listOf("/f/1.png", "/f/2.png", "/f/0.png"), out.paths)
        assertEquals(listOf("bravo.png", "charlie.png", "Alpha.png"), out.names)
    }

    @Test
    fun `a manual order of the wrong length falls back to the current order`() {
        val out = reorder(FrameOrderMode.MANUAL, manualOrder = listOf(1, 0))
        assertEquals(paths, out.paths)
    }

    @Test
    fun `a null manual order falls back to the current order`() {
        val out = reorder(FrameOrderMode.MANUAL, manualOrder = null)
        assertEquals(paths, out.paths)
    }

    @Test
    fun `a date list of the wrong length degrades to a name sort instead of throwing`() {
        val out = FrameOrderHelper.reorder(
            paths = paths,
            names = names,
            dates = listOf(1L), // shorter than paths
            sizes = sizes,
            mode = FrameOrderMode.DATE,
        )
        // All dates compare equal, so the name tiebreaker decides.
        assertEquals(listOf("Alpha.png", "bravo.png", "charlie.png"), out.names)
    }

    @Test
    fun `sizes are carried across for the frames that remain`() {
        val out = reorder(FrameOrderMode.NAME)
        assertEquals(3, out.sizes.size)
        assertEquals(20 to 20, out.sizes["/f/0.png"])
        assertEquals(10 to 10, out.sizes["/f/2.png"])
    }
}
