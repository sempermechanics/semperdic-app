package com.sempermechanics.semper.ui.analysis.run

import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel

/**
 * Decides when a run has lost the speckle and should stop.
 *
 * Convergence below [AnalysisViewModel.MIN_CONVERGENCE_PERCENT] on
 * [AnalysisViewModel.LOW_CONVERGENCE_STRIKES] consecutive solves means the image
 * pair has decorrelated, not that one frame was unlucky — the frames after it
 * will be no better, and finishing the run only spends minutes producing fields
 * nobody should trust.
 *
 * The batch path only. A sweep runs its whole plan: its consecutive solves are
 * parameter combinations on one frame pair, not successive frames, so "the next
 * one will be no better" does not follow — and the plan starts at the smallest
 * subset, the one most likely to under-converge. See [SweepStudyRunner.run].
 */
class ConvergenceGate(
    private val minPercent: Float = AnalysisViewModel.MIN_CONVERGENCE_PERCENT,
    private val strikes: Int = AnalysisViewModel.LOW_CONVERGENCE_STRIKES,
) {
    private var consecutiveLow = 0

    /** True once the run should stop. Stays true afterwards. */
    var shouldStop: Boolean = false
        private set

    /**
     * Records one solve's convergence and reports whether to stop.
     *
     * A negative value means the engine reported nothing for this solve; it
     * neither counts as a strike nor clears the streak, since absence of a
     * reading says nothing about correlation either way.
     */
    fun record(convergencePercent: Float): Boolean {
        if (convergencePercent < 0f) return shouldStop
        if (convergencePercent < minPercent) {
            consecutiveLow++
            if (consecutiveLow >= strikes) shouldStop = true
        } else {
            consecutiveLow = 0
        }
        return shouldStop
    }

    companion object {
        /**
         * The convergence an incremental frame is gated on. The engine's slot
         * describes the solve against the moving reference only; a run can
         * also lose first-image points one reference update at a time, and
         * those never come back. So the lower of the two: the solve's
         * [solvePercent], and the share of the first image's [frame0Points]
         * still [trackedPoints]. A negative [solvePercent] (no reading) or an
         * unknown first-image count leaves [solvePercent] as it is.
         */
        fun incremental(solvePercent: Float, trackedPoints: Int, frame0Points: Int): Float {
            if (solvePercent < 0f || frame0Points <= 0) return solvePercent
            return minOf(solvePercent, PERCENT * trackedPoints / frame0Points)
        }

        private const val PERCENT = 100f
    }
}
