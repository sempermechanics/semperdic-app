package com.sempermechanics.semper.ui.analysis.run

import android.content.res.Resources
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.wizard.BatchProgressUpdate
import java.util.Locale

/** What the run overlay says for a batch run's or a sweep's tick. */
object RunOverlayText {

    /** "57.0%": the header's one-decimal percentage. */
    fun percent(value: Float): String = String.format(Locale.US, "%.1f%%", value)

    /**
     * The header: the engine's line while the reference is cached, "Frame 23
     * of 40" while frames are solved, then completed while the run saves.
     */
    fun header(res: Resources, tick: BatchProgressUpdate): String {
        val planned = tick.plannedFrames
        return when {
            planned > 0 && tick.frameIndex >= planned ->
                res.getQuantityString(R.plurals.run_completed_fmt, planned, planned, planned)
            tick.frameIndex < 0 || planned <= 0 -> tick.status
            else -> res.getString(R.string.run_frame_of_fmt, tick.frameIndex + 1, planned)
        }
    }

    /**
     * A sweep's header: "Analysis 7 of 20", then, once every combination has
     * an outcome, how many solved while it saves.
     */
    fun sweepHeader(res: Resources, tick: SweepStudyRunner.Progress): String {
        if (sweepEnded(tick)) {
            val solved = tick.outcomes.count { it == SweepStudyRunner.NodeOutcome.SOLVED }
            return res.getQuantityString(R.plurals.sweep_completed_fmt, tick.totalRuns, solved, tick.totalRuns)
        }
        return res.getString(R.string.run_analysis_of_fmt, minOf(tick.runIndex + 1, tick.totalRuns), tick.totalRuns)
    }

    /** The line under the sweep lattice: the combination being solved, or null once all have ended. */
    fun sweepStatus(res: Resources, tick: SweepStudyRunner.Progress): String? {
        if (sweepEnded(tick)) return null
        val point = tick.point
        return res.getString(R.string.sweep_running_fmt, point.subset, point.step, point.window)
    }

    /** The one-strike warning, or null when no frame leaves the run one low frame from stopping. */
    fun strikeWarning(res: Resources, values: FloatArray): String? =
        ConvergenceTrace.pendingStrike(values)?.let {
            res.getString(R.string.run_strike_warn_fmt, it.frameNumber, it.convergencePercent)
        }

    private fun sweepEnded(tick: SweepStudyRunner.Progress): Boolean =
        tick.outcomes.isNotEmpty() && SweepStudyRunner.NodeOutcome.PENDING !in tick.outcomes
}
