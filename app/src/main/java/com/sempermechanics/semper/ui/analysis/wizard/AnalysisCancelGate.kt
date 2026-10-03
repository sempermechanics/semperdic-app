package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudyRunner

/**
 * Single cancel channel for batch analysis and parameter sweeps.
 * [AnalysisViewModel] owns the public API; [VsgStudyRunner] observes the same flag.
 */
object AnalysisCancelGate {
    @Volatile
    var requested: Boolean = false
        set(value) {
            field = value
            SemperNativeLib.setCancelRequested(value)
        }

    fun clear() {
        requested = false
    }
}
