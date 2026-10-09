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

/**
 * [done] of [total] as a percent, plus [fraction] (0–1) of the item after them;
 * 0 while there is nothing to count.
 */
internal fun percentOf(done: Int, total: Int, fraction: Double = 0.0): Double =
    if (total > 0) {
        (done.coerceIn(0, total) + fraction.coerceIn(0.0, 1.0)).coerceAtMost(total.toDouble()) * WHOLE / total
    } else {
        0.0
    }

/**
 * This report for one part of a bigger job: the part's own 0–100 lands in
 * [from]–[to] of the whole, and its status passes through as it is.
 */
internal fun ExportReport.within(from: Double, to: Double): ExportReport =
    { percent, status -> this(from + (to - from) * percent.coerceIn(0.0, WHOLE) / WHOLE, status) }

/** [within] the bounds of [range]. */
internal fun ExportReport.within(range: ClosedFloatingPointRange<Double>): ExportReport =
    within(range.start, range.endInclusive)

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
    this(percentOf(done, total), status(res, done, total, format, label))
}

/**
 * [step] without a label, [fraction] (0–1) of the way through the item in
 * progress: the bar moves inside one long item while the status still names it.
 */
internal fun ExportReport.stepPartway(
    res: Resources,
    done: Int,
    fraction: Double,
    total: Int,
    @StringRes format: Int,
) {
    this(percentOf(done, total, fraction), status(res, done, total, format, label = null))
}

private fun status(res: Resources, done: Int, total: Int, @StringRes format: Int, label: String?): String {
    val current = ProgressCount.current(done, total)
    return if (label == null) {
        res.getString(format, current, total)
    } else {
        res.getString(format, current, total, label)
    }
}

/**
 * A byte counter for `Zips.putFile`'s `onBytes`: [total] bytes is this
 * report's 100%. It reports [status] each time another whole percent is
 * written rather than every chunk, and nothing when [total] is 0.
 */
internal fun ExportReport.byteCounter(total: Long, status: String): (Long) -> Unit {
    var written = 0L
    var shown = -1
    return { bytes ->
        written += bytes
        if (total > 0) {
            val percent = written.coerceAtMost(total) * WHOLE / total
            if (percent.toInt() > shown) {
                shown = percent.toInt()
                this(percent, status)
            }
        }
    }
}
