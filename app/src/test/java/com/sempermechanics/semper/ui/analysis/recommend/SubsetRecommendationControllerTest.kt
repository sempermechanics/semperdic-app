package com.sempermechanics.semper.ui.analysis.recommend

import android.app.Application
import androidx.core.view.isVisible
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recommendation seeds the subset slider until the user sets their own,
 * re-seeds the sweep, and reports the speckle size on one chip at most.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SubsetRecommendationControllerTest {

    private lateinit var bed: WizardTestBed
    private lateinit var subsets: SubsetRecommendationController

    @Before
    fun setUp() {
        bed = WizardTestBed()
        subsets = SubsetRecommendationController(bed.activity, bed.viewModel, bed.binding, bed.settings, bed.host)
    }

    private fun recommend(subset: Int, speckle: Double? = null, capped: Int = 0) {
        bed.viewModel.subsetRecommendation = SubsetRecommender.Recommendation(
            subsetSize = subset,
            samples = 10,
            cappedSamples = capped,
            speckleDiameterPx = speckle,
        )
    }

    @Test
    fun `before a measurement the form shows the historical subset`() {
        assertEquals(DicParams.DEFAULT_SUBSET, subsets.defaultSubsetSize())
        recommend(30)
        assertEquals(31, subsets.defaultSubsetSize()) // snapped onto the odd grid
    }

    @Test
    fun `a recommendation seeds an untouched slider and re-seeds the sweep`() {
        recommend(25, speckle = 5.0)
        subsets.apply()

        assertEquals(25, bed.settings.sliderSubsetSize.value.toInt())
        assertEquals(listOf("commitParamFields", "onSweepInputsChanged"), bed.host.calls)
        assertTrue(bed.settings.tvSpeckleReadout.isVisible)
        assertFalse(bed.binding.speckleWarnRow.root.isVisible)
        assertFalse(bed.binding.lowTextureWarnRow.root.isVisible)
    }

    @Test
    fun `a size the user set is kept`() {
        bed.viewModel.subsetUserModified = true
        bed.settings.sliderSubsetSize.value = 61f
        recommend(25)
        subsets.apply()

        assertEquals(61, bed.settings.sliderSubsetSize.value.toInt())
        assertEquals(listOf("onSweepInputsChanged"), bed.host.calls)
    }

    @Test
    fun `too fine a speckle shows the size chip, and it suppresses the span chip`() {
        bed.viewModel.subsetUserModified = true
        bed.settings.sliderSubsetSize.value = 15f
        recommend(25, speckle = 1.5, capped = 10)
        subsets.apply()

        assertTrue(bed.binding.speckleWarnRow.root.isVisible)
        assertTrue(bed.binding.lowTextureWarnRow.root.isVisible)
        assertFalse(bed.settings.speckleSpanWarnRow.root.isVisible)
    }

    @Test
    fun `no measurement hides every speckle surface`() {
        recommend(25, speckle = 1.5)
        subsets.apply()
        bed.viewModel.subsetRecommendation = null
        subsets.apply()

        assertFalse(bed.settings.tvSpeckleReadout.isVisible)
        assertFalse(bed.binding.speckleWarnRow.root.isVisible)
        assertFalse(bed.binding.lowTextureWarnRow.root.isVisible)
    }

    @Test
    fun `nothing is measured without a reference`() {
        subsets.request()
        assertEquals(null, bed.viewModel.subsetRecommendationKey)
    }
}
