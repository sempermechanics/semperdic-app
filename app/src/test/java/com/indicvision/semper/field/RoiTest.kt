package com.indicvision.semper.field

import android.graphics.Rect
import android.graphics.RectF
import com.indicvision.semper.ui.analysis.roi.RoiResolveHelper
import com.indicvision.semper.ui.analysis.roiPixels
import com.indicvision.semper.ui.viewer.HeatmapFit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [Roi] is a view over the ROI the code already passes around. Each helper is
 * pinned to the function it will replace, over a grid of inside, edge, off-image,
 * negative and empty inputs, so adopting it cannot change a result.
 */
@RunWith(RobolectricTestRunner::class) // Rect / RectF are stubs on the plain JVM
class RoiTest {

    private val sizes = listOf(ImageSize(640, 480), ImageSize(100, 100), ImageSize(0, 0), ImageSize(-5, 40))
    private val coords = listOf(-50, -1, 0, 1, 10, 99, 100, 300, 639, 640, 700)
    private val extents = listOf(-10, 0, 1, 20, 21, 90, 300, 640, 1000)

    /** Every pairing of this list's items with [other]'s. */
    private operator fun <A, B> List<A>.times(other: List<B>): List<Pair<A, B>> =
        flatMap { a -> other.map { b -> a to b } }

    private fun rois(): Sequence<Roi> = sequence {
        for (x in coords) for (y in coords) for (w in extents) for (h in extents) yield(Roi(x, y, w, h))
    }

    @Test
    fun `clampTo matches RoiResolveHelper clipToImage everywhere on the grid`() {
        for (size in sizes) {
            for (roi in rois()) {
                val expected = RoiResolveHelper.clipToImage(roi.x, roi.y, roi.w, roi.h, size.width, size.height)
                assertEquals("$roi on $size", expected?.toList(), roi.clampTo(size)?.toXywh()?.toList())
            }
        }
    }

    @Test
    fun `clampTo keeps edges past Int range from overflowing`() {
        val roi = Roi(Int.MAX_VALUE - 1, 0, Int.MAX_VALUE, 10)
        assertNull(roi.clampTo(ImageSize(640, 480)))
        assertEquals(Roi(10, 0, 630, 10), Roi(10, 0, Int.MAX_VALUE, 10).clampTo(ImageSize(640, 480)))
    }

    @Test
    fun `forSolve matches RoiResolveHelper resolve for custom and full-frame ROIs`() {
        val cases = listOf(1, 21, 41, 101, 301) * listOf(true, false) * sizes
        for ((subsetAndCustom, size) in cases) {
            val (subset, custom) = subsetAndCustom
            for (roi in rois().filterIndexed { i, _ -> i % 3 == 0 }) {
                val expected = RoiResolveHelper.resolve(
                    subset,
                    custom,
                    roi.x,
                    roi.y,
                    roi.w,
                    roi.h,
                    size.width,
                    size.height,
                )
                val actual = Roi.forSolve(subset, custom, roi, size)
                val label = "$roi s=$subset custom=$custom on $size"
                assertEquals(label, expected?.toList(), actual?.toXywh()?.toList())
            }
        }
    }

    @Test
    fun `the full-frame slack is RoiResolveHelper's`() {
        assertEquals(RoiResolveHelper.ROI_MARGIN_SLACK_PX, Roi.FULL_FRAME_SLACK_PX)
    }

    @Test
    fun `insetFullFrame insets by half a subset plus the slack a side`() {
        val margin = 41 / 2 + Roi.FULL_FRAME_SLACK_PX
        assertEquals(
            Roi(margin, margin, 640 - 2 * margin, 480 - 2 * margin),
            Roi.insetFullFrame(ImageSize(640, 480), 41),
        )
    }

    @Test
    fun `isCustomFor matches HeatmapFit isCustomRoi everywhere on the grid`() {
        for (size in sizes) {
            for (roi in rois()) {
                assertEquals(
                    "$roi on $size",
                    HeatmapFit.isCustomRoi(size.width, size.height, roi.x, roi.y, roi.w, roi.h),
                    roi.isCustomFor(size),
                )
            }
        }
    }

    @Test
    fun `coversFrameOf is the wizard's whole-image test, which ignores position`() {
        val size = ImageSize(640, 480)
        assertTrue(Roi.full(size).coversFrameOf(size))
        assertTrue(Roi(5, 5, 640, 480).coversFrameOf(size))
        assertFalse(Roi(0, 0, 639, 480).coversFrameOf(size))
        // The two rules disagree on a shifted full-size ROI; that is why both exist.
        assertTrue(Roi(5, 5, 640, 480).isCustomFor(size))
    }

    @Test
    fun `orFullFrame is currentSamplingRoi's choice`() {
        val size = ImageSize(640, 480)
        val drawn = Roi(10, 20, 30, 40)
        assertEquals(drawn, drawn.orFullFrame(hasCustomRoi = true, size = size))
        assertEquals(Roi.full(size), drawn.orFullFrame(hasCustomRoi = false, size = size))
        assertEquals(Roi.full(size), Roi(10, 20, 0, 40).orFullFrame(hasCustomRoi = true, size = size))
        assertEquals(Roi.full(size), Roi(10, 20, 30, -1).orFullFrame(hasCustomRoi = true, size = size))
        // No reference measured yet: nothing to sample, custom or not.
        assertNull(drawn.orFullFrame(hasCustomRoi = true, size = ImageSize.UNKNOWN))
        assertNull(drawn.orFullFrame(hasCustomRoi = false, size = ImageSize(640, 0)))
    }

    @Test
    fun `fromImageRect matches RoiDrawActivity roiPixels`() {
        val size = ImageSize(640, 480)
        val edges = listOf(-3.6f, -0.4f, 0f, 0.5f, 1.49f, 99.5f, 320.2f, 639.5f, 640f, 700.7f)
        for ((ltr, b) in edges * edges * edges * edges) {
            val (lt, r) = ltr
            val rect = RectF(lt.first, lt.second, r, b)
            val expected = roiPixels(rect, size.width, size.height)
            assertEquals("$rect", Roi.fromRect(expected), Roi.fromImageRect(rect, size))
        }
    }

    @Test
    fun `Rect and edge conversions round-trip`() {
        for (roi in rois().take(500)) {
            assertEquals(roi, Roi.fromRect(roi.toRect()))
            assertEquals(roi, Roi.fromLtrb(roi.x, roi.y, roi.right, roi.bottom))
            assertEquals(roi, Roi.fromXywh(roi.toXywh()))
        }
        assertEquals(Rect(10, 20, 40, 60), Roi(10, 20, 30, 40).toRect())
    }

    @Test
    fun `toLtrb is HeatmapFit's box for a custom ROI`() {
        val size = ImageSize(640, 480)
        val roi = Roi(10, 20, 300, 200)
        assertArrayEquals(
            HeatmapFit.resolve(size.width, size.height, roi.x, roi.y, roi.w, roi.h),
            roi.toLtrb(),
            0f,
        )
    }

    @Test
    fun `fromXywh rejects anything but four ints, as the wizard restore does`() {
        assertNull(Roi.fromXywh(null))
        assertNull(Roi.fromXywh(intArrayOf(1, 2, 3)))
        assertNull(Roi.fromXywh(intArrayOf(1, 2, 3, 4, 5)))
        assertEquals(Roi(1, 2, 3, 4), Roi.fromXywh(intArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `fits needs one subset in both directions`() {
        assertTrue(Roi(0, 0, 21, 21).fits(21))
        assertFalse(Roi(0, 0, 20, 21).fits(21))
        assertFalse(Roi(0, 0, 21, 20).fits(21))
    }
}
