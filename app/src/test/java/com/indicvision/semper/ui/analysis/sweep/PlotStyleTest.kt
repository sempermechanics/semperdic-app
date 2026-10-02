package com.indicvision.semper.ui.analysis.sweep

import android.app.Application
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import com.indicvision.semper.ui.viewer.inspect.FieldHistogramView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlotStyle]'s paints are the ones the three charts build today: each is
 * compared with the chart's own private paint, field for field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "xxhdpi")
class PlotStyleTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun View.paint(name: String): Paint =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as Paint

    private data class Look(
        val flags: Int,
        val style: Paint.Style,
        val strokeWidth: Float,
        val textSize: Float,
        val monospace: Boolean,
        val fakeBold: Boolean,
    )

    private fun Paint.look() =
        Look(flags, style, strokeWidth, textSize, typeface == Typeface.MONOSPACE, isFakeBoldText)

    private fun assertSamePaint(expected: Paint, actual: Paint, withColor: Boolean = true) {
        assertEquals(expected.look(), actual.look())
        if (withColor) assertEquals(expected.color, actual.color)
    }

    @Test
    fun `the strain plot's grid, axis text and value text`() {
        val plot = VsgPlotView(context)
        assertSamePaint(plot.paint("gridPaint"), PlotStyle.gridPaint(context))
        assertSamePaint(plot.paint("textPaint"), PlotStyle.axisTextPaint(context))
        assertSamePaint(plot.paint("valuePaint"), PlotStyle.valueTextPaint(context))
    }

    @Test
    fun `the lattice's grid and axis text`() {
        val lattice = VsgLatticeView(context)
        assertSamePaint(lattice.paint("gridPaint"), PlotStyle.gridPaint(context))
        assertSamePaint(lattice.paint("textPaint"), PlotStyle.axisTextPaint(context))
    }

    @Test
    fun `the histogram's axis and text, coloured at draw time`() {
        val histogram = FieldHistogramView(context)
        val axis = PlotStyle.gridPaint(context, R.color.viewer_plot_ink)
        assertSamePaint(histogram.paint("axisPaint"), axis, withColor = false)
        assertSamePaint(histogram.paint("textPaint"), PlotStyle.axisTextPaint(context), withColor = false)
    }

    @Suppress("DEPRECATION") // scaledDensity: the sp scale, read independently of applyDimension
    @Test
    fun `sizes and colours`() {
        val density = context.resources.displayMetrics.density
        assertEquals(PlotStyle.GRID_DP * density, PlotStyle.px(context, PlotStyle.GRID_DP), 0f)
        assertEquals(11f * context.resources.displayMetrics.scaledDensity, PlotStyle.axisLabelPx(context), 1e-3f)
        assertEquals(ContextCompat.getColor(context, R.color.viewer_plot_ink), PlotStyle.ink(context))
        assertEquals(ContextCompat.getColor(context, R.color.viewer_plot_ink_strong), PlotStyle.inkStrong(context))
        assertEquals(ContextCompat.getColor(context, R.color.viewer_plot_grid), PlotStyle.grid(context))
    }
}
