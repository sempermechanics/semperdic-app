package com.indicvision.semper.analysis

import android.graphics.Rect
import com.indicvision.semper.ui.analysis.recommend.NoiseFloorProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        // 15 px was the analysis default VSG the probe then borrowed.
        val fixedVsg = 15
        val step = NoiseFloorProbe.probeStepFor(Rect(0, 0, 2400, 1800), subset = 47)
        assertTrue("step should be coarse here", step > fixedVsg)
        assertEquals(1, supportPoints(step, fixedVsg))
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

    private companion object {
        /** `valid_pts >= 3` in the engine's own support check. */
        const val ENGINE_MIN_SUPPORT = 3
    }
}
