package com.sempermechanics.semper.ui.analysis.wizard

import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.databinding.ActivityStaticAnalysisBinding
import com.sempermechanics.semper.databinding.WizardStepSettingsContentBinding
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.recommend.RunEstimate
import com.sempermechanics.semper.ui.analysis.sweep.SweepSetupController
import com.sempermechanics.semper.ui.common.dialog.WarnChip

/**
 * Wizard readiness / Compute / Sweep enablement extracted from
 * [StaticAnalysisActivity.checkReady].
 */
class AnalysisReadyGate(
    private val viewModel: AnalysisViewModel,
    private val binding: ActivityStaticAnalysisBinding,
    private val settings: WizardStepSettingsContentBinding,
    private val frameSizeChip: WarnChip,
) {

    fun apply(isProcessing: Boolean, sweepController: SweepSetupController?) {
        val ready = viewModel.isReadyToCompute()

        binding.btnNext.isEnabled = ready && !isProcessing
        binding.tvNextReason.text = when {
            viewModel.refBytes == null -> binding.root.context.getString(R.string.next_reason_ref)
            viewModel.defFilePaths.isEmpty() -> binding.root.context.getString(R.string.next_reason_def)
            else -> ""
        }

        val sizeError = viewModel.frameSizeError
        frameSizeChip.showOrHide(sizeError)

        // Compute and Run sweep share every condition but the mode.
        val canRun = ready &&
            viewModel.settingsReviewed &&
            !isProcessing &&
            sizeError == null
        binding.btnCalculateFullField.isEnabled = canRun && !viewModel.sweepMode
        sweepController?.setRunSweepEnabled(canRun && viewModel.sweepMode && sweepController.currentPlan().isNotEmpty())

        settings.btnDefineRoi.isEnabled = (viewModel.refBytes != null) && !isProcessing
        binding.btnBack.isEnabled = !isProcessing
        showEstimate()
    }

    /**
     * "8,800 points in the region" under the step slider, and "Compute · about
     * 1 min" on the button. The time comes from this phone's past runs; without
     * any, the button says Compute alone.
     */
    private fun showEstimate() {
        val subset = settings.sliderSubsetSize.value.toInt()
        val step = settings.sliderStepSize.value.toInt()
        val roi = Roi.forSolve(subset, viewModel.hasCustomRoi, viewModel.roi, viewModel.refSize)
        val points = roi?.let { RunEstimate.gridPoints(it.w, it.h, step) } ?: 0
        val res = binding.root.resources
        settings.tvStepPoints.isVisible = points > 0
        if (points > 0) settings.tvStepPoints.text = RunEstimate.regionLabel(res, points)

        val rate = AppSettings.runPointsPerSecond(binding.root.context)
        val seconds = RunEstimate.seconds(points, viewModel.defCount, rate)
        binding.btnCalculateFullField.text = if (seconds != null) {
            res.getString(R.string.run_compute_eta_fmt, RunEstimate.duration(res, seconds))
        } else {
            res.getString(R.string.run_analysis)
        }
    }
}
