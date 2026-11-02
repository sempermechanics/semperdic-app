package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.databinding.ActivityStaticAnalysisBinding
import com.sempermechanics.semper.databinding.WizardStepSettingsContentBinding
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.recommend.RunEstimate
import com.sempermechanics.semper.ui.analysis.run.DicFieldIo
import com.sempermechanics.semper.ui.analysis.sweep.SweepSetupController
import com.sempermechanics.semper.ui.common.dialog.WarnChip
import java.util.Locale

/**
 * Wizard readiness / Compute / Sweep enablement extracted from
 * [StaticAnalysisActivity.checkReady]. [gridSizeChip] says when the run's
 * grid would not fit a heap of [maxHeapBytes] ([DicFieldIo.fits]), which
 * blocks Compute and Run sweep as `admitRun` would at the start.
 */
class AnalysisReadyGate(
    private val viewModel: AnalysisViewModel,
    private val binding: ActivityStaticAnalysisBinding,
    private val settings: WizardStepSettingsContentBinding,
    private val frameSizeChip: WarnChip,
    private val gridSizeChip: WarnChip,
    private val maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
) {

    /** The wizard's inputs as the view model holds them now. */
    private val inputs: WizardInputs get() = viewModel.inputs.value

    /**
     * Enables Next, Compute and Run sweep for what the wizard holds, unless
     * [isProcessing]. [sweepController] is the sweep page's; before its
     * views are found its Run button is not there to enable.
     */
    fun apply(isProcessing: Boolean, sweepController: SweepSetupController) {
        val ready = viewModel.isReadyToCompute()

        binding.btnNext.isEnabled = ready && !isProcessing
        binding.tvNextReason.text = when {
            inputs.refBytes == null -> binding.root.context.getString(R.string.next_reason_ref)
            viewModel.defFilePaths.isEmpty() -> binding.root.context.getString(R.string.next_reason_def)
            else -> ""
        }

        val sizeError = inputs.frameSizeError
        frameSizeChip.showOrHide(sizeError)
        val gridError = gridSizeError(sweepController)
        gridSizeChip.showOrHide(gridError)

        // Compute and Run sweep share every condition but the mode.
        val canRun = ready &&
            inputs.settingsReviewed &&
            !isProcessing &&
            sizeError == null &&
            gridError == null
        binding.btnCalculateFullField.isEnabled = canRun && !inputs.sweepMode
        sweepController.setRunSweepEnabled(canRun && inputs.sweepMode && sweepController.currentPlan().isNotEmpty())

        settings.btnDefineRoi.isEnabled = (inputs.refBytes != null) && !isProcessing
        binding.btnBack.isEnabled = !isProcessing
        showEstimate()
    }

    /**
     * Why the run's grid would not fit this phone's memory, or null when it
     * does. A single run's is its region at the step slider's step; a sweep's
     * is the one buffer its runner sizes: the region the largest subset
     * leaves, at the plan's finest step (`WizardRunLauncher`, `SweepStudyRunner`).
     */
    private fun gridSizeError(sweepController: SweepSetupController): String? {
        val format = { n: Long -> String.format(Locale.getDefault(), "%,d", n) }
        return runGridPoints(sweepController)?.takeUnless { DicFieldIo.fits(it, maxHeapBytes) }?.let {
            binding.root.context.getString(
                R.string.grid_too_large_fmt,
                format(it),
                format(DicFieldIo.pointCap(maxHeapBytes)),
            )
        }
    }

    /** The grid [gridSizeError] checks, or null with no region or no sweep plan yet. */
    private fun runGridPoints(sweepController: SweepSetupController): Long? = if (inputs.sweepMode) {
        sweepController.currentPlan().takeIf { it.isNotEmpty() }?.let { plan ->
            Roi.forSolve(plan.maxOf { it.subset }, inputs.hasCustomRoi, inputs.roi, inputs.refSize)
                ?.let { DicFieldIo.gridPoints(it.w, it.h, plan.minOf { point -> point.step }) }
        }
    } else {
        val subset = settings.sliderSubsetSize.value.toInt()
        Roi.forSolve(subset, inputs.hasCustomRoi, inputs.roi, inputs.refSize)
            ?.let { DicFieldIo.gridPoints(it.w, it.h, settings.sliderStepSize.value.toInt()) }
    }

    /**
     * "1100 × 800 px · 8,800 points" on the ROI row, and "Compute · 1 min"
     * on the button, spoken "Compute, about 1 minute" (the half-width button
     * has the sweep's Run button's short form; "about" did not fit at font
     * scale 2). Runs on every step or region change, so the count follows
     * both. The time comes from this phone's past runs; without any, the
     * button says Compute alone.
     */
    private fun showEstimate() {
        val subset = settings.sliderSubsetSize.value.toInt()
        val step = settings.sliderStepSize.value.toInt()
        val roi = Roi.forSolve(subset, inputs.hasCustomRoi, inputs.roi, inputs.refSize)
        val points = roi?.let { RunEstimate.gridPoints(it.w, it.h, step) } ?: 0
        val res = binding.root.resources
        showRoi(points)

        val rate = AppSettings.runPointsPerSecond(binding.root.context)
        val seconds = RunEstimate.seconds(points, inputs.deformedFrames.size, rate)
        binding.btnCalculateFullField.text = if (seconds != null) {
            res.getString(R.string.run_compute_eta_fmt, RunEstimate.shortDuration(res, seconds))
        } else {
            res.getString(R.string.run_analysis)
        }
        binding.btnCalculateFullField.contentDescription = seconds?.let {
            res.getString(R.string.run_compute_eta_spoken_fmt, RunEstimate.spokenDuration(res, it))
        }
    }

    /**
     * The ROI row's line: the region, "Full image" or "w × h px", then its
     * [points] once there are any. A sweep solves at a step per combination
     * (subset ÷ N), not the single step the count assumes, so in sweep mode
     * the row names the region alone. The mode toggle re-checks readiness,
     * so the line follows it both ways.
     */
    private fun showRoi(points: Int) {
        val res = binding.root.resources
        val region = if (inputs.hasCustomRoi) {
            res.getString(R.string.roi_custom_fmt, inputs.roi.w, inputs.roi.h)
        } else {
            res.getString(R.string.roi_full_image)
        }
        val counted = points > 0 && !inputs.sweepMode
        settings.tvInstruction.text = if (counted) RunEstimate.regionLabel(res, region, points) else region
    }
}
