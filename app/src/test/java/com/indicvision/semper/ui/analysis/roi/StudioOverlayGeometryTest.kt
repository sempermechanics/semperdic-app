package com.indicvision.semper.ui.analysis.roi

import android.app.Application
import android.graphics.RectF
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.Roi
import com.indicvision.semper.ui.analysis.roi.StudioOverlayView.TouchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ROI overlay's pure geometry, moved out of the view: what a finger grabs,
 * how a drag moves or resizes a rect, and the view-px / image-px mapping. The
 * view-level behaviour stays covered by [StudioOverlayViewTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StudioOverlayGeometryTest {

    private val rect = RectF(100f, 100f, 300f, 200f)

    private fun assertRect(expected: RectF, actual: RectF?) {
        requireNotNull(actual) { "expected $expected, got null" }
        assertEquals("left of $actual", expected.left, actual.left, EPS)
        assertEquals("top of $actual", expected.top, actual.top, EPS)
        assertEquals("right of $actual", expected.right, actual.right, EPS)
        assertEquals("bottom of $actual", expected.bottom, actual.bottom, EPS)
    }

    // ── hitState ──

    @Test
    fun `each corner grabs within the slop`() {
        assertEquals(TouchState.TOP_LEFT, hitState(rect, 110f, 90f, SLOP))
        assertEquals(TouchState.TOP_RIGHT, hitState(rect, 290f, 110f, SLOP))
        assertEquals(TouchState.BOTTOM_LEFT, hitState(rect, 100f, 200f, SLOP))
        assertEquals(TouchState.BOTTOM_RIGHT, hitState(rect, 300f, 200f, SLOP))
    }

    @Test
    fun `inside the rect but clear of the corners grabs the body`() {
        assertEquals(TouchState.CENTER, hitState(rect, 200f, 150f, SLOP))
        // Exactly the slop away is no longer a corner.
        assertEquals(TouchState.CENTER, hitState(rect, 145f, 100f, SLOP))
    }

    @Test
    fun `outside the rect and its corners grabs nothing`() {
        assertNull(hitState(rect, 400f, 400f, SLOP))
    }

    @Test
    fun `on a rect smaller than the slop the top-left corner wins`() {
        assertEquals(TouchState.TOP_LEFT, hitState(RectF(0f, 0f, 20f, 20f), 10f, 10f, SLOP))
    }

    // ── dragRect, safeCoerce and makeSquare ──

    private val bounds = RectF(0f, 0f, 400f, 300f)

    @Test
    fun `the body slides but never leaves the bounds`() {
        val target = RectF(100f, 100f, 200f, 200f)
        dragRect(target, TouchState.CENTER, 500f, -500f, bounds, MIN_SIZE, square = false)
        assertRect(RectF(300f, 0f, 400f, 100f), target)
    }

    @Test
    fun `a corner resizes no smaller than the minimum`() {
        val target = RectF(100f, 100f, 200f, 200f)
        dragRect(target, TouchState.TOP_LEFT, 80f, 80f, bounds, MIN_SIZE, square = false)
        assertRect(RectF(150f, 150f, 200f, 200f), target)
    }

    @Test
    fun `a square corner drag squares on the longer side about the opposite corner`() {
        val target = RectF(100f, 100f, 200f, 150f)
        dragRect(target, TouchState.BOTTOM_RIGHT, 50f, 0f, bounds, MIN_SIZE, square = true)
        assertRect(RectF(100f, 100f, 250f, 250f), target)
    }

    @Test
    fun `a square corner drag stops at the bounds`() {
        val target = RectF(100f, 100f, 200f, 150f)
        dragRect(target, TouchState.BOTTOM_RIGHT, 50f, 0f, RectF(0f, 0f, 400f, 200f), MIN_SIZE, square = true)
        assertRect(RectF(100f, 100f, 200f, 200f), target)
    }

    @Test
    fun `no grab and a drawing finger leave the rect alone`() {
        for (state in listOf(TouchState.NONE, TouchState.DRAWING)) {
            val target = RectF(100f, 100f, 200f, 200f)
            dragRect(target, state, 30f, 30f, bounds, MIN_SIZE, square = true)
            assertRect(RectF(100f, 100f, 200f, 200f), target)
        }
    }

    @Test
    fun `safeCoerce pins to the minimum once the range has closed`() {
        assertEquals(5f, safeCoerce(5f, 0f, 10f), 0f)
        assertEquals(10f, safeCoerce(15f, 0f, 10f), 0f)
        assertEquals(10f, safeCoerce(5f, 10f, 0f), 0f)
    }

    @Test
    fun `makeSquare grows up and left from a bottom-right pivot`() {
        val target = RectF(150f, 120f, 200f, 200f)
        makeSquare(200f, 200f, bounds, target, MIN_SIZE)
        assertRect(RectF(120f, 120f, 200f, 200f), target)
    }

    // ── Image px ↔ view px ──

    /** A 2000×1000 photo drawn into a 400×200 rect at y 100: five image px per view px. */
    private val image = ImageSize(2000, 1000)
    private val drawn = RectF(0f, 100f, 400f, 300f)

    @Test
    fun `a typed rect maps onto the drawn image`() {
        assertRect(RectF(20f, 140f, 120f, 200f), imageRectInView(Roi(100, 200, 500, 300), image, drawn))
    }

    @Test
    fun `an unknown image or an empty rect maps to nothing`() {
        assertNull(imageRectInView(Roi(100, 200, 500, 300), ImageSize.UNKNOWN, drawn))
        assertNull(imageRectInView(Roi(100, 200, 0, 300), image, drawn))
        assertNull(imageRectInView(Roi(100, 200, 500, -1), image, drawn))
    }

    @Test
    fun `a rect starting off the image is pulled on and keeps its size`() {
        // Not Roi.clampTo's clip, which would leave it 400 x 250.
        assertRect(RectF(0f, 100f, 100f, 160f), imageRectInView(Roi(-100, -50, 500, 300), image, drawn))
    }

    @Test
    fun `a rect running off the far edges is cut at them`() {
        assertRect(RectF(360f, 280f, 400f, 300f), imageRectInView(Roi(1800, 900, 500, 300), image, drawn))
    }

    @Test
    fun `a width that overflows an Int is refused`() {
        assertNull(imageRectInView(Roi(10, 10, Int.MAX_VALUE, 10), image, drawn))
    }

    @Test
    fun `view px and image px round-trip`() {
        val view = RectF(20f, 140f, 120f, 200f)
        val inImage = viewToImage(view, drawn, 5f)
        assertRect(RectF(100f, 200f, 600f, 500f), inImage)
        assertRect(view, imageToView(inImage, drawn, 5f))
    }

    private companion object {
        const val SLOP = 45f
        const val MIN_SIZE = 50f
        const val EPS = 1e-3f
    }
}
