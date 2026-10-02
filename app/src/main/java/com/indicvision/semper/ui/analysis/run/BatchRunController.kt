package com.indicvision.semper.ui.analysis.run

import android.annotation.SuppressLint
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.indicvision.semper.R
import com.indicvision.semper.field.RunStop
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import com.indicvision.semper.ui.analysis.sweep.VsgStudyRunner
import com.indicvision.semper.ui.analysis.wizard.AnalysisNavHelper
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.indicvision.semper.ui.common.dialog.Dialogs
import kotlinx.coroutines.launch

/**
 * Observes [AnalysisViewModel] batch and sweep progress/outcome and routes
 * terminal results into UI callbacks owned by [StaticAnalysisActivity].
 *
 * Both runs live on the view model's scope, so this is the only place that
 * knows whether an Activity is still around to be told how they ended.
 */
@SuppressLint("SetTextI18n") // same result strings as the former Activity handlers
class BatchRunController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val chrome: RunChrome,
    private val tvResult: TextView,
    private val host: Host,
) {

    /** What the wizard does with each way a run can end. */
    interface Host {
        /** Re-checks what the wizard's buttons allow; once the run's chrome is down. */
        fun checkReady()

        /** A run that stopped partway with its frames saved. */
        fun onPartialRun(outcome: BatchAnalysisOutcome)

        fun openResultViewer()

        /** Why a run produced nothing, naming the frame it stopped on. */
        fun engineFailureMessage(code: Int, frameIndex: Int, frameName: String?): String

        fun showEngineFailureDialog(message: String, titleRes: Int, faqUrlRes: Int)

        fun clearEngineFailFaq()

        fun onSweepProgress(progress: VsgStudyRunner.Progress)

        /** A sweep's end; null when it failed. */
        fun onSweepFinished(outcome: BatchAnalysisOutcome?)
    }

    private val overlayHelper get() = chrome.overlay

    fun observe() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.progress.collect { progress ->
                        if (progress == null) return@collect
                        overlayHelper.update(
                            percent = progress.percent,
                            status = progress.status,
                            title = progress.status,
                            pointsSolved = progress.pointsSolved,
                            convergencePercent = progress.convergencePercent,
                        )
                    }
                }
                launch {
                    viewModel.batchOutcome.collect { result ->
                        handleBatchOutcome(result)
                    }
                }
                launch {
                    viewModel.sweepProgress.collect { progress ->
                        if (progress != null) host.onSweepProgress(progress)
                    }
                }
                launch {
                    viewModel.sweepOutcome.collect { result ->
                        handleSweepOutcome(result)
                    }
                }
            }
        }
    }

    /**
     * A sweep ends with the same chrome teardown as a batch run but its own
     * routing: the lattice, not the frame viewer, is what a finished sweep
     * opens. A failure is reported as a null outcome, which is the shape
     * [Host.onSweepFinished] already branched on. A sweep whose session the
     * index refused is told so as a batch run is.
     */
    @VisibleForTesting
    internal fun handleSweepOutcome(result: Result<BatchAnalysisOutcome>) {
        chrome.end()
        host.checkReady()
        val outcome = result.getOrNull()
        if (outcome?.indexUnavailable == true) showNotSaved() else host.onSweepFinished(outcome)
    }

    @VisibleForTesting
    internal fun handleBatchOutcome(result: Result<BatchAnalysisOutcome>) {
        chrome.end()
        // Once, before any branch: Compute was disabled for the run, and a branch
        // that forgot to re-check left it disabled — a failed run could not be
        // re-run after changing a setting, since the sliders never re-check.
        host.checkReady()

        result.onFailure { e ->
            host.clearEngineFailFaq()
            val detail = e.message ?: e::class.java.simpleName
            tvResult.text = activity.getString(R.string.analysis_unexpected_title)
            Dialogs.info(
                activity,
                activity.getText(R.string.analysis_unexpected_title),
                activity.getString(R.string.analysis_unexpected_fmt, detail),
            )
            return
        }

        val outcome = result.getOrThrow()
        when {
            // Ahead of the cancel: a cancelled re-run saves its frames, and must
            // say so when that save could not reach the index.
            outcome.indexUnavailable -> showNotSaved()
            outcome.stop == RunStop.Cancelled -> Unit
            outcome.stop == RunStop.SessionLimit -> AnalysisNavHelper.openSessionLimit(activity)
            // Stopped early (low convergence included) with frames kept. Only a
            // run that saved them may say so: a first frame that kept no points
            // saves nothing, however many frames solved after it.
            outcome.engineErrorCode < 0 && outcome.saved && outcome.totalFrames > 0 -> {
                host.clearEngineFailFaq()
                host.onPartialRun(outcome)
            }
            outcome.engineErrorCode < 0 || outcome.firstFrameValidPoints <= 0 -> {
                showNamedEngineFailure(outcome)
            }
            else -> {
                host.clearEngineFailFaq()
                // The runner records the planned count on a saved run; frames that
                // kept no points are skipped, so kept can be below planned.
                tvResult.text = RunSummaryText.computed(
                    activity.resources,
                    kept = outcome.totalFrames,
                    planned = viewModel.lastPlannedFrames,
                )
                viewModel.lastDefPath = viewModel.defFilePaths.firstOrNull() ?: ""
                viewModel.lastBatchDirPath = outcome.batchDirPath
                host.openResultViewer()
            }
        }
    }

    /** The run solved, but the session index could not be read or written, so nothing was saved. */
    private fun showNotSaved() {
        host.clearEngineFailFaq()
        tvResult.setText(R.string.analysis_not_saved_title)
        Dialogs.info(activity, R.string.analysis_not_saved_title, R.string.analysis_index_unavailable_body)
    }

    private fun showNamedEngineFailure(outcome: BatchAnalysisOutcome) {
        // Code 0 is "the first frame kept no points", which is not always the
        // strain window's fault: say which it was, with the run's own VSG and step.
        // A run that went on past such a frame and stopped later saved nothing
        // because of that first frame, so it is the one to explain.
        val zeroPoints = outcome.engineErrorCode == 0 ||
            (outcome.firstFrameValidPoints <= 0 && outcome.failedFrameIndex > 0)
        val errorMsg = if (zeroPoints) {
            EngineFailure.zeroPointsMessage(
                activity,
                outcome.firstFrameCorrelatedPoints,
                viewModel.runResult.value.spec,
            )
        } else {
            host.engineFailureMessage(outcome.engineErrorCode, outcome.failedFrameIndex, outcome.failedFrameName)
        }
        val faqRes = if (zeroPoints) {
            EngineFailure.zeroPointsFaqUrlRes(outcome.firstFrameCorrelatedPoints)
        } else {
            EngineFailure.faqUrlRes(outcome.engineErrorCode)
        }
        tvResult.text = "❌ Error: $errorMsg"
        host.showEngineFailureDialog(errorMsg, R.string.analysis_failed_title, faqRes)
    }
}
