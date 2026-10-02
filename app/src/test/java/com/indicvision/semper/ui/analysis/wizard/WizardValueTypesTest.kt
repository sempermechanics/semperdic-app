package com.indicvision.semper.ui.analysis.wizard

import android.app.Application
import android.graphics.Bitmap
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.Roi
import com.indicvision.semper.field.getRoi
import com.indicvision.semper.ui.analysis.frames.DeformedFrame
import com.indicvision.semper.ui.analysis.sweep.SweepRanges
import com.indicvision.semper.ui.analysis.sweep.SweepSetupHelper
import com.indicvision.semper.ui.analysis.sweep.VsgStudy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The value types the wizard will adopt read and write exactly the saved
 * state [WizardState] writes today: the `"roi"` array, the `"sweepRanges"`
 * array and the draft's frame list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WizardValueTypesTest {

    private fun wizard(): AnalysisViewModel = AnalysisViewModel().apply {
        realRefWidth = 400
        realRefHeight = 300
        hasCustomRoi = true
        roiX = 10
        roiY = 20
        roiW = 300
        roiH = 200
        deformedFrames = listOf(
            DeformedFrame("/c/f1.png", "IMG_1.png", 100L, ImageSize(400, 300)),
            DeformedFrame("/c/f2.png", "IMG_2.png", Long.MAX_VALUE),
            DeformedFrame("/c/f3.png", "IMG_3.png", 50L, ImageSize(400, 300)),
        )
        subsetMin = 21
        subsetMax = 61
        strainWinMin = 9
        strainWinMax = 31
        subsetSamples = 4
        strainWinSamples = 5
        stepDenominator = 4
    }

    private fun AnalysisViewModel.sweepFields() =
        SweepRanges(subsetMin, subsetMax, strainWinMin, strainWinMax, subsetSamples, strainWinSamples, stepDenominator)

    @Test
    fun `the seven sweep fields read and write the view model's sweep ranges`() {
        val vm = wizard()
        assertEquals(vm.sweepFields(), vm.sweepRanges)
        vm.strainWinSamples = 7
        assertEquals(7, vm.sweepRanges.strainWinSamples)
        assertEquals(vm.sweepFields(), vm.sweepRanges)
    }

    @Test
    fun `the saved roi array reads back as the view model's ROI`() {
        val vm = wizard()
        val saved = WizardState.save(vm, "")
        assertEquals(Roi(10, 20, 300, 200), saved.getRoi("roi"))
    }

    @Test
    fun `sweep ranges pack exactly as the saved state does and restore the same fields`() {
        val vm = wizard()
        val saved = WizardState.save(vm, "")
        assertEquals(saved.getIntArray("sweepRanges")!!.toList(), vm.sweepRanges.toIntArray().toList())

        val restored = AnalysisViewModel()
        WizardState.restoreScalars(restored, saved)
        assertEquals(vm.sweepRanges, restored.sweepRanges)
        assertEquals(vm.sweepRanges, SweepRanges.fromIntArray(saved.getIntArray("sweepRanges")))
    }

    @Test
    fun `sweep ranges reject a packed array of the wrong length, as the restore does`() {
        assertNull(SweepRanges.fromIntArray(null))
        assertNull(SweepRanges.fromIntArray(IntArray(6)))
        assertNull(SweepRanges.fromIntArray(IntArray(8)))
    }

    @Test
    fun `unseeded ranges are the view model's defaults`() {
        assertEquals(SweepRanges.UNSEEDED, AnalysisViewModel().sweepRanges)
        assertEquals(SweepRanges.UNSEEDED, AnalysisViewModel().sweepFields())
    }

    /** Only [SweepSetupHelper.Callbacks.maxSubsetForRoi] matters to `currentPlan`. */
    private class Ceiling(var max: Int) : SweepSetupHelper.Callbacks {
        override fun goToStep(step: Int, animate: Boolean) = Unit
        override fun updateWizardChrome() = Unit
        override fun checkReady() = Unit
        override fun commitParamFields() = Unit
        override fun startVsgSweep() = Unit
        override fun currentSubsetSize(): Int = 21
        override fun maxSubsetForRoi(): Int = max
        override fun refPreviewBitmap(): Bitmap? = null
        override fun renderParamField(field: EditText, value: Int) = Unit
        override fun confirmOpenFaq(url: String) = Unit
    }

    /**
     * `SweepSetupHelper.currentPlan` as it was before it called [SweepRanges.plan]:
     * the body verbatim, its ceiling passed in rather than read from the callbacks.
     */
    private fun legacyCurrentPlan(viewModel: AnalysisViewModel, ceiling: Int): List<VsgStudy.Point> {
        if (viewModel.subsetMin > ceiling) return emptyList()
        return VsgStudy.plan(
            subsetMin = viewModel.subsetMin,
            subsetMax = viewModel.subsetMax.coerceAtMost(ceiling),
            subsetSamples = viewModel.subsetSamples,
            strainWinMin = viewModel.strainWinMin,
            strainWinMax = viewModel.strainWinMax,
            strainWinSamples = viewModel.strainWinSamples,
            stepDenominator = viewModel.stepDenominator,
        )
    }

    @Test
    fun `currentPlan and plan match the old inline plan for ceilings below, inside and above the range`() {
        val vm = wizard()
        val ceiling = Ceiling(0)
        // currentPlan never touches the views, so the helper is never set up.
        val helper = SweepSetupHelper(AppCompatActivity(), vm, ceiling)
        for (max in listOf(1, 20, 21, 22, 41, 60, 61, 62, 101, 301)) {
            ceiling.max = max
            val expected = legacyCurrentPlan(vm, max)
            assertEquals("currentPlan, ceiling $max", expected, helper.currentPlan())
            assertEquals("plan, ceiling $max", expected, vm.sweepRanges.plan(subsetCeiling = max))
        }
        assertEquals(emptyList<VsgStudy.Point>(), vm.sweepRanges.plan(subsetCeiling = 20))
        assertEquals(41, vm.sweepRanges.plan(subsetCeiling = 41).maxOf { it.subset })
    }

    @Test
    fun `the view model's frame lists are its deformed frames, index-aligned`() {
        val vm = wizard()
        assertEquals(listOf("/c/f1.png", "/c/f2.png", "/c/f3.png"), vm.defFilePaths)
        assertEquals(listOf("IMG_1.png", "IMG_2.png", "IMG_3.png"), vm.defOriginalNames)
        assertEquals(listOf(100L, Long.MAX_VALUE, 50L), vm.defFrameDates)
        // A frame with no measured size has no entry.
        assertEquals(mapOf("/c/f1.png" to (400 to 300), "/c/f3.png" to (400 to 300)), vm.defFrameSizes)
        assertEquals(3, vm.defCount)
    }

    @Test
    fun `draft frame list converts both ways exactly as WizardState writes and restores it`() {
        val vm = wizard()
        val draftFrames = WizardState.frames(vm)
        assertEquals(listOf(400, -1, 400), draftFrames.widths)
        val asFrames = draftFrames.toDeformedFrames()
        assertEquals(vm.deformedFrames, asFrames)
        assertEquals(draftFrames, wizardFramesOf(asFrames))
    }

    @Test
    fun `a draft frame list with short or unmeasured entries pads rather than drops frames`() {
        val draft = WizardState.Frames(
            paths = listOf("a", "b"),
            names = listOf("A"),
            widths = listOf(0, 5),
            heights = listOf(3, 5),
        )
        val frames = draft.toDeformedFrames()
        assertEquals(listOf("A", ""), frames.map { it.name })
        assertEquals(listOf(DeformedFrame.UNKNOWN_DATE, DeformedFrame.UNKNOWN_DATE), frames.map { it.date })
        assertEquals(listOf(null, ImageSize(5, 5)), frames.map { it.size })
    }
}
