package com.indicvision.semper.results

import com.indicvision.semper.field.DicResult
import com.indicvision.semper.report.AnalysisCsvWriter
import com.indicvision.semper.report.RigidBodyFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scene-motion fit, against fields whose answer is known by construction.
 *
 * Its job is to tell a frame that moved apart from a specimen that deformed,
 * and the tests that matter are the ones where it must *not* claim motion: a
 * stretched field is not a shifted one, however large the displacements get.
 */
class RigidBodyFitTest {

    /** A 10x10 grid at 20 px spacing, displaced by [displace], all points accepted. */
    private fun field(
        znssd: Float = 0.05f,
        edge: Int = 10,
        displace: (Float, Float) -> Pair<Float, Float>,
    ): FloatArray {
        val out = FloatArray(edge * edge * DicResult.STRIDE)
        var i = 0
        for (row in 0 until edge) {
            for (col in 0 until edge) {
                val x = col * 20f
                val y = row * 20f
                val (u, v) = displace(x, y)
                out[i + DicResult.IDX_X] = x
                out[i + DicResult.IDX_Y] = y
                out[i + DicResult.IDX_U] = u
                out[i + DicResult.IDX_V] = v
                out[i + DicResult.IDX_ZNSSD] = znssd
                i += DicResult.STRIDE
            }
        }
        return out
    }

    @Test
    fun `a field that only translated reports the translation and nothing else`() {
        val fit = requireNotNull(RigidBodyFit.fit(field { _, _ -> 1.5f to -2.3f }))
        assertEquals(1.5, fit.uPx, TOLERANCE)
        assertEquals(-2.3, fit.vPx, TOLERANCE)
        assertEquals(0.0, fit.rotationDeg, TOLERANCE)
        assertEquals(0.0, fit.residualPx, TOLERANCE)
        assertEquals(2.75, fit.shiftPx(), 0.01)
    }

    @Test
    fun `a field that only rotated reports the angle and no shift`() {
        // Small-angle rotation about the grid centre (90, 90), 0.01 rad.
        val theta = 0.01f
        val fit = requireNotNull(
            RigidBodyFit.fit(field { x, y -> (-theta * (y - 90f)) to (theta * (x - 90f)) }),
        )
        assertEquals(0.0, fit.uPx, TOLERANCE)
        assertEquals(0.0, fit.vPx, TOLERANCE)
        assertEquals(Math.toDegrees(theta.toDouble()), fit.rotationDeg, 1e-3)
        assertEquals(0.0, fit.residualPx, TOLERANCE)
    }

    @Test
    fun `a stretched field is not reported as a shift`() {
        // 5 me of uniform Exx about the centre: real deformation, and the fit
        // must leave all of it in the residual rather than claim the scene moved.
        val fit = requireNotNull(RigidBodyFit.fit(field { x, _ -> (0.005f * (x - 90f)) to 0f }))
        assertEquals(0.0, fit.uPx, TOLERANCE)
        assertEquals(0.0, fit.vPx, TOLERANCE)
        assertEquals(0.0, fit.rotationDeg, TOLERANCE)
        assertTrue("residual was ${fit.residualPx}", fit.residualPx > 0.1)
        assertFalse(fit.notable())
    }

    @Test
    fun `translation on top of stretch is separated from it`() {
        val fit = requireNotNull(
            RigidBodyFit.fit(field { x, _ -> (2f + 0.005f * (x - 90f)) to 0.5f }),
        )
        assertEquals(2.0, fit.uPx, TOLERANCE)
        assertEquals(0.5, fit.vPx, TOLERANCE)
        assertTrue(fit.residualPx > 0.1)
        assertTrue(fit.notable())
    }

    @Test
    fun `a shift below a pixel is not worth a sentence`() {
        val fit = requireNotNull(RigidBodyFit.fit(field { _, _ -> 0.3f to 0.4f }))
        assertEquals(0.5, fit.shiftPx(), TOLERANCE)
        assertFalse(fit.notable())
    }

    @Test
    fun `unconverged points are left out of the fit`() {
        // Half the grid solved with a 2 px shift, half rejected with a wild one.
        val data = field { _, _ -> 2f to 0f }
        var i = 0
        var index = 0
        while (i < data.size) {
            if (index % 2 == 1) {
                data[i + DicResult.IDX_ZNSSD] = 0.9f
                data[i + DicResult.IDX_U] = 500f
            }
            index++
            i += DicResult.STRIDE
        }
        val fit = requireNotNull(RigidBodyFit.fit(data))
        assertEquals(2.0, fit.uPx, TOLERANCE)
        assertEquals(50, fit.points)
    }

    @Test
    fun `too few converged points is no measurement at all`() {
        assertNull(RigidBodyFit.fit(field(edge = 5) { _, _ -> 1f to 1f }))
        assertNull(RigidBodyFit.fit(null))
        assertNull(RigidBodyFit.fit(FloatArray(0)))
    }

    @Test
    fun `every point rejected is no measurement either`() {
        assertNull(RigidBodyFit.fit(field(znssd = 0.9f) { _, _ -> 1f to 1f }))
        assertNull(RigidBodyFit.fit(field(znssd = -1f) { _, _ -> 1f to 1f }))
    }

    @Test
    fun `the label leads with the shift`() {
        val fit = requireNotNull(RigidBodyFit.fit(field { _, _ -> 3f to 4f }))
        assertTrue(RigidBodyFit.label(fit).startsWith("5.00 px shift"))
    }

    @Test
    fun `an unmeasured frame writes empty motion fields`() {
        assertEquals(",,", AnalysisCsvWriter.motionSuffixColumns(null))
    }

    @Test
    fun `the motion suffix is three fields`() {
        val fit = requireNotNull(RigidBodyFit.fit(field { _, _ -> 1.5f to -2.25f }))
        val parts = AnalysisCsvWriter.motionSuffixColumns(fit).split(',')
        assertEquals(3, parts.size)
        assertEquals(1.5, parts[0].toDouble(), TOLERANCE)
        assertEquals(-2.25, parts[1].toDouble(), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-4
    }
}
