package com.sempermechanics.semper.ui.analysis.sweep

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.dp

/**
 * The look the in-app charts share: the strain plot (`VsgPlotView`), the VSG
 * lattice (`VsgLatticeView`) and the viewer's histogram (`FieldHistogramView`).
 *
 * Each built the same 11 sp monospace axis text and 1 dp grid stroke in
 * `viewer_plot_*` colours. The values here are copied from them; where the
 * lattice differs ([LATTICE_TICK_GAP_DP], [LATTICE_TICK_BASELINE]) its own
 * value is kept beside the shared one rather than folded in.
 */
object PlotStyle {

    /** Axis and tick labels, sp: all three charts. */
    const val AXIS_LABEL_SP = 11f

    /** Grid / axis stroke, dp: all three charts. */
    const val GRID_DP = 1f

    /** Gap between a tick label and its axis, dp: the plot and the histogram. */
    const val TICK_GAP_DP = 4f

    /** The lattice's wider tick gap, dp. */
    const val LATTICE_TICK_GAP_DP = 5f

    /** Baseline nudge, in text heights, that centres a tick label on its gridline: the plot and the histogram. */
    const val TICK_BASELINE = 0.33f

    /** The lattice's baseline nudge. */
    const val LATTICE_TICK_BASELINE = 0.34f

    /** [AXIS_LABEL_SP] in px for [context], scaled for the user's font size. */
    fun axisLabelPx(context: Context): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, AXIS_LABEL_SP, context.resources.displayMetrics)

    /** [dp] in px for [context]. */
    fun px(context: Context, dp: Float): Float = dp * context.resources.displayMetrics.density

    /** Tick and axis text: anti-aliased monospace at [axisLabelPx], in [colorRes]. */
    fun axisTextPaint(context: Context, @ColorRes colorRes: Int = R.color.viewer_plot_ink): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = axisLabelPx(context)
            color = ContextCompat.getColor(context, colorRes)
            typeface = Typeface.MONOSPACE
        }

    /** The bold value label beside a scrub point (the strain plot's). */
    fun valueTextPaint(context: Context): Paint =
        axisTextPaint(context, R.color.viewer_plot_ink_strong).apply { isFakeBoldText = true }

    /** Gridlines: an anti-aliased [GRID_DP] stroke in [colorRes]. */
    fun gridPaint(context: Context, @ColorRes colorRes: Int = R.color.viewer_plot_grid): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(context, GRID_DP)
            color = ContextCompat.getColor(context, colorRes)
        }

    @ColorInt
    fun ink(context: Context): Int = ContextCompat.getColor(context, R.color.viewer_plot_ink)

    @ColorInt
    fun inkStrong(context: Context): Int = ContextCompat.getColor(context, R.color.viewer_plot_ink_strong)

    @ColorInt
    fun grid(context: Context): Int = ContextCompat.getColor(context, R.color.viewer_plot_grid)
}
