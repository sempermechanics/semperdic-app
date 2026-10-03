package com.sempermechanics.semper.ui.analysis.roi

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
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
 * The ROI editor's zoom and pan gestures on their own: which touches they take
 * from one-finger editing, when they cancel an edit, and what they do to the
 * viewport. A 200×100 image fits a 400×400 view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StudioOverlayViewportTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewport = RoiViewport()
    private var cancels = 0
    private var moves = 0
    private lateinit var gestures: StudioOverlayViewport
    private var clock = 0L

    @Before
    fun setUp() {
        viewport.layout(200f, 100f, 400f, 400f)
        gestures = StudioOverlayViewport(context, viewport, cancelEdit = { cancels++ }, onMoved = { moves++ })
        clock = SystemClock.uptimeMillis()
    }

    /** Feeds one event with a finger at each of [points]; returns whether the gestures took it. */
    private fun fingers(action: Int, vararg points: Pair<Float, Float>, at: Long = clock): Boolean {
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
        return gestures.onTouchEvent(
            MotionEvent.obtain(clock, at, action, points.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0),
        )
    }

    private val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private val secondUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    @Test
    fun `one finger is left to editing`() {
        assertFalse(fingers(MotionEvent.ACTION_DOWN, 100f to 100f))
        assertFalse(fingers(MotionEvent.ACTION_MOVE, 150f to 150f))
        assertFalse(fingers(MotionEvent.ACTION_UP, 150f to 150f))
        assertEquals(0, cancels)
        assertEquals(0, moves)
        assertEquals(1f, viewport.zoom, EPS)
    }

    @Test
    fun `a second finger takes the touch, cancels the edit once and pinches`() {
        assertFalse(fingers(MotionEvent.ACTION_DOWN, 150f to 200f))
        assertTrue(fingers(secondDown, 150f to 200f, 250f to 200f))
        assertEquals(1, cancels)

        assertTrue(fingers(MotionEvent.ACTION_MOVE, 100f to 200f, 300f to 200f))
        assertEquals(2f, viewport.zoom, EPS)
        assertTrue(moves > 0)

        assertTrue(fingers(secondUp, 100f to 200f, 300f to 200f))
        // The last finger lifting still belongs to the pinch it ends.
        assertTrue(fingers(MotionEvent.ACTION_UP, 100f to 200f))
        assertEquals(1, cancels)

        // The next touch starts over with editing.
        assertFalse(fingers(MotionEvent.ACTION_DOWN, 100f to 100f))
    }

    @Test
    fun `a cancelled pinch claims the cancel and hands the next touch back`() {
        fingers(MotionEvent.ACTION_DOWN, 150f to 200f)
        fingers(secondDown, 150f to 200f, 250f to 200f)
        assertTrue(fingers(MotionEvent.ACTION_CANCEL, 150f to 200f, 250f to 200f))
        assertFalse(fingers(MotionEvent.ACTION_DOWN, 100f to 100f))
    }

    @Test
    fun `a cancel outside a pinch is left to editing`() {
        fingers(MotionEvent.ACTION_DOWN, 150f to 200f)
        assertFalse(fingers(MotionEvent.ACTION_CANCEL, 150f to 200f))
    }

    @Test
    fun `a double tap cancels the edit and zooms in`() {
        fingers(MotionEvent.ACTION_DOWN, 200f to 200f, at = clock)
        fingers(MotionEvent.ACTION_UP, 200f to 200f, at = clock + 30)
        assertTrue(fingers(MotionEvent.ACTION_DOWN, 200f to 200f, at = clock + 150))
        assertTrue(fingers(MotionEvent.ACTION_UP, 200f to 200f, at = clock + 180))

        assertEquals(1, cancels)
        assertEquals(RoiViewport.DOUBLE_TAP_ZOOM, viewport.zoom, EPS)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
