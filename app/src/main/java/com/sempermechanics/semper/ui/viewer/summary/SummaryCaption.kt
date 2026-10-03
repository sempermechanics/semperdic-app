package com.sempermechanics.semper.ui.viewer.summary

import android.content.res.Resources
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.ReportBuilder

/**
 * The summary view's stats caption. It quotes the colour bar's ends — the
 * whole-sequence clamp, or the user's fixed scale — with the scale bar's own
 * "≥" / "≤", since neither is the data's max or min.
 */
internal object SummaryCaption {

    /** [bounds] as (bottom, top) in stored units, or null while unknown. */
    fun text(res: Resources, bounds: Pair<Float, Float>?, dataIndex: Int, unit: String): String {
        val multiplier = DicResult.strainMultiplier(dataIndex)
        val placeholder = res.getString(R.string.stat_empty)
        val top = bounds?.let { ReportBuilder.formatMetric(it.second * multiplier) } ?: placeholder
        val bottom = bounds?.let { ReportBuilder.formatMetric(it.first * multiplier) } ?: placeholder
        return res.getString(R.string.viewer_stats_sequence_fmt, top, bottom, unit)
    }
}
