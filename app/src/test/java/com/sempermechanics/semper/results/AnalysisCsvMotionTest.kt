package com.sempermechanics.semper.results

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.AnalysisCsvWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rigid-body motion columns every analysis CSV ends with.
 *
 * They used to be written only for a session carrying a measured capture floor,
 * which tied an export column to a feature that no longer exists. The fit is
 * read off the solved field itself, so these assert it is now unconditional.
 */
class AnalysisCsvMotionTest {

    @Test
    fun `a frame with no fit still writes its three empty columns`() {
        assertEquals(",,", AnalysisCsvWriter.motionSuffixColumns(null))
    }

    @Test
    fun `the motion suffix is three fields when the fit succeeds`() {
        val data = FloatArray(10 * 10 * DicResult.STRIDE)
        var i = 0
        for (row in 0 until 10) {
            for (col in 0 until 10) {
                data[i + DicResult.IDX_X] = col * 20f
                data[i + DicResult.IDX_Y] = row * 20f
                data[i + DicResult.IDX_U] = 1.5f
                data[i + DicResult.IDX_V] = -2.25f
                data[i + DicResult.IDX_ZNSSD] = 0.05f
                i += DicResult.STRIDE
            }
        }
        val fit = com.sempermechanics.semper.report.RigidBodyFit.fit(data)
        val parts = AnalysisCsvWriter.motionSuffixColumns(fit).split(',')
        assertEquals(3, parts.size)
        assertEquals(1.5, parts[0].toDouble(), 1e-4)
        assertEquals(-2.25, parts[1].toDouble(), 1e-4)
    }

    @Test
    fun `the point header carries the motion columns for every session`() {
        assertEquals(
            "image,x_px,y_px,u_px,v_px,exx,eyy,exy,znssd,shift_u_px,shift_v_px,shift_rot_deg",
            AnalysisCsvWriter.pointHeader(sweep = false),
        )
    }

    @Test
    fun `a sweep header keeps its settings columns before the DIC ones`() {
        val header = AnalysisCsvWriter.pointHeader(sweep = true)
        assertTrue(header.startsWith("image,subset_px,step_px,strain_window,vsg_px,x_px"))
        assertTrue(header.endsWith("znssd,shift_u_px,shift_v_px,shift_rot_deg"))
    }
}
