package com.sempermechanics.semper.ui.analysis.wizard

import android.app.Application
import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.ParamClipboard
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommendationController
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommender
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import org.junit.After
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
 * onto the sliders, Reset goes back to the recommended defaults, and the
 * Advanced section opens on a tap or on a Paste that changes what it shows.
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

    @After
    fun closeBed() = bed.close()

    @Test
    fun `a run takes the window as a VSG in px at the slider's step`() {
        bed.settings.sliderSubsetSize.value = 31f
        bed.settings.sliderStepSize.value = 4f
        bed.settings.sliderStrainWindow.value = 7f

        assertEquals(DicParams(31, 4, SweepStudy.vsgFor(7, 4)), fields.dicParams())
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
        bed.settings.ddInterpolator.setText(bed.activity.getString(R.string.label_6_6_keys), false)
        assertTrue(fields.isKeysInterpolatorSelected())
        bed.host.calls.clear()

        fields.reset()

        assertEquals(25, bed.settings.sliderSubsetSize.value.toInt())
        assertEquals(DicParams.DEFAULT_STEP, bed.settings.sliderStepSize.value.toInt())
        assertEquals(SweepStudy.DEFAULT_WINDOW_POINTS, bed.settings.sliderStrainWindow.value.toInt())
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

    @Test
    fun `Advanced starts closed and its header opens and closes it`() {
        val body = bed.settings.advancedBody
        val chevron = bed.settings.advancedHeader.imgAdvancedChevron
        assertFalse(body.isVisible)
        assertEquals(0f, chevron.rotation)

        bed.settings.advancedHeader.root.performClick()
        assertTrue(body.isVisible)
        assertEquals(180f, chevron.rotation)

        bed.settings.advancedHeader.root.performClick()
        assertFalse(body.isVisible)
        assertEquals(0f, chevron.rotation)
    }

    @Test
    fun `a Paste that moves the overlap opens Advanced`() {
        fields.reset() // subset 41, step 5
        assertEquals("0.88", bed.settings.etOverlapValue.text.toString())
        // Subset 41 at step 10: the overlap goes from 0.88 to 0.76.
        ParamClipboard.copy(bed.activity, subset = 41, step = 10, vsg = 41)

        fields.paste()

        assertEquals("0.76", bed.settings.etOverlapValue.text.toString())
        assertTrue(bed.settings.advancedBody.isVisible)
    }

    @Test
    fun `a Paste that leaves the overlap and interpolation as shown keeps Advanced closed`() {
        fields.reset()
        val subset = bed.settings.sliderSubsetSize.value.toInt()
        ParamClipboard.copy(bed.activity, subset = subset, step = DicParams.DEFAULT_STEP, vsg = 61)

        fields.paste()

        val window = bed.settings.sliderStrainWindow.value.toInt()
        assertEquals(SweepStudy.nearestWindowPoints(61, DicParams.DEFAULT_STEP), window)
        assertFalse(bed.settings.advancedBody.isVisible)
    }
}
