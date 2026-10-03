package com.sempermechanics.semper.results

import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.viewer.HeatmapFit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapFitTest {

    @Test
    fun `custom ROI wins over accepted points`() {
        val box = HeatmapFit.resolve(
            imgW = 100,
            imgH = 80,
            roiX = 10,
            roiY = 5,
            roiW = 40,
            roiH = 50,
            accepted = floatArrayOf(0f, 0f, 99f, 79f),
        )
        assertArrayEquals(floatArrayOf(10f, 5f, 50f, 55f), box, 0f)
    }

    @Test
    fun `full-frame ROI falls back to accepted then full image`() {
        val withAccepted = HeatmapFit.resolve(
            imgW = 100,
            imgH = 80,
            roiX = 0,
            roiY = 0,
            roiW = 100,
            roiH = 80,
            accepted = floatArrayOf(2f, 3f, 40f, 50f),
        )
        assertArrayEquals(floatArrayOf(2f, 3f, 40f, 50f), withAccepted, 0f)

        val full = HeatmapFit.resolve(100, 80, 0, 0, 100, 80, accepted = null)
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 80f), full, 0f)
    }

    @Test
    fun `render cap enlarges so a small fit still fills maxEdge after crop`() {
        // 1000×1000 image, 100×100 fit, maxEdge 640 → need ~6400 long-edge render.
        val cap = HeatmapFit.renderLongEdgeCap(
            imgW = 1000,
            imgH = 1000,
            fit = floatArrayOf(0f, 0f, 100f, 100f),
            maxEdge = 640,
        )
        assertEquals(6400, cap)
    }

    @Test
    fun `cropAndScale maps the fit box long edge to maxEdge`() {
        val w = 20
        val h = 10
        val indices = ByteArray(w * h) { i -> (i % 200).toByte() }
        val plane = VisualizationEngine.IndexPlane(indices, w, h, -1f, 1f)
        // Fit is the right half: 10×10 in a 20×10 plane (1:1 with image).
        val cropped = HeatmapFit.cropAndScale(
            plane,
            imgW = 20,
            imgH = 10,
            fit = floatArrayOf(10f, 0f, 20f, 10f),
            maxEdge = 40,
        )
        assertEquals(40, maxOf(cropped.width, cropped.height))
        assertEquals(40, cropped.width)
        assertEquals(40, cropped.height)
        assertTrue(cropped.indices.isNotEmpty())
    }
}
