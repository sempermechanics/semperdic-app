@file:Suppress("MagicNumber")

package com.sempermechanics.semper.e2e

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.R
import com.sempermechanics.semper.navigation.DicKeys
import com.sempermechanics.semper.ui.analysis.RoiDrawActivity
import com.sempermechanics.semper.ui.analysis.roi.StudioOverlayView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The ROI editor's zoom and pan under real touches, injected through the input
 * system (WORKFLOWS.md §6.21–6.25): a pinch zooms and the ROI keeps its image
 * pixels, two fingers pan and the photo stays on screen, a double-tap toggles
 * 2x and fit, a stray tap keeps the ROI, and a crop drawn zoomed in saves the
 * image pixels under the finger.
 */
@RunWith(AndroidJUnit4::class)
class RoiEditorGestureTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var photo: File

    @Before
    fun writePhoto() {
        photo = File(context.cacheDir, "roi_gesture_test.png")
        val random = Random(SEED)
        val pixels = IntArray(IMG_W * IMG_H) {
            val grey = random.nextInt(256)
            Color.rgb(grey, grey, grey)
        }
        Bitmap.createBitmap(pixels, IMG_W, IMG_H, Bitmap.Config.ARGB_8888).apply {
            photo.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
    }

    @After
    fun removePhoto() {
        photo.delete()
    }

    @Test
    fun aPinchZoomsAndTheRoiKeepsItsPixels() {
        launch().use { scenario ->
            setRoi(scenario)
            val before = roi(scenario)
            val c = centre(scenario)
            // Both fingers land outside the ROI, where one finger alone starts a new crop.
            twoFingers(c.plus(-60f, 0f), c.plus(60f, 0f), c.plus(-240f, 0f), c.plus(240f, 0f))

            val zoom = zoom(scenario)
            assertTrue("pinch-open did not zoom ($zoom)", zoom > 1.5f)
            assertRect(before, roi(scenario), ROI_TOLERANCE)
            assertEquals(context.getString(R.string.roi_hud_zoom_fmt, zoom), hud(scenario))
        }
    }

    @Test
    fun twoFingersPanAndThePhotoStaysOnScreen() {
        launch().use { scenario ->
            setRoi(scenario)
            val before = roi(scenario)
            val c = centre(scenario)
            twoFingers(c.plus(-60f, 0f), c.plus(60f, 0f), c.plus(-240f, 0f), c.plus(240f, 0f))
            val zoomed = photoOnScreen(scenario)
            val zoom = zoom(scenario)

            // Same spread, both fingers 200 px left: a pure pan.
            twoFingers(c.plus(-80f, -50f), c.plus(-80f, 50f), c.plus(-280f, -50f), c.plus(-280f, 50f))
            assertEquals(zoom, zoom(scenario), 0.01f)
            assertEquals(zoomed.left - 200f, photoOnScreen(scenario).left, PAN_TOLERANCE)

            // Far further right than the photo reaches: it stops at the canvas edge. The
            // fingers go down inside the canvas, clear of its edges: a DOWN off the screen
            // is refused, and the CI emulator's canvas is narrower than a phone's.
            val canvas = canvasOnScreen(scenario)
            val inset = canvas.width() * EDGE_INSET
            val from = canvas.left + inset
            val to = canvas.right - inset
            // As many sweeps as the photo has left to travel, plus two to push past the edge:
            // the pinch's zoom, and so the distance, depends on the screen.
            val sweeps = ((canvas.left - photoOnScreen(scenario).left) / (to - from)).toInt() + EXTRA_SWEEPS
            // Where the canvas, photo and zoom were after each sweep: printed if the photo
            // does not end on the edge (TD-146).
            val trace = mutableListOf("start: ${state(scenario)}")
            repeat(sweeps) { i ->
                twoFingers(
                    PointF(from, c.y - 50f),
                    PointF(from, c.y + 50f),
                    PointF(to, c.y - 50f),
                    PointF(to, c.y + 50f),
                )
                trace += "sweep ${i + 1}: ${state(scenario)}"
            }
            assertEquals(
                trace.joinToString("\n", prefix = "photo left vs canvas left\n"),
                canvasOnScreen(scenario).left,
                photoOnScreen(scenario).left,
                1f,
            )
            assertRect(before, roi(scenario), ROI_TOLERANCE)
        }
    }

    @Test
    fun aDoubleTapZoomsToTwiceAndBackToFit() {
        launch().use { scenario ->
            setRoi(scenario)
            val before = roi(scenario)
            val rest = photoOnScreen(scenario)
            val c = centre(scenario)

            doubleTap(c)
            assertEquals(2f, zoom(scenario), 0.01f)
            doubleTap(c)
            assertEquals(1f, zoom(scenario), 0.001f)

            assertRect(rest, photoOnScreen(scenario), 1f)
            assertRect(before, roi(scenario), ROI_TOLERANCE)
        }
    }

    @Test
    fun aStrayTapOutsideTheRoiKeepsIt() {
        launch().use { scenario ->
            setRoi(scenario)
            val before = roi(scenario)
            tap(centre(scenario))
            Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + SETTLE_MS)
            instrumentation.waitForIdleSync()

            var kept = false
            scenario.onActivity { kept = overlay(it).hasValidRoi }
            assertTrue("a tap wiped the ROI", kept)
            assertRect(before, roi(scenario), ROI_TOLERANCE)
        }
    }

    @Test
    fun aCropDrawnZoomedInSavesThePixelsUnderTheFinger() {
        launch().use { scenario ->
            val c = centre(scenario)
            doubleTap(c)
            assertEquals(2f, zoom(scenario), 0.01f)
            val bounds = photoOnScreen(scenario)

            val from = c.plus(-150f, -100f)
            val to = c.plus(150f, 100f)
            drag(from, to)
            val expected = RectF(
                imageX(bounds, from.x),
                imageY(bounds, from.y),
                imageX(bounds, to.x),
                imageY(bounds, to.y),
            )
            assertRect(expected, roi(scenario), DRAW_TOLERANCE)

            val result = save(scenario)
            assertEquals(expected.left.roundToInt().toFloat(), result.getIntExtra(DicKeys.ROI_X, -1).toFloat(), DRAW_TOLERANCE)
            assertEquals(expected.top.roundToInt().toFloat(), result.getIntExtra(DicKeys.ROI_Y, -1).toFloat(), DRAW_TOLERANCE)
            assertEquals(expected.width(), result.getIntExtra(DicKeys.ROI_W, -1).toFloat(), DRAW_TOLERANCE)
            assertEquals(expected.height(), result.getIntExtra(DicKeys.ROI_H, -1).toFloat(), DRAW_TOLERANCE)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun launch(): ActivityScenario<RoiDrawActivity> {
        val intent = Intent(context, RoiDrawActivity::class.java)
            .putExtra(DicKeys.IMAGE_FILE_PATH, photo.absolutePath)
            .putExtra(DicKeys.IMAGE_WIDTH, IMG_W)
            .putExtra(DicKeys.IMAGE_HEIGHT, IMG_H)
        val scenario = ActivityScenario.launchActivityForResult<RoiDrawActivity>(intent)
        awaitOn(scenario, "the photo") { overlay(it).imageView?.drawable != null }
        instrumentation.waitForIdleSync()
        return scenario
    }

    private fun overlay(activity: Activity): StudioOverlayView = activity.findViewById(R.id.overlayRoi)

    /** A crop in the top-left of the photo, clear of the canvas centre where the gestures land. */
    private fun setRoi(scenario: ActivityScenario<RoiDrawActivity>) {
        scenario.onActivity { assertTrue(overlay(it).applyImageRoi(ROI_X, ROI_Y, ROI_W, ROI_H)) }
    }

    private fun roi(scenario: ActivityScenario<RoiDrawActivity>): RectF {
        var roi = RectF()
        scenario.onActivity { roi = RectF(overlay(it).getRelativeRoi()) }
        return roi
    }

    private fun zoom(scenario: ActivityScenario<RoiDrawActivity>): Float {
        var zoom = 0f
        scenario.onActivity { zoom = overlay(it).zoom }
        return zoom
    }

    private fun hud(scenario: ActivityScenario<RoiDrawActivity>): String {
        var text = ""
        scenario.onActivity { text = it.findViewById<TextView>(R.id.tvHud).text.toString() }
        return text
    }

    /** Where the photo is drawn now, in screen px. */
    private fun photoOnScreen(scenario: ActivityScenario<RoiDrawActivity>): RectF {
        val rect = RectF()
        scenario.onActivity {
            val image = it.findViewById<ImageView>(R.id.imgRoiCanvas)
            val drawable = checkNotNull(image.drawable)
            rect.set(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
            image.imageMatrix.mapRect(rect)
            val at = IntArray(2)
            image.getLocationOnScreen(at)
            rect.offset(at[0].toFloat(), at[1].toFloat())
        }
        return rect
    }

    private fun canvasOnScreen(scenario: ActivityScenario<RoiDrawActivity>): RectF {
        val rect = RectF()
        scenario.onActivity {
            val view = overlay(it)
            val at = IntArray(2)
            view.getLocationOnScreen(at)
            rect.set(at[0].toFloat(), at[1].toFloat(), (at[0] + view.width).toFloat(), (at[1] + view.height).toFloat())
        }
        return rect
    }

    /** Canvas and photo on screen, zoom and ROI, for a failure message. */
    private fun state(scenario: ActivityScenario<RoiDrawActivity>): String =
        "canvas=${canvasOnScreen(scenario).toShortString()} photo=${photoOnScreen(scenario).toShortString()} " +
            "zoom=${zoom(scenario)} roi=${roi(scenario).toShortString()} hud=\"${hud(scenario)}\""

    private fun centre(scenario: ActivityScenario<RoiDrawActivity>): PointF {
        val canvas = canvasOnScreen(scenario)
        return PointF(canvas.centerX(), canvas.centerY())
    }

    private fun imageX(photo: RectF, screenX: Float) = (screenX - photo.left) * IMG_W / photo.width()

    private fun imageY(photo: RectF, screenY: Float) = (screenY - photo.top) * IMG_H / photo.height()

    private fun PointF.plus(dx: Float, dy: Float) = PointF(x + dx, y + dy)

    private fun lerp(a: PointF, b: PointF, t: Float) = PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun inject(action: Int, downTime: Long, points: List<PointF>) {
        val props = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].x
                y = points[i].y
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, points.size, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "injection refused: $event" }
        event.recycle()
    }

    /** Two fingers from [a0], [b0] to [a1], [b1], then both lift. */
    private fun twoFingers(a0: PointF, b0: PointF, a1: PointF, b1: PointF) {
        instrumentation.waitForIdleSync()
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, listOf(a0))
        inject(SECOND_DOWN, down, listOf(a0, b0))
        for (i in 1..STEPS) {
            val t = i / STEPS.toFloat()
            inject(MotionEvent.ACTION_MOVE, down, listOf(lerp(a0, a1, t), lerp(b0, b1, t)))
            Thread.sleep(MOVE_MS)
        }
        inject(SECOND_UP, down, listOf(a1, b1))
        inject(MotionEvent.ACTION_UP, down, listOf(a1))
        instrumentation.waitForIdleSync()
    }

    private fun drag(from: PointF, to: PointF) {
        instrumentation.waitForIdleSync()
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, listOf(from))
        for (i in 1..STEPS) {
            inject(MotionEvent.ACTION_MOVE, down, listOf(lerp(from, to, i / STEPS.toFloat())))
            Thread.sleep(MOVE_MS)
        }
        inject(MotionEvent.ACTION_UP, down, listOf(to))
        instrumentation.waitForIdleSync()
    }

    private fun tap(at: PointF) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, listOf(at))
        Thread.sleep(TAP_MS)
        inject(MotionEvent.ACTION_UP, down, listOf(at))
    }

    /** Two taps inside the double-tap window, then waits it out so the next touch starts fresh. */
    private fun doubleTap(at: PointF) {
        instrumentation.waitForIdleSync()
        tap(at)
        Thread.sleep(DOUBLE_TAP_GAP_MS)
        tap(at)
        Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + SETTLE_MS)
        instrumentation.waitForIdleSync()
    }

    private fun save(scenario: ActivityScenario<RoiDrawActivity>): Intent {
        scenario.onActivity { it.findViewById<View>(R.id.btnSaveRoi).performClick() }
        val result = scenario.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        return result.resultData
    }

    private fun assertRect(expected: RectF, actual: RectF, tolerance: Float) {
        assertEquals("left of $actual", expected.left, actual.left, tolerance)
        assertEquals("top of $actual", expected.top, actual.top, tolerance)
        assertEquals("right of $actual", expected.right, actual.right, tolerance)
        assertEquals("bottom of $actual", expected.bottom, actual.bottom, tolerance)
    }

    private fun awaitOn(
        scenario: ActivityScenario<RoiDrawActivity>,
        what: String,
        done: (RoiDrawActivity) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            var ok = false
            scenario.onActivity { ok = done(it) }
            if (ok) return
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        /** Share of the canvas width kept clear at each end of the far pan's sweep. */
        const val EDGE_INSET = 0.15f

        /** Sweeps past the ones the photo needs to reach the canvas edge. */
        const val EXTRA_SWEEPS = 2
        const val IMG_W = 800
        const val IMG_H = 600
        const val ROI_X = 40
        const val ROI_Y = 40
        const val ROI_W = 160
        const val ROI_H = 110
        const val SEED = 11

        const val SECOND_DOWN = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        const val SECOND_UP = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        const val STEPS = 20
        const val MOVE_MS = 10L
        const val TAP_MS = 40L
        const val DOUBLE_TAP_GAP_MS = 120L

        /** Image px: the ROI remaps through float bounds on every pinch frame. */
        const val ROI_TOLERANCE = 0.5f

        /** Image px: the saved rect is rounded, and 2x on a phone is under one image px per screen px. */
        const val DRAW_TOLERANCE = 1.5f
        const val PAN_TOLERANCE = 2f
        const val SETTLE_MS = 200L
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 50L
    }
}
