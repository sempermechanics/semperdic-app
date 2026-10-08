package com.sempermechanics.semper.ui.viewer.share

import android.content.res.Resources
import androidx.annotation.StringRes
import com.sempermechanics.semper.util.ProgressCount

/**
 * How far an export is: [percent] 0–100, fractions kept (the dialog shows a
 * tenth), and the status line naming what it is on ("Frame 12 of 40 · heatmaps").
 * Called on the generator's thread; [ShareExportJobs] takes it from there.
 */
internal typealias ExportReport = (percent: Double, status: String) -> Unit

/** A report that goes nowhere, for callers that do not show progress. */
internal val NO_REPORT: ExportReport = { _, _ -> }

private const val WHOLE = 100.0

/** [done] of [total] as a percent; 0 while there is nothing to count. */
internal fun percentOf(done: Int, total: Int): Double =
    if (total > 0) done.coerceIn(0, total) * WHOLE / total else 0.0

/**
 * This report for one part of a bigger job: the part's own 0–100 lands in
 * [from]–[to] of the whole, and its status passes through as it is.
 */
internal fun ExportReport.within(from: Double, to: Double): ExportReport =
    { percent, status -> this(from + (to - from) * percent.coerceIn(0.0, WHOLE) / WHOLE, status) }

/** Part [index] of [count] equal parts of this report (see [within]). */
internal fun ExportReport.part(index: Int, count: Int): ExportReport =
    within(index * WHOLE / count, (index + 1) * WHOLE / count)

/**
 * Reports [done] of [total] items finished, with a status naming the item now
 * in progress: [format] takes that item's 1-based number and [total], then
 * [label] when there is one ("Frame %1$d of %2$d · %3$s animation").
 */
internal fun ExportReport.step(
    res: Resources,
    done: Int,
    total: Int,
    @StringRes format: Int,
    label: String? = null,
) {
    val current = ProgressCount.current(done, total)
    val status = if (label == null) {
        res.getString(format, current, total)
    } else {
        res.getString(format, current, total, label)
    }
    this(percentOf(done, total), status)
}
