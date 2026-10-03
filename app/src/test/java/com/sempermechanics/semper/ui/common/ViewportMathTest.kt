package com.sempermechanics.semper.ui.common

import android.app.Activity
import android.app.Application
import android.graphics.Matrix
import android.view.ScaleGestureDetector
import com.sempermechanics.semper.ui.analysis.sweep.VsgPlotView
import com.sempermechanics.semper.ui.viewer.inspect.TouchImageView
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * [ViewportMath] is a port, so each function is run beside the code it came
 * from — the views' own private clamps, reached by reflection, or for a view
 * that has since adopted it, the values its original returned — over a grid
 * of inputs, and must give the same floats, bit for bit. A view that adopts
 * it then cannot move by even an ulp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewportMathTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    // ── TouchImageView: translation clamp and pinch limits ──

    private fun touchField(name: String): Field =
        TouchImageView::class.java.getDeclaredField(name).apply { isAccessible = true }

    private val limitPan: Method by lazy {
        TouchImageView::class.java.getDeclaredMethod("limitPan").apply { isAccessible = true }
    }

    /** A viewer view with the given geometry, set straight into its fields. */
    @Suppress("LongParameterList")
    private fun touchView(
        viewW: Int,
        viewH: Int,
        imageW: Float,
        imageH: Float,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
    ): TouchImageView = TouchImageView(activity).also { v ->
        touchField("viewWidth").setInt(v, viewW)
        touchField("viewHeight").setInt(v, viewH)
        touchField("trueImageWidth").setFloat(v, imageW)
        touchField("trueImageHeight").setFloat(v, imageH)
        touchField("contentInsetLeft").setInt(v, insetLeft)
        touchField("contentInsetTop").setInt(v, insetTop)
        touchField("contentInsetRight").setInt(v, insetRight)
        touchField("contentInsetBottom").setInt(v, insetBottom)
    }

    private fun TouchImageView.panMatrix(): Matrix = touchField("matrix").get(this) as Matrix

    private fun Matrix.values(): FloatArray = FloatArray(9).also { getValues(it) }

    /** One viewer geometry and pan the translation clamp is checked at. */
    private data class PanCase(val viewW: Int, val insets: IntArray, val imageW: Float, val scale: Float, val tx: Float)

    private fun panCases(): List<PanCase> {
        val insetSets = listOf(intArrayOf(0, 0, 0, 0), intArrayOf(12, 96, 30, 140))
        val translations = floatArrayOf(-9000f, -512.25f, -1f, 0f, 3.3f, 96f, 700f)
        return intArrayOf(400, 1080).flatMap { viewW ->
            insetSets.flatMap { insets ->
                floatArrayOf(200f, 1333.3f, 4000f).flatMap { imageW ->
                    floatArrayOf(0.1f, 0.37f, 1f, 2.5f, 10f).flatMap { scale ->
                        translations.map { tx -> PanCase(viewW, insets, imageW, scale, tx) }
                    }
                }
            }
        }
    }

    @Test
    fun `panCorrection matches TouchImageView's limitPan`() {
        val cases = panCases()
        for (c in cases) {
            val (viewW, insets, imageW) = Triple(c.viewW, c.insets, c.imageW)
            val view = touchView(viewW, viewW * 2, imageW, imageW * 0.75f, insets[0], insets[1], insets[2], insets[3])
            val m = view.panMatrix()
            m.setValues(floatArrayOf(c.scale, 0f, c.tx, 0f, c.scale, c.tx * 0.7f - 11f, 0f, 0f, 1f))
            val before = m.values()

            limitPan.invoke(view)
            val after = m.values()

            val dx = ViewportMath.panCorrection(
                before[Matrix.MTRANS_X],
                imageW * before[Matrix.MSCALE_X],
                insets[0].toFloat(),
                (viewW - insets[2]).toFloat(),
            )
            val dy = ViewportMath.panCorrection(
                before[Matrix.MTRANS_Y],
                imageW * 0.75f * before[Matrix.MSCALE_Y],
                insets[1].toFloat(),
                (viewW * 2 - insets[3]).toFloat(),
            )
            val label = "view=$viewW insets=${insets.toList()} image=$imageW scale=${c.scale} tx=${c.tx}"
            assertEquals(label, before[Matrix.MTRANS_X] + dx, after[Matrix.MTRANS_X], 0f)
            assertEquals(label, before[Matrix.MTRANS_Y] + dy, after[Matrix.MTRANS_Y], 0f)
        }
        assertEquals(420, cases.size)
    }

    /** Drives TouchImageView's own pinch callback with a detector reporting [factor] about ([fx], [fy]). */
    private fun pinch(view: TouchImageView, factor: Float, fx: Float, fy: Float) {
        val detector = ScaleGestureDetector(activity, ScaleGestureDetector.SimpleOnScaleGestureListener())
        fun set(name: String, value: Float) =
            ScaleGestureDetector::class.java.getDeclaredField(name)
                .apply { isAccessible = true }
                .setFloat(detector, value)
        set("mPrevSpan", 1f)
        set("mCurrSpan", factor)
        set("mFocusX", fx)
        set("mFocusY", fy)
        check(detector.scaleFactor == factor && detector.focusX == fx) { "detector did not take the fake span" }
        val listenerClass = Class.forName(TouchImageView::class.java.name + "\$ScaleListener")
        val listener = listenerClass.getDeclaredConstructor(TouchImageView::class.java)
            .apply { isAccessible = true }
            .newInstance(view) as ScaleGestureDetector.OnScaleGestureListener
        listener.onScale(detector)
    }

    @Test
    fun `clampScale matches TouchImageView's pinch, scale and matrix`() {
        val minScale = 0.27f
        val maxScale = 10f
        var cases = 0
        for (current in floatArrayOf(0.27f, 0.3f, 1f, 3.7f, 9.9f, 10f)) {
            for (factor in floatArrayOf(0.01f, 0.5f, 0.97f, 1f, 1.03f, 2f, 40f)) {
                val view = touchView(400, 800, 1500f, 1000f, 0, 64, 0, 120)
                touchField("minScale").setFloat(view, minScale)
                touchField("maxScale").setFloat(view, maxScale)
                touchField("currentScale").setFloat(view, current)
                val m = view.panMatrix()
                m.setValues(floatArrayOf(current, 0f, -37.5f, 0f, current, 12.25f, 0f, 0f, 1f))
                val expectedMatrix = Matrix(m)

                pinch(view, factor, 180.5f, 333f)

                val step = ViewportMath.clampScale(current, factor, minScale, maxScale)
                val label = "current=$current factor=$factor"
                assertEquals(label, step.scale, touchField("currentScale").getFloat(view), 0f)
                // The view's own matrix moves by step.factor, then its pan clamp.
                expectedMatrix.postScale(step.factor, step.factor, 180.5f, 333f)
                val v = expectedMatrix.values()
                expectedMatrix.postTranslate(
                    ViewportMath.panCorrection(v[Matrix.MTRANS_X], 1500f * v[Matrix.MSCALE_X], 0f, 400f),
                    ViewportMath.panCorrection(v[Matrix.MTRANS_Y], 1000f * v[Matrix.MSCALE_Y], 64f, 680f),
                )
                assertEquals(label, expectedMatrix.values().toList(), m.values().toList())
                cases++
            }
        }
        assertEquals(42, cases)
    }

    @Test
    fun `clampScale lands exactly on a limit`() {
        assertEquals(ViewportMath.ScaleStep(10f, 10f / 4f), ViewportMath.clampScale(4f, 3f, 1f, 10f))
        assertEquals(ViewportMath.ScaleStep(1f, 1f / 4f), ViewportMath.clampScale(4f, 0.1f, 1f, 10f))
        assertEquals(ViewportMath.ScaleStep(6f, 1.5f), ViewportMath.clampScale(4f, 1.5f, 1f, 10f))
    }

    // ── RoiViewport and VsgPlotView: captured from the originals ──
    //
    // Both views now call ViewportMath themselves, so their own clamps are gone.
    // Each case below is the value the original returned for it, captured
    // before the move (test resources beside this class).

    /** The rows of the oracle file [name], each split into floats; `#` lines are comments. */
    private fun oracle(name: String): List<List<Float>> =
        requireNotNull(javaClass.getResourceAsStream(name)) { "missing oracle $name" }
            .bufferedReader()
            .useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { line -> line.trim().split(' ').map(String::toFloat) }
                    .toList()
            }

    @Test
    fun `centerFraction matches RoiViewport's original clampAxis`() {
        val rows = oracle("roi_viewport_clamp_axis.txt").iterator()
        var cases = 0
        for (center in floatArrayOf(-0.4f, 0f, 0.1f, 0.33f, 0.5f, 0.71f, 0.999f, 1f, 1.8f)) {
            for (view in floatArrayOf(0f, 1f, 360f, 1079.5f)) {
                val legacy = rows.next().iterator()
                for (size in floatArrayOf(-5f, 0f, 1f, 359f, 360f, 361f, 2000f, 12345.6f)) {
                    val ported = ViewportMath.centerFraction(center, view, size)
                    assertEquals("c=$center v=$view s=$size", legacy.next(), ported, 0f)
                    cases++
                }
            }
        }
        assertEquals(288, cases)
    }

    @Test
    fun `clampWindow matches VsgPlotView's original clampViewport on both axes`() {
        val rows = oracle("vsg_plot_clamp_viewport.txt").iterator()
        val fulls = listOf(0f to 10f, -5f to 3.3f, 1f to 1.0001f, -0.002f to 0.0035f)
        val fraction = VsgPlotView.MIN_SPAN_FRACTION
        var cases = 0
        for ((fullMin, fullMax) in fulls) {
            val extent = fullMax - fullMin
            for (startFrac in floatArrayOf(-1.3f, -0.2f, 0f, 0.013f, 0.4f, 0.97f, 1f, 1.6f)) {
                for (spanFrac in floatArrayOf(0f, 0.001f, 0.049f, 0.05f, 0.3f, 0.999f, 1f, 2.5f)) {
                    val lo = fullMin + startFrac * extent
                    val hi = lo + spanFrac * extent
                    // y gets a different window over the same extent, so both axes are exercised.
                    val yLo = fullMin + (1f - startFrac) * extent
                    val yHi = yLo + spanFrac * extent * 0.5f
                    val row = rows.next()

                    val label = "full=$fullMin..$fullMax start=$startFrac span=$spanFrac"
                    val x = ViewportMath.clampWindow(lo, hi, fullMin, fullMax, fraction)
                    val y = ViewportMath.clampWindow(yLo, yHi, fullMin, fullMax, fraction)
                    assertEquals(label, ViewportMath.Window(row[0], row[1]), x)
                    assertEquals(label, ViewportMath.Window(row[2], row[3]), y)
                    cases++
                }
            }
        }
        assertEquals(256, cases)
    }

    @Test
    fun `clampWindow widens a sliver, then slides it back inside`() {
        // 0.1 wide about 9.98 is widened to the 0.5 floor, then slid left off the edge.
        val sliver = ViewportMath.clampWindow(9.93f, 10.03f, 0f, 10f, 0.05f)
        assertEquals(10f, sliver.max, 0f)
        assertEquals(9.5f, sliver.min, 1e-5f)
        assertEquals(ViewportMath.Window(0f, 10f), ViewportMath.clampWindow(-3f, 12f, 0f, 10f, 0.05f))
        assertEquals(ViewportMath.Window(2f, 4f), ViewportMath.clampWindow(2f, 4f, 0f, 10f, 0.05f))
    }
}
