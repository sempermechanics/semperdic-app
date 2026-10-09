package com.sempermechanics.semper.ui.analysis.run

import android.content.res.Resources
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.wizard.BatchProgressUpdate
import java.util.Locale
import kotlin.math.roundToInt

/** What the run overlay says for a batch run's tick, worded from its frame fields. */
object RunOverlayText {

    private const val MS_PER_SECOND = 1000.0

    /** "57.0%": the ring's one-decimal percentage. */
    fun percent(value: Float): String = String.format(Locale.US, "%.1f%%", value)

    /** "Frame 23 of 40", or null for a single-frame run or before the first frame. */
    fun frameCount(res: Resources, tick: BatchProgressUpdate): String? {
        val planned = tick.plannedFrames
        if (planned <= 1 || tick.frameIndex < 0) return null
        return res.getString(R.string.run_frame_of_fmt, minOf(tick.frameIndex + 1, planned), planned)
    }

    /** The line under the graph: correlating, then completed while the run saves. */
    fun status(res: Resources, tick: BatchProgressUpdate): String {
        val planned = tick.plannedFrames
        val framePercent = tick.framePercent.roundToInt()
        return when {
            planned > 0 && tick.frameIndex >= planned ->
                res.getQuantityString(R.plurals.run_completed_fmt, planned, planned, planned)
            tick.frameIndex < 0 -> tick.status
            planned > 1 -> res.getString(R.string.run_correlating_fmt, tick.frameIndex + 1, framePercent)
            else -> res.getString(R.string.run_correlating_single_fmt, framePercent)
        }
    }

    /**
     * "1.9 s a frame" from the frames finished so far, or null before the
     * first finishes. The first frame carries the reference caching, so the
     * pace settles as frames add up.
     */
    fun pace(res: Resources, tick: BatchProgressUpdate, elapsedMs: Long): String? {
        val finished = tick.frameIndex.coerceAtMost(tick.plannedFrames)
        if (tick.plannedFrames <= 1 || finished <= 0) return null
        return res.getString(R.string.run_pace_fmt, elapsedMs / MS_PER_SECOND / finished)
    }

    /** The one-strike warning, or null when no frame leaves the run one low frame from stopping. */
    fun strikeWarning(res: Resources, values: FloatArray): String? =
        ConvergenceTrace.pendingStrike(values)?.let {
            res.getString(R.string.run_strike_warn_fmt, it.frameNumber, it.convergencePercent)
        }
}
