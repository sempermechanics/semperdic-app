package com.sempermechanics.semper.ui.analysis.run

import android.app.Application
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.sweep.SweepSetupController
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * A failed run names its frame and leaves the ⓘ by the status line; a sweep
 * that failed outright says so; a cancelled sweep that kept nothing opens
 * nothing. The status line clears on new input, but not mid-run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WizardRunOutcomesTest {

    private lateinit var bed: WizardTestBed
    private lateinit var chrome: RunChrome
    private lateinit var status: RunStatusLine
    private lateinit var outcomes: WizardRunOutcomes
    private var checks = 0

    @Before
    fun setUp() {
        bed = WizardTestBed()
        chrome = bed.chrome()
        status = RunStatusLine(bed.activity, chrome, bed.settings.tvStaticResult, bed.settings.btnEngineFailFaq)
        val sweep = SweepSetupController(bed.activity, bed.viewModel, bed.host)
        outcomes = WizardRunOutcomes(bed.activity, bed.viewModel, chrome, status, sweep) { checks++ }
    }

    @After
    fun closeBed() = bed.close()

    private fun outcome(frames: Int, code: Int) = BatchAnalysisOutcome(
        engineErrorCode = code,
        firstFrameValidPoints = 0,
        totalFrames = frames,
        executionTimeMs = 0,
        batchDirPath = "",
    )

    @Test
    fun `a failure names the frame it stopped on`() {
        val res = bed.activity.resources
        val reason = res.getString(EngineFailure.reasonRes(-3), -3)
        assertEquals(reason, outcomes.engineFailureMessage(-3, frameIndex = -1, frameName = null))
        assertEquals(
            res.getString(R.string.failure_frame_fmt, 3, "c.png") + reason,
            outcomes.engineFailureMessage(-3, frameIndex = 2, frameName = "c.png"),
        )
        assertEquals(
            res.getString(R.string.failure_frame_no_name_fmt, 3) + reason,
            outcomes.engineFailureMessage(-3, frameIndex = 2, frameName = null),
        )
    }

    @Test
    fun `a run that stopped early names the frame, counts what it kept, and keeps the rest behind Why`() {
        bed.viewModel.deformedFrames = (1..4).map { DeformedFrame(path = "/f/$it.png", name = "$it.png") }
        val stopped = outcome(frames = 2, code = RunStop.LowConvergence.wireCode)
            .copy(failedFrameIndex = 2, failedFrameName = "3.png")
        outcomes.onPartialRun(stopped)
        bed.idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val title = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()
        assertEquals("Stopped at frame 3", title)
        assertEquals(
            "2 of 4 frames kept. Convergence fell below 50% on 2 frames in a row.",
            dialog.findViewById<TextView>(android.R.id.message)!!.text.toString(),
        )

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        bed.idle()
        assertTrue("Why? leaves the results one tap away", dialog.isShowing)
        val why = (ShadowDialog.getLatestDialog() as AlertDialog)
            .findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(why, why.contains("Frame 3 (3.png)"))
        assertTrue(why, why.contains(bed.activity.getString(R.string.error_low_convergence_why)))
    }

    @Test
    fun `the failure dialog leaves its FAQ by the status line until it is cleared`() {
        val faq = bed.settings.btnEngineFailFaq
        outcomes.showEngineFailureDialog("why", R.string.analysis_failed_title, R.string.url_faq_speckle)
        assertTrue(faq.isVisible)
        assertTrue(faq.hasOnClickListeners())

        outcomes.clearEngineFailFaq()
        assertFalse(faq.isVisible)
    }

    @Test
    fun `the status line clears on new input, but not mid-run`() {
        status.show("done")
        chrome.beginRun {}
        status.clear()
        assertEquals("done", bed.settings.tvStaticResult.text.toString())

        chrome.end()
        status.clear()
        assertEquals("", bed.settings.tvStaticResult.text.toString())
    }

    @Test
    fun `a sweep that failed outright says so`() {
        outcomes.onSweepFinished(null)
        assertEquals(bed.activity.getString(R.string.sweep_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a cancelled sweep that kept nothing opens nothing`() {
        val cancelled = outcome(frames = 0, code = CANCELLED)
        assertEquals(RunStop.Cancelled, cancelled.stop)
        outcomes.onSweepFinished(cancelled)
        assertNull(shadowOf(bed.activity).nextStartedActivity)
        assertEquals(0, checks)
    }

    private companion object {
        const val CANCELLED = -99
    }
}
