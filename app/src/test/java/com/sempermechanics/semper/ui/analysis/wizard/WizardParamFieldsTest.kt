package com.sempermechanics.semper.ui.analysis.wizard

import android.app.Application
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommendationController
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommender
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings page's sliders give the run its settings, typed values snap
 * onto the sliders, and Reset goes back to the recommended defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WizardParamFieldsTest {

    private lateinit var bed: WizardTestBed
    private lateinit var subsets: SubsetRecommendationController
    private lateinit var fields: WizardParamFields

    @Before
    fun setUp() {
        bed = WizardTestBed()
        subsets = SubsetRecommendationController(bed.activity, bed.viewModel, bed.binding, bed.settings, bed.host)
        fields = WizardParamFields(bed.activity, bed.viewModel, bed.settings, subsets, bed.host).also { it.bind() }
    }

    @Test
    fun `a run takes the window as a VSG in px at the slider's step`() {
        bed.settings.sliderSubsetSize.value = 31f
        bed.settings.sliderStepSize.value = 4f
        bed.settings.sliderStrainWindow.value = 7f

        assertEquals(DicParams(31, 4, VsgStudy.vsgFor(7, 4)), fields.dicParams())
        assertEquals(31, fields.subsetSize())
    }

    @Test
    fun `a typed value snaps into range and onto the odd grid`() {
        val subset = bed.settings.sliderSubsetSize
        assertEquals(41, snapToSlider(subset, 40))
        assertEquals(subset.valueFrom.toInt(), snapToSlider(subset, -3))
        assertEquals(subset.valueTo.toInt(), snapToSlider(subset, 10_000))
    }

    @Test
    fun `typing a subset moves the slider and counts as the user's own`() {
        bed.settings.etSubsetValue.setText("52")
        bed.settings.etSubsetValue.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)

        assertEquals(snapToSlider(bed.settings.sliderSubsetSize, 52), bed.settings.sliderSubsetSize.value.toInt())
        assertTrue(bed.viewModel.subsetUserModified)
        assertTrue(bed.host.count("clearRunStatus") > 0)
    }

    @Test
    fun `Reset goes back to the recommendation and the default step, window and interpolator`() {
        bed.viewModel.subsetRecommendation =
            SubsetRecommender.Recommendation(subsetSize = 25, samples = 10, cappedSamples = 0)
        bed.viewModel.subsetUserModified = true
        bed.settings.sliderSubsetSize.value = 61f
        bed.settings.sliderStepSize.value = 9f
        bed.settings.sliderStrainWindow.value = 11f
        bed.settings.rgInterpolator.check(R.id.rbKeys)
        bed.host.calls.clear()

        fields.reset()

        assertEquals(25, bed.settings.sliderSubsetSize.value.toInt())
        assertEquals(DicParams.DEFAULT_STEP, bed.settings.sliderStepSize.value.toInt())
        assertEquals(VsgStudy.DEFAULT_WINDOW_POINTS, bed.settings.sliderStrainWindow.value.toInt())
        assertFalse(fields.isKeysInterpolatorSelected())
        assertFalse(bed.viewModel.subsetUserModified)
        assertEquals(listOf("commitParamFields", "resetSweepInputs", "clearRunStatus"), bed.host.calls)
    }

    @Test
    fun `Reset with nothing measured shows the historical subset`() {
        bed.settings.sliderSubsetSize.value = 61f
        fields.reset()
        assertEquals(DicParams.DEFAULT_SUBSET, bed.settings.sliderSubsetSize.value.toInt())
    }
}
