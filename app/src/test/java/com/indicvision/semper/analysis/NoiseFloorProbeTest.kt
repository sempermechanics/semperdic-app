package com.indicvision.semper.analysis

import android.graphics.Rect
import com.indicvision.semper.DicResult
import com.indicvision.semper.ui.analysis.NoiseFloorProbe
import com.indicvision.semper.ui.analysis.VsgStudy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The probe's grid, and the strain window that has to fit on it.
 *
 * The engine will not hand back a displacement whose strain it could not
 * compute — the point is dropped from the output entirely, u and v with it — so
 * a strain window sized for the analysis rather than for this deliberately
 * coarse lattice does not merely produce worse strain. It produces **nothing**,
 * on every point, which is a burst that measures as zero and a gate that
 * silently concludes it cannot conclude. That happened on both test devices,
 * and this is the test that would have caught it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoiseFloorProbeTest {

    /**
     * How many grid points the engine would find inside its circular window.
     *
     * Mirrors `StrainCalculator::compute_vsg_strain`: a radius of half the
     * window in pixels, walked at the grid's own spacing, with a support rule of
     * three points before any strain is written at all.
     */
    private fun supportPoints(step: Int, window: Int): Int {
        val radius = window / 2.0
        val gridRadius = ceil(radius / step).toInt()
        var points = 0
        for (dy in -gridRadius..gridRadius) {
            for (dx in -gridRadius..gridRadius) {
                val x = (dx * step).toDouble()
                val y = (dy * step).toDouble()
                if (sqrt(x * x + y * y) <= radius) points++
            }
        }
        return points
    }

    @Test
    fun `the analysis default window collapses to a single point on the probe grid`() {
        // Not a hypothetical: this is the shipped defect, reproduced. A 4032 px
        // frame with a 47 px subset gives a step far wider than a 15 px window,
        // so the centre point is alone in its own window and the engine drops it.
        val step = NoiseFloorProbe.probeStepFor(Rect(0, 0, 2400, 1800), subset = 47)
        assertTrue("step should be coarse here", step > VsgStudy.DEFAULT_STRAIN_WINDOW)
        assertEquals(1, supportPoints(step, VsgStudy.DEFAULT_STRAIN_WINDOW))
    }

    @Test
    fun `the derived window reaches the four orthogonal neighbours and no further`() {
        // Five points: one clear of the engine's minimum, and the most
        // permissive fill its 90% support rule allows. Reaching the diagonals
        // too would take nine, and then every one of the nine must converge.
        listOf(4, 7, 12, 23, 40, 96).forEach { step ->
            val window = NoiseFloorProbe.strainWindowFor(step)
            assertEquals("step $step", 5, supportPoints(step, window))
        }
    }

    @Test
    fun `every step the probe can choose yields a solvable window`() {
        // The step is derived from the ROI and the subset, both of which the
        // user's framing decides, so the guarantee has to hold across the range
        // rather than at the one size that was tried on a bench.
        listOf(
            Rect(0, 0, 64, 64) to 15,
            Rect(0, 0, 480, 360) to 21,
            Rect(0, 0, 2400, 1800) to 47,
            Rect(0, 0, 4032, 3024) to 101,
        ).forEach { (roi, subset) ->
            val step = NoiseFloorProbe.probeStepFor(roi, subset)
            val support = supportPoints(step, NoiseFloorProbe.strainWindowFor(step))
            assertTrue("$roi subset $subset gave $support", support >= ENGINE_MIN_SUPPORT)
        }
    }

    @Test
    fun `the grid stays coarse enough to stay cheap`() {
        // The probe is a scatter estimate, not a field. Widening the window must
        // not have quietly become a reason to narrow the step.
        val step = NoiseFloorProbe.probeStepFor(Rect(0, 0, 2400, 1800), subset = 47)
        assertTrue("step $step", step >= 47 / 2)
    }

    // ------------------------------------------------------------------
    // The per-cell scatter behind the sigma heat map
    // ------------------------------------------------------------------

    @Test
    fun `scatter accumulates by grid coordinate, not by array position`() {
        // The defect this exists to prevent. Two frames of the same burst come
        // back with different points in them, because the engine drops whatever
        // would not solve. Here the second frame is missing its first point, so
        // every later point shifts one slot down the array: an accumulator
        // keyed on position would pair cell 1 with cell 0, cell 2 with cell 1,
        // and so on, and would report a scatter that is really the difference
        // between two different places on the specimen.
        val accumulator = NoiseFloorProbe.SigmaAccumulator()
        // Every cell holds still — u and v are the same in all three frames —
        // so a correctly keyed accumulator reports zero scatter everywhere.
        accumulator.add(field(cells = 0 until 20) { index -> index * DISPLACEMENT_PER_CELL })
        accumulator.add(field(cells = 1 until 20) { index -> index * DISPLACEMENT_PER_CELL })
        accumulator.add(field(cells = 0 until 20) { index -> index * DISPLACEMENT_PER_CELL })

        val map = accumulator.field(imgW = 4000, imgH = 3000, step = STEP)!!
        for (i in map.points.indices step DicResult.STRIDE) {
            assertEquals("cell at ${map.points[i]}", 0.0, map.points[i + DicResult.IDX_U].toDouble(), 1e-6)
        }
    }

    @Test
    fun `a cell seen fewer than three times is left off the map`() {
        // Two samples give one difference, which drawn as a colour would read
        // as a finding rather than as the artefact of having looked twice.
        val accumulator = NoiseFloorProbe.SigmaAccumulator()
        accumulator.add(field(cells = 0 until 20) { 0f })
        accumulator.add(field(cells = 0 until 20) { 0f })
        accumulator.add(field(cells = 0 until 14) { 0f })

        val map = accumulator.field(imgW = 4000, imgH = 3000, step = STEP)!!
        assertEquals(14, map.points.size / DicResult.STRIDE)
    }

    @Test
    fun `a burst too thin to map returns no map rather than a sparse one`() {
        val accumulator = NoiseFloorProbe.SigmaAccumulator()
        repeat(3) { accumulator.add(field(cells = 0 until 5) { 0f }) }
        assertNull(accumulator.field(imgW = 4000, imgH = 3000, step = STEP))
    }

    @Test
    fun `a point the engine rejected contributes nothing`() {
        val accumulator = NoiseFloorProbe.SigmaAccumulator()
        repeat(3) { accumulator.add(field(cells = 0 until 20) { 0f }) }
        // Same cells again, wildly displaced, but marked as failed correlation.
        repeat(3) { accumulator.add(field(cells = 0 until 20, znssd = REJECTED) { 99f }) }

        val map = accumulator.field(imgW = 4000, imgH = 3000, step = STEP)!!
        assertEquals(20, map.points.size / DicResult.STRIDE)
        for (i in map.points.indices step DicResult.STRIDE) {
            assertEquals(0.0, map.points[i + DicResult.IDX_U].toDouble(), 1e-6)
        }
    }

    @Test
    fun `the field is laid out the way the visualiser reads it`() {
        // The map is drawn by VisualizationEngine.generateHeatmap with no
        // changes inside it, which only works while this layout matches: grid
        // position in IDX_X and IDX_Y, the value in IDX_U, and a ZNSSD that
        // DicResult.isAcceptedPoint accepts. A marker of 1f would fail the
        // 0.15 threshold and the map would come back empty with nothing said.
        val accumulator = NoiseFloorProbe.SigmaAccumulator()
        repeat(3) { frame -> accumulator.add(field(cells = 0 until 20) { frame * 0.01f }) }

        val map = accumulator.field(imgW = 4000, imgH = 3000, step = STEP)!!
        assertEquals(0, map.points.size % DicResult.STRIDE)
        for (i in map.points.indices step DicResult.STRIDE) {
            assertTrue("znssd", DicResult.isAcceptedPoint(map.points[i + DicResult.IDX_ZNSSD]))
            assertTrue("x", map.points[i + DicResult.IDX_X] >= 0f)
            assertTrue("sigma", map.points[i + DicResult.IDX_U] >= 0f)
        }
        assertEquals(4000, map.imgW)
        assertEquals(3000, map.imgH)
        assertEquals(STEP, map.step)
    }

    @Test
    fun `the legend ends bracket every cell on the map`() {
        val accumulator = NoiseFloorProbe.SigmaAccumulator()
        // Cell index sets how far that cell moves between frames, so the map
        // has a real spread rather than one value everywhere.
        for (frame in 0 until 4) {
            accumulator.add(field(cells = 0 until 20) { index -> index * frame * 0.001f })
        }
        val map = accumulator.field(imgW = 4000, imgH = 3000, step = STEP)!!
        val sigmas = (map.points.indices step DicResult.STRIDE)
            .map { map.points[it + DicResult.IDX_U].toDouble() }
        assertEquals(sigmas.min(), map.minSigmaPx, 1e-9)
        assertEquals(sigmas.max(), map.maxSigmaPx, 1e-9)
        assertTrue("a real spread", map.maxSigmaPx > map.minSigmaPx)
    }

    /**
     * A solved field in [DicResult]'s layout, holding [cells] laid out on a
     * grid, each displaced by [displacement] of its own index.
     *
     * The cell index is written into the grid coordinates, so a cell keeps its
     * identity across frames however the array is packed — which is exactly the
     * thing the accumulator has to get right.
     */
    private fun field(
        cells: IntRange,
        znssd: Float = ACCEPTED,
        displacement: (Int) -> Float,
    ): FloatArray {
        val points = FloatArray(cells.count() * DicResult.STRIDE)
        cells.forEachIndexed { slot, cell ->
            val at = slot * DicResult.STRIDE
            points[at + DicResult.IDX_X] = ((cell % GRID_EDGE) * STEP).toFloat()
            points[at + DicResult.IDX_Y] = ((cell / GRID_EDGE) * STEP).toFloat()
            points[at + DicResult.IDX_U] = displacement(cell)
            points[at + DicResult.IDX_V] = displacement(cell)
            points[at + DicResult.IDX_ZNSSD] = znssd
        }
        return points
    }

    private companion object {
        /** `valid_pts >= 3` in the engine's own support check. */
        const val ENGINE_MIN_SUPPORT = 3

        const val STEP = 16
        const val GRID_EDGE = 5
        const val ACCEPTED = 0.01f
        const val REJECTED = -1f

        /** Distinct per cell, so pairing the wrong two cells cannot look like zero. */
        const val DISPLACEMENT_PER_CELL = 0.1f
    }
}
