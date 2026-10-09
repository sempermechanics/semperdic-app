package com.sempermechanics.semper.ui.analysis.sweep

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.recommend.RunEstimate
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import com.sempermechanics.semper.ui.common.Motion
import com.sempermechanics.semper.ui.common.dialog.WarnChip
import com.sempermechanics.semper.ui.common.dialog.bindInfo
import com.sempermechanics.semper.ui.common.onButtonChecked

/**
 * Parameter-sweep setup UI for the analysis wizard (§5.4.5 parameter sweep).
 * Page 2 (sweep setup): the mode toggle, the frame to sweep, and the planned
 * lattice with its summary and, under its gear, the sample counts. Page 3
 * (sweep settings): the subset, window and step inputs, the line cut axis over
 * a preview that opens on a tap, and "Run 9 analyses · about 33 s".
 * Orchestration ([startSweep], progress, lifecycle) stays in the Activity.
 * The range inputs are [SweepRangeFields]; the frame dropdown is
 * [SweepFramePicker].
 */
@Suppress("TooManyFunctions") // the wizard-facing API StaticAnalysisActivity calls, and its Callbacks
class SweepSetupController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun goToStep(step: WizardStep, animate: Boolean)
        fun updateWizardChrome()
        fun checkReady()
        fun commitParamFields()
        fun startSweep()
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
        const val STRAIN_WIN_MIN_INPUT = SweepStudy.MIN_WINDOW_POINTS
        const val STRAIN_WIN_MAX_INPUT = SweepStudy.MAX_WINDOW_POINTS

        /** The open line-cut preview takes at most this share of the screen height. */
        private const val EXPANDED_MAX_SCREEN = 0.6f

        /** Height over width of the open preview before a reference is known. */
        private const val DEFAULT_ASPECT = 0.75f
    }

    private lateinit var rgAnalysisMode: MaterialButtonToggleGroup
    private lateinit var advancedParamsCard: View
    private lateinit var sweepSettingsCard: View
    private lateinit var rgLineCutAxis: MaterialButtonToggleGroup
    private lateinit var tvSweepPlan: TextView
    private lateinit var tvStrainWinVsg: TextView
    private lateinit var sweepPlanWarn: WarnChip
    private lateinit var lineCutPreview: LineCutPreviewView
    private lateinit var roiThumb: LineCutPreviewView
    private lateinit var sweepLatticePreview: SweepLatticeView
    lateinit var btnRunSweep: Button
        private set
    private lateinit var latticeSamplesBody: View

    /** True while the line-cut preview is open at full width rather than the strip. */
    private var lineCutExpanded = false

    /** The subset, window, step and sample inputs; null until [setup]. */
    private var rangeFields: SweepRangeFields? = null

    private val framePicker = SweepFramePicker(activity, viewModel) { picked ->
        viewModel.sweepFrameIndex = picked
        refreshSweepPlan()
    }

    fun setup() {
        rgAnalysisMode = activity.findViewById(R.id.rgAnalysisMode)
        advancedParamsCard = activity.findViewById(R.id.advancedParamsCard)
        sweepSettingsCard = activity.findViewById(R.id.sweepSettingsCard)
        rgLineCutAxis = activity.findViewById(R.id.rgLineCutAxis)
        tvSweepPlan = activity.findViewById(R.id.tvSweepPlan)
        tvStrainWinVsg = activity.findViewById(R.id.tvStrainWinVsg)
        sweepPlanWarn = WarnChip(activity.findViewById(R.id.sweepPlanWarnRow), callbacks::confirmOpenFaq)
        lineCutPreview = activity.findViewById(R.id.lineCutPreview)
        roiThumb = activity.findViewById<LineCutPreviewView>(R.id.roiThumb).apply { compact = true }
        sweepLatticePreview = activity.findViewById(R.id.sweepLatticePreview)
        // Same compact axes as the result lattice, now that the preview is the
        // same 136dp height -- full/default mode needs more room than that.
        sweepLatticePreview.compact = true
        btnRunSweep = activity.findViewById(R.id.btnRunSweep)
        latticeSamplesBody = activity.findViewById(R.id.latticeSamplesBody)
        framePicker.bind()
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

        btnRunSweep.setOnClickListener {
            callbacks.commitParamFields()
            callbacks.startSweep()
        }

        activity.findViewById<View>(R.id.btnLatticeSamples).setOnClickListener {
            (latticeSamplesBody.parent as? ViewGroup)?.let(Motion::animateExpandCollapse)
            latticeSamplesBody.isVisible = !latticeSamplesBody.isVisible
        }
        lineCutPreview.setOnClickListener { expandLineCut(!lineCutExpanded) }
        expandLineCut(false)

        wireSweepInfoButtons()

        applyAnalysisModeUi()
        fields.showCountsAndStepDepth()
        seedSweepSuggestions()
    }

    /**
     * Single setting keeps the correlation section and Compute on page 2.
     * Parameter sweep shows the frame and planned lattice on page 2 and routes
     * through Next → page 3 (sweep settings).
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
    fun currentPlan(): List<SweepStudy.Point> = sweepRanges().plan(callbacks.maxSubsetForRoi())

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
        tvStrainWinVsg.isVisible = plan.isNotEmpty()
        btnRunSweep.text = runLabel(plan)
        when {
            plan.isNotEmpty() -> {
                tvSweepPlan.isVisible = true
                sweepPlanWarn.hide()
                tvSweepPlan.text = planSummary(plan)
                tvStrainWinVsg.text = activity.getString(
                    R.string.strain_win_vsg_range_fmt,
                    plan.minOf { it.vsg },
                    plan.maxOf { it.vsg },
                )
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
        val stored = viewModel.sweepFrameIndex
        return if (stored < 0 || stored > last) {
            (n / 2).coerceIn(0, last)
        } else {
            stored
        }
    }

    /** "N analyses · subset a–b px · window c–d points" for a plan. */
    fun planSummary(plan: List<SweepStudy.Point>): String = activity.resources.getQuantityString(
        R.plurals.sweep_plan_grid_fmt,
        plan.size,
        plan.size,
        plan.minOf { it.subset },
        plan.maxOf { it.subset },
        plan.minOf { it.window },
        plan.maxOf { it.window },
    )

    /**
     * "Run 9 analyses · about 33 s": each analysis solves the sweep frame once
     * over the region its subset leaves, at its own step. The time comes from
     * this phone's finished runs; without any, the count alone.
     */
    fun runLabel(plan: List<SweepStudy.Point>): String {
        val points = plan.sumOf { point ->
            Roi.forSolve(point.subset, viewModel.hasCustomRoi, viewModel.roi, viewModel.refSize)
                ?.let { RunEstimate.gridPoints(it.w, it.h, point.step) } ?: 0
        }
        val res = activity.resources
        val seconds = RunEstimate.seconds(points, 1, AppSettings.runPointsPerSecond(activity))
            ?: return res.getQuantityString(R.plurals.run_sweep_fmt, plan.size, plan.size)
        return res.getQuantityString(
            R.plurals.run_sweep_eta_fmt,
            plan.size,
            plan.size,
            RunEstimate.duration(res, seconds),
        )
    }

    /** Short per-combination label; becomes the frame name in viewer and report. */
    fun combinationLabel(point: SweepStudy.Point): String = activity.getString(
        R.string.sweep_frame_label_fmt,
        point.subset,
        point.step,
        point.window,
    )

    /** The reference with the current ROI: the line-cut preview, and the ROI row's thumbnail. */
    fun refreshLineCutPreview() {
        if (!::lineCutPreview.isInitialized) return
        val size = ImageSize(viewModel.realRefWidth, viewModel.realRefHeight)
        val drawn = Roi(viewModel.roiX, viewModel.roiY, viewModel.roiW, viewModel.roiH)
        val roi = drawn.orFullFrame(viewModel.hasCustomRoi, size)
        for (view in listOf(lineCutPreview, roiThumb)) {
            if (roi == null) {
                view.setPreview(null, ImageSize(1, 1), Roi(0, 0, 1, 1), viewModel.lineCutHorizontal)
            } else {
                view.setPreview(
                    bitmap = callbacks.refPreviewBitmap(),
                    image = size,
                    roi = roi,
                    horizontal = viewModel.lineCutHorizontal,
                    maskBytes = viewModel.roiMaskBytes,
                )
            }
        }
    }

    /**
     * The line-cut preview as a strip, or [open]: at the reference's own
     * aspect across the page, at least `line_cut_preview_open_min` tall and at
     * most [EXPANDED_MAX_SCREEN] of the screen. A reference too wide for that
     * height runs past the page edge; its scroll starts centred.
     */
    private fun expandLineCut(open: Boolean) {
        lineCutExpanded = open
        val res = activity.resources
        val scroll = lineCutPreview.parent as ViewGroup
        val strip = res.getDimensionPixelSize(R.dimen.line_cut_preview_strip)
        var width = ViewGroup.LayoutParams.MATCH_PARENT
        var height = strip
        if (open) {
            val w = viewModel.realRefWidth
            val h = viewModel.realRefHeight
            val aspect = if (w > 0 && h > 0) h.toFloat() / w else DEFAULT_ASPECT
            val cap = (res.displayMetrics.heightPixels * EXPANDED_MAX_SCREEN).toInt()
            val floor = res.getDimensionPixelSize(R.dimen.line_cut_preview_open_min).coerceAtMost(cap)
            height = (scroll.width * aspect).toInt().coerceIn(floor, cap.coerceAtLeast(floor))
            val needed = (height / aspect).toInt()
            if (needed > scroll.width) width = needed
        }
        (scroll.parent as? ViewGroup)?.let(Motion::animateExpandCollapse)
        // The scroll measures its child's width unspecified, so the minimum carries it.
        lineCutPreview.minimumWidth = width.coerceAtLeast(0)
        lineCutPreview.updateLayoutParams {
            this.width = width
            this.height = height
        }
        lineCutPreview.doOnNextLayout {
            scroll.scrollTo(((it.width - scroll.width) / 2).coerceAtLeast(0), 0)
        }
        val name = activity.getString(R.string.line_cut_axis)
        lineCutPreview.contentDescription =
            activity.getString(if (open) R.string.preview_shrink_fmt else R.string.preview_enlarge_fmt, name)
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

    private fun refreshSweepFrameUi() = framePicker.show(resolvedSweepFrame())

    private fun refreshLatticePreview(plan: List<SweepStudy.Point>) {
        if (!::sweepLatticePreview.isInitialized) return
        sweepLatticePreview.onNodeClick = null
        sweepLatticePreview.setNodes(
            plan.map { point ->
                SweepLatticeView.Node(
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
