package com.indicvision.semper.results

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.field.ValueRange
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.VisualizationEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The colour bar baked into report and share PNGs must be the ramp the map is
 * drawn with, or a value read off the bar is wrong. Native graphics, so the
 * gradient is really rasterised.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportColorBarTest {

    private companion object {
        const val SIZE = 400

        /** One ramp step is ~4 levels; gradient interpolation and dithering add a little. */
        const val TOLERANCE = 12

        /** For the bar's ends read just inside its outline. */
        const val END_TOLERANCE = 28
    }

    @Test
    fun `the bar shows the map's colour at every height`() {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        ReportBuilder.bakeAnnotationsToCanvas(
            Canvas(bitmap),
            SIZE,
            SIZE,
            range = ValueRange(min = 0f, max = 1f),
            extrema = ReportBuilder.FieldExtrema(maxIdx = -1, minIdx = -1),
            data = FloatArray(0),
            annotation = ReportBuilder.FieldAnnotation(typeString = "U", unit = "px"),
        )

        // The bar's geometry, as bakeAnnotationsToCanvas lays it out.
        val textSize = SIZE * 0.025f
        val padding = SIZE * 0.02f
        val barWidth = SIZE * 0.03f
        val barHeight = SIZE * 0.5f
        val barLeft = SIZE - padding - barWidth - textSize * 4.5f
        val barTop = (SIZE - barHeight) / 2f
        val barBottom = barTop + barHeight
        val x = (barLeft + barWidth / 2f).toInt()

        val ramp = VisualizationEngine.rampColors()
        val last = ramp.size - 1
        val misses = mutableListOf<String>()
        // Inside the 3 px outline.
        for (y in (barTop + 4).toInt()..(barBottom - 4).toInt()) {
            val t = (barBottom - (y + 0.5f)) / barHeight
            // What the map paints for a value at t of the scale.
            val expected = ramp[(t * last).toInt().coerceIn(0, last)]
            val actual = bitmap.getPixel(x, y)
            val worst = maxOf(
                abs(Color.red(actual) - Color.red(expected)),
                abs(Color.green(actual) - Color.green(expected)),
                abs(Color.blue(actual) - Color.blue(expected)),
            )
            if (worst > TOLERANCE) misses += "t=%.3f off by %d".format(t, worst)
        }
        assertTrue("bar differs from the map: ${misses.take(5)} (${misses.size} rows)", misses.isEmpty())
        // Its ends, against the jet values written out by hand rather than read
        // back from the ramp under test. The outline covers the very ends, so the
        // rows read sit ~5 ramp steps (~20 levels) in.
        val bottom = bitmap.getPixel(x, (barBottom - 5).toInt())
        val top = bitmap.getPixel(x, (barTop + 5).toInt())
        assertNear(Color.rgb(0, 0, 127), bottom, END_TOLERANCE)
        assertNear(Color.rgb(131, 0, 0), top, END_TOLERANCE)
    }

    @Test
    fun `the ramp is the jet map less the transparent slot`() {
        val ramp = VisualizationEngine.rampColors()
        assertEquals(VisualizationEngine.TRANSPARENT_INDEX, ramp.size)
        assertEquals(255, ramp.size)
        // Jet at v = i/255: dark blue, then green, then dark red.
        assertEquals(Color.rgb(0, 0, 127), ramp[0])
        assertEquals(Color.rgb(125, 255, 129), ramp[127])
        assertEquals(Color.rgb(131, 0, 0), ramp[254])
        // The GIF's colour table holds the same colours below its background slot.
        val gif = VisualizationEngine.gifPalette(Color.MAGENTA)
        assertArrayEquals(ramp, gif.copyOf(ramp.size))
    }

    @Test
    fun `the map paints the ramp's ends at the ends of the scale`() {
        assertEquals(VisualizationEngine.rampColors().first(), centrePixelOfUniformField(0f))
        assertEquals(VisualizationEngine.rampColors().last(), centrePixelOfUniformField(1f))
    }

    /** The heatmap's centre pixel for a field whose u is [value] everywhere, on a 0..1 scale. */
    private fun centrePixelOfUniformField(value: Float): Int {
        val grid = 10
        val step = 4
        val data = FloatArray(grid * grid * DicResult.STRIDE)
        for (i in 0 until grid * grid) {
            val o = i * DicResult.STRIDE
            data[o] = ((i % grid) * step).toFloat()
            data[o + 1] = ((i / grid) * step).toFloat()
            data[o + DicResult.IDX_U] = value
            data[o + DicResult.IDX_ZNSSD] = 0.01f // a well-correlated point
        }
        val (heatmap, _, _) = VisualizationEngine.generateHeatmap(
            data,
            grid * step,
            grid * step,
            DicResult.IDX_U,
            step,
            customMin = 0f,
            customMax = 1f,
        )
        return heatmap.getPixel(heatmap.width / 2, heatmap.height / 2).also { heatmap.recycle() }
    }

    private fun assertNear(expected: Int, actual: Int, tolerance: Int) {
        val worst = maxOf(
            abs(Color.red(actual) - Color.red(expected)),
            abs(Color.green(actual) - Color.green(expected)),
            abs(Color.blue(actual) - Color.blue(expected)),
        )
        assertTrue("#%08X vs #%08X".format(actual, expected), worst <= tolerance)
    }
}
