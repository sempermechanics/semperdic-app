package com.sempermechanics.semper.ui.analysis.roi

import android.app.Application
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ROI editor's zoom and pan: a 200×100 preview in a 400×400 view fits at
 * (0, 100)-(400, 300). Every ROI the overlay reports is mapped through these
 * bounds, so they must be exact, and the photo must never leave the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoiViewportTest {

    private val viewport = RoiViewport().apply { layout(200f, 100f, 400f, 400f) }

    private fun bounds() = viewport.bounds(RectF())

    private fun assertRect(expected: RectF, actual: RectF) {
        assertEquals("left of $actual", expected.left, actual.left, EPS)
        assertEquals("top of $actual", expected.top, actual.top, EPS)
        assertEquals("right of $actual", expected.right, actual.right, EPS)
        assertEquals("bottom of $actual", expected.bottom, actual.bottom, EPS)
    }

    @Test
    fun `at rest the image is fit and centred`() {
        assertFalse(viewport.isZoomed)
        assertRect(RectF(0f, 100f, 400f, 300f), bounds())
    }

    @Test
    fun `a zoom keeps the image point under the fingers where it was`() {
        // View (100, 150) is image fraction (0.25, 0.25).
        viewport.zoomBy(2f, 100f, 150f)
        val b = bounds()
        assertEquals(100f, b.left + 0.25f * b.width(), EPS)
        assertEquals(800f, b.width(), EPS)
        // 400 tall now fills the view exactly, so it cannot sit anywhere but 0..400.
        assertRect(RectF(-100f, 0f, 700f, 400f), b)
    }

    @Test
    fun `zoom stays between fit and the ceiling`() {
        viewport.zoomBy(0.2f, 200f, 200f)
        assertEquals(1f, viewport.zoom, EPS)
        viewport.zoomBy(100f, 200f, 200f)
        assertEquals(RoiViewport.MAX_ZOOM, viewport.zoom, EPS)
    }

    @Test
    fun `a pan cannot pull the photo off the screen`() {
        viewport.zoomBy(2f, 200f, 200f) // (-200, 0)-(600, 400)
        viewport.panBy(1_000f, 1_000f)
        assertRect(RectF(0f, 0f, 800f, 400f), bounds())
        viewport.panBy(-5_000f, -5_000f)
        assertRect(RectF(-400f, 0f, 400f, 400f), bounds())
    }

    @Test
    fun `a lift's correction does not pull the photo off an edge it was pushed against`() {
        viewport.zoomBy(2f, 200f, 200f) // (-200, 0)-(600, 400)
        viewport.beginPan()
        viewport.panBy(250f, 0f) // stops 50 px short: left edge on 0
        viewport.settleBy(-4f, 0f)
        assertRect(RectF(0f, 0f, 800f, 400f), bounds())
    }

    @Test
    fun `a lift's correction moves the photo once the stopped travel is used up`() {
        viewport.zoomBy(2f, 200f, 200f) // (-200, 0)-(600, 400)
        viewport.beginPan()
        viewport.panBy(203f, 0f) // 3 px stopped
        viewport.settleBy(-5f, 0f)
        assertRect(RectF(-2f, 0f, 798f, 400f), bounds())
    }

    @Test
    fun `a move back clears the stopped travel, so a later correction moves the photo`() {
        viewport.zoomBy(2f, 200f, 200f) // (-200, 0)-(600, 400)
        viewport.beginPan()
        viewport.panBy(250f, 0f)
        viewport.panBy(-30f, 0f) // the photo follows at once
        viewport.settleBy(-4f, 0f)
        assertRect(RectF(-34f, 0f, 766f, 400f), bounds())
    }

    @Test
    fun `a new pan starts with nothing stopped`() {
        viewport.zoomBy(2f, 200f, 200f) // (-200, 0)-(600, 400)
        viewport.panBy(250f, 0f)
        viewport.beginPan()
        viewport.settleBy(-4f, 0f)
        assertRect(RectF(-4f, 0f, 796f, 400f), bounds())
    }

    @Test
    fun `a side narrower than the view stays centred`() {
        viewport.zoomBy(1.5f, 200f, 200f) // 600 × 300: taller view, so y is letterboxed
        viewport.panBy(0f, 500f)
        val b = bounds()
        assertEquals(50f, b.top, EPS)
        assertEquals(350f, b.bottom, EPS)
    }

    @Test
    fun `a canvas resize keeps the zoom and the point in the middle`() {
        viewport.zoomBy(4f, 200f, 200f)
        viewport.panBy(300f, 0f)
        val before = bounds()
        val middle = (200f - before.left) / before.width()

        viewport.layout(200f, 100f, 400f, 237f) // keyboard up
        val after = bounds()

        assertEquals(4f, viewport.zoom, EPS)
        assertEquals(middle, (200f - after.left) / after.width(), 1e-4f)
    }

    @Test
    fun `double-tap zooms in about the tap and back to fit`() {
        viewport.toggle(100f, 200f)
        assertTrue(viewport.isZoomed)
        assertEquals(RoiViewport.DOUBLE_TAP_ZOOM, viewport.zoom, EPS)

        viewport.toggle(100f, 200f)
        assertFalse(viewport.isZoomed)
        assertRect(RectF(0f, 100f, 400f, 300f), bounds())
    }

    @Test
    fun `before a layout nothing moves`() {
        val fresh = RoiViewport()
        fresh.zoomBy(3f, 10f, 10f)
        fresh.panBy(10f, 10f)
        assertEquals(1f, fresh.zoom, EPS)
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
