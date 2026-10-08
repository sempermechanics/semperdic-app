package com.sempermechanics.semper.ui.analysis.recommend

import android.app.Application
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.core.view.isVisible
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import com.sempermechanics.semper.ui.common.InlineBusy
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

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

    @After
    fun closeBed() = bed.close()

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

    private fun idleFor(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    }

    /** Makes the subset slider visible on screen, as it is on page 2, so a recommendation glides it. */
    private fun showSlider() {
        var view: View? = bed.settings.sliderSubsetSize
        while (view != null) {
            view.visibility = View.VISIBLE
            view = view.parent as? View
        }
    }

    @Test
    fun `the readout says what the measurement set, and only the measurement once the user sets a size`() {
        recommend(31, speckle = 4.3)
        subsets.apply()
        assertEquals(
            "Set to 31 px from speckle 4.3 px. Good practice asks for 3–9 px.",
            bed.settings.tvSpeckleReadout.text.toString(),
        )

        bed.viewModel.subsetUserModified = true
        subsets.showSpeckleFeedback()
        assertEquals(
            "Speckle measures about 4.3 px across. Good practice asks for 3–9 px.",
            bed.settings.tvSpeckleReadout.text.toString(),
        )
    }

    @Test
    fun `a slow measurement says so, and a failed one clears the readout`() {
        val held = mutableListOf<Runnable>()
        val measuring = SubsetRecommendationController(
            bed.activity,
            bed.viewModel,
            bed.binding,
            bed.settings,
            bed.host,
            measureOn = { Executor { held += it }.asCoroutineDispatcher() },
        )
        // Too small for the smallest subset: the measurement comes back empty.
        bed.viewModel.applyNewReference(ByteArray(64), "ref.png", ImageSize(8, 8))
        val readout = bed.settings.tvSpeckleReadout

        measuring.request()
        idleFor(InlineBusy.SHOW_AFTER_MS - 1)
        assertFalse("nothing for the first 300 ms", readout.isVisible)
        idleFor(2)
        assertTrue(readout.isVisible)
        assertEquals("Measuring speckle…", readout.text.toString())

        while (held.isNotEmpty()) held.removeAt(0).run()
        bed.idle()
        assertFalse("a failed measurement leaves no caption", readout.isVisible)
        assertEquals(null, bed.viewModel.subsetRecommendation)
    }

    @Test
    fun `on screen the slider glides to the recommendation, then the sweep follows`() {
        showSlider()
        recommend(25, speckle = 5.0)
        subsets.apply()

        assertEquals("the readout states the new size at once", true, bed.settings.tvSpeckleReadout.isVisible)
        assertEquals(DicParams.DEFAULT_SUBSET, bed.settings.sliderSubsetSize.value.toInt())
        assertEquals(listOf("commitParamFields"), bed.host.calls)

        idleFor(GLIDE_PAST_MS)
        assertEquals(25, bed.settings.sliderSubsetSize.value.toInt())
        assertEquals(listOf("commitParamFields", "onSweepInputsChanged"), bed.host.calls)
    }

    @Test
    fun `reading the size lands a glide still running`() {
        showSlider()
        recommend(25)
        subsets.apply()

        subsets.settle()
        assertEquals(25, bed.settings.sliderSubsetSize.value.toInt())
        assertEquals(1, bed.host.count("onSweepInputsChanged"))
    }

    @Test
    fun `with animations off the slider jumps`() {
        Settings.Global.putFloat(bed.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        showSlider()
        recommend(25)
        subsets.apply()

        assertEquals(25, bed.settings.sliderSubsetSize.value.toInt())
        assertEquals(listOf("commitParamFields", "onSweepInputsChanged"), bed.host.calls)
    }

    private companion object {
        const val GLIDE_PAST_MS = 400L
    }
}
