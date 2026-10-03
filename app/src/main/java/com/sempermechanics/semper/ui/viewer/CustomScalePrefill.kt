package com.sempermechanics.semper.ui.viewer

import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.report.ReportBuilder

/**
 * What the custom colour-scale dialog opens with: the field's custom bounds when
 * it has some, otherwise the auto bounds the scale bar is showing, written the
 * way the bar writes them, so Apply with no edits keeps the scale as it is.
 */
internal object CustomScalePrefill {

    /**
     * @param custom the field's custom bounds in stored units, if any.
     * @param shown the range of the heatmap on screen, or null when none is
     *   (the summary shows a whole-sequence scale instead).
     * @param multiplier stored units to display units (strain to mε).
     * @return (min, max) text, or null to leave the fields empty.
     */
    fun text(custom: ValueRange?, shown: ValueRange?, multiplier: Float): Pair<String, String>? {
        val range = custom ?: shown?.takeIf { it.isUsable() } ?: return null
        return ReportBuilder.formatMetric(range.min * multiplier) to ReportBuilder.formatMetric(range.max * multiplier)
    }

    private fun ValueRange.isUsable(): Boolean = min.isFinite() && max.isFinite() && max > min
}
