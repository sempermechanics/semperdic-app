package com.sempermechanics.semper.ui.analysis.sweep

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputLayout
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.recommend.RunEstimate
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

/**
 * The sweep's two pages: step 2 picks the frame in a dialog and shows the
 * planned lattice, the sample counts under its gear, and a frame thumbnail
 * that opens at full width; step 3 holds the ranges, says the VSG span they
 * cover, opens its line-cut strip on a tap, and its run button counts the
 * analyses and, once this phone has a rate, the time.
 */
@RunWith(RobolectricTestRunner::class)
class SweepStepsTest {

    private val bed = WizardTestBed()
    private lateinit var controller: SweepSetupController

    @After
    fun closeBed() = bed.close()

    private fun open(frames: Int) {
        bed.binding.stubStepSweep.inflate()
        bed.viewModel.deformedFrames = List(frames) { DeformedFrame("/tmp/def$it.png", "steel_$it.tif") }
        bed.viewModel.refSize = ImageSize(1200, 900)
        bed.viewModel.hasCustomRoi = true
        bed.viewModel.roi = Roi(50, 50, 1100, 800)
        bed.viewModel.sweepMode = true
        bed.viewModel.step = WizardStep.SETTINGS
        controller = SweepSetupController(bed.activity, bed.viewModel, bed.host).also { it.setup() }
        bed.idle()
    }

    private fun <T : View> view(id: Int): T = bed.activity.findViewById(id)

    @Test
    fun `step 2 names the swept frame in its field, middle frame first`() {
        open(frames = 40)

        assertEquals(View.VISIBLE, view<View>(R.id.sweepFrameBlock).visibility)
        assertEquals("steel_20.tif", view<EditText>(R.id.ddSweepFrame).text.toString())
        assertEquals("Frame 21 of 40", view<TextInputLayout>(R.id.tilSweepFrame).helperText.toString())
    }

    @Test
    fun `a tap on the field opens the frame dialog, and its OK makes the pick the swept frame`() {
        open(frames = 40)

        view<View>(R.id.ddSweepFrame).performClick()
        bed.idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val rows = dialog.findViewById<RadioGroup>(R.id.rgSweepFrames)!!
        assertEquals(40, rows.childCount)
        assertTrue((rows.getChildAt(20) as RadioButton).isChecked)

        (rows.getChildAt(22) as RadioButton).isChecked = true
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        bed.idle()

        assertEquals(22, bed.viewModel.sweepFrameIndex)
        assertEquals("steel_22.tif", view<EditText>(R.id.ddSweepFrame).text.toString())
        assertEquals("Frame 23 of 40", view<TextInputLayout>(R.id.tilSweepFrame).helperText.toString())
    }

    @Test
    fun `a single frame has nothing to pick`() {
        open(frames = 1)

        assertEquals(View.GONE, view<View>(R.id.sweepFrameBlock).visibility)
    }

    @Test
    fun `the strain window caption spans the plan's VSGs`() {
        open(frames = 40)
        val plan = controller.currentPlan()

        assertTrue(plan.isNotEmpty())
        assertEquals(
            "Fits planes over ${plan.minOf { it.vsg }}–${plan.maxOf { it.vsg }} px (VSG)",
            view<TextView>(R.id.tvStrainWinVsg).text.toString(),
        )
    }

    @Test
    fun `the run button counts the analyses, and adds the time once this phone has a rate`() {
        open(frames = 40)
        val plan = controller.currentPlan()

        assertEquals("Run ${plan.size} analyses", bed.binding.btnRunSweep.text.toString())

        AppSettings.setRunPointsPerSecond(bed.activity, 5000)
        controller.refreshSweepPlan()

        // Each analysis solves the one frame at its own step over the drawn region.
        val points = plan.sumOf { RunEstimate.gridPoints(1100, 800, it.step) }
        val seconds = RunEstimate.seconds(points, 1, 5000)!!
        assertEquals(
            "Run ${plan.size} analyses · about $seconds s",
            bed.binding.btnRunSweep.text.toString(),
        )
    }

    @Test
    fun `the lattice gear shows and hides the sample counts`() {
        open(frames = 40)
        val samples = view<View>(R.id.latticeSamplesBody)
        assertEquals(View.GONE, samples.visibility)

        view<View>(R.id.btnLatticeSamples).performClick()
        assertEquals(View.VISIBLE, samples.visibility)

        view<View>(R.id.btnLatticeSamples).performClick()
        assertEquals(View.GONE, samples.visibility)
    }

    @Test
    fun `a tap on the frame thumbnail opens the frame at full width, a tap on it closes it`() {
        open(frames = 40)
        val large = view<ImageView>(R.id.imgSweepFrameLarge)
        large.setImageDrawable(ColorDrawable(Color.GRAY))

        view<View>(R.id.imgSweepFrame).performClick()
        assertEquals(View.VISIBLE, large.visibility)
        assertEquals("Frame to sweep, tap to shrink", view<View>(R.id.imgSweepFrame).contentDescription)

        large.performClick()
        assertEquals(View.GONE, large.visibility)
        assertEquals("Frame to sweep, tap to enlarge", view<View>(R.id.imgSweepFrame).contentDescription)
    }

    @Test
    fun `a tap on the line-cut strip opens it, a second shrinks it back`() {
        open(frames = 40)
        val preview = view<View>(R.id.lineCutPreview)
        val strip = bed.activity.resources.getDimensionPixelSize(R.dimen.line_cut_preview_strip)
        assertEquals(strip, preview.layoutParams.height)
        assertEquals("Line cut axis, tap to enlarge", preview.contentDescription)

        preview.performClick()
        assertEquals("Line cut axis, tap to shrink", preview.contentDescription)

        preview.performClick()
        assertEquals(strip, preview.layoutParams.height)
        assertEquals("Line cut axis, tap to enlarge", preview.contentDescription)
    }
}
