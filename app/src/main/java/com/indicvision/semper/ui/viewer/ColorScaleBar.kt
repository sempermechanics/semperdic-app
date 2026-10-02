package com.indicvision.semper.ui.viewer

import android.content.res.Resources
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import com.indicvision.semper.report.VisualizationEngine

/**
 * The viewer's colour scale bar: the heatmap's own ramp, lowest value at the
 * bottom, in evenly spaced stops, so the bar shows the colours the map uses.
 * The report's bar draws the same ramp.
 */
internal object ColorScaleBar {

    /** The bar's corner radius, as the drawable it replaces had. */
    private const val CORNER_RADIUS_DP = 4f

    fun drawable(resources: Resources): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, VisualizationEngine.rampColors()).apply {
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                CORNER_RADIUS_DP,
                resources.displayMetrics,
            )
        }
}
