package com.indicvision.semper.ui.analysis.sweep

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The sweep lattice's geometry and hit-testing: where a node lands (columns
 * for subsets, the swept VSG range framed rather than anchored at 1), and
 * which node a tap, double-tap or long-press resolves to. A wrong answer
 * opens the wrong combination in the viewer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VsgLatticeViewTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val density = context.resources.displayMetrics.density
    private lateinit var view: VsgLatticeView

    private val clicks = mutableListOf<VsgLatticeView.Node>()
    private val doubles = mutableListOf<VsgLatticeView.Node>()
    private val longs = mutableListOf<VsgLatticeView.Node>()

    /** Two subsets, three VSGs each; one combination the engine skipped. */
    private val nodes = listOf(
        node(subset = 21, vsg = 21, frame = 0),
        node(subset = 21, vsg = 41, frame = 1),
        node(subset = 21, vsg = 61, frame = 2),
        node(subset = 41, vsg = 21, frame = 3),
        node(subset = 41, vsg = 41, frame = 4),
        node(subset = 41, vsg = 61, frame = -1, solved = false),
    )

    private var clock = 0L

    @Before
    fun setUp() {
        view = VsgLatticeView(context).apply {
            compact = true
            interactionEnabled = true
            onNodeClick = { clicks += it }
            onNodeDoubleClick = { doubles += it }
            onNodeLongClick = { longs += it }
            setNodes(nodes)
        }
        layoutAndDraw(W_DP, H_DP)
        clock = SystemClock.uptimeMillis()
    }

    private fun node(subset: Int, vsg: Int, frame: Int, solved: Boolean = true) =
        VsgLatticeView.Node(subset = subset, step = 5, window = null, vsg = vsg, solved = solved, frameIndex = frame)

    private fun layoutAndDraw(wDp: Int, hDp: Int) {
        val w = (wDp * density).toInt()
        val h = (hDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        view.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
    }

    // Compact gutters: 30dp left, 14dp right, 14dp top, 20dp bottom. The swept
    // VSG range 21..61 is framed with a 12 % margin: (61 - 21) * 0.12 → 4, so
    // the y axis runs 17..65.
    private fun columnX(index: Int, columns: Int = 2): Float {
        val left = 30f * density
        val right = W_DP * density - 14f * density
        return left + (index + 0.5f) / columns * (right - left)
    }

    private fun rowY(vsg: Int, winMin: Int = 17, winMax: Int = 65): Float {
        val top = 14f * density
        val bottom = H_DP * density - 20f * density
        return bottom - (vsg - winMin).toFloat() / (winMax - winMin) * (bottom - top)
    }

    private fun event(action: Int, x: Float, y: Float, at: Long): MotionEvent =
        MotionEvent.obtain(clock, clock + at, action, x, y, 0)

    private fun tap(x: Float, y: Float, startAt: Long = 0L): Boolean {
        val down = view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, y, startAt))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x, y, startAt + 40))
        return down
    }

    /** Lets GestureDetector's delayed single-tap / long-press messages fire. */
    private fun waitOutGestureTimeouts() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getDoubleTapTimeout() + 100L))
    }

    @Test
    fun `a tap on a node's drawn position selects that combination`() {
        tap(columnX(1), rowY(41))
        waitOutGestureTimeouts()

        assertEquals(listOf(nodes[4]), clicks)
        assertTrue(doubles.isEmpty())
    }

    @Test
    fun `the swept VSG range is framed, so the ends of a column are distinct nodes`() {
        tap(columnX(0), rowY(61))
        waitOutGestureTimeouts()
        tap(columnX(0), rowY(21), startAt = 1_000)
        waitOutGestureTimeouts()

        assertEquals(listOf(nodes[2], nodes[0]), clicks)
    }

    @Test
    fun `a tap near a node resolves to the nearest one within the tolerance`() {
        // 8dp off the node centre, well inside the 22dp touch radius and far
        // closer to this node than to its column neighbours.
        tap(columnX(0) + 8f * density, rowY(41) - 8f * density)
        waitOutGestureTimeouts()

        assertEquals(listOf(nodes[1]), clicks)
    }

    @Test
    fun `a press in empty space is not claimed and fires nothing`() {
        // Between the two columns and halfway between two rows: over 22dp from
        // every node.
        val claimed = tap((columnX(0) + columnX(1)) / 2f, (rowY(21) + rowY(41)) / 2f)
        waitOutGestureTimeouts()

        assertFalse("a miss must let the parent scroll", claimed)
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun `a skipped combination is still hit, so its reason can be shown`() {
        tap(columnX(1), rowY(61))
        waitOutGestureTimeouts()

        assertEquals(1, clicks.size)
        assertFalse(clicks.single().solved)
        assertEquals(-1, clicks.single().frameIndex)
    }

    @Test
    fun `a double tap opens the node instead of focusing it`() {
        val x = columnX(0)
        val y = rowY(41)
        tap(x, y, startAt = 0)
        tap(x, y, startAt = 120)
        waitOutGestureTimeouts()

        assertEquals(listOf(nodes[1]), doubles)
        assertTrue("a double tap is not also a single tap", clicks.isEmpty())
    }

    @Test
    fun `a long press reports the node under the finger`() {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, columnX(1), rowY(21), 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))

        assertEquals(listOf(nodes[3]), longs)
    }

    @Test
    fun `the passive preview ignores taps`() {
        view.interactionEnabled = false
        tap(columnX(1), rowY(41))
        waitOutGestureTimeouts()

        assertTrue(clicks.isEmpty())
    }

    @Test
    fun `a single VSG still gets a non-empty axis and one centred column`() {
        view.setNodes(listOf(node(subset = 31, vsg = 25, frame = 0)))
        layoutAndDraw(W_DP, H_DP)

        // One value: the margin floors at 1, so the axis is 24..26 and the node
        // sits mid-height in the only column.
        tap(columnX(0, columns = 1), rowY(25, winMin = 24, winMax = 26))
        waitOutGestureTimeouts()

        assertEquals(31, clicks.single().subset)
    }

    @Test
    fun `new nodes replace the old hit targets once drawn`() {
        view.setNodes(listOf(node(subset = 99, vsg = 21, frame = 7), node(subset = 99, vsg = 61, frame = 8)))
        layoutAndDraw(W_DP, H_DP)

        tap(columnX(0, columns = 1), rowY(61))
        waitOutGestureTimeouts()

        assertEquals(8, clicks.single().frameIndex)
    }

    private companion object {
        const val W_DP = 320
        const val H_DP = 200
    }
}
