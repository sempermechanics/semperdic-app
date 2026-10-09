package com.sempermechanics.semper.ui.analysis.wizard

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
     * "1100 × 800 px · 8,800 points" on the ROI row, and "Compute · about
     * 1 min" on the button. Runs on every step or region change, so the count
     * follows both. The time comes from this phone's past runs; without any,
     * the button says Compute alone.
     */
    private fun showEstimate() {
        val subset = settings.sliderSubsetSize.value.toInt()
        val step = settings.sliderStepSize.value.toInt()
        val roi = Roi.forSolve(subset, viewModel.hasCustomRoi, viewModel.roi, viewModel.refSize)
        val points = roi?.let { RunEstimate.gridPoints(it.w, it.h, step) } ?: 0
        val res = binding.root.resources
        showRoi(points)

        val rate = AppSettings.runPointsPerSecond(binding.root.context)
        val seconds = RunEstimate.seconds(points, viewModel.defCount, rate)
        binding.btnCalculateFullField.text = if (seconds != null) {
            res.getString(R.string.run_compute_eta_fmt, RunEstimate.duration(res, seconds))
        } else {
            res.getString(R.string.run_analysis)
        }
    }

    /** The ROI row's line: the region, "Full image" or "w × h px", then its [points] once there are any. */
    private fun showRoi(points: Int) {
        val res = binding.root.resources
        val region = if (viewModel.hasCustomRoi) {
            res.getString(R.string.roi_custom_fmt, viewModel.roiW, viewModel.roiH)
        } else {
            res.getString(R.string.roi_full_image)
        }
        settings.tvInstruction.text = if (points > 0) RunEstimate.regionLabel(res, region, points) else region
    }
}
