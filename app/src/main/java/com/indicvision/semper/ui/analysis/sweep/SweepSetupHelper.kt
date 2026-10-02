package com.indicvision.semper.ui.analysis.sweep

import android.graphics.Bitmap
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButtonToggleGroup
import com.indicvision.semper.R
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.Roi
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.analysis.wizard.WizardStep
import com.indicvision.semper.ui.common.dialog.WarnChip
import com.indicvision.semper.ui.common.dialog.bindInfo
import com.indicvision.semper.ui.common.onButtonChecked

/**
 * Parameter-sweep setup UI for the analysis wizard (§5.4.5 parameter sweep): mode
 * toggle, subset/VSG/sample fields, lattice + line-cut previews, and plan
 * summary. Orchestration ([startVsgSweep], progress, lifecycle) stays in the
 * Activity. The range inputs are [SweepRangeFields]; the frame dialog is
 * [SweepFramePicker].
 */
@Suppress("TooManyFunctions") // the wizard-facing API StaticAnalysisActivity calls, and its Callbacks
class SweepSetupHelper(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun goToStep(step: WizardStep, animate: Boolean)
        fun updateWizardChrome()
        fun checkReady()
        fun commitParamFields()
        fun startVsgSweep()
        fun currentSubsetSize(): Int
        fun maxSubsetForRoi(): Int
        fun refPreviewBitmap(): Bitmap?
        fun renderParamField(field: EditText, value: Int)
        fun confirmOpenFaq(url: String)
    }

    companion object {
        /** Width of the subset window a fresh sweep suggests, centred on the recommendation. */
        const val SUGGESTED_SUBSET_SPAN = 20

        /** Hard bounds on strain window input, in data points — the guardrail against a mistyped huge number. */
        const val STRAIN_WIN_MIN_INPUT = VsgStudy.MIN_WINDOW_POINTS
        const val STRAIN_WIN_MAX_INPUT = VsgStudy.MAX_WINDOW_POINTS
    }

    private lateinit var rgAnalysisMode: MaterialButtonToggleGroup
    private lateinit var advancedParamsCard: View
    private lateinit var sweepSettingsCard: View
    private lateinit var rgLineCutAxis: MaterialButtonToggleGroup
    private lateinit var btnPickSweepFrame: Button
    private lateinit var tvSweepPlan: TextView
    private lateinit var sweepPlanWarn: WarnChip
    private lateinit var lineCutPreview: LineCutPreviewView
    private lateinit var sweepLatticePreview: VsgLatticeView
    lateinit var btnRunSweep: Button
        private set
    private lateinit var latticeSamplesBody: View

    /** The subset, window, step and sample inputs; null until [setup]. */
    private var rangeFields: SweepRangeFields? = null

    private val framePicker = SweepFramePicker(activity, viewModel) { picked ->
        viewModel.vsgFrameIndex = picked
        refreshSweepPlan()
    }

    fun setup() {
        rgAnalysisMode = activity.findViewById(R.id.rgAnalysisMode)
        advancedParamsCard = activity.findViewById(R.id.advancedParamsCard)
        sweepSettingsCard = activity.findViewById(R.id.sweepSettingsCard)
        rgLineCutAxis = activity.findViewById(R.id.rgLineCutAxis)
        btnPickSweepFrame = activity.findViewById(R.id.btnPickSweepFrame)
        tvSweepPlan = activity.findViewById(R.id.tvSweepPlan)
        sweepPlanWarn = WarnChip(activity.findViewById(R.id.sweepPlanWarnRow), callbacks::confirmOpenFaq)
        lineCutPreview = activity.findViewById(R.id.lineCutPreview)
        sweepLatticePreview = activity.findViewById(R.id.sweepLatticePreview)
        // Same compact axes as the result lattice, now that the preview is the
        // same 136dp height -- full/default mode needs more room than that.
        sweepLatticePreview.compact = true
        btnRunSweep = activity.findViewById(R.id.btnRunSweep)
        latticeSamplesBody = activity.findViewById(R.id.latticeSamplesBody)
        val fields = SweepRangeFields(activity, viewModel, callbacks, onChanged = ::refreshSweepPlan)
        rangeFields = fields

        rgAnalysisMode.check(if (viewModel.sweepMode) R.id.rbModeSweep else R.id.rbModeSingle)
        rgAnalysisMode.onButtonChecked { checkedId ->
            viewModel.sweepMode = checkedId == R.id.rbModeSweep
            // Leaving sweep mode while on the sweep page returns to settings.
            if (!viewModel.sweepMode && viewModel.step == WizardStep.SWEEP) {
                callbacks.goToStep(WizardStep.SETTINGS, animate = true)
            } else {
                applyAnalysisModeUi()
                refreshSweepPlan()
            }
        }

        rgLineCutAxis.check(if (viewModel.lineCutHorizontal) R.id.rbAxisX else R.id.rbAxisY)
        rgLineCutAxis.onButtonChecked { checkedId ->
            viewModel.lineCutHorizontal = checkedId == R.id.rbAxisX
            refreshLineCutPreview()
        }

        btnPickSweepFrame.setOnClickListener { framePicker.show(resolvedSweepFrame()) }

        btnRunSweep.setOnClickListener {
            callbacks.commitParamFields()
            callbacks.startVsgSweep()
        }

        activity.findViewById<View>(R.id.btnLatticeSamples).setOnClickListener {
            val expanded = latticeSamplesBody.isVisible
            latticeSamplesBody.isVisible = !expanded
        }

        wireSweepInfoButtons()

        applyAnalysisModeUi()
        fields.showCountsAndStepDepth()
        seedSweepSuggestions()
    }

    /**
     * Single setting keeps Advanced + Compute on page 2. Parameter sweep shows
     * sweep settings on page 2 and routes through Next → page 3 (summary).
     */
    fun applyAnalysisModeUi() {
        val sweep = viewModel.sweepMode
        advancedParamsCard.isVisible = !sweep
        sweepSettingsCard.isVisible = sweep
        if (sweep) refreshSweepPlan()
        callbacks.updateWizardChrome()
        callbacks.checkReady()
    }

    /** Clears focus on sweep numeric fields so in-progress typing commits. */
    fun clearSweepFieldFocus() {
        rangeFields?.clearFocus()
    }

    /** Hands the sweep back to suggested inputs (e.g. Advanced Reset). */
    fun resetUserModified() {
        rangeFields?.userModified = false
    }

    fun onRecommendationChanged() = seedSweepSuggestions()

    /**
     * Seeds the sweep inputs with the app's suggestions — a subset window
     * centred on the SSSIG recommendation and a strain window of 3 to 11 points.
     * Runs until the user edits a sweep control; after that their values stand.
     */
    fun seedSweepSuggestions() {
        val fields = rangeFields ?: return
        fields.seed()
        refreshSweepPlan()
    }

    /**
     * The sweep grid the current inputs describe, capped to the subsets the ROI
     * can hold: x subset sizes × y strain windows, one step per subset.
     */
    fun currentPlan(): List<VsgStudy.Point> = sweepRanges().plan(callbacks.maxSubsetForRoi())

    /** The view model's seven sweep inputs as one value. */
    private fun sweepRanges() = SweepRanges(
        subsetMin = viewModel.subsetMin,
        subsetMax = viewModel.subsetMax,
        strainWinMin = viewModel.strainWinMin,
        strainWinMax = viewModel.strainWinMax,
        subsetSamples = viewModel.subsetSamples,
        strainWinSamples = viewModel.strainWinSamples,
        stepDenominator = viewModel.stepDenominator,
    )

    fun refreshSweepPlan() {
        if (!::tvSweepPlan.isInitialized) return
        rangeFields?.showCountsAndStepDepth()
        refreshSweepFrameUi()

        val plan = currentPlan()
        when {
            plan.isNotEmpty() -> {
                tvSweepPlan.isVisible = true
                sweepPlanWarn.hide()
                tvSweepPlan.text = planSummary(plan)
            }
            viewModel.subsetMin > callbacks.maxSubsetForRoi() -> {
                tvSweepPlan.isVisible = false
                sweepPlanWarn.show(
                    activity.getString(
                        R.string.sweep_plan_subset_too_big_fmt,
                        callbacks.maxSubsetForRoi(),
                    ),
                    activity.getString(R.string.url_faq_sweep_subset_range),
                )
            }
            else -> {
                tvSweepPlan.isVisible = false
                sweepPlanWarn.show(
                    activity.getString(R.string.sweep_plan_empty),
                    activity.getString(R.string.url_faq_sweep_empty_plan),
                )
            }
        }
        refreshLatticePreview(plan)
        refreshLineCutPreview()
        callbacks.checkReady()
    }

    /** Defaults to the middle of the sequence (1-based frame n/2+1). */
    fun resolvedSweepFrame(): Int {
        val n = viewModel.defCount
        if (n <= 0) return 0
        val last = n - 1
        val stored = viewModel.vsgFrameIndex
        return if (stored < 0 || stored > last) {
            (n / 2).coerceIn(0, last)
        } else {
            stored
        }
    }

    /** "N analyses · subset a–b px · window c–d points" for a plan. */
    fun planSummary(plan: List<VsgStudy.Point>): String = activity.resources.getQuantityString(
        R.plurals.sweep_plan_grid_fmt,
        plan.size,
        plan.size,
        plan.minOf { it.subset },
        plan.maxOf { it.subset },
        plan.minOf { it.window },
        plan.maxOf { it.window },
    )

    /** Short per-combination label; becomes the frame name in viewer and report. */
    fun combinationLabel(point: VsgStudy.Point): String = activity.getString(
        R.string.sweep_frame_label_fmt,
        point.subset,
        point.step,
        point.window,
    )

    /** Centre-line cut over the reference image and current ROI. */
    fun refreshLineCutPreview() {
        if (!::lineCutPreview.isInitialized) return
        val size = ImageSize(viewModel.realRefWidth, viewModel.realRefHeight)
        val drawn = Roi(viewModel.roiX, viewModel.roiY, viewModel.roiW, viewModel.roiH)
        val roi = drawn.orFullFrame(viewModel.hasCustomRoi, size)
        if (roi == null) {
            lineCutPreview.setPreview(
                bitmap = null,
                image = ImageSize(1, 1),
                roi = Roi(0, 0, 1, 1),
                horizontal = viewModel.lineCutHorizontal,
                maskBytes = null,
            )
            return
        }
        lineCutPreview.setPreview(
            bitmap = callbacks.refPreviewBitmap(),
            image = size,
            roi = roi,
            horizontal = viewModel.lineCutHorizontal,
            maskBytes = viewModel.roiMaskBytes,
        )
    }

    fun setRunSweepEnabled(enabled: Boolean) {
        if (!::btnRunSweep.isInitialized) return
        btnRunSweep.isEnabled = enabled
    }

    private fun wireSweepInfoButtons() {
        fun info(@IdRes button: Int, @StringRes title: Int, @StringRes body: Int) =
            activity.findViewById<View>(button).bindInfo(activity, title, body)
        info(R.id.btnSweepInfo, R.string.analysis_mode, R.string.info_analysis_mode)
        info(R.id.btnSubsetRangeInfo, R.string.subset_range, R.string.info_subset_range)
        info(R.id.btnVsgMaxInfo, R.string.strain_win_range, R.string.info_strain_win_range)
        info(R.id.btnSamplesInfo, R.string.subset_samples, R.string.info_subset_samples)
        info(R.id.btnStepDepthInfo, R.string.step_depth, R.string.info_step_depth)
        info(R.id.btnSweepOverlapInfo, R.string.subset_overlap, R.string.info_subset_overlap)
        info(R.id.btnLineCutInfo, R.string.line_cut_axis, R.string.info_line_cut_axis)
    }

    private fun refreshSweepFrameUi() {
        if (!::btnPickSweepFrame.isInitialized) return
        val count = viewModel.defCount
        if (count <= 1) {
            btnPickSweepFrame.isVisible = false
            return
        }
        val index = resolvedSweepFrame()
        btnPickSweepFrame.isVisible = true
        btnPickSweepFrame.text = activity.getString(
            R.string.sweep_frame_summary_fmt,
            framePicker.frameLabel(index),
            index + 1,
            count,
        )
    }

    private fun refreshLatticePreview(plan: List<VsgStudy.Point>) {
        if (!::sweepLatticePreview.isInitialized) return
        sweepLatticePreview.onNodeClick = null
        sweepLatticePreview.setNodes(
            plan.map { point ->
                VsgLatticeView.Node(
                    subset = point.subset,
                    step = point.step,
                    window = point.window,
                    vsg = point.vsg,
                    solved = true,
                )
            },
        )
    }
}
