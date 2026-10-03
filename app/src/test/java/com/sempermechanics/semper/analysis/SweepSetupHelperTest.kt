package com.sempermechanics.semper.analysis

import android.app.Application
import android.graphics.Bitmap
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.sweep.SweepSetupHelper
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudy
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two decisions [SweepSetupHelper] makes without its views: which frame a
 * sweep solves, and which grid it plans once the ROI caps the subset. Both
 * feed the run directly, so a wrong answer here is a wrong sweep, not a
 * cosmetic slip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SweepSetupHelperTest {

    private class FakeCallbacks(var maxSubset: Int) : SweepSetupHelper.Callbacks {
        override fun goToStep(step: WizardStep, animate: Boolean) = Unit
        override fun updateWizardChrome() = Unit
        override fun checkReady() = Unit
        override fun commitParamFields() = Unit
        override fun startVsgSweep() = Unit
        override fun currentSubsetSize(): Int = 21
        override fun maxSubsetForRoi(): Int = maxSubset
        override fun refPreviewBitmap(): Bitmap? = null
        override fun renderParamField(field: EditText, value: Int) = Unit
        override fun confirmOpenFaq(url: String) = Unit
    }

    private val vm = AnalysisViewModel()
    private val callbacks = FakeCallbacks(maxSubset = 101)

    // Neither method touches the views, so the helper is never set up.
    private val helper = SweepSetupHelper(AppCompatActivity(), vm, callbacks)

    private fun frames(n: Int) {
        vm.deformedFrames = (1..n).map { DeformedFrame("/frames/f$it.png", "") }
    }

    @Test
    fun `no frames resolves to frame zero`() {
        vm.vsgFrameIndex = 3
        assertEquals(0, helper.resolvedSweepFrame())
    }

    @Test
    fun `an unset frame defaults to the middle of the sequence`() {
        frames(5)
        assertEquals(2, helper.resolvedSweepFrame())
        frames(2)
        assertEquals(1, helper.resolvedSweepFrame())
        frames(1)
        assertEquals(0, helper.resolvedSweepFrame())
    }

    @Test
    fun `a stored frame is kept while it exists and replaced once it does not`() {
        frames(5)
        vm.vsgFrameIndex = 4
        assertEquals(4, helper.resolvedSweepFrame())
        frames(3) // the user removed frames after picking
        assertEquals(1, helper.resolvedSweepFrame())
    }

    private fun sweep(min: Int, max: Int) {
        vm.subsetMin = min
        vm.subsetMax = max
        vm.subsetSamples = 3
        vm.strainWinMin = 11
        vm.strainWinMax = 21
        vm.strainWinSamples = 2
    }

    @Test
    fun `the plan never holds a subset larger than the roi allows`() {
        sweep(21, 61)
        callbacks.maxSubset = 41
        val plan = helper.currentPlan()
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { it.subset <= 41 })
        val expected = VsgStudy.plan(
            subsetMin = 21,
            subsetMax = 41,
            subsetSamples = 3,
            strainWinMin = 11,
            strainWinMax = 21,
            strainWinSamples = 2,
            stepDenominator = vm.stepDenominator,
        )
        assertEquals(expected, plan)
    }

    @Test
    fun `a range that starts above the ceiling plans nothing`() {
        sweep(51, 61)
        callbacks.maxSubset = 41
        assertEquals(emptyList<VsgStudy.Point>(), helper.currentPlan())
    }

    @Test
    fun `a roi with room leaves the plan uncapped`() {
        sweep(21, 61)
        assertEquals(61, helper.currentPlan().maxOf { it.subset })
    }
}
