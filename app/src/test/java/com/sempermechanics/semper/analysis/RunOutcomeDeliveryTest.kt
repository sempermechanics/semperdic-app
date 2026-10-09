package com.sempermechanics.semper.analysis

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.run.BatchRunController
import com.sempermechanics.semper.ui.analysis.run.ComputeOverlayController
import com.sempermechanics.semper.ui.analysis.run.RunChrome
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.sempermechanics.semper.ui.analysis.wizard.PendingOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * TD-168: a run that ends while the wizard is in the background must still
 * tear its chrome down and open its result when the wizard comes back, and
 * only once. The outcomes used to be a no-replay `SharedFlow`, collected
 * only while the screen was started, so an outcome with no collector was
 * dropped and the wizard sat behind a run overlay that never went away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RunOutcomeDeliveryTest {

    private val built = mutableListOf<ActivityController<AppCompatActivity>>()

    /** What the controllers handed their hosts. */
    private var viewersOpened = 0
    private val sweepsFinished = mutableListOf<BatchAnalysisOutcome?>()
    private var computeEnabled = false

    @After
    fun tearDown() = built.forEach { runCatching { it.pause().stop().destroy() } }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A started wizard observing [viewModel]; its chrome is up when [runInFlight]. */
    private fun screen(
        viewModel: AnalysisViewModel,
        runInFlight: Boolean = true,
    ): ActivityController<AppCompatActivity> {
        val ac = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
        built += ac
        val activity = ac.get()
        val overlay = ComputeOverlayController(
            overlay = View(activity),
            title = TextView(activity),
            progress = ProgressBar(activity),
            percent = TextView(activity),
            status = TextView(activity),
            elapsed = TextView(activity),
            eta = TextView(activity),
        )
        val chrome = RunChrome(activity, overlay, cancelButton = View(activity))
        if (runInFlight) chrome.beginRun {}
        val host = object : BatchRunController.Host {
            override fun checkReady() {
                computeEnabled = !chrome.isBusy
            }

            override fun onPartialRun(outcome: BatchAnalysisOutcome) = Unit
            override fun openResultViewer() {
                viewersOpened++
            }

            override fun engineFailureMessage(code: Int, frameIndex: Int, frameName: String?) = ""
            override fun showEngineFailureDialog(message: String, titleRes: Int, faqUrlRes: Int) = Unit
            override fun clearEngineFailFaq() = Unit
            override fun onSweepProgress(progress: SweepStudyRunner.Progress) = Unit
            override fun onSweepFinished(outcome: BatchAnalysisOutcome?) {
                sweepsFinished += outcome
            }
        }
        BatchRunController(activity, viewModel, chrome, TextView(activity), host).observe()
        idle()
        return ac
    }

    private fun outcome(code: Int = 0, frames: Int = 3) = BatchAnalysisOutcome(
        engineErrorCode = code,
        firstFrameValidPoints = 500,
        totalFrames = frames,
        executionTimeMs = 0,
        batchDirPath = "",
        saved = code != RunStop.Cancelled.wireCode,
    )

    @Test
    fun `a batch run that ends while the wizard is stopped is handled when it starts again`() {
        val viewModel = AnalysisViewModel()
        val ac = screen(viewModel)
        ac.pause().stop()
        idle()

        viewModel.runs.batchOutcome.post(Result.success(outcome()))
        idle()
        assertEquals("nothing handled while stopped", 0, viewersOpened)
        assertTrue(viewModel.batchOutcome.isPending)

        ac.start().resume()
        idle()
        assertEquals(1, viewersOpened)
        assertTrue("chrome down, Compute usable", computeEnabled)
        assertFalse(viewModel.batchOutcome.isPending)
    }

    @Test
    fun `a handled batch outcome is not handled again when the wizard restarts`() {
        val viewModel = AnalysisViewModel()
        val ac = screen(viewModel)
        viewModel.runs.batchOutcome.post(Result.success(outcome()))
        idle()
        assertEquals(1, viewersOpened)

        ac.pause().stop()
        idle()
        ac.start().resume()
        idle()
        // A second screen over the same view model sees nothing either.
        screen(viewModel)
        assertEquals(1, viewersOpened)
    }

    @Test
    fun `a sweep that ends while the wizard is stopped is handled once when it starts again`() {
        val viewModel = AnalysisViewModel()
        val ac = screen(viewModel)
        ac.pause().stop()
        idle()

        val finished = outcome(frames = 9)
        viewModel.runs.sweepOutcome.post(Result.success(finished))
        idle()
        assertTrue("nothing handled while stopped", sweepsFinished.isEmpty())

        ac.start().resume()
        idle()
        assertEquals(listOf(finished), sweepsFinished)
        assertTrue(computeEnabled)

        ac.pause().stop()
        idle()
        ac.start().resume()
        idle()
        assertEquals("handled once", listOf(finished), sweepsFinished)
    }

    @Test
    fun `a run the destroyed screen cancelled ends quietly on the next screen, once`() {
        // onDestroy flips the cancel flag on every destroy. A screen recreated
        // over the same view model (a configuration change the manifest does not
        // absorb) never raised the run's chrome; the cancelled outcome must not
        // open anything there, only leave Compute usable.
        val viewModel = AnalysisViewModel()
        viewModel.runs.batchOutcome.post(Result.success(outcome(RunStop.Cancelled.wireCode, frames = 0)))

        screen(viewModel, runInFlight = false)
        assertEquals(0, viewersOpened)
        assertTrue(computeEnabled)
        assertFalse(viewModel.batchOutcome.isPending)

        computeEnabled = false
        screen(viewModel, runInFlight = false)
        assertFalse("taken by the first screen only", computeEnabled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `of two collectors that see one outcome, only one handles it`() = runTest(UnconfinedTestDispatcher()) {
        val pending = PendingOutcome<String>()
        val handled = mutableListOf<String>()
        val first = launch { pending.consumeEach { handled += "first:$it" } }
        val second = launch { pending.consumeEach { handled += "second:$it" } }

        pending.post("done")
        pending.post("again")

        assertEquals(2, handled.size)
        assertEquals(listOf("done", "again"), handled.map { it.substringAfter(':') })
        assertFalse(pending.isPending)
        first.cancel()
        second.cancel()
    }
}
