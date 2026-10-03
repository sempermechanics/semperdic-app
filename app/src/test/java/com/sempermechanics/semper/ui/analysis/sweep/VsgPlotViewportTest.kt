package com.sempermechanics.semper.ui.analysis.sweep

import android.app.Application
import android.graphics.RectF
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The VSG plot's data-space window, moved out of the view: the full extent of
 * the series, the zoom and pan clamping, and the px-to-data mapping. A
 * 100×100 px plot area shows data 0..10 on both axes unless zoomed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VsgPlotViewportTest {

    private val full = PlotBounds(0f, 10f, 0f, 10f)
    private val frame = RectF(0f, 0f, 100f, 100f)

    private fun series(vararg points: Pair<Float, Float>) = VsgPlotView.Series("s", 0, points.toList())

    private fun assertBounds(xMin: Float, xMax: Float, yMin: Float, yMax: Float, actual: PlotBounds?) {
        requireNotNull(actual)
        assertEquals("xMin", xMin, actual.xMin, EPS)
        assertEquals("xMax", xMax, actual.xMax, EPS)
        assertEquals("yMin", yMin, actual.yMin, EPS)
        assertEquals("yMax", yMax, actual.yMax, EPS)
    }

    // ── plotBoundsOf ──

    @Test
    fun `no points have no extent`() {
        assertNull(plotBoundsOf(emptyList(), MARGIN))
        assertNull(plotBoundsOf(listOf(series()), MARGIN))
    }

    @Test
    fun `the extent spans every series with head-room above and below`() {
        val bounds = plotBoundsOf(listOf(series(0f to 0f, 4f to 1f), series(10f to 5f)), MARGIN)
        assertBounds(0f, 10f, -0.4f, 5.4f, bounds)
    }

    @Test
    fun `a single point still gets a unit-wide x and a unit y span`() {
        assertBounds(3f, 4f, -0.08f, 0.08f, plotBoundsOf(listOf(series(3f to 0f)), MARGIN))
    }

    @Test
    fun `a flat non-zero line gets head-room in proportion to its value`() {
        assertBounds(0f, 1f, 1.9872f, 2.0128f, plotBoundsOf(listOf(series(0f to 2f, 1f to 2f)), MARGIN))
    }

    // ── VsgPlotViewport ──

    @Test
    fun `with no zoom the full extent is shown`() {
        assertSame(full, VsgPlotViewport(MIN_SPAN).visible(full))
    }

    @Test
    fun `a pinch zooms about its focus`() {
        val viewport = VsgPlotViewport(MIN_SPAN)
        viewport.zoomAbout(full, 50f, 50f, 2f, frame)
        assertBounds(2.5f, 7.5f, 2.5f, 7.5f, viewport.visible(full))
    }

    @Test
    fun `zoom stops at the minimum span`() {
        val viewport = VsgPlotViewport(MIN_SPAN)
        viewport.zoomAbout(full, 50f, 50f, 1000f, frame)
        assertBounds(4.75f, 5.25f, 4.75f, 5.25f, viewport.visible(full))
    }

    @Test
    fun `a pan follows the fingers`() {
        val viewport = VsgPlotViewport(MIN_SPAN)
        viewport.zoomAbout(full, 50f, 50f, 2f, frame)
        // Fingers left and down: the data moves with them, so the window moves right and up.
        viewport.panByPx(full, -20f, 20f, frame)
        assertBounds(3.5f, 8.5f, 3.5f, 8.5f, viewport.visible(full))
    }

    @Test
    fun `a pan stops at the edge of the data`() {
        val viewport = VsgPlotViewport(MIN_SPAN)
        viewport.zoomAbout(full, 50f, 50f, 2f, frame)
        viewport.panByPx(full, -1000f, 1000f, frame)
        assertBounds(5f, 10f, 5f, 10f, viewport.visible(full))
    }

    @Test
    fun `reset goes back to the full extent`() {
        val viewport = VsgPlotViewport(MIN_SPAN)
        viewport.zoomAbout(full, 50f, 50f, 2f, frame)
        viewport.reset()
        assertSame(full, viewport.visible(full))
    }

    @Test
    fun `px map to data clamped to the frame, y counting up from the bottom`() {
        assertEquals(0f, pxToDataX(-50f, frame, full), EPS)
        assertEquals(2.5f, pxToDataX(25f, frame, full), EPS)
        assertEquals(10f, pxToDataX(150f, frame, full), EPS)
        assertEquals(10f, pxToDataY(0f, frame, full), EPS)
        assertEquals(7.5f, pxToDataY(25f, frame, full), EPS)
        assertEquals(0f, pxToDataY(200f, frame, full), EPS)
    }

    // ── interpolateY and the pointer focus ──

    @Test
    fun `interpolateY is linear between points and holds the ends past them`() {
        val points = listOf(0f to 0f, 2f to 4f, 4f to 0f)
        fun y(x: Float) = requireNotNull(interpolateY(points, x))
        assertNull(interpolateY(emptyList(), 1f))
        assertEquals(0f, y(-1f), EPS)
        assertEquals(2f, y(1f), EPS)
        assertEquals(3f, y(2.5f), EPS)
        assertEquals(0f, y(9f), EPS)
    }

    @Test
    fun `the pointer focus is the fingers' mean position`() {
        val props = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i } }
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = 10f
                y = 20f
            },
            MotionEvent.PointerCoords().apply {
                x = 30f
                y = 60f
            },
        )
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        assertEquals(20f, event.focusX(), EPS)
        assertEquals(40f, event.focusY(), EPS)
    }

    private companion object {
        const val MARGIN = 0.08f
        const val MIN_SPAN = 0.05f
        const val EPS = 1e-4f
    }
}
