@file:Suppress("MagicNumber")

package com.indicvision.semper.analysis

import android.graphics.Rect
import com.indicvision.semper.ui.analysis.recommend.SubsetRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * [SubsetRecommender.recommend] samples only inside the ROI. A beam's side
 * face is a thin band on a plain background; patches that spilled past the
 * ROI measured the band's edge as a speckle ~70 px across.
 */
@RunWith(RobolectricTestRunner::class)
class SubsetRecommenderRoiTest {

    private val width = 400
    private val height = 300
    private val bandTop = 100
    private val bandBottom = 160

    /** Dark background with a speckled band — 2 px dots — as a raw RGBA buffer. */
    private fun beamImage(): ByteArray {
        val rng = Random(11)
        val cellsPerRow = width / 2
        val cells = IntArray((bandBottom - bandTop) / 2 * cellsPerRow) { if (rng.nextBoolean()) 230 else 30 }
        val grey = IntArray(width * height) { i ->
            val y = i / width
            if (y in bandTop until bandBottom) cells[(y - bandTop) / 2 * cellsPerRow + (i % width) / 2] else 40
        }
        val rgba = ByteArray(width * height * 4)
        grey.forEachIndexed { i, v ->
            rgba[i * 4] = v.toByte()
            rgba[i * 4 + 1] = v.toByte()
            rgba[i * 4 + 2] = v.toByte()
            rgba[i * 4 + 3] = 255.toByte()
        }
        return rgba
    }

    @Test
    fun `a thin ROI on a band measures the band's speckle, not its edge`() {
        val roi = Rect(10, bandTop + 4, width - 10, bandBottom - 4)

        val result = SubsetRecommender.recommend(beamImage(), width, height, roi)

        assertNotNull(result)
        val diameter = result!!.speckleDiameterPx
        assertNotNull(diameter)
        assertTrue("speckle $diameter px", diameter!! < 6.0)
    }

    /**
     * Before an ROI is drawn the whole frame is sampled, and the patches in
     * the top and bottom rows are mostly dark background with the specimen's
     * edge across them. Their median read ~100 px on the steel set; only the
     * patches that carry the pattern may speak for it.
     */
    @Test
    fun `the whole frame measures the specimen's speckle, not the background`() {
        val frameW = 480
        val frameH = 436
        val rng = Random(5)
        // A soft pattern on a bright bar, as on the steel set: the bar's edge
        // against the dark surround is a bigger step than any speckle.
        val cells = IntArray(frameW / 2 * frameH / 2) { if (rng.nextBoolean()) 170 else 110 }
        val rgba = ByteArray(frameW * frameH * 4)
        for (i in 0 until frameW * frameH) {
            val x = i % frameW
            val y = i / frameW
            // A dim, slowly shaded background, like the dark surround of a photo.
            val v = if (y in 70 until 366) cells[y / 2 * (frameW / 2) + x / 2] else 15 + x / 48
            rgba[i * 4] = v.toByte()
            rgba[i * 4 + 1] = v.toByte()
            rgba[i * 4 + 2] = v.toByte()
            rgba[i * 4 + 3] = 255.toByte()
        }

        val result = SubsetRecommender.recommend(rgba, frameW, frameH, Rect(0, 0, frameW, frameH))

        val diameter = result?.speckleDiameterPx
        assertNotNull(diameter)
        assertTrue("speckle $diameter px", diameter!! < 6.0)
    }

    @Test
    fun `only the textured patches speak for the speckle`() {
        val samples = listOf(
            550.0 to 4.8,
            500.0 to 4.7,
            470.0 to 4.9,
            300.0 to 102.0,
            290.0 to 118.0,
            560.0 to null,
        )

        assertEquals(4.8, SubsetRecommender.texturedSpeckleMedian(samples)!!, 1e-9)
        assertNull(SubsetRecommender.texturedSpeckleMedian(emptyList()))
    }
}
