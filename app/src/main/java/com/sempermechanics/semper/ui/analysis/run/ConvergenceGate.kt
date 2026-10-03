package com.sempermechanics.semper.ui.analysis.run

import com.sempermechanics.semper.ui.analysis.sweep.VsgStudyRunner
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
 * subset, the one most likely to under-converge. See [VsgStudyRunner.run].
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
}
