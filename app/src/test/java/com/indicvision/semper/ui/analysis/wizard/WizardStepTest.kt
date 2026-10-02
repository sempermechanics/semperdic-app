package com.indicvision.semper.ui.analysis.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The wizard's pages keep the numbers the saved state has always held. */
class WizardStepTest {

    @Test
    fun `each page keeps its saved number`() {
        assertEquals(listOf(1, 2, 3), WizardStep.entries.map { it.number })
        WizardStep.entries.forEach { assertEquals(it, WizardStep.of(it.number)) }
    }

    @Test
    fun `a number out of range lands on the nearest page`() {
        assertEquals(WizardStep.IMAGES, WizardStep.of(0))
        assertEquals(WizardStep.IMAGES, WizardStep.of(-4))
        assertEquals(WizardStep.SWEEP, WizardStep.of(7))
    }

    @Test
    fun `the sweep page shows only in sweep mode`() {
        assertEquals(WizardStep.SETTINGS, WizardStep.shown(WizardStep.SWEEP, sweepMode = false))
        assertEquals(WizardStep.SWEEP, WizardStep.shown(WizardStep.SWEEP, sweepMode = true))
        assertEquals(WizardStep.IMAGES, WizardStep.shown(WizardStep.IMAGES, sweepMode = false))
        assertEquals(2, WizardStep.count(sweepMode = false))
        assertEquals(3, WizardStep.count(sweepMode = true))
    }

    @Test
    fun `back goes one page at a time, and not past the first`() {
        assertEquals(WizardStep.SETTINGS, WizardStep.SWEEP.previous)
        assertEquals(WizardStep.IMAGES, WizardStep.SETTINGS.previous)
        assertNull(WizardStep.IMAGES.previous)
    }

    @Test
    fun `the view model's saved number follows its page`() {
        val vm = AnalysisViewModel()
        assertEquals(1, vm.wizardStep)
        vm.wizardStep = 3
        assertEquals(WizardStep.SWEEP, vm.step)
        vm.step = WizardStep.SETTINGS
        assertEquals(2, vm.wizardStep)
    }
}
