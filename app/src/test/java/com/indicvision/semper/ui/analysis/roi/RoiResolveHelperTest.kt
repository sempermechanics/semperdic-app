package com.indicvision.semper.ui.analysis.roi

import com.indicvision.semper.ui.analysis.recommend.SubsetRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rectangle the engine is handed. A custom ROI outlives the image it was
 * drawn on (it is kept across a same-size reference and restored after a
 * process death), so it is clipped to the image, never trusted to fit.
 */
class RoiResolveHelperTest {

    /** [roi] is `[x, y, w, h]` on a 640 x 480 image unless [image] says otherwise. */
    private fun resolve(
        vararg roi: Int,
        custom: Boolean = true,
        subset: Int = 21,
        image: Pair<Int, Int> = 640 to 480,
    ) = RoiResolveHelper.resolve(subset, custom, roi[0], roi[1], roi[2], roi[3], image.first, image.second)?.toList()

    @Test
    fun `a custom ROI inside the image is solved as drawn`() {
        assertEquals(listOf(10, 20, 300, 200), resolve(10, 20, 300, 200))
    }

    @Test
    fun `a custom ROI past the right and bottom edges is clipped to the image`() {
        // Drawn on a 1000 x 800 reference, then a 640 x 480 one was loaded.
        assertEquals(listOf(400, 300, 240, 180), resolve(400, 300, 600, 500))
    }

    @Test
    fun `a custom ROI starting off the image is clipped at the origin`() {
        assertEquals(listOf(0, 0, 90, 80), resolve(-10, -20, 100, 100))
    }

    @Test
    fun `a custom ROI wholly off the image cannot be solved`() {
        assertNull(resolve(700, 10, 100, 100))
    }

    @Test
    fun `a clipped ROI narrower than one subset cannot be solved`() {
        assertNull(resolve(630, 0, 100, 100, subset = 21))
    }

    @Test
    fun `a custom ROI with no image size cannot be solved`() {
        assertNull(resolve(0, 0, 100, 100, image = 0 to 0))
    }

    @Test
    fun `the full frame is inset by half a subset plus the slack`() {
        val margin = 21 / 2 + RoiResolveHelper.ROI_MARGIN_SLACK_PX
        assertEquals(
            listOf(margin, margin, 640 - 2 * margin, 480 - 2 * margin),
            resolve(0, 0, 0, 0, custom = false),
        )
    }

    @Test
    fun `the subset cap reads a custom ROI clipped to the image`() {
        // A stale 2000 px ROI on a 150 px tall image: the image decides.
        val cap = RoiResolveHelper.maxSubsetForRoi(
            hasCustomRoi = true,
            roiX = 0,
            roiY = 0,
            roiW = 2000,
            roiH = 2000,
            realRefWidth = 640,
            realRefHeight = 150,
        )
        assertEquals(111, cap)
    }

    @Test
    fun `the subset cap clips a custom ROI where it sits, not from the corner`() {
        // 500 px wide from x = 600 on a 640 px image: only 40 px of it is
        // inside, so the cap bottoms out. Clipped from (0, 0) it read 100 px.
        val cap = RoiResolveHelper.maxSubsetForRoi(
            hasCustomRoi = true,
            roiX = 600,
            roiY = 0,
            roiW = 500,
            roiH = 100,
            realRefWidth = 640,
            realRefHeight = 480,
        )
        assertEquals(SubsetRecommender.MIN_SUBSET, cap)
    }
}
