package com.sempermechanics.semper.ui.analysis.sweep

import com.google.android.material.button.MaterialButtonToggleGroup
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Switching the analysis mode to single while on the sweep page goes back to
 * settings, and switching it either way drops the last run's status line.
 */
@RunWith(RobolectricTestRunner::class)
class SweepModeToggleTest {

    private val bed = WizardTestBed()

    @After
    fun closeBed() = bed.close()

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

    @Test
    fun `switching to sweep after a single run drops its status line`() {
        bed.binding.stubStepSweep.inflate()
        bed.viewModel.step = WizardStep.SETTINGS
        SweepSetupController(bed.activity, bed.viewModel, bed.host).setup()
        bed.host.calls.clear()

        bed.activity.findViewById<MaterialButtonToggleGroup>(R.id.rgAnalysisMode).check(R.id.rbModeSweep)
        bed.idle()

        assertEquals(1, bed.host.count("clearRunStatus"))
        assertTrue(bed.viewModel.sweepMode)
        // The re-check redraws the ROI row, which drops its point count in sweep mode.
        assertTrue(bed.host.count("checkReady") >= 1)
    }

    @Test
    fun `switching back to single drops it too`() {
        stepsAfterLeavingSweepMode(on = WizardStep.SETTINGS)

        assertTrue(bed.host.count("clearRunStatus") >= 1)
        assertTrue(bed.host.count("checkReady") >= 1)
    }
}
