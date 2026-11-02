package com.sempermechanics.semper.ui.analysis.wizard

import androidx.annotation.VisibleForTesting
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner

/**
 * Single cancel channel for batch analysis and parameter sweeps.
 * [AnalysisViewModel] owns the public API; [SweepStudyRunner] observes the same flag.
 */
object AnalysisCancel {
    /**
     * Hands the flag to the engine. A lambda, not a reference to
     * [SemperNativeLib.setCancelRequested], so the library is loaded on the
     * first cancel and not when this object is.
     *
     * A JVM test has no `semper_core`; it swaps in a no-op here so it can
     * destroy the wizard, whose `onDestroy` cancels, and restores this after
     * (TD-200).
     */
    @VisibleForTesting
    internal var toEngine: (Boolean) -> Unit = { SemperNativeLib.setCancelRequested(it) }

    @Volatile
    var requested: Boolean = false
        set(value) {
            field = value
            toEngine(value)
        }

    fun clear() {
        requested = false
    }

    /**
     * Hands a cancel the engine may have dropped back to it. The engine clears
     * its flag as each solve starts (`full_field_solver.cpp`), so a Cancel
     * confirmed between a run's last check and that start would be wiped and
     * the frame solved to its end. A run calls this once its solve is under
     * way (from the solve's progress ticks): while [requested] is set, the
     * engine is told again, and the solve stops at its next poll. Any thread.
     * Returns whether it told the engine.
     */
    fun reassertIfRequested(): Boolean {
        if (!requested) return false
        toEngine(true)
        return true
    }
}
