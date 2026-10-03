package com.sempermechanics.semper.ui.viewer.inspect

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The viewer's pan / zoom math: the rest pose that contains the specimen (or
 * its ROI) in the chrome-safe box, the double-tap zoom, the pan clamp that
 * keeps the image covering that box, the pinch limits, and which gestures
 * turn into frame scrubs. The matrix is read back through [getZoomMatrix],
 * the same image-space matrix the overlays and the probe use.
 *
 * Deliberately not covered here: what a resize does to a zoomed-in view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TouchImageViewTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var view: TouchImageView
    private val scrubs = mutableListOf<Int>()
    private val chromeSwipes = mutableListOf<Boolean>()
    private val taps = mutableListOf<Pair<Float, Float>>()
    private var clock = 0L

    @Before
    fun setUp() {
        // Attached to a real window: the view defers its first fit with post(),
        // which only runs once attached, as it always is in the viewer.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = TouchImageView(activity).apply {
            onScrubListener = { scrubs += it }
            onChromeSwipeListener = { chromeSwipes += it }
            onTapListener = { x, y -> taps += x to y }
        }
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(VIEW_W, VIEW_H)) })
        idle()
        check(view.width == VIEW_W && view.height == VIEW_H) { "laid out at ${view.width}x${view.height}" }
        clock = SystemClock.uptimeMillis()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A 200×100 specimen: rest scale 2 in the 400×800 view, centred vertically. */
    private fun specimen(w: Int = 200, h: Int = 100) {
        view.setTrueImageDimensions(w, h)
        idle()
    }

    private fun values(m: Matrix = view.getZoomMatrix()) = FloatArray(9).also { m.getValues(it) }
    private fun scale() = values()[Matrix.MSCALE_X]
    private fun transX() = values()[Matrix.MTRANS_X]
    private fun transY() = values()[Matrix.MTRANS_Y]

    /** Where image pixel ([x], [y]) lands in the view. */
    private fun mapped(x: Float, y: Float): Pair<Float, Float> {
        val pts = floatArrayOf(x, y)
        view.getZoomMatrix().mapPoints(pts)
        return pts[0] to pts[1]
    }

    // ── Touch plumbing ───────────────────────────────────────────────────────

    private fun send(action: Int, x: Float, y: Float, at: Long) {
        view.dispatchTouchEvent(MotionEvent.obtain(clock, clock + at, action, x, y, 0))
    }

    private fun doubleTap(x: Float, y: Float, startAt: Long = 0L) {
        send(MotionEvent.ACTION_DOWN, x, y, startAt)
        send(MotionEvent.ACTION_UP, x, y, startAt + 40)
        send(MotionEvent.ACTION_DOWN, x, y, startAt + 120)
        send(MotionEvent.ACTION_UP, x, y, startAt + 160)
        waitOutTapTimeout()
    }

    private fun waitOutTapTimeout() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getDoubleTapTimeout() + 100L))
    }

    /** One finger from ([x0], [y0]) by ([dx], [dy]) in [steps] moves over [durationMs]. */
    @Suppress("LongParameterList") // a drag's start, travel, pacing and clock offset
    private fun drag(x0: Float, y0: Float, dx: Float, dy: Float, durationMs: Long, steps: Int = 8, startAt: Long = 0L) {
        send(MotionEvent.ACTION_DOWN, x0, y0, startAt)
        for (i in 1..steps) {
            send(MotionEvent.ACTION_MOVE, x0 + dx * i / steps, y0 + dy * i / steps, startAt + durationMs * i / steps)
        }
        send(MotionEvent.ACTION_UP, x0 + dx, y0 + dy, startAt + durationMs + 1)
        waitOutTapTimeout()
    }

    /** Two fingers on a vertical line through the centre, from [fromHalf] to [toHalf] px either side. */
    private fun pinch(fromHalf: Float, toHalf: Float, startAt: Long = 0L) {
        val x = VIEW_W / 2f
        val cy = VIEW_H / 2f
        var t = startAt
        view.dispatchTouchEvent(twoFinger(MotionEvent.ACTION_DOWN, x, cy - fromHalf, cy + fromHalf, t, pointers = 1))
        t += 10
        view.dispatchTouchEvent(twoFinger(POINTER_1_DOWN, x, cy - fromHalf, cy + fromHalf, t))
        for (i in 1..8) {
            t += 16
            val half = fromHalf + (toHalf - fromHalf) * i / 8
            view.dispatchTouchEvent(twoFinger(MotionEvent.ACTION_MOVE, x, cy - half, cy + half, t))
        }
        t += 16
        view.dispatchTouchEvent(twoFinger(POINTER_1_UP, x, cy - toHalf, cy + toHalf, t))
        t += 16
        view.dispatchTouchEvent(twoFinger(MotionEvent.ACTION_UP, x, cy - toHalf, cy + toHalf, t, pointers = 1))
        waitOutTapTimeout()
    }

    @Suppress("LongParameterList") // one MotionEvent.obtain with its pointer layout
    private fun twoFinger(action: Int, x: Float, y0: Float, y1: Float, at: Long, pointers: Int = 2): MotionEvent {
        val props = Array(pointers) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointers) { i ->
            MotionEvent.PointerCoords().apply {
                this.x = x
                y = if (i == 0) y0 else y1
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(clock, clock + at, action, pointers, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    // ── Rest pose ────────────────────────────────────────────────────────────

    @Test
    fun `at rest the specimen is contained and centred in the view`() {
        specimen()
        assertEquals(2f, scale(), EPS)
        assertEquals(0f to 300f, mapped(0f, 0f))
        assertEquals(400f to 500f, mapped(200f, 100f))
    }

    @Test
    fun `chrome insets shrink the box the rest pose fits into`() {
        specimen(w = 100, h = 200) // fills 400×800 exactly at scale 4 with no insets
        view.setContentInsets(top = 100, bottom = 100)

        assertEquals(3f, scale(), EPS)
        assertEquals("framed between the bars", 50f to 100f, mapped(0f, 0f))
        assertEquals(350f to 700f, mapped(100f, 200f))
    }

    @Test
    fun `an ROI fit bound fills the box with the ROI, not the whole specimen`() {
        specimen()
        view.setFitBounds(50f, 25f, 100f, 50f)

        assertEquals(8f, scale(), EPS)
        assertEquals(0f to 300f, mapped(50f, 25f))
        assertEquals(400f to 500f, mapped(100f, 50f))
    }

    @Test
    fun `a fit bound beyond the specimen is clamped to it`() {
        specimen()
        view.setFitBounds(-50f, -50f, 1_000f, 1_000f)
        assertEquals(2f, scale(), EPS)
        assertEquals(0f to 300f, mapped(0f, 0f))
    }

    // ── Double-tap zoom and the pan clamp ───────────────────────────────────

    @Test
    fun `a double tap zooms to twice the rest scale about the tap`() {
        specimen()
        doubleTap(100f, 400f) // off-centre, so the chrome hook is not consulted

        assertEquals(4f, scale(), EPS)
        // postScale(2, 2) about (100, 400); x stays inside the clamp, and the
        // 400-px-tall image is re-centred in the 800-px box.
        assertEquals(-100f, transX(), EPS)
        assertEquals(200f, transY(), EPS)
    }

    @Test
    fun `a second double tap returns to the rest pose`() {
        specimen()
        doubleTap(100f, 400f)
        doubleTap(100f, 400f, startAt = 2_000)

        assertEquals(2f, scale(), EPS)
        assertEquals(0f to 300f, mapped(0f, 0f))
    }

    @Test
    fun `a centre double tap that shows chrome does not zoom`() {
        specimen()
        view.onCenterDoubleTapShowChrome = { true }
        doubleTap(VIEW_W / 2f, VIEW_H / 2f)
        assertEquals(2f, scale(), EPS)

        view.onCenterDoubleTapShowChrome = { false }
        doubleTap(VIEW_W / 2f, VIEW_H / 2f, startAt = 2_000)
        assertEquals("chrome already up: the usual zoom", 4f, scale(), EPS)
    }

    @Test
    fun `a zoomed image cannot be dragged off the box's edges`() {
        specimen()
        doubleTap(100f, 400f)

        drag(200f, 400f, dx = 1_000f, dy = 0f, durationMs = 400, startAt = 2_000)
        assertEquals("left edge pinned to the box", 0f, transX(), EPS)

        drag(200f, 400f, dx = -3_000f, dy = 0f, durationMs = 400, startAt = 4_000)
        assertEquals("right edge pinned to the box", VIEW_W - 800f, transX(), EPS)
        assertEquals("the short axis stays centred", 200f, transY(), EPS)
    }

    @Test
    fun `at rest a drag leaves the specimen centred`() {
        specimen()
        drag(200f, 400f, dx = 30f, dy = 60f, durationMs = 1_500)
        assertEquals(0f to 300f, mapped(0f, 0f))
    }

    // ── Pinch limits ─────────────────────────────────────────────────────────

    @Test
    fun `pinching out stops at ten times`() {
        specimen()
        repeat(4) { pinch(fromHalf = 100f, toHalf = 390f, startAt = it * 2_000L) }
        assertEquals(10f, scale(), EPS)
    }

    @Test
    fun `pinching in stops at the whole specimen, below an ROI's rest pose`() {
        specimen()
        view.setFitBounds(50f, 25f, 100f, 50f) // rest 8x; whole specimen 2x
        repeat(4) { pinch(fromHalf = 390f, toHalf = 100f, startAt = it * 2_000L) }
        assertEquals(2f, scale(), EPS)
    }

    // ── Gestures that scrub or show chrome ───────────────────────────────────

    @Test
    fun `a slow horizontal swipe at rest steps one frame`() {
        specimen()
        drag(300f, 400f, dx = -200f, dy = 0f, durationMs = 2_000)
        drag(100f, 400f, dx = 200f, dy = 0f, durationMs = 2_000, startAt = 5_000)
        assertEquals("left = next, right = previous", listOf(1, -1), scrubs)
    }

    @Test
    fun `a quick short flick at rest steps one frame`() {
        specimen()
        drag(300f, 400f, dx = -60f, dy = 0f, durationMs = 20, steps = 4)
        assertEquals(listOf(1), scrubs)
    }

    @Test
    fun `a fast long swipe is one fling and one swipe but steps one frame`() {
        specimen()
        // Past SWIPE_DISTANCE and FLING_MIN_VELOCITY at once: both paths fire on ACTION_UP.
        drag(350f, 400f, dx = -250f, dy = 0f, durationMs = 60, steps = 4)
        drag(100f, 400f, dx = 250f, dy = 0f, durationMs = 60, steps = 4, startAt = 5_000)
        assertEquals(listOf(1, -1), scrubs)
    }

    @Test
    fun `a swipe down shows chrome and a swipe up does nothing`() {
        specimen()
        drag(200f, 200f, dx = 0f, dy = 200f, durationMs = 2_000)
        drag(200f, 600f, dx = 0f, dy = -200f, durationMs = 2_000, startAt = 5_000)
        assertEquals(listOf(true), chromeSwipes)
        assertTrue(scrubs.isEmpty())
    }

    @Test
    fun `a swipe while zoomed pans instead of scrubbing`() {
        specimen()
        doubleTap(100f, 400f)
        drag(300f, 400f, dx = -200f, dy = 0f, durationMs = 2_000, startAt = 2_000)
        assertTrue(scrubs.isEmpty())
    }

    @Test
    fun `a short tap is reported once the double-tap window closes`() {
        specimen()
        send(MotionEvent.ACTION_DOWN, 120f, 340f, 0)
        send(MotionEvent.ACTION_UP, 120f, 340f, 40)
        assertTrue("not before the double-tap timeout", taps.isEmpty())
        waitOutTapTimeout()
        assertEquals(listOf(120f to 340f), taps)
    }

    // ── Display bitmap vs image space ────────────────────────────────────────

    @Test
    fun `a downsampled display bitmap is drawn in true image space`() {
        specimen()
        view.setImageBitmap(Bitmap.createBitmap(50, 25, Bitmap.Config.ARGB_8888))

        assertEquals("the logical matrix is unchanged", 2f, scale(), EPS)
        assertEquals(
            "the draw matrix scales the 4x-smaller bitmap back up",
            8f,
            values(view.imageMatrix)[Matrix.MSCALE_X],
            EPS,
        )
    }

    @Test
    fun `a view with no dimensions learns them from its drawable`() {
        view.setImageDrawable(BitmapDrawable(context.resources, Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)))
        idle()
        assertEquals(2f, scale(), EPS)
        assertEquals(0f to 300f, mapped(0f, 0f))
    }

    private fun assertEquals(expected: Pair<Float, Float>, actual: Pair<Float, Float>) =
        assertEquals(null, expected, actual)

    private fun assertEquals(message: String?, expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(message, expected.first, actual.first, EPS)
        assertEquals(message, expected.second, actual.second, EPS)
    }

    private companion object {
        const val VIEW_W = 400
        const val VIEW_H = 800
        const val EPS = 1e-3f
        const val POINTER_1_DOWN =
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        const val POINTER_1_UP =
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    }
}
