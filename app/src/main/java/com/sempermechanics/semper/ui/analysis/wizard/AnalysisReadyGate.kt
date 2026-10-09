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
     * "8,800 points in the region" under the step slider, and "8,800 points ×
     * 40 frames · about 1 min" above Compute on the single-setting page. The
     * time comes from this phone's past runs; without any, points only.
     */
    private fun showEstimate() {
        val subset = settings.sliderSubsetSize.value.toInt()
        val step = settings.sliderStepSize.value.toInt()
        val roi = Roi.forSolve(subset, viewModel.hasCustomRoi, viewModel.roi, viewModel.refSize)
        val points = roi?.let { RunEstimate.gridPoints(it.w, it.h, step) } ?: 0
        val res = binding.root.resources
        settings.tvStepPoints.isVisible = points > 0
        if (points > 0) settings.tvStepPoints.text = RunEstimate.regionLabel(res, points)

        val frames = viewModel.defCount
        val shown = points > 0 && frames > 0 && !viewModel.sweepMode && viewModel.step == WizardStep.SETTINGS
        binding.tvRunEstimate.isVisible = shown
        if (!shown) return
        val rate = AppSettings.runPointsPerSecond(binding.root.context)
        val seconds = RunEstimate.seconds(points, frames, rate)
        binding.tvRunEstimate.text = RunEstimate.runLabel(res, points, frames, seconds)
    }
}
