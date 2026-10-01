package com.indicvision.semper.ui.analysis.run

import com.indicvision.semper.ui.analysis.sweep.VsgStudyRunner
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel

/**
 * Outcome codes for an analysis or sweep that are not native-engine failures.
 * Shared by [AnalysisViewModel] and [VsgStudyRunner].
 */
object AnalysisRunCodes {
    /** User cancelled the run (not an engine failure). */
    const val ERROR_CANCELLED = -99

    /** A new session would exceed the account quota. */
    const val ERROR_SESSION_LIMIT = -98

    /** Two consecutive frames had convergence below 50%. */
    const val ERROR_LOW_CONVERGENCE = -96
}
