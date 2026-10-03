package com.sempermechanics.semper.ui.analysis.sweep

import com.google.android.material.button.MaterialButtonToggleGroup
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Switching the analysis mode to single while on the sweep page goes back to settings. */
@RunWith(RobolectricTestRunner::class)
class SweepModeToggleTest {

    private val bed = WizardTestBed()

    /** The page calls the host made after the mode was switched to single on page [on]. */
    private fun stepsAfterLeavingSweepMode(on: WizardStep): List<String> {
        bed.binding.stubStepSweep.inflate()
        bed.viewModel.sweepMode = true
        bed.viewModel.step = on
        SweepSetupController(bed.activity, bed.viewModel, bed.host).setup()

        bed.activity.findViewById<MaterialButtonToggleGroup>(R.id.rgAnalysisMode).check(R.id.rbModeSingle)
        bed.idle()

        return bed.host.calls.filter { it.startsWith("goToStep") }
    }

    @Test
    fun `on the sweep page it goes back to settings`() {
        assertEquals(listOf("goToStep SETTINGS"), stepsAfterLeavingSweepMode(on = WizardStep.SWEEP))
    }

    @Test
    fun `on the settings page it stays`() {
        assertEquals(emptyList<String>(), stepsAfterLeavingSweepMode(on = WizardStep.SETTINGS))
    }
}
