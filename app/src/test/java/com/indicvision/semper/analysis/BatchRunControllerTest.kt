package com.indicvision.semper.analysis

import android.app.Application
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import com.indicvision.semper.field.RunStop
import com.indicvision.semper.ui.analysis.run.BatchRunController
import com.indicvision.semper.ui.analysis.run.ComputeOverlayHelper
import com.indicvision.semper.ui.analysis.run.EngineFailure
import com.indicvision.semper.ui.analysis.run.RunChrome
import com.indicvision.semper.ui.analysis.run.RunSpec
import com.indicvision.semper.ui.analysis.sweep.VsgStudyRunner
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.analysis.wizard.BatchAnalysisOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Compute is disabled while a run is in flight, and only a `checkReady()`
 * after the run turns it back on — the settings sliders never re-check it.
 * A branch that skipped that call left Compute dead: after a failed run the
 * user could change the strain window, or any setting, and not run again.
 * Every way a single run can end must leave Compute usable.
 *
 * A first frame that kept no points (code 0) used to be reported as a
 * strain-window failure whatever the cause; the dialog now says which.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BatchRunControllerTest {

    /** The real gate's rule for this: Compute follows `!chrome.isBusy` at check time. */
    private class Gate {
        var computeEnabled = false
    }

    private data class Shown(val message: String, val faqUrlRes: Int)

    private lateinit var activity: AppCompatActivity

    private fun controller(
        gate: Gate,
        viewModel: AnalysisViewModel = AnalysisViewModel(),
        onPartial: () -> Unit = {},
        resultLine: TextView? = null,
        onDialog: (Shown) -> Unit = {},
    ): BatchRunController {
        activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
            .get()
        val overlay = ComputeOverlayHelper(
            overlay = View(activity),
            title = TextView(activity),
            progress = ProgressBar(activity),
            percent = TextView(activity),
            status = TextView(activity),
            elapsed = TextView(activity),
        )
        // The run is in flight when its outcome arrives.
        val chrome = RunChrome(activity, overlay, cancelButton = View(activity)).apply { beginRun {} }
        val host = object : BatchRunController.Host {
            override fun checkReady() {
                gate.computeEnabled = !chrome.isBusy
            }

            override fun onPartialRun(outcome: BatchAnalysisOutcome) = onPartial()
            override fun openResultViewer() = Unit
            override fun engineFailureMessage(code: Int, frameIndex: Int, frameName: String?) = ""
            override fun showEngineFailureDialog(message: String, titleRes: Int, faqUrlRes: Int) =
                onDialog(Shown(message, faqUrlRes))

            override fun clearEngineFailFaq() = Unit
            override fun onSweepProgress(progress: VsgStudyRunner.Progress) = Unit
            override fun onSweepFinished(outcome: BatchAnalysisOutcome?) = Unit
        }
        return BatchRunController(activity, viewModel, chrome, resultLine ?: TextView(activity), host)
    }

    /** A run is saved when its first frame kept points; [route]'s tests override that. */
    private fun outcome(code: Int, validPoints: Int, frames: Int, correlated: Int = -1) =
        BatchAnalysisOutcome(
            engineErrorCode = code,
            firstFrameValidPoints = validPoints,
            totalFrames = frames,
            executionTimeMs = 0,
            batchDirPath = "",
            failedFrameIndex = if (code < 0 || validPoints == 0) 0 else -1,
            firstFrameCorrelatedPoints = correlated,
            saved = validPoints > 0,
        )

    /** Whether [outcome] opened the "stopped early, frames kept" dialog, and any failure dialog it raised. */
    private fun route(outcome: BatchAnalysisOutcome): Pair<Boolean, Shown?> {
        val viewModel = AnalysisViewModel()
        viewModel.resetRunResult("", strainFailSpec)
        var partial = false
        var shown: Shown? = null
        controller(Gate(), viewModel, onPartial = { partial = true }) { shown = it }
            .handleBatchOutcome(Result.success(outcome))
        return partial to shown
    }

    /** The dialog a zero-point first frame raises, for a run with [spec]. */
    private fun zeroPointDialog(correlated: Int, spec: RunSpec): Shown {
        val viewModel = AnalysisViewModel()
        viewModel.resetRunResult("", spec)
        var shown: Shown? = null
        controller(Gate(), viewModel) { shown = it }
            .handleBatchOutcome(Result.success(outcome(code = 0, validPoints = 0, frames = 0, correlated)))
        return checkNotNull(shown) { "no failure dialog" }
    }

    private val strainFailSpec = RunSpec.of(
        subset = 41,
        step = 20,
        strainWindow = 601,
        roi = intArrayOf(30, 30, 452, 452),
        mask = null,
        use6x6 = false,
        debugDir = null,
    )

    private fun assertComputeUsableAfter(name: String, result: Result<BatchAnalysisOutcome>) {
        val gate = Gate()
        controller(gate).handleBatchOutcome(result)
        assertTrue("Compute stayed disabled after: $name", gate.computeEnabled)
    }

    @Test
    fun `a strain-only failure leaves Compute usable`() {
        // ICGN solved the grid, then every point failed the VSG fill rule: 0 points, code 0.
        assertComputeUsableAfter("VSG failure", Result.success(outcome(code = 0, validPoints = 0, frames = 0)))
    }

    @Test
    fun `points that correlated but fit no strain name the strain window, with the run's VSG and step`() {
        val shown = zeroPointDialog(correlated = 441, spec = strainFailSpec)

        assertEquals(
            activity.resources.getQuantityString(R.plurals.run_fail_strain_window_fmt, 441, 441, 601, 20),
            shown.message,
        )
        assertEquals(R.string.url_faq_engine_vsg, shown.faqUrlRes)
    }

    @Test
    fun `a frame where nothing correlated does not blame the strain window`() {
        val shown = zeroPointDialog(correlated = 0, spec = strainFailSpec)

        assertEquals(activity.getString(R.string.run_fail_no_correlation), shown.message)
        assertEquals(R.string.url_faq_engine_features, shown.faqUrlRes)
    }

    @Test
    fun `a frame that never reached the engine is reported as unreadable`() {
        val shown = zeroPointDialog(correlated = -1, spec = strainFailSpec)

        assertEquals(activity.getString(R.string.sweep_fail_init), shown.message)
        assertEquals(R.string.url_faq_engine_init, shown.faqUrlRes)
    }

    @Test
    fun `every named engine failure leaves Compute usable`() {
        listOf(
            EngineFailure.ENGINE_ERROR_FEATURES,
            EngineFailure.ENGINE_ERROR_ROI,
            EngineFailure.ENGINE_ERROR_INIT,
        ).forEach { code ->
            assertComputeUsableAfter("engine code $code", Result.success(outcome(code, validPoints = 0, frames = 0)))
        }
    }

    @Test
    fun `a run that stopped early with its frames saved says they are kept`() {
        val (partial, shown) = route(
            outcome(EngineFailure.ENGINE_ERROR_FEATURES, validPoints = 500, frames = 3).copy(failedFrameIndex = 3),
        )
        assertTrue(partial)
        assertNull(shown)
    }

    @Test
    fun `frames that solved after a first frame that kept nothing are not called saved`() {
        // Frame 1 kept no points, frames 2-3 solved, frame 4 failed: no record.
        listOf(EngineFailure.ENGINE_ERROR_FEATURES, RunStop.LowConvergence.wireCode).forEach { code ->
            val (partial, shown) = route(
                outcome(code, validPoints = 0, frames = 2, correlated = 0).copy(failedFrameIndex = 3),
            )
            assertFalse("code $code opened the frames-kept dialog", partial)
            // The first frame is why nothing was saved, so it is what the dialog explains.
            assertEquals(activity.getString(R.string.run_fail_no_correlation), shown?.message)
        }
    }

    @Test
    fun `the other endings leave Compute usable too`() {
        assertComputeUsableAfter(
            "cancel",
            Result.success(outcome(RunStop.Cancelled.wireCode, validPoints = 0, frames = 0)),
        )
        assertComputeUsableAfter(
            "session limit",
            Result.success(outcome(RunStop.SessionLimit.wireCode, validPoints = 0, frames = 0)),
        )
        assertComputeUsableAfter(
            "partial run",
            Result.success(outcome(RunStop.LowConvergence.wireCode, validPoints = 500, frames = 2)),
        )
        assertComputeUsableAfter("success", Result.success(outcome(code = 0, validPoints = 500, frames = 3)))
        assertComputeUsableAfter("exception", Result.failure(IllegalStateException("boom")))
    }

    @Test
    fun `an index that could not be written says the run was not saved, not that the limit is reached`() {
        val line = TextView(ApplicationProvider.getApplicationContext<Application>())
        val gate = Gate()
        controller(gate, resultLine = line).handleBatchOutcome(
            Result.success(
                outcome(code = 0, validPoints = 500, frames = 3).copy(saved = false, indexUnavailable = true),
            ),
        )

        assertNull("no session-limit screen", shadowOf(activity).nextStartedActivity)
        assertEquals(activity.getString(R.string.analysis_not_saved_title), line.text.toString())
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals(
            activity.getString(R.string.analysis_index_unavailable_body),
            dialog.findViewById<TextView>(android.R.id.message)?.text.toString(),
        )
        assertTrue(gate.computeEnabled)
    }

    @Test
    fun `a cancelled re-run whose save missed the index still says it was not saved`() {
        val line = TextView(ApplicationProvider.getApplicationContext<Application>())
        controller(Gate(), resultLine = line).handleBatchOutcome(
            Result.success(
                outcome(RunStop.Cancelled.wireCode, validPoints = 500, frames = 2)
                    .copy(saved = false, indexUnavailable = true),
            ),
        )

        assertEquals(activity.getString(R.string.analysis_not_saved_title), line.text.toString())
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(
            activity.getString(R.string.analysis_index_unavailable_body),
            dialog.findViewById<TextView>(android.R.id.message)?.text.toString(),
        )
    }

    @Test
    fun `a full quota at save time still opens the session-limit screen`() {
        controller(Gate()).handleBatchOutcome(
            Result.success(outcome(RunStop.SessionLimit.wireCode, validPoints = 500, frames = 3).copy(saved = false)),
        )
        assertTrue(shadowOf(activity).nextStartedActivity != null)
    }

    /** The status line a finished run leaves, with [planned] frames recorded by the runner. */
    private fun successLine(kept: Int, planned: Int): String {
        val viewModel = AnalysisViewModel()
        viewModel.lastPlannedFrames = planned
        val line = TextView(ApplicationProvider.getApplicationContext<Application>())
        controller(Gate(), viewModel, resultLine = line)
            .handleBatchOutcome(Result.success(outcome(code = 0, validPoints = 500, frames = kept)))
        return line.text.toString()
    }

    @Test
    fun `a finished run counts its frames with a plural, not a hard-coded English line`() {
        assertEquals("✅ Computed 1 frame", successLine(kept = 1, planned = 1))
        assertEquals("✅ Computed 3 frames", successLine(kept = 3, planned = 3))
    }

    @Test
    fun `frames dropped for keeping no points are counted, not hidden`() {
        assertEquals(
            "✅ Computed 3 of 5 frames — 2 frames kept no valid points",
            successLine(kept = 3, planned = 5),
        )
        assertEquals(
            "✅ Computed 4 of 5 frames — 1 frame kept no valid points",
            successLine(kept = 4, planned = 5),
        )
    }
}
