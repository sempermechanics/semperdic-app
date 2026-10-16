package com.sempermechanics.semper.ui.analysis.wizard

import android.app.Application
import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.ParamClipboard
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
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
 * onto the sliders, Reset goes back to the recommended defaults, the
 * Advanced section opens on a tap or on a Paste that changes what it shows,
 * and the reference's choices are seconds of a video, frames of picked images.
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

    @Test
    fun `a Re-run's settings land on the sliders as the user's own, a large step included`() {
        fields.reset()
        // A step larger than the default subset allows: the step's range must follow the subset first.
        val run = DicParams(subset = 61, step = 18, strainWindow = SweepStudy.vsgFor(9, 18))

        fields.applyRerun(run.subset, run.step, run.strainWindow, use6x6 = true)

        assertEquals(run, fields.dicParams())
        assertTrue(fields.isKeysInterpolatorSelected())
        assertTrue("the recommendation must not move it", bed.viewModel.subsetUserModified)
    }

    @Test
    fun `a video's reference choices are seconds of the clip, run as frames at its spacing`() {
        bed.viewModel.deformedFrames = video(count = 50, spacingMs = 100)
        fields.reference.onFramesChanged()

        assertEquals(listOf(fixed, everySeconds(10), everySeconds(30), everySeconds(60)), referenceItems())
        bed.settings.ddReference.setText(everySeconds(10), false)
        assertEquals("10 s at 10 fps", 100, fields.refUpdateInterval)

        // The same choice on a clip sampled at 2 fps.
        bed.viewModel.deformedFrames = video(count = 50, spacingMs = 500)
        fields.reference.onFramesChanged()
        assertEquals(everySeconds(10), bed.settings.ddReference.text.toString())
        assertEquals(20, fields.refUpdateInterval)
    }

    @Test
    fun `picked images keep frame counts, and a video converts their choice`() {
        assertEquals(fixed, bed.settings.ddReference.text.toString())
        assertEquals(5, referenceItems().size)
        bed.settings.ddReference.setText(bed.activity.getString(R.string.reference_every_frame), false)
        assertEquals(1, fields.refUpdateInterval)

        bed.viewModel.deformedFrames = video(count = 50, spacingMs = 100)
        fields.reference.onFramesChanged()

        // Every frame lands on the shortest video choice.
        assertEquals(everySeconds(10), bed.settings.ddReference.text.toString())
    }

    @Test
    fun `a restored draft's video choice lands back once its frames are back`() {
        // The dropdown's saved text comes back before the draft's frames do.
        bed.settings.ddReference.setText(everySeconds(30), false)
        bed.viewModel.deformedFrames = video(count = 50, spacingMs = 100)

        fields.reference.onFramesChanged()

        assertEquals(everySeconds(30), bed.settings.ddReference.text.toString())
        assertEquals(300, fields.refUpdateInterval)
    }

    @Test
    fun `a Re-run of a video run saved every 2 frames lands on every 10 s`() {
        bed.viewModel.deformedFrames = video(count = 50, spacingMs = 100)

        fields.applyRerun(41, 5, 41, use6x6 = false, refUpdateInterval = 2)

        assertEquals(everySeconds(10), bed.settings.ddReference.text.toString())
        assertEquals(100, fields.refUpdateInterval)
        assertTrue(bed.settings.advancedBody.isVisible)
    }

    @Test
    fun `a Re-run of a fixed video run stays fixed`() {
        bed.viewModel.deformedFrames = video(count = 50, spacingMs = 100)

        fields.applyRerun(41, 5, 41, use6x6 = false, refUpdateInterval = 0)

        assertEquals(fixed, bed.settings.ddReference.text.toString())
        assertEquals(0, fields.refUpdateInterval)
    }

    private val fixed get() = bed.activity.getString(R.string.reference_fixed)

    private fun everySeconds(seconds: Int) = bed.activity.getString(R.string.reference_every_n_seconds, seconds)

    private fun referenceItems(): List<String> {
        val adapter = bed.settings.ddReference.adapter
        return List(adapter.count) { adapter.getItem(it).toString() }
    }

    private fun video(count: Int, spacingMs: Long) =
        List(count) { DeformedFrame(path = "f$it.png", name = "f$it", timeMs = spacingMs * (it + 1)) }
}
