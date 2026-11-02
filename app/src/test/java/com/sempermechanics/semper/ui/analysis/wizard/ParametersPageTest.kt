package com.sempermechanics.semper.ui.analysis.wizard

import android.app.Application
import android.widget.TextView
import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.databinding.WizardStepSweepBinding
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommendationController
import com.sempermechanics.semper.ui.analysis.sweep.SweepSetupController
import com.sempermechanics.semper.ui.common.dialog.WarnChip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        WarnChip(bed.settings.gridSizeWarnRow.root) {},
    )

    /** The sweep page's controller, its views never found: its Run button is not there to enable. */
    private val sweep = SweepSetupController(bed.activity, bed.viewModel, bed.host)

    @After
    fun tearDown() = bed.close()

    /** A 1200 × 900 reference with a 1100 × 800 region, 40 frames, a 10 px step: 8,800 points. */
    private fun loadRun() {
        bed.viewModel.edit {
            it.copy(
                refSize = ImageSize(1200, 900),
                hasCustomRoi = true,
                roi = Roi(50, 50, 1100, 800),
            )
        }
        val picked = List(40) { DeformedFrame("/tmp/def$it.png", "def$it.png") }
        bed.viewModel.edit { it.copy(deformedFrames = picked) }
        bed.viewModel.edit { it.copy(step = WizardStep.SETTINGS) }
        bed.settings.sliderSubsetSize.value = 31f
        bed.settings.sliderStepSize.value = 10f
    }

    @Test
    fun `the ROI row counts the region's points, and Compute stays plain before any run`() {
        loadRun()

        gate.apply(isProcessing = false, sweepController = sweep)

        assertEquals("1100 × 800 px · 8,800 points", bed.settings.tvInstruction.text.toString())
        assertEquals("Compute", bed.binding.btnCalculateFullField.text.toString())
    }

    @Test
    fun `the ROI row's count follows the step and the region`() {
        loadRun()
        val subsets = SubsetRecommendationController(bed.activity, bed.viewModel, bed.binding, bed.settings, bed.host)
        WizardParamFields(bed.activity, bed.viewModel, bed.settings, subsets, bed.host) {
            gate.apply(isProcessing = false, sweepController = sweep)
        }.bind()

        bed.settings.sliderStepSize.value = 5f
        assertEquals("1100 × 800 px · 35,200 points", bed.settings.tvInstruction.text.toString())

        // The full 1200 × 900 frame, inset 25 px a side for the 31 px subset: 1150 × 850.
        bed.viewModel.edit { it.copy(hasCustomRoi = false) }
        gate.apply(isProcessing = false, sweepController = sweep)
        assertEquals("Full image · 39,100 points", bed.settings.tvInstruction.text.toString())
    }

    @Test
    fun `in sweep mode the ROI row names the region without a count, and single brings it back`() {
        loadRun()

        bed.viewModel.edit { it.copy(sweepMode = true) }
        gate.apply(isProcessing = false, sweepController = sweep)
        assertEquals("1100 × 800 px", bed.settings.tvInstruction.text.toString())

        bed.viewModel.edit { it.copy(sweepMode = false) }
        gate.apply(isProcessing = false, sweepController = sweep)
        assertEquals("1100 × 800 px · 8,800 points", bed.settings.tvInstruction.text.toString())

        bed.viewModel.edit { it.copy(hasCustomRoi = false, sweepMode = true) }
        gate.apply(isProcessing = false, sweepController = sweep)
        assertEquals("Full image", bed.settings.tvInstruction.text.toString())
    }

    @Test
    fun `a grid this heap cannot hold says so and blocks Compute`() {
        loadRun()
        bed.viewModel.edit { it.copy(refBytes = ByteArray(1), settingsReviewed = true) }
        // Room for 8,000 points at four 32-byte copies each; the run needs 8,800.
        val tight = AnalysisReadyGate(
            bed.viewModel,
            bed.binding,
            bed.settings,
            WarnChip(bed.settings.frameSizeWarnRow.root) {},
            WarnChip(bed.settings.gridSizeWarnRow.root) {},
            maxHeapBytes = 8_000L * 4 * 32,
        )

        tight.apply(isProcessing = false, sweepController = sweep)

        assertFalse(bed.binding.btnCalculateFullField.isEnabled)
        assertTrue(bed.settings.gridSizeWarnRow.root.isVisible)
        assertEquals(
            "8,800 points is more than this phone has memory for (8,000 at most). " +
                "Raise the step or draw a smaller ROI.",
            bed.settings.gridSizeWarnRow.root.findViewById<TextView>(R.id.tvWarnText).text.toString(),
        )

        gate.apply(isProcessing = false, sweepController = sweep)
        assertTrue("this JVM's heap holds it", bed.binding.btnCalculateFullField.isEnabled)
        assertFalse(bed.settings.gridSizeWarnRow.root.isVisible)
    }

    @Test
    fun `once this phone has a rate Compute gives the time`() {
        loadRun()
        AppSettings.setRunPointsPerSecond(bed.activity, 5000)

        gate.apply(isProcessing = false, sweepController = sweep)

        // Short on the half-width button, as the sweep's Run button is; spoken in full
        // (readiness AE1V-001: "Compute · about 71 s" did not fit at font scale 2).
        assertEquals("Compute · 71 s", bed.binding.btnCalculateFullField.text.toString())
        assertEquals("Compute, about 71 seconds", bed.binding.btnCalculateFullField.contentDescription)
    }

    @Test
    fun `without a region there is no point count and no time`() {
        AppSettings.setRunPointsPerSecond(bed.activity, 5000)

        gate.apply(isProcessing = false, sweepController = sweep)

        assertEquals("Full image", bed.settings.tvInstruction.text.toString())
        assertEquals("Compute", bed.binding.btnCalculateFullField.text.toString())
        assertEquals(null, bed.binding.btnCalculateFullField.contentDescription)
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
        assertEquals("Next", bed.binding.btnNext.text.toString())
        assertEquals("● ● ●", bed.binding.tvStepDots.text.toString())
        assertEquals("Step 2 of 3", bed.binding.tvStepDots.contentDescription.toString())

        chrome.updateBottomNav(WizardStep.SWEEP, sweepMode = true)
        assertEquals("Sweep settings", bed.binding.toolbar.title.toString())
        assertEquals(null, bed.binding.toolbar.subtitle)
    }
}
