package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.ui.analysis.sweep.SweepSetupController

/**
 * What the wizard's parts call back on the screen that hosts them: the sweep
 * setup's callbacks (dropping the last run's status line among them), and the
 * refreshes every changed input asks for.
 */
interface AnalysisWizardHost : SweepSetupController.Callbacks {

    /** The subset or its recommendation changed: the sweep re-seeds its suggestions, once it is set up. */
    fun onSweepInputsChanged()

    /** The settings went back to their defaults: so do the sweep's, once it is set up. */
    fun resetSweepInputs()
}
