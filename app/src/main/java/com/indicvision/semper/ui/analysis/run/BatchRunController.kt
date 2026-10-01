package com.indicvision.semper.ui.analysis.run

import android.annotation.SuppressLint
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import com.indicvision.semper.ui.analysis.sweep.VsgStudyRunner
import com.indicvision.semper.ui.analysis.wizard.AnalysisNavHelper
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import kotlinx.coroutines.launch

/**
 * Observes [AnalysisViewModel] batch and sweep progress/outcome and routes
 * terminal results into UI callbacks owned by [StaticAnalysisActivity].
 *
 * Both runs live on the view model's scope, so this is the only place that
 * knows whether an Activity is still around to be told how they ended.
 */
@SuppressLint("SetTextI18n") // same result strings as the former Activity handlers
@Suppress("LongParameterList") // Activity-bound callbacks; grouping would just rename the fan-out
class BatchRunController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val overlayHelper: ComputeOverlayHelper,
    private val tvResult: TextView,
    private val setProcessing: (Boolean) -> Unit,
    private val checkReady: () -> Unit,
    private val onPartialRun: (AnalysisViewModel.BatchAnalysisOutcome) -> Unit,
    private val openResultViewer: () -> Unit,
    private val engineFailureMessage: (code: Int, frameIndex: Int, frameName: String?) -> String,
    private val showEngineFailureDialog: (message: String, titleRes: Int, faqUrlRes: Int) -> Unit,
    private val clearEngineFailFaq: () -> Unit,
    private val onSweepProgress: (VsgStudyRunner.Progress) -> Unit,
    private val onSweepFinished: (AnalysisViewModel.BatchAnalysisOutcome?) -> Unit,
) {

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
                        if (progress != null) onSweepProgress(progress)
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
     * [onSweepFinished] already branched on.
     */
    private fun handleSweepOutcome(result: Result<AnalysisViewModel.BatchAnalysisOutcome>) {
        setProcessing(false)
        overlayHelper.hide()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        checkReady()
        onSweepFinished(result.getOrNull())
    }

    @VisibleForTesting
    internal fun handleBatchOutcome(result: Result<AnalysisViewModel.BatchAnalysisOutcome>) {
        setProcessing(false)
        overlayHelper.hide()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Once, before any branch: Compute was disabled for the run, and a branch
        // that forgot to re-check left it disabled — a failed run could not be
        // re-run after changing a setting, since the sliders never re-check.
        checkReady()

        result.onFailure { e ->
            clearEngineFailFaq()
            val detail = e.message ?: e::class.java.simpleName
            tvResult.text = activity.getString(R.string.analysis_unexpected_title)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.analysis_unexpected_title)
                .setMessage(activity.getString(R.string.analysis_unexpected_fmt, detail))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val outcome = result.getOrThrow()
        when {
            outcome.engineErrorCode == AnalysisRunCodes.ERROR_CANCELLED -> Unit
            outcome.engineErrorCode == AnalysisRunCodes.ERROR_SESSION_LIMIT ->
                AnalysisNavHelper.openSessionLimit(activity)
            // Stopped early (low convergence included) with frames kept. Only a
            // run that saved them may say so: a first frame that kept no points
            // saves nothing, however many frames solved after it.
            outcome.engineErrorCode < 0 && outcome.saved && outcome.totalFrames > 0 -> {
                clearEngineFailFaq()
                onPartialRun(outcome)
            }
            outcome.engineErrorCode < 0 || outcome.firstFrameValidPoints <= 0 -> {
                showNamedEngineFailure(outcome)
            }
            else -> {
                clearEngineFailFaq()
                // The runner records the planned count on a saved run; frames that
                // kept no points are skipped, so kept can be below planned.
                tvResult.text = RunSummaryText.computed(
                    activity.resources,
                    kept = outcome.totalFrames,
                    planned = viewModel.lastPlannedFrames,
                )
                viewModel.lastDefPath = viewModel.defFilePaths.firstOrNull() ?: ""
                viewModel.lastBatchDirPath = outcome.batchDirPath
                openResultViewer()
            }
        }
    }

    private fun showNamedEngineFailure(outcome: AnalysisViewModel.BatchAnalysisOutcome) {
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
            engineFailureMessage(outcome.engineErrorCode, outcome.failedFrameIndex, outcome.failedFrameName)
        }
        val faqRes = if (zeroPoints) {
            EngineFailure.zeroPointsFaqUrlRes(outcome.firstFrameCorrelatedPoints)
        } else {
            EngineFailure.faqUrlRes(outcome.engineErrorCode)
        }
        tvResult.text = "❌ Error: $errorMsg"
        showEngineFailureDialog(errorMsg, R.string.analysis_failed_title, faqRes)
    }
}
