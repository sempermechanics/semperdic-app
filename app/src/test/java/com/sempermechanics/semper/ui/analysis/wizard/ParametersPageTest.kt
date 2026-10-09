package com.sempermechanics.semper.ui.analysis.wizard

import android.app.Application
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.databinding.WizardStepSweepBinding
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommendationController
import com.sempermechanics.semper.ui.common.dialog.WarnChip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The parameters page's chrome: the toolbar names the page and shows a dot
 * per page; the ROI row says how many points the region holds at the step;
 * Compute says, once this phone has run one, about how long a run takes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ParametersPageTest {

    private val bed = WizardTestBed()
    private val gate = AnalysisReadyGate(
        bed.viewModel,
        bed.binding,
        bed.settings,
        WarnChip(bed.settings.frameSizeWarnRow.root) {},
    )

    @After
    fun tearDown() = bed.close()

    /** A 1200 × 900 reference with a 1100 × 800 region, 40 frames, a 10 px step: 8,800 points. */
    private fun loadRun() {
        bed.viewModel.refSize = ImageSize(1200, 900)
        bed.viewModel.hasCustomRoi = true
        bed.viewModel.roi = Roi(50, 50, 1100, 800)
        bed.viewModel.deformedFrames = List(40) { DeformedFrame("/tmp/def$it.png", "def$it.png") }
        bed.viewModel.step = WizardStep.SETTINGS
        bed.settings.sliderSubsetSize.value = 31f
        bed.settings.sliderStepSize.value = 10f
    }

    @Test
    fun `the ROI row counts the region's points, and Compute stays plain before any run`() {
        loadRun()

        gate.apply(isProcessing = false, sweepController = null)

        assertEquals("1100 × 800 px · 8,800 points", bed.settings.tvInstruction.text.toString())
        assertEquals("Compute", bed.binding.btnCalculateFullField.text.toString())
    }

    @Test
    fun `the ROI row's count follows the step and the region`() {
        loadRun()
        val subsets = SubsetRecommendationController(bed.activity, bed.viewModel, bed.binding, bed.settings, bed.host)
        WizardParamFields(bed.activity, bed.viewModel, bed.settings, subsets, bed.host) {
            gate.apply(isProcessing = false, sweepController = null)
        }.bind()

        bed.settings.sliderStepSize.value = 5f
        assertEquals("1100 × 800 px · 35,200 points", bed.settings.tvInstruction.text.toString())

        // The full 1200 × 900 frame, inset 25 px a side for the 31 px subset: 1150 × 850.
        bed.viewModel.hasCustomRoi = false
        gate.apply(isProcessing = false, sweepController = null)
        assertEquals("Full image · 39,100 points", bed.settings.tvInstruction.text.toString())
    }

    @Test
    fun `once this phone has a rate Compute gives the time`() {
        loadRun()
        AppSettings.setRunPointsPerSecond(bed.activity, 5000)

        gate.apply(isProcessing = false, sweepController = null)

        assertEquals("Compute · about 71 s", bed.binding.btnCalculateFullField.text.toString())
    }

    @Test
    fun `without a region there is no point count and no time`() {
        AppSettings.setRunPointsPerSecond(bed.activity, 5000)

        gate.apply(isProcessing = false, sweepController = null)

        assertEquals("Full image", bed.settings.tvInstruction.text.toString())
        assertEquals("Compute", bed.binding.btnCalculateFullField.text.toString())
    }

    @Test
    fun `the toolbar names each page and marks it among the dots`() {
        val sweepPage = WizardStepSweepBinding.bind(bed.binding.stubStepSweep.inflate())
        val settingsPage = com.sempermechanics.semper.databinding.WizardStepSettingsBinding.bind(
            bed.binding.root.findViewById(R.id.scrollStepSettings),
        )
        val chrome = AnalysisWizardChrome(bed.activity, bed.binding, settingsPage, sweepPage)

        chrome.updateBottomNav(WizardStep.IMAGES, sweepMode = false)
        assertEquals("New analysis", bed.binding.toolbar.title.toString())
        assertEquals("● ●", bed.binding.tvStepDots.text.toString())
        assertEquals("Step 1 of 2", bed.binding.tvStepDots.contentDescription.toString())

        chrome.updateBottomNav(WizardStep.SETTINGS, sweepMode = false)
        assertEquals("Parameters", bed.binding.toolbar.title.toString())

        chrome.updateBottomNav(WizardStep.SETTINGS, sweepMode = true)
        assertEquals("Sweep setup", bed.binding.toolbar.title.toString())
        assertEquals("● ● ●", bed.binding.tvStepDots.text.toString())
        assertEquals("Step 2 of 3", bed.binding.tvStepDots.contentDescription.toString())

        chrome.updateBottomNav(WizardStep.SWEEP, sweepMode = true)
        assertEquals("Sweep settings", bed.binding.toolbar.title.toString())
        assertEquals(null, bed.binding.toolbar.subtitle)
    }
}
