package com.sempermechanics.semper.ui.analysis.run

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.sweep.SweepSetupController
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudyRunner
import com.sempermechanics.semper.ui.analysis.sweep.toSkippedNode
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisNavHelper
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.sempermechanics.semper.ui.common.dialog.FaqRedirect
import com.sempermechanics.semper.ui.common.dialog.Feedback

/**
 * What the wizard does with each way a run ends, for [BatchRunController]:
 * a partial run is told and opened, a sweep opens the lattice, a failure
 * names its frame and its FAQ. [recheck] re-checks the wizard's buttons.
 */
class WizardRunOutcomes(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val chrome: RunChrome,
    private val status: RunStatusLine,
    private val sweep: SweepSetupController,
    private val recheck: () -> Unit,
) : BatchRunController.Host {

    override fun checkReady() = recheck()

    /**
     * A run that stopped itself partway: the images decorrelated, but the frames
     * solved before that are valid and the session already holds them.
     *
     * Told plainly and then opened. The alternative — a failure dialog — left a
     * session appearing on Home that the user had just been told was a failure,
     * with no route to it from here.
     */
    override fun onPartialRun(outcome: BatchAnalysisOutcome) {
        val kept = outcome.totalFrames
        val planned = viewModel.defFilePaths.size
        status.show(activity.getString(R.string.run_stopped_early_fmt, outcome.stoppedAtFrame, planned))
        viewModel.lastDefPath = viewModel.defFilePaths.firstOrNull() ?: ""
        viewModel.lastBatchDirPath = outcome.batchDirPath
        checkReady()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.run_stopped_early_title)
            .setMessage(
                activity.resources.getQuantityString(R.plurals.run_stopped_early_body, kept, kept, planned) +
                    System.lineSeparator() + System.lineSeparator() +
                    engineFailureMessage(
                        outcome.engineErrorCode,
                        frameIndex = outcome.failedFrameIndex,
                        frameName = outcome.failedFrameName,
                    ),
            )
            // The dialog explains, it does not ask: the frames are saved either
            // way, so dismissing it back onto the settings page would strand the
            // user one screen away from the data the run just produced.
            .setCancelable(false)
            .setPositiveButton(R.string.run_stopped_early_view) { _, _ -> openResultViewer() }
            .show()
    }

    override fun openResultViewer() = openResults(activity, viewModel, sweep, sweep = false)

    /**
     * Why a run produced nothing. The engine's codes are the same for single
     * analysis and sweep; messages reuse the sweep-path string resources.
     */
    override fun engineFailureMessage(code: Int, frameIndex: Int, frameName: String?): String {
        val frameInfo = when {
            frameIndex < 0 -> ""
            frameName != null -> activity.getString(R.string.failure_frame_fmt, frameIndex + 1, frameName)
            else -> activity.getString(R.string.failure_frame_no_name_fmt, frameIndex + 1)
        }
        return frameInfo + activity.getString(EngineFailure.reasonRes(code), code)
    }

    override fun showEngineFailureDialog(message: String, @StringRes titleRes: Int, @StringRes faqUrlRes: Int) {
        status.setFaq(faqUrlRes)
        FaqRedirect.errorDialog(activity, activity.getString(titleRes), message, faqUrlRes)
    }

    override fun clearEngineFailFaq() = status.setFaq(null)

    override fun onSweepProgress(progress: VsgStudyRunner.Progress) {
        chrome.overlay.update(
            percent = progress.percent.toFloat(),
            status = activity.getString(
                R.string.sweep_running_fmt,
                progress.runIndex + 1,
                progress.totalRuns,
                progress.point.subset,
                progress.point.step,
                progress.point.window,
            ),
            title = activity.getString(R.string.mode_sweep),
            pointsSolved = if (progress.pointsSolved > 0) progress.pointsSolved else -1,
            convergencePercent = progress.convergencePercent,
        )
    }

    /**
     * A finished sweep is an ordinary session whose frames happen to be
     * settings rather than images, so it opens in the normal result viewer.
     */
    override fun onSweepFinished(outcome: BatchAnalysisOutcome?) {
        when {
            outcome == null -> Feedback.toast(activity, R.string.sweep_failed, long = true)
            outcome.stop == RunStop.SessionLimit -> AnalysisNavHelper.openSessionLimit(activity)
            outcome.totalFrames == 0 -> if (outcome.stop != RunStop.Cancelled) openFailedSweep(outcome)
            else -> openSweep(outcome)
        }
    }

    /** Routes to the lattice with all-failed nodes so the user can tap each for details. */
    private fun openFailedSweep(outcome: BatchAnalysisOutcome) {
        // Each node keeps its own reason; they used to all show the last one's.
        viewModel.sweepPlan = emptyList()
        viewModel.sweepSkippedNodes = SkippedNode.forFailedSweep(viewModel.sweepSkippedNodes) {
            val plan = viewModel.runResult.value.spec?.sweep?.plan ?: sweep.currentPlan()
            plan.map { it.toSkippedNode(outcome.engineErrorCode) }
        }
        viewModel.lastBatchDirPath = outcome.batchDirPath
        openResults(activity, viewModel, sweep, sweep = true)
    }

    private fun openSweep(outcome: BatchAnalysisOutcome) {
        val skipped = viewModel.sweepSkippedNodes.size
        if (skipped > 0) {
            // Partial sweeps are still worth browsing; say what was dropped.
            Feedback.toast(
                activity,
                activity.resources.getQuantityString(
                    R.plurals.sweep_partial_fmt,
                    skipped,
                    skipped,
                    skipped + outcome.totalFrames,
                ),
                long = true,
            )
        }

        val sweepFrame = viewModel.runResult.value.spec?.sweep?.frameIndex ?: sweep.resolvedSweepFrame()
        viewModel.lastDefPath = viewModel.defFilePaths.getOrNull(sweepFrame) ?: ""
        viewModel.lastBatchDirPath = outcome.batchDirPath
        checkReady()
        // Stage the swept parameter space on the interactive lattice; it opens
        // the result viewer from there.
        openResults(activity, viewModel, sweep, sweep = true)
    }
}

/**
 * Opens the viewer on the run that just finished.
 *
 * @param sweep true when the frames are parameter combinations rather than
 *   deformed images. The viewer needs each frame's own settings then — the
 *   step size alone changes how a frame renders — and names the frames
 *   after the combination instead of after an image file.
 */
private fun openResults(
    activity: AppCompatActivity,
    viewModel: AnalysisViewModel,
    setup: SweepSetupController,
    sweep: Boolean,
) {
    val frameNames = if (sweep) {
        ArrayList(viewModel.sweepPlan.map { setup.combinationLabel(it) })
    } else {
        ArrayList(viewModel.defFilePaths.map { it.substringAfterLast('/') })
    }
    AnalysisNavHelper.openResults(
        host = activity,
        viewModel = viewModel,
        sweep = sweep,
        frameNames = frameNames,
    )
}
