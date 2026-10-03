package com.sempermechanics.semper.ui.analysis.run

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.diagnostics.EngineDebug
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.sweep.SweepSetupHelper
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisNavHelper
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.launchBatchAnalysis
import com.sempermechanics.semper.ui.analysis.wizard.launchVsgSweep
import com.sempermechanics.semper.ui.common.dialog.FaqRedirect
import kotlinx.coroutines.launch

/**
 * Starts a run from the wizard: freezes the settings into a [RunSpec], checks
 * the seat and session quota, raises the run's chrome and hands the run to the
 * view model, which outlives the screen. [checkReady] re-checks the buttons
 * once the chrome is up.
 */
class WizardRunLauncher(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val chrome: RunChrome,
    private val sweep: SweepSetupHelper,
    private val checkReady: () -> Unit,
) {
    /** One solve per deformed frame with [params]; [use6x6] picks the Keys interpolator. */
    fun startBatch(params: DicParams, use6x6: Boolean) {
        if (!viewModel.isReadyToCompute()) return
        val roi = resolveRoi(params.subset) ?: return
        // Frozen here: everything after Compute reads the run's spec, not the sliders.
        val spec = RunSpec.of(
            params = params,
            roi = roi,
            mask = viewModel.roiMaskBytes,
            use6x6 = use6x6,
            debugDir = EngineDebug.dirFor(activity.cacheDir),
        )

        // Hard stop: do not start a new analysis when the session quota is full.
        // Re-runs that update an existing Home row are still allowed.
        activity.lifecycleScope.launch {
            if (!AnalysisNavHelper.ensureCanStart(activity, viewModel)) return@launch

            chrome.beginRun { viewModel.cancelRequested = true }
            checkReady()

            // Survives Activity destroy; progress/outcome observed via StateFlow / SharedFlow.
            viewModel.launchBatchAnalysis(
                activity.applicationContext,
                spec,
                activity.cacheDir,
                chrome.overlay.processingStartTime,
            )
        }
    }

    /** One solve per combination of the sweep page's plan. */
    fun startSweep(use6x6: Boolean) {
        val plan = if (viewModel.isReadyToCompute()) sweep.currentPlan() else emptyList()
        if (plan.isEmpty()) return
        // Every combination shares the ROI, so the largest subset has to fit it.
        val roi = resolveRoi(plan.maxOf { it.subset }) ?: return
        val spec = RunSpec.sweep(
            RunSpec.Sweep(
                plan = plan,
                labels = plan.map { sweep.combinationLabel(it) },
                lineCutHorizontal = viewModel.lineCutHorizontal,
                frameIndex = sweep.resolvedSweepFrame(),
            ),
            roi = roi,
            mask = viewModel.roiMaskBytes,
            use6x6 = use6x6,
            debugDir = EngineDebug.dirFor(activity.cacheDir),
        )

        activity.lifecycleScope.launch {
            if (!AnalysisNavHelper.ensureCanStart(activity, viewModel)) return@launch

            chrome.beginRun(activity.getString(R.string.mode_sweep), sweep.planSummary(plan)) {
                viewModel.cancelRequested = true
            }
            checkReady()

            // Handed to the view model rather than run here: a sweep is one
            // solve per combination, long enough that a rotation mid-run used to
            // cancel it and leave the half-written session behind.
            // BatchRunController tears the chrome down when it ends.
            viewModel.launchVsgSweep(activity.applicationContext, spec)
        }
    }

    /**
     * The rectangle the engine solves over, as `[x, y, w, h]`: the drawn ROI,
     * or the whole frame inset by half a subset (plus slack) so no subset hangs
     * off the edge. Null — with the user told why — when it cannot hold one
     * subset.
     */
    private fun resolveRoi(subset: Int): Roi? {
        val roi = Roi.forSolve(subset, viewModel.hasCustomRoi, viewModel.roi, viewModel.refSize)
        if (roi == null) {
            FaqRedirect.snackbar(activity, R.string.roi_too_small, R.string.url_faq_roi_too_small)
        }
        return roi
    }
}
