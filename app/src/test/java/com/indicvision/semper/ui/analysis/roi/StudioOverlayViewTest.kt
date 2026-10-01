package com.indicvision.semper.ui.analysis.roi

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ROI editor's coordinate mapping: a 200×100 preview letterboxed into a
 * 400×400 view (image drawn at y 100..300, two view px per preview px) stands
 * for a 2000×1000 photo, so one view px is five image px. Every ROI the engine
 * gets goes through this mapping, and the mask it writes marks which pixels
 * are correlated — an off-by-a-scale here is a wrong analysis, not a glitch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioOverlayViewTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var image: ImageView
    private lateinit var overlay: StudioOverlayView
    private val reported = mutableListOf<RectF>()
    private var clock = 0L

    @Before
    fun setUp() {
        image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(BitmapDrawable(context.resources, Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)))
        }
        layout(image, 400, 400)
        overlay = StudioOverlayView(context).apply {
            realImageWidth = 2000
            realImageHeight = 1000
            onRoiChangedListener = { reported += RectF(it) }
        }
        layout(overlay, 400, 400)
        overlay.imageView = image
        clock = SystemClock.uptimeMillis()
    }

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, w, h)
    }

    private fun touch(action: Int, x: Float, y: Float) {
        overlay.onTouchEvent(MotionEvent.obtain(clock, clock, action, x, y, 0))
    }

    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float) {
        touch(MotionEvent.ACTION_DOWN, x0, y0)
        touch(MotionEvent.ACTION_MOVE, (x0 + x1) / 2f, (y0 + y1) / 2f)
        touch(MotionEvent.ACTION_MOVE, x1, y1)
        touch(MotionEvent.ACTION_UP, x1, y1)
    }

    /** One event with a finger at each of [points]; pointer ids are their indices. */
    private fun fingers(action: Int, vararg points: Pair<Float, Float>, at: Long = clock) {
        val props = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].first
                y = points[i].second
                pressure = 1f
                size = 1f
            }
        }
        overlay.onTouchEvent(
            MotionEvent.obtain(clock, at, action, points.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0),
        )
    }

    private val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private val secondUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** Two fingers from [a0], [b0] to [a1], [b1], then both lift. */
    private fun pinch(a0: Pair<Float, Float>, b0: Pair<Float, Float>, a1: Pair<Float, Float>, b1: Pair<Float, Float>) {
        fingers(MotionEvent.ACTION_DOWN, a0)
        fingers(secondDown, a0, b0)
        fingers(MotionEvent.ACTION_MOVE, a1, b1)
        fingers(secondUp, a1, b1)
        fingers(MotionEvent.ACTION_UP, a1)
    }

    /** Spreads two fingers about the view centre from 50 to 100 px apart: 2x about (200, 200). */
    private fun zoomTwiceAtCentre() = pinch(150f to 200f, 250f to 200f, 100f to 200f, 300f to 200f)

    private fun tap(x: Float, y: Float, at: Long) {
        fingers(MotionEvent.ACTION_DOWN, x to y, at = at)
        fingers(MotionEvent.ACTION_UP, x to y, at = at + 30)
    }

    private fun assertRect(expected: RectF, actual: RectF) {
        assertEquals("left of $actual", expected.left, actual.left, EPS)
        assertEquals("top of $actual", expected.top, actual.top, EPS)
        assertEquals("right of $actual", expected.right, actual.right, EPS)
        assertEquals("bottom of $actual", expected.bottom, actual.bottom, EPS)
    }

    // ── Typed (manual) ROI ───────────────────────────────────────────────────

    @Test
    fun `a typed ROI round-trips to the same image pixels`() {
        assertTrue(overlay.applyImageRoi(100, 200, 500, 300))

        assertTrue(overlay.hasValidRoi)
        assertRect(RectF(100f, 200f, 600f, 500f), overlay.getRelativeRoi())
        assertRect(RectF(100f, 200f, 600f, 500f), reported.last())
    }

    @Test
    fun `a typed ROI running off the image is clipped to it`() {
        assertTrue(overlay.applyImageRoi(1900, 900, 500, 500))
        assertRect(RectF(1900f, 900f, 2000f, 1000f), overlay.getRelativeRoi())
    }

    @Test
    fun `a typed ROI with no size is refused`() {
        assertFalse(overlay.applyImageRoi(10, 10, 0, 50))
        assertFalse(overlay.applyImageRoi(10, 10, 50, -1))
        assertFalse(overlay.hasValidRoi)
    }

    @Test
    fun `a typed ROI before the image is laid out is refused`() {
        val early = StudioOverlayView(context).apply {
            realImageWidth = 2000
            realImageHeight = 1000
        }
        assertFalse(early.applyImageRoi(0, 0, 100, 100))
    }

    // ── Drawn ROI ────────────────────────────────────────────────────────────

    @Test
    fun `a drawn ROI is reported in image pixels`() {
        drag(50f, 150f, 250f, 250f)

        assertTrue(overlay.hasValidRoi)
        // View (50,150)-(250,250), image top at y=100, five image px per view px.
        assertRect(RectF(250f, 250f, 1250f, 750f), overlay.getRelativeRoi())
    }

    @Test
    fun `drawing outside the letterboxed image is clamped to it`() {
        drag(-20f, 0f, 500f, 500f)
        assertRect(RectF(0f, 0f, 2000f, 1000f), overlay.getRelativeRoi())
    }

    @Test
    fun `a drag under fifty view px each way leaves no ROI`() {
        drag(100f, 150f, 140f, 190f)
        assertFalse(overlay.hasValidRoi)
    }

    @Test
    fun `dragging inside the ROI moves it, but never off the image`() {
        overlay.applyImageRoi(0, 0, 500, 500) // view (0,100)-(100,200)
        drag(50f, 150f, 1_050f, 150f)
        assertRect(RectF(1500f, 0f, 2000f, 500f), overlay.getRelativeRoi())
    }

    @Test
    fun `a corner drag resizes the ROI down to the fifty px minimum`() {
        overlay.applyImageRoi(0, 0, 500, 500) // bottom-right handle at view (100, 200)
        drag(100f, 200f, 10f, 110f)
        assertRect(RectF(0f, 0f, 250f, 250f), overlay.getRelativeRoi())
    }

    @Test
    fun `in square mode a corner drag keeps the ROI square`() {
        overlay.currentMode = StudioOverlayView.RoiMode.SQUARE
        overlay.applyImageRoi(0, 0, 500, 500)
        drag(100f, 200f, 200f, 220f) // wider than tall

        val roi = overlay.getRelativeRoi()
        assertRect(RectF(0f, 0f, 1000f, 1000f), roi)
        assertEquals(roi.width(), roi.height(), EPS)
    }

    // ── Erase holes ──────────────────────────────────────────────────────────

    @Test
    fun `erase mode punches a hole and keeps the crop`() {
        overlay.applyImageRoi(0, 0, 1000, 1000)
        overlay.isSubtractMode = true
        drag(20f, 120f, 120f, 170f)

        assertTrue(overlay.hasValidRoi)
        assertEquals(1, overlay.holes.size)
        assertRect(RectF(100f, 100f, 600f, 350f), overlay.lastHoleRelative())
        assertRect(RectF(0f, 0f, 1000f, 1000f), overlay.getRelativeRoi())
    }

    @Test
    fun `a typed hole maps like a typed ROI`() {
        assertTrue(overlay.applyImageHole(300, 400, 200, 100))
        assertRect(RectF(300f, 400f, 500f, 500f), overlay.lastHoleRelative())
    }

    @Test
    fun `a fresh crop drag starts over, dropping earlier holes`() {
        overlay.applyImageHole(300, 400, 200, 100)
        drag(50f, 150f, 250f, 250f)

        assertTrue(overlay.holes.isEmpty())
        assertTrue(overlay.lastHoleRelative().isEmpty)
    }

    @Test
    fun `reset clears everything and reports an empty ROI`() {
        overlay.applyImageRoi(0, 0, 500, 500)
        overlay.applyImageHole(100, 100, 100, 100)
        overlay.reset()

        assertFalse(overlay.hasValidRoi)
        assertTrue(overlay.holes.isEmpty())
        assertTrue(reported.last().isEmpty)
    }

    // ── Layout changes and restore ───────────────────────────────────────────

    @Test
    fun `a new letterbox keeps the ROI and holes on the same image pixels`() {
        overlay.applyImageRoi(100, 200, 500, 300)
        overlay.applyImageHole(300, 400, 200, 100)

        layout(image, 400, 200) // toolbar or IME resize: image now fills y 0..200
        overlay.updateImageBounds()

        assertRect(RectF(100f, 200f, 600f, 500f), overlay.getRelativeRoi())
        assertRect(RectF(300f, 400f, 500f, 500f), overlay.lastHoleRelative())
    }

    @Test
    fun `keyboard open and close cycles leave the ROI and holes where they were`() {
        overlay.applyImageRoi(101, 203, 499, 297)
        overlay.applyImageHole(301, 401, 199, 97)

        // The ROI dock grows by the keyboard height and shrinks back, each time
        // refitting the image; odd heights give scales that are not whole numbers.
        repeat(5) {
            layout(image, 400, 237)
            overlay.updateImageBounds()
            layout(image, 400, 400)
            overlay.updateImageBounds()
        }

        assertRect(RectF(101f, 203f, 600f, 500f), overlay.getRelativeRoi())
        assertRect(RectF(301f, 401f, 500f, 498f), overlay.lastHoleRelative())
    }

    @Test
    fun `a canvas squeezed to nothing keeps the ROI for when it regrows`() {
        overlay.applyImageRoi(100, 200, 500, 300)
        overlay.applyImageHole(300, 400, 200, 100)

        layout(image, 400, 0) // keyboard plus dock taller than the screen
        overlay.updateImageBounds()
        layout(image, 400, 300)
        overlay.updateImageBounds()

        assertRect(RectF(100f, 200f, 600f, 500f), overlay.getRelativeRoi())
        assertRect(RectF(300f, 400f, 500f, 500f), overlay.lastHoleRelative())
    }

    @Test
    fun `a saved ROI restored before layout is applied once the image is ready`() {
        val restored = StudioOverlayView(context).apply {
            realImageWidth = 2000
            realImageHeight = 1000
        }
        restored.restoreRelativeRoi(RectF(100f, 200f, 600f, 500f))
        assertFalse("nothing to map onto yet", restored.hasValidRoi)

        layout(restored, 400, 400)
        restored.imageView = image

        assertTrue(restored.hasValidRoi)
        assertRect(RectF(100f, 200f, 600f, 500f), restored.getRelativeRoi())
    }

    // ── Zoom and pan ─────────────────────────────────────────────────────────

    @Test
    fun `a pinch zooms about the fingers and the ROI stays on its image pixels`() {
        overlay.applyImageRoi(100, 200, 500, 300)
        zoomTwiceAtCentre()

        assertEquals(2f, overlay.zoom, EPS)
        assertTrue(overlay.hasValidRoi)
        assertRect(RectF(100f, 200f, 600f, 500f), overlay.getRelativeRoi())
    }

    @Test
    fun `the photo is drawn through the same rect as the overlay`() {
        zoomTwiceAtCentre() // image now (-200, 0)-(600, 400)

        val drawn = RectF(0f, 0f, 200f, 100f)
        Matrix(image.imageMatrix).mapRect(drawn)
        assertRect(RectF(-200f, 0f, 600f, 400f), drawn)
    }

    @Test
    fun `a pan ends where the fingers lift, not where the last move overshot`() {
        zoomTwiceAtCentre() // image now (-200, 0)-(600, 400)
        fingers(MotionEvent.ACTION_DOWN, 200f to 150f)
        fingers(secondDown, 200f to 150f, 200f to 250f)
        // Input resampling can put the last MOVE a few px past the fingers.
        fingers(MotionEvent.ACTION_MOVE, 146f to 150f, 146f to 250f)
        fingers(secondUp, 150f to 150f, 150f to 250f)
        fingers(MotionEvent.ACTION_UP, 150f to 150f)

        assertEquals(2f, overlay.zoom, EPS)
        val drawn = RectF(0f, 0f, 200f, 100f)
        Matrix(image.imageMatrix).mapRect(drawn)
        assertRect(RectF(-250f, 0f, 550f, 400f), drawn)
    }

    @Test
    fun `a pan pushed against the edge stays on it when the fingers lift`() {
        zoomTwiceAtCentre() // image now (-200, 0)-(600, 400)
        fingers(MotionEvent.ACTION_DOWN, 100f to 150f)
        fingers(secondDown, 100f to 150f, 100f to 250f)
        // 254 px right against 200 px of room; the lift is 4 px back from the last MOVE.
        fingers(MotionEvent.ACTION_MOVE, 354f to 150f, 354f to 250f)
        fingers(secondUp, 350f to 150f, 350f to 250f)
        fingers(MotionEvent.ACTION_UP, 350f to 150f)

        val drawn = RectF(0f, 0f, 200f, 100f)
        Matrix(image.imageMatrix).mapRect(drawn)
        assertRect(RectF(0f, 0f, 800f, 400f), drawn)
    }

    @Test
    fun `zoomed in, a drawn ROI is finer in image pixels`() {
        zoomTwiceAtCentre() // 2.5 image px per view px, image left edge at view x -200
        drag(0f, 0f, 200f, 200f)
        assertRect(RectF(500f, 0f, 1000f, 500f), overlay.getRelativeRoi())
    }

    @Test
    fun `the first finger of a pinch does not wipe the ROI or its holes`() {
        overlay.applyImageRoi(100, 200, 500, 300)
        overlay.applyImageHole(150, 250, 100, 100)
        // Lands outside the ROI, where one finger alone would start a new crop.
        pinch(300f to 250f, 350f to 250f, 280f to 250f, 370f to 250f)

        assertTrue(overlay.hasValidRoi)
        assertEquals(1, overlay.holes.size)
        assertRect(RectF(100f, 200f, 600f, 500f), overlay.getRelativeRoi())
    }

    @Test
    fun `a grab that turns into a pinch puts the ROI back`() {
        overlay.applyImageRoi(100, 200, 500, 300) // view (20, 140)-(120, 200)
        fingers(MotionEvent.ACTION_DOWN, 70f to 170f)
        fingers(MotionEvent.ACTION_MOVE, 90f to 170f)
        fingers(secondDown, 90f to 170f, 200f to 250f)

        assertRect(RectF(100f, 200f, 600f, 500f), overlay.getRelativeRoi())
        assertRect(RectF(100f, 200f, 600f, 500f), reported.last())
    }

    @Test
    fun `a stray tap outside the ROI keeps it`() {
        overlay.applyImageRoi(100, 200, 500, 300)
        tap(300f, 250f, clock)

        assertTrue(overlay.hasValidRoi)
        assertRect(RectF(100f, 200f, 600f, 500f), overlay.getRelativeRoi())
    }

    @Test
    fun `double-tap zooms to 2x and double-tap again fits, keeping the ROI`() {
        overlay.applyImageRoi(100, 200, 500, 300)
        tap(200f, 200f, clock)
        tap(200f, 200f, clock + 150)
        assertEquals(RoiViewport.DOUBLE_TAP_ZOOM, overlay.zoom, EPS)

        tap(200f, 200f, clock + 1_000)
        tap(200f, 200f, clock + 1_150)
        assertEquals(1f, overlay.zoom, EPS)

        assertTrue(overlay.hasValidRoi)
        assertRect(RectF(100f, 200f, 600f, 500f), overlay.getRelativeRoi())
    }

    @Test
    fun `a zoomed canvas keeps its zoom through a keyboard resize`() {
        overlay.applyImageRoi(101, 203, 499, 297)
        zoomTwiceAtCentre()

        repeat(3) {
            layout(image, 400, 237)
            overlay.updateImageBounds()
            layout(image, 400, 400)
            overlay.updateImageBounds()
        }

        assertEquals(2f, overlay.zoom, EPS)
        assertRect(RectF(101f, 203f, 600f, 500f), overlay.getRelativeRoi())
    }

    @Test
    fun `resetZoom goes back to the fit view`() {
        zoomTwiceAtCentre()
        overlay.resetZoom()

        assertEquals(1f, overlay.zoom, EPS)
        drag(50f, 150f, 250f, 250f)
        assertRect(RectF(250f, 250f, 1250f, 750f), overlay.getRelativeRoi())
    }

    // ── Mask ─────────────────────────────────────────────────────────────────

    /** Mask at preview resolution (200×100, half a view px per image px) so every byte is checkable. */
    private fun smallMask(configure: StudioOverlayView.() -> Unit): Pair<ByteArray, Int> {
        overlay.realImageWidth = 200
        overlay.realImageHeight = 100
        overlay.configure()
        val bytes = overlay.generateMaskBytes()
        return bytes to bytes.size / 100
    }

    private fun ByteArray.at(stride: Int, x: Int, y: Int): Int = this[y * stride + x].toInt() and 0xFF

    @Test
    fun `the mask covers the whole photo and marks the crop as correlated`() {
        val (mask, stride) = smallMask { applyImageRoi(20, 10, 100, 50) }

        assertTrue("one row per image row, at least one byte per pixel", stride >= 200)
        assertEquals(255, mask.at(stride, 20, 10))
        assertEquals(255, mask.at(stride, 119, 59))
    }

    @Test
    fun `outside the crop stays correlated so edge subsets keep their points`() {
        // The ROI rect bounds the grid; the engine drops any point whose subset
        // touches a void pixel, so a void background would eat the crop's edge (TD-74).
        val (mask, stride) = smallMask { applyImageRoi(20, 10, 100, 50) }

        assertEquals(255, mask.at(stride, 0, 0))
        assertEquals(255, mask.at(stride, 19, 30))
        assertEquals(255, mask.at(stride, 120, 30))
        assertEquals(255, mask.at(stride, 60, 60))
        assertEquals(255, mask.at(stride, 199, 99))
    }

    @Test
    fun `a hole voids its pixels inside the crop`() {
        val (mask, stride) = smallMask {
            applyImageRoi(0, 0, 200, 100)
            applyImageHole(50, 25, 20, 10)
        }

        assertEquals(0, mask.at(stride, 55, 30))
        assertEquals(255, mask.at(stride, 49, 30))
        assertEquals(255, mask.at(stride, 70, 30))
    }

    @Test
    fun `holes with no crop mean the full image minus the holes`() {
        val (mask, stride) = smallMask { applyImageHole(0, 0, 10, 10) }

        assertEquals(0, mask.at(stride, 5, 5))
        assertEquals(255, mask.at(stride, 150, 80))
    }

    private companion object {
        const val EPS = 1e-2f
    }
}
