package com.indicvision.semper.capture

import android.graphics.Rect
import android.graphics.RectF
import com.indicvision.semper.ui.analysis.SubsetRecommender
import com.indicvision.semper.ui.capture.NoiseFloorGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The coordinate carry between the speckle check and the noise-floor burst.
 *
 * These two images are taken by different cameras in every sense that matters:
 * the speckle check runs on the vendor camera app's JPEG, the burst on the
 * locked session's own output size. Carrying a pixel rectangle or a pixel
 * subset across unchanged would measure a different part of a different image,
 * which is precisely the class of defect the burst exists to avoid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoiseFloorGateTest {

    @Test
    fun `a roi survives the round trip through fractions`() {
        val roi = Rect(756, 1008, 2268, 3024)
        val norm = NoiseFloorGate.normalize(roi, 3024, 4032)
        assertEquals(roi, NoiseFloorGate.denormalize(norm, 3024, 4032))
    }

    @Test
    fun `the same fractions land on the same part of a smaller frame`() {
        // The centre quarter of a 4000 px frame is the centre quarter of a
        // 2000 px one: the mapping is a scale, not a crop.
        val norm = RectF(0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(Rect(500, 500, 1500, 1500), NoiseFloorGate.denormalize(norm, 2000, 2000))
    }

    @Test
    fun `a degenerate rect still yields a rect with area`() {
        // A zero-width ROI would make the probe allocate nothing and report a
        // floor of zero — a flattering answer from a failed measurement.
        val rect = NoiseFloorGate.denormalize(RectF(0.5f, 0.5f, 0.5f, 0.5f), 1000, 1000)
        assertTrue(rect.width() > 0)
        assertTrue(rect.height() > 0)
    }

    @Test
    fun `a roi cannot escape the frame it is mapped onto`() {
        val rect = NoiseFloorGate.denormalize(RectF(-0.5f, -0.5f, 2f, 2f), 800, 600)
        assertTrue(rect.left >= 0 && rect.top >= 0)
        assertTrue(rect.right <= 800 && rect.bottom <= 600)
    }

    @Test
    fun `a subset is a length in pixels and scales with the frame`() {
        // 47 px of speckle on a 3024 px frame is ~31 px on a 2016 px one. Left
        // unscaled it would solve for a different pattern than the one measured.
        assertEquals(
            31,
            NoiseFloorGate.rescaleSubset(47, fromWidth = 3024, fromHeight = 2268, toWidth = 2016, toHeight = 1512),
        )
    }

    @Test
    fun `a subset scales by the long edge across an orientation change`() {
        // The defect this pins: the vendor camera app returns a portrait test
        // shot while the run records landscape, and the old code compared the
        // two *widths* — a full 3072 px sensor edge against an 1600 px long
        // edge, which are not corresponding lengths. The true linear ratio is
        // 1600/4080, so a 101 px subset is 39 px on the burst frame; comparing
        // widths gives 1600/3072 and answers 53, a subset a third too large
        // that nothing downstream would have flagged.
        val portraitShot = NoiseFloorGate.rescaleSubset(
            subset = 101,
            fromWidth = 3072,
            fromHeight = 4080,
            toWidth = 1600,
            toHeight = 1200,
        )
        assertEquals(39, portraitShot)
    }

    @Test
    fun `which way up each image is cannot change the subset`() {
        // Same two images, each named the other way round. A length in pixels
        // is a length in pixels; the answer must not depend on the order the
        // dimensions arrived in.
        val all = listOf(
            NoiseFloorGate.rescaleSubset(101, 3072, 4080, 1600, 1200),
            NoiseFloorGate.rescaleSubset(101, 4080, 3072, 1600, 1200),
            NoiseFloorGate.rescaleSubset(101, 3072, 4080, 1200, 1600),
            NoiseFloorGate.rescaleSubset(101, 4080, 3072, 1200, 1600),
        )
        assertEquals("all four orientations agree", 1, all.distinct().size)
    }

    @Test
    fun `a rescaled subset stays odd`() {
        // The engine centres the subset on a pixel; an even size has no centre.
        for (from in listOf(3000, 3024, 4000)) {
            val scaled = NoiseFloorGate.rescaleSubset(
                47,
                fromWidth = from,
                fromHeight = from * 4 / 3,
                toWidth = 2048,
                toHeight = 1536,
            )
            assertTrue("$scaled from $from", scaled % 2 == 1)
        }
    }

    @Test
    fun `a rescaled subset stays inside the recommender's own range`() {
        val tiny = NoiseFloorGate.rescaleSubset(47, fromWidth = 4000, fromHeight = 3000, toWidth = 64, toHeight = 48)
        assertEquals(SubsetRecommender.MIN_SUBSET, tiny)
        val huge = NoiseFloorGate.rescaleSubset(101, fromWidth = 640, fromHeight = 480, toWidth = 8000, toHeight = 6000)
        assertEquals(SubsetRecommender.MAX_SUBSET, huge)
    }

    @Test
    fun `an unknown source size leaves the subset alone rather than guessing`() {
        assertEquals(47, NoiseFloorGate.rescaleSubset(47, 0, 0, toWidth = 2048, toHeight = 1536))
        assertEquals(47, NoiseFloorGate.rescaleSubset(47, 3024, 2268, toWidth = 0, toHeight = 0))
    }
}
