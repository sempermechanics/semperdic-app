package com.sempermechanics.semper.ui.analysis.run

import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel

/**
 * Reads a run's per-frame convergence the way [ConvergenceGate] counts it, so
 * the overlay can warn one low frame before the gate stops the run.
 */
object ConvergenceTrace {

    /** A low frame that leaves the run one more low frame from stopping. */
    data class Strike(val frameNumber: Int, val convergencePercent: Float)

    /**
     * The latest frame of a streak that is one short of [strikes], or null when
     * no streak is that long. Walks [values] in frame order as the gate saw
     * them: NaN (not solved, or no points) and negative values neither count
     * nor break a streak, as in [ConvergenceGate.record].
     */
    fun pendingStrike(
        values: FloatArray,
        minPercent: Float = AnalysisViewModel.MIN_CONVERGENCE_PERCENT,
        strikes: Int = AnalysisViewModel.LOW_CONVERGENCE_STRIKES,
    ): Strike? {
        var streak = 0
        var last: Strike? = null
        for ((index, value) in values.withIndex()) {
            if (value.isNaN() || value < 0f) continue
            if (value < minPercent) {
                streak++
                last = Strike(index + 1, value)
            } else {
                streak = 0
                last = null
            }
        }
        return last.takeIf { strikes > 1 && streak == strikes - 1 }
    }

    /** The most recently solved frame's convergence, or null before the first. */
    fun latest(values: FloatArray): Float? = values.lastOrNull { !it.isNaN() && it >= 0f }
}
