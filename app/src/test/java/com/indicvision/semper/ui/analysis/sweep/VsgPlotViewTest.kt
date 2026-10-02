package com.indicvision.semper.ui.analysis.sweep

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the strain plot reports back: the value under the scrub line (the
 * readout the guide's conclusions are read from), and the data-space viewport
 * a pinch or pan leaves behind. Everything is read through [VsgPlotView.onScrub]
 * and [VsgPlotView.scrubToFraction], so no assertion depends on the gutter
 * widths in pixels. The gutter's own tests call [VsgPlotAxes.compactLeftPad]
 * with a phone's font metrics, which Robolectric's text measuring does not have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VsgPlotViewTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var view: VsgPlotView

    private data class Scrub(val x: Float, val samples: List<VsgPlotView.Sample>)

    private val scrubs = mutableListOf<Scrub>()
    private val fractions = mutableListOf<Float>()

    private val rising = VsgPlotView.Series("rising", Color.RED, listOf(0f to 0f, 10f to 10f, 20f to 40f))
    private val flat = VsgPlotView.Series("flat", Color.BLUE, listOf(5f to 3f, 15f to 3f))
    private val muted = VsgPlotView.Series("muted", Color.GRAY, listOf(0f to 100f, 20f to 100f), muted = true)

    private var clock = 0L

    @Before
    fun setUp() {
        view = VsgPlotView(context).apply {
            onScrub = { x, samples -> scrubs += Scrub(x, samples) }
            onScrubMove = { fractions += it }
            setData(listOf(rising, flat, muted), "VSG", "strain")
        }
        layoutAndDraw()
        clock = SystemClock.uptimeMillis()
    }

    private fun layoutAndDraw(w: Int = W, h: Int = H) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        redraw()
    }

    /** The plot area is fixed on draw; a pinch changes the viewport, the next draw uses it. */
    private fun redraw() {
        view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
    }

    /** Data-x of the current viewport's left and right edges. */
    private fun viewportX(): Pair<Float, Float> {
        view.scrubToFraction(0f)
        val lo = scrubs.last().x
        view.scrubToFraction(1f)
        val hi = scrubs.last().x
        return lo to hi
    }

    private fun sampleOf(label: String): Float = scrubs.last().samples.single { it.label == label }.value

    // ── Scrub readout ────────────────────────────────────────────────────────

    @Test
    fun `nothing is reported before the plot has been laid out and drawn`() {
        val fresh = VsgPlotView(context).apply {
            onScrub = { x, samples -> scrubs += Scrub(x, samples) }
            setData(listOf(rising), "x", "y")
        }
        fresh.scrubToFraction(0.5f)
        assertTrue(scrubs.isEmpty())
    }

    @Test
    fun `the scrub interpolates each visible curve linearly`() {
        view.scrubToFraction(0.25f) // x = 5 on a 0..20 extent

        assertEquals(5f, scrubs.last().x, EPS)
        assertEquals(5f, sampleOf("rising"), EPS)
        assertEquals(3f, sampleOf("flat"), EPS)
        view.scrubToFraction(0.75f) // x = 15, between (10,10) and (20,40)
        assertEquals(25f, sampleOf("rising"), EPS)
    }

    @Test
    fun `muted background curves never appear in the readout`() {
        view.scrubToFraction(0.5f)
        assertEquals(listOf("rising", "flat"), scrubs.last().samples.map { it.label })
    }

    @Test
    fun `each sample carries the colour its curve is drawn in`() {
        view.scrubToFraction(0.5f)
        assertEquals(Color.RED, scrubs.last().samples.single { it.label == "rising" }.color)
        assertEquals(Color.BLUE, scrubs.last().samples.single { it.label == "flat" }.color)
    }

    @Test
    fun `past a curve's own ends the readout holds its end value`() {
        view.scrubToFraction(0f) // x = 0, before "flat" starts at 5
        assertEquals(3f, sampleOf("flat"), EPS)
        view.scrubToFraction(1f) // x = 20, after "flat" ends at 15
        assertEquals(3f, sampleOf("flat"), EPS)
        assertEquals(40f, sampleOf("rising"), EPS)
    }

    @Test
    fun `a fraction outside 0 to 1 is clamped to the viewport`() {
        view.scrubToFraction(-1f)
        assertEquals(0f, scrubs.last().x, EPS)
        view.scrubToFraction(3f)
        assertEquals(20f, scrubs.last().x, EPS)
        assertEquals(listOf(0f, 1f), fractions)
    }

    @Test
    fun `a single x value still gets a unit-wide axis`() {
        view.setData(listOf(VsgPlotView.Series("one", Color.RED, listOf(7f to 2f))), "x", "y")
        redraw()
        assertEquals(7f to 8f, viewportX())
        assertEquals(2f, sampleOf("one"), EPS)
    }

    @Test
    fun `new data clears the scrub line`() {
        view.scrubToFraction(0.5f)
        view.setData(listOf(rising), "x", "y")
        val before = scrubs.size

        // Lifting a finger clears a live scrub by reporting NaN; nothing is
        // live after setData, so nothing is reported.
        view.onTouchEvent(event(MotionEvent.ACTION_CANCEL, 0f, 0f))
        assertEquals(before, scrubs.size)
    }

    // ── Touch scrub ──────────────────────────────────────────────────────────

    private fun event(action: Int, x: Float, y: Float, at: Long = 0L): MotionEvent =
        MotionEvent.obtain(clock, clock + at, action, x, y, 0)

    @Test
    fun `a finger dragged right scrubs right, and lifting it clears the readout`() {
        val y = H / 2f
        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_DOWN, W * 0.4f, y)))
        val first = scrubs.last().x
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, W * 0.6f, y, 16))
        val second = scrubs.last().x
        view.onTouchEvent(event(MotionEvent.ACTION_UP, W * 0.6f, y, 32))

        assertTrue("$first < $second", first < second)
        assertTrue(fractions[0] < fractions[1])
        assertTrue("lifting reports NaN", scrubs.last().x.isNaN())
        assertTrue(scrubs.last().samples.isEmpty())
        assertTrue("the slider is sent back to rest", fractions.last().isNaN())
    }

    @Test
    fun `a drag past the plot's edge pins the scrub to the last x`() {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, W / 2f, H / 2f))
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, W * 5f, H / 2f, 16))
        assertEquals(20f, scrubs.last().x, EPS)
    }

    @Test
    fun `a press in the right-hand gutter is not claimed`() {
        // PAD_RIGHT_DP leaves the last dp columns outside the plot area.
        assertFalse(view.onTouchEvent(event(MotionEvent.ACTION_DOWN, W - 1f, H / 2f)))
        assertTrue(scrubs.isEmpty())
    }

    // ── Pinch-zoom viewport ──────────────────────────────────────────────────

    /** Two fingers either side of [focusX], spread from [fromHalf] to [toHalf] px apart. */
    private fun pinch(focusX: Float, fromHalf: Float, toHalf: Float, steps: Int = 6) {
        val y = H / 2f
        var t = 0L
        view.onTouchEvent(twoFinger(MotionEvent.ACTION_DOWN, focusX - fromHalf, focusX + fromHalf, y, t, pointers = 1))
        t += 10
        view.onTouchEvent(twoFinger(POINTER_1_DOWN, focusX - fromHalf, focusX + fromHalf, y, t))
        for (i in 1..steps) {
            t += 16
            val half = fromHalf + (toHalf - fromHalf) * i / steps
            view.onTouchEvent(twoFinger(MotionEvent.ACTION_MOVE, focusX - half, focusX + half, y, t))
        }
        t += 16
        view.onTouchEvent(twoFinger(POINTER_1_UP, focusX - toHalf, focusX + toHalf, y, t))
        t += 16
        view.onTouchEvent(twoFinger(MotionEvent.ACTION_UP, focusX - toHalf, focusX + toHalf, y, t, pointers = 1))
        redraw()
    }

    /** Two fingers a fixed distance apart, both moved by [dx]: a pan, not a scale. */
    private fun twoFingerPan(dx: Float) {
        val y = H / 2f
        val a = W * 0.4f
        val b = W * 0.6f
        var t = 1_000L
        view.onTouchEvent(twoFinger(MotionEvent.ACTION_DOWN, a, b, y, t, pointers = 1))
        t += 10
        view.onTouchEvent(twoFinger(POINTER_1_DOWN, a, b, y, t))
        for (i in 1..4) {
            t += 16
            view.onTouchEvent(twoFinger(MotionEvent.ACTION_MOVE, a + dx * i / 4, b + dx * i / 4, y, t))
        }
        t += 16
        view.onTouchEvent(twoFinger(POINTER_1_UP, a + dx, b + dx, y, t))
        t += 16
        view.onTouchEvent(twoFinger(MotionEvent.ACTION_UP, a + dx, b + dx, y, t, pointers = 1))
        redraw()
    }

    @Suppress("LongParameterList") // one MotionEvent.obtain with its pointer layout
    private fun twoFinger(action: Int, x0: Float, x1: Float, y: Float, at: Long, pointers: Int = 2): MotionEvent {
        val props = Array(pointers) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointers) { i ->
            MotionEvent.PointerCoords().apply {
                x = if (i == 0) x0 else x1
                this.y = y
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(clock, clock + at, action, pointers, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    @Test
    fun `a spread pinch narrows the x viewport inside the data extent`() {
        view.zoomEnabled = true
        pinch(focusX = W / 2f, fromHalf = 100f, toHalf = 230f)

        val (lo, hi) = viewportX()
        assertTrue("zoomed in: span ${hi - lo}", hi - lo < 20f * 0.6f)
        assertTrue("stays inside the data: $lo..$hi", lo >= 0f && hi <= 20f)
    }

    @Test
    fun `zoom never goes past five percent of the extent`() {
        view.zoomEnabled = true
        repeat(5) { pinch(focusX = W / 2f, fromHalf = 100f, toHalf = 230f) }

        val (lo, hi) = viewportX()
        assertEquals(20f * 0.05f, hi - lo, 1e-3f)
    }

    @Test
    fun `a pan cannot drag the viewport past the data`() {
        view.zoomEnabled = true
        pinch(focusX = W / 2f, fromHalf = 100f, toHalf = 230f)
        val (lo, hi) = viewportX()

        twoFingerPan(dx = W * 4f) // fingers right → content right → viewport left
        val (panLo, panHi) = viewportX()

        assertEquals("clamped at the data's left edge", 0f, panLo, EPS)
        assertEquals("the span survives the clamp", hi - lo, panHi - panLo, 1e-3f)
    }

    @Test
    fun `a double tap resets a zoomed plot to the full extent`() {
        view.zoomEnabled = true
        pinch(focusX = W / 2f, fromHalf = 100f, toHalf = 230f)
        val x = W / 2f
        val y = H / 2f
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, y, 5_000))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x, y, 5_040))
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, y, 5_120))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x, y, 5_160))

        assertEquals(0f to 20f, viewportX())
    }

    @Test
    fun `a pinch sends the slider back to rest, even one the slider had set`() {
        view.zoomEnabled = true
        view.scrubToFraction(0.8f)
        pinch(focusX = W / 2f, fromHalf = 100f, toHalf = 230f)

        assertTrue("slider left at ${fractions.last()}", fractions.last().isNaN())
        assertTrue(scrubs.last().x.isNaN())
    }

    @Test
    fun `a double tap leaves the slider at rest`() {
        view.zoomEnabled = true
        val y = H / 2f
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, W * 0.7f, y))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, W * 0.7f, y, 40))
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, W * 0.7f, y, 120))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, W * 0.7f, y, 160))

        assertTrue("slider left at ${fractions.last()}", fractions.last().isNaN())
    }

    @Test
    fun `with zoom off a pinch leaves the viewport alone`() {
        pinch(focusX = W / 2f, fromHalf = 100f, toHalf = 230f)
        assertEquals(0f to 20f, viewportX())
    }

    @Test
    fun `new data keeps the zoom only when asked to`() {
        view.zoomEnabled = true
        pinch(focusX = W / 2f, fromHalf = 100f, toHalf = 230f)
        val zoomed = viewportX()

        view.setData(listOf(rising, flat), "VSG", "strain", preserveViewport = true)
        redraw()
        assertEquals(zoomed, viewportX())

        view.setData(listOf(rising, flat), "VSG", "strain")
        redraw()
        assertEquals(0f to 20f, viewportX())
    }

    // ── Tick labels ──────────────────────────────────────────────────────────

    @Test
    fun `a tick that rounds to zero never reads -0`() {
        assertEquals("0.00", VsgPlotAxes.tickLabel(-0.001f))
        assertEquals("0.00", VsgPlotAxes.tickLabel(-0f))
        assertEquals("-0.26", VsgPlotAxes.tickLabel(-0.26f))
        assertEquals("-1.8", VsgPlotAxes.tickLabel(-1.8f))
        assertEquals("-150", VsgPlotAxes.tickLabel(-150f))
        assertEquals("1962", VsgPlotAxes.tickLabel(1962f))
    }

    @Test
    fun `the scrub label sits clear of its line, flipping left near the edge`() {
        // Plot 100..900, label 200 wide, 10 clear of the dot.
        assertEquals(410f, VsgPlotAxes.scrubLabelX(400f, 200f, 10f, 100f, 900f), EPS)
        assertEquals(700f, VsgPlotAxes.scrubLabelX(690f, 200f, 10f, 100f, 900f), EPS) // just fits
        assertEquals(640f, VsgPlotAxes.scrubLabelX(850f, 200f, 10f, 100f, 900f), EPS)
        // Too wide for either side: held at the left edge.
        assertEquals(100f, VsgPlotAxes.scrubLabelX(150f, 900f, 10f, 100f, 900f), EPS)
    }

    /** Pixel 6 (2.625 dp/px): 11 sp monospace, whose glyphs advance 0.6 em. */
    private val pixel6Px = 2.625f
    private val monoChar = VsgPlotView.AXIS_LABEL_SP * pixel6Px * MONO_ADVANCE_EM
    private val measureMono: (String) -> Float = { it.length * monoChar }
    private val minPad = VsgPlotView.PAD_LEFT_COMPACT_DP * pixel6Px
    private val gap = VsgPlotView.TICK_GAP_DP * pixel6Px

    @Test
    fun `a bending load axis's widest tick fits inside the compact gutter`() {
        // concrete_00 on a Pixel 6 (2026-09-26): the fixed 34 dp gutter showed "1686" for 21686.
        // Fractional, as the padded data bounds are: the middle tick lands on 7855.3, not 7855.5.
        val (yMin, yMax) = -5975.4f to 21686f
        val labels = (0..VsgPlotView.GRID_LINES).map { VsgPlotAxes.tickLabel(VsgPlotAxes.yTick(yMin, yMax, it)) }
        assertEquals(listOf("-5975", "940", "7855", "14771", "21686"), labels)
        assertTrue("the old fixed gutter clipped it", measureMono("21686") + gap > minPad)

        val pad = VsgPlotAxes.compactLeftPad(yMin, yMax, minPad, gap, measureMono)

        labels.forEach { label ->
            // drawGridTicks right-aligns each tick at left - gap, so its left edge is here.
            val leftEdge = pad - gap - measureMono(label)
            assertTrue("\"$label\" starts at $leftEdge px, off the view", leftEdge >= 0f)
        }
    }

    @Test
    fun `ticks that fit keep the compact gutter's minimum`() {
        // "1.0" … "5.0": three characters, well inside 34 dp.
        assertEquals(minPad, VsgPlotAxes.compactLeftPad(1f, 5f, minPad, gap, measureMono), EPS)
    }

    // ── Export and palette ───────────────────────────────────────────────────

    @Test
    fun `the export renders at the asked size and restores the on-screen layout`() {
        val bitmap = view.renderToBitmap(1600, 1000)

        assertEquals(1600, bitmap.width)
        assertEquals(1000, bitmap.height)
        assertEquals(W, view.width)
        assertEquals(H, view.height)
    }

    @Test
    fun `palette slots wrap for any index, including a skipped node's -1`() {
        assertEquals(VsgPlotView.paletteColor(context, 7), VsgPlotView.paletteColor(context, -1))
        assertEquals(VsgPlotView.paletteColor(context, 0), VsgPlotView.paletteColor(context, 8))
        assertEquals(VsgPlotView.lineCutColor(context, 0), VsgPlotView.lineCutColor(context, 3))
        val slots = (0 until 3).map { VsgPlotView.lineCutColor(context, it) }
        assertEquals("the three concurrent line-cut curves are distinct", 3, slots.toSet().size)
    }

    private companion object {
        const val W = 480
        const val H = 320
        const val EPS = 1e-4f
        const val MONO_ADVANCE_EM = 0.6f
        const val POINTER_1_DOWN =
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        const val POINTER_1_UP =
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    }
}
