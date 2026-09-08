package com.indicvision.semper.analysis

import com.indicvision.semper.ui.analysis.SpeckleScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The speckle-size measurement, against patterns whose dot size is known
 * because this test drew them.
 *
 * The autocorrelation-to-diameter constant is derived for an ideal disc and
 * real speckle is not that, so the derivation alone is not evidence. These
 * cases are the evidence: seeded fields of discs at four sizes, measured back
 * through the same path the app uses.
 */
class SpeckleScaleTest {

    @Test
    fun `a seeded pattern measures back at the dot size it was drawn with`() {
        for (drawn in intArrayOf(3, 5, 7, 11)) {
            val measured = SpeckleScale.diameterPx(speckleField(edge = 256, dotDiameter = drawn, seed = 7))
            assertNotNull("$drawn px dots measured nothing", measured)
            // Tolerance grows with the dot, because overlap does: discs
            // thrown down at random merge, and merged discs decorrelate
            // sooner than isolated ones. What the measurement has to get
            // right is which side of 3 px and 9 px the pattern sits on, not
            // its third decimal place.
            assertEquals("drawn $drawn px", drawn.toDouble(), measured!!, maxOf(1.0, drawn * 0.2))
        }
    }

    @Test
    fun `a coarser pattern always measures larger than a finer one`() {
        // The property the resolution recommendation actually rests on: the
        // ordering must be right even where the absolute value drifts.
        val sizes = intArrayOf(3, 5, 7, 11, 15)
            .map { SpeckleScale.diameterPx(speckleField(256, it, seed = 3)) ?: 0.0 }
        for (i in 1 until sizes.size) {
            assertTrue("${sizes[i - 1]} then ${sizes[i]}", sizes[i] > sizes[i - 1])
        }
    }

    @Test
    fun `a flat window is not a speckle of infinite size`() {
        assertNull(SpeckleScale.diameterPx(FloatArray(64 * 64) { 128f }))
    }

    @Test
    fun `a window too small to measure returns nothing`() {
        assertNull(SpeckleScale.diameterPx(speckleField(edge = 8, dotDiameter = 3, seed = 1)))
    }

    @Test
    fun `a non-square window returns nothing`() {
        assertNull(SpeckleScale.diameterPx(FloatArray(100 * 50)))
    }

    @Test
    fun `no window is not a measurement`() {
        assertNull(SpeckleScale.diameterPx(null))
    }

    @Test
    fun `a smooth gradient has no speckle to measure`() {
        // Correlated with itself at every lag the window allows: reporting a
        // number here would be reporting the window's size, not a speckle's.
        // A ramp is what an out-of-focus or unpatterned specimen looks like.
        val edge = 64
        assertNull(SpeckleScale.diameterPx(FloatArray(edge * edge) { (it % edge).toFloat() }))
    }

    @Test
    fun `the same speckle on a smaller frame is proportionally smaller`() {
        assertEquals(4.0, SpeckleScale.scaledTo(16.0, fromLongEdge = 4000, toLongEdge = 1000)!!, 1e-9)
        assertEquals(16.0, SpeckleScale.scaledTo(4.0, fromLongEdge = 1000, toLongEdge = 4000)!!, 1e-9)
    }

    @Test
    fun `an unknown frame size leaves the speckle unscaled rather than guessing`() {
        assertNull(SpeckleScale.scaledTo(16.0, fromLongEdge = 0, toLongEdge = 1000))
        assertNull(SpeckleScale.scaledTo(16.0, fromLongEdge = 1000, toLongEdge = 0))
        assertNull(SpeckleScale.scaledTo(Double.NaN, fromLongEdge = 1000, toLongEdge = 1000))
    }

    /**
     * A square field of randomly placed discs of one diameter, on a mid-grey
     * ground — the closest thing to a sprayed speckle pattern that can be
     * generated with a known answer.
     *
     * Coverage is held near half so the pattern has as much edge as possible,
     * which is what a good speckle is trying to achieve and what the
     * autocorrelation is measuring.
     */
    private fun speckleField(edge: Int, dotDiameter: Int, seed: Int): FloatArray {
        val pixels = FloatArray(edge * edge) { BACKGROUND }
        val random = Random(seed)
        val radius = dotDiameter / 2.0
        val area = Math.PI * radius * radius
        val dots = ((edge * edge * COVERAGE) / area.coerceAtLeast(1.0)).roundToInt().coerceAtLeast(1)
        repeat(dots) {
            val cx = random.nextDouble(edge.toDouble())
            val cy = random.nextDouble(edge.toDouble())
            val span = kotlin.math.ceil(radius).toInt() + 1
            for (y in (cy - span).toInt()..(cy + span).toInt()) {
                for (x in (cx - span).toInt()..(cx + span).toInt()) {
                    if (x !in 0 until edge || y !in 0 until edge) continue
                    val dx = x + 0.5 - cx
                    val dy = y + 0.5 - cy
                    if (dx * dx + dy * dy <= radius * radius) pixels[y * edge + x] = FOREGROUND
                }
            }
        }
        return pixels
    }

    private companion object {
        const val BACKGROUND = 30f
        const val FOREGROUND = 225f

        /** Fraction of the field the dots aim to cover before overlap. */
        const val COVERAGE = 0.5
    }
}
