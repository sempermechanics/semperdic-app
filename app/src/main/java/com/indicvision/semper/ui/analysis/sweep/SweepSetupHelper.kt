// Sweep setup wires many sliders/fields and seeds suggested values. Per-control
// methods and literal UI constants are inherent; suppress rather than baseline.

@file:Suppress("TooManyFunctions", "MagicNumber", "LargeClass")

package com.indicvision.semper.ui.analysis.sweep

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.RangeSlider
import com.indicvision.semper.R
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.RawRgba
import com.indicvision.semper.ui.analysis.recommend.SubsetRecommender
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Parameter-sweep setup UI for the analysis wizard (§5.4.5 parameter sweep): mode
 * toggle, subset/VSG/sample fields, lattice + line-cut previews, and plan
 * summary. Orchestration ([startVsgSweep], progress, lifecycle) stays in the
 * Activity.
 */
class SweepSetupHelper(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun goToStep(step: Int, animate: Boolean)
        fun updateWizardChrome()
        fun checkReady()
        fun showInfo(titleRes: Int, bodyRes: Int)
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

        /** Longest edge of a frame thumbnail in the pick dialog. */
        private const val PREVIEW_MAX_EDGE = 480
    }

    private lateinit var rgAnalysisMode: MaterialButtonToggleGroup
    private lateinit var advancedParamsCard: View
    private lateinit var sweepSettingsCard: View
    private lateinit var rangeSubset: RangeSlider
    private lateinit var etSubsetMinValue: EditText
    private lateinit var etSubsetMaxValue: EditText
    private lateinit var rangeStrainWin: RangeSlider
    private lateinit var etStrainWinMinValue: EditText
    private lateinit var etStrainWinMaxValue: EditText
    private lateinit var etStepDepthValue: EditText
    private lateinit var tvSweepOverlapValue: EditText
    private lateinit var etSubsetSamplesValue: EditText
    private lateinit var etStrainWinSamplesValue: EditText
    private lateinit var rgLineCutAxis: MaterialButtonToggleGroup
    private lateinit var btnPickSweepFrame: Button
    private lateinit var tvSweepPlan: TextView
    private lateinit var sweepPlanWarnRow: View
    private lateinit var lineCutPreview: LineCutPreviewView
    private lateinit var sweepLatticePreview: VsgLatticeView
    lateinit var btnRunSweep: Button
        private set
    private lateinit var latticeSamplesBody: View

    /** True while a suggestion/clamp is driving the sweep sliders, not the user. */
    private var bindingSweep = false

    /** Cancels in-flight frame-pick preview decodes when the selection changes. */
    private var framePreviewJob: Job? = null

    /**
     * Set once the user edits any sweep control. Until then the three sweep
     * inputs — min subset, max subset, Max VSG — follow the app's suggestions,
     * which track the SSSIG recommendation. After it, the user is in charge and
     * the app only clamps their input to safe bounds.
     */
    private var sweepUserModified = false

    fun setup() {
        rgAnalysisMode = activity.findViewById(R.id.rgAnalysisMode)
        advancedParamsCard = activity.findViewById(R.id.advancedParamsCard)
        sweepSettingsCard = activity.findViewById(R.id.sweepSettingsCard)
        rangeSubset = activity.findViewById(R.id.rangeSubset)
        etSubsetMinValue = activity.findViewById(R.id.etSubsetMinValue)
        etSubsetMaxValue = activity.findViewById(R.id.etSubsetMaxValue)
        rangeStrainWin = activity.findViewById(R.id.rangeStrainWin)
        etStrainWinMinValue = activity.findViewById(R.id.etStrainWinMinValue)
        etStrainWinMaxValue = activity.findViewById(R.id.etStrainWinMaxValue)
        etStepDepthValue = activity.findViewById(R.id.etStepDepthValue)
        tvSweepOverlapValue = activity.findViewById(R.id.tvSweepOverlapValue)
        etSubsetSamplesValue = activity.findViewById(R.id.etSubsetSamplesValue)
        etStrainWinSamplesValue = activity.findViewById(R.id.etVsgSamplesValue)
        rgLineCutAxis = activity.findViewById(R.id.rgLineCutAxis)
        btnPickSweepFrame = activity.findViewById(R.id.btnPickSweepFrame)
        tvSweepPlan = activity.findViewById(R.id.tvSweepPlan)
        sweepPlanWarnRow = activity.findViewById(R.id.sweepPlanWarnRow)
        lineCutPreview = activity.findViewById(R.id.lineCutPreview)
        sweepLatticePreview = activity.findViewById(R.id.sweepLatticePreview)
        // Same compact axes as the result lattice, now that the preview is the
        // same 136dp height -- full/default mode needs more room than that.
        sweepLatticePreview.compact = true
        btnRunSweep = activity.findViewById(R.id.btnRunSweep)
        latticeSamplesBody = activity.findViewById(R.id.latticeSamplesBody)

        rgAnalysisMode.check(if (viewModel.sweepMode) R.id.rbModeSweep else R.id.rbModeSingle)
        rgAnalysisMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.sweepMode = checkedId == R.id.rbModeSweep
            // Leaving sweep mode while on the sweep page returns to settings.
            if (!viewModel.sweepMode && viewModel.wizardStep == 3) {
                callbacks.goToStep(2, animate = true)
            } else {
                applyAnalysisModeUi()
                refreshSweepPlan()
            }
        }

        rgLineCutAxis.check(if (viewModel.lineCutHorizontal) R.id.rbAxisX else R.id.rbAxisY)
        rgLineCutAxis.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.lineCutHorizontal = checkedId == R.id.rbAxisX
            refreshLineCutPreview()
        }

        btnPickSweepFrame.setOnClickListener { pickSweepFrameWithPreview() }

        btnRunSweep.setOnClickListener {
            callbacks.commitParamFields()
            callbacks.startVsgSweep()
        }

        activity.findViewById<View>(R.id.btnLatticeSamples).setOnClickListener {
            val expanded = latticeSamplesBody.isVisible
            latticeSamplesBody.isVisible = !expanded
        }

        wireControls()
        wireSweepInfoButtons()

        applyAnalysisModeUi()
        callbacks.renderParamField(etSubsetSamplesValue, viewModel.subsetSamples)
        callbacks.renderParamField(etStrainWinSamplesValue, viewModel.strainWinSamples)
        writeStepDepth(viewModel.stepDenominator)
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
        if (!::etSubsetMinValue.isInitialized) return
        etSubsetMinValue.clearFocus()
        etSubsetMaxValue.clearFocus()
        etStrainWinMinValue.clearFocus()
        etStrainWinMaxValue.clearFocus()
        etStepDepthValue.clearFocus()
        tvSweepOverlapValue.clearFocus()
    }

    /** Hands the sweep back to suggested inputs (e.g. Advanced Reset). */
    fun resetUserModified() {
        sweepUserModified = false
    }

    fun onRecommendationChanged() = seedSweepSuggestions()

    /**
     * Seeds the sweep inputs with the app's suggestions — a subset window
     * centred on the SSSIG recommendation and a strain window of 3 to 11 points.
     * Runs until the user edits a sweep control; after that their values stand.
     */
    fun seedSweepSuggestions() {
        if (!::rangeSubset.isInitialized) return
        if (sweepUserModified) {
            // Past this point their values stand -- but an ROI edit can still
            // shrink what it's physically possible to solve, so the displayed
            // range must keep up even though the app stops suggesting a fresh
            // default. Without this, currentPlan() silently clamped subsetMax
            // for plan generation while the slider kept showing the old value.
            reclampSubsetRangeToRoi()
            refreshSweepPlan()
            return
        }
        val ceiling = effectiveSubsetCeiling()
        val rec = callbacks.currentSubsetSize().coerceIn(SubsetRecommender.MIN_SUBSET, ceiling)
        val (lo, hi) = suggestedSubsetWindow(rec, ceiling)
        viewModel.subsetMin = lo
        viewModel.subsetMax = hi
        viewModel.strainWinMin = VsgStudy.DEFAULT_SWEEP_WINDOW_MIN
        viewModel.strainWinMax = VsgStudy.DEFAULT_SWEEP_WINDOW_MAX
        writeSubsetRange(lo, hi)
        writeStrainWinRange(viewModel.strainWinMin, viewModel.strainWinMax)
        refreshSweepPlan()
    }

    /**
     * The sweep grid the current inputs describe, capped to the subsets the ROI
     * can hold: x subset sizes × y strain windows, one step per subset.
     */
    fun currentPlan(): List<VsgStudy.Point> {
        val ceiling = callbacks.maxSubsetForRoi()
        if (viewModel.subsetMin > ceiling) return emptyList()
        return VsgStudy.plan(
            subsetMin = viewModel.subsetMin,
            subsetMax = viewModel.subsetMax.coerceAtMost(ceiling),
            subsetSamples = viewModel.subsetSamples,
            strainWinMin = viewModel.strainWinMin,
            strainWinMax = viewModel.strainWinMax,
            strainWinSamples = viewModel.strainWinSamples,
            stepDenominator = viewModel.stepDenominator,
        )
    }

    fun refreshSweepPlan() {
        if (!::tvSweepPlan.isInitialized) return
        callbacks.renderParamField(etSubsetSamplesValue, viewModel.subsetSamples)
        callbacks.renderParamField(etStrainWinSamplesValue, viewModel.strainWinSamples)
        writeStepDepth(viewModel.stepDenominator)
        refreshSweepFrameUi()

        val plan = currentPlan()
        when {
            plan.isNotEmpty() -> {
                tvSweepPlan.isVisible = true
                sweepPlanWarnRow.isVisible = false
                tvSweepPlan.text = planSummary(plan)
            }
            viewModel.subsetMin > callbacks.maxSubsetForRoi() -> {
                tvSweepPlan.isVisible = false
                showSweepPlanWarning(
                    activity.getString(
                        R.string.sweep_plan_subset_too_big_fmt,
                        callbacks.maxSubsetForRoi(),
                    ),
                    activity.getString(R.string.url_faq_sweep_subset_range),
                )
            }
            else -> {
                tvSweepPlan.isVisible = false
                showSweepPlanWarning(
                    activity.getString(R.string.sweep_plan_empty),
                    activity.getString(R.string.url_faq_sweep_empty_plan),
                )
            }
        }
        refreshLatticePreview(plan)
        refreshLineCutPreview()
        callbacks.checkReady()
    }

    private fun showSweepPlanWarning(message: String, faqUrl: String) {
        sweepPlanWarnRow.findViewById<TextView>(R.id.tvWarnText).text = message
        sweepPlanWarnRow.findViewById<ImageButton>(R.id.btnWarnFaq).setOnClickListener {
            callbacks.confirmOpenFaq(faqUrl)
        }
        sweepPlanWarnRow.isVisible = true
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
        val w = viewModel.realRefWidth
        val h = viewModel.realRefHeight
        if (w <= 0 || h <= 0) {
            lineCutPreview.setPreview(
                bitmap = null,
                imageW = 1,
                imageH = 1,
                roiX = 0,
                roiY = 0,
                roiW = 1,
                roiH = 1,
                horizontal = viewModel.lineCutHorizontal,
                maskBytes = null,
            )
            return
        }
        val roiX = if (viewModel.hasCustomRoi) viewModel.roiX else 0
        val roiY = if (viewModel.hasCustomRoi) viewModel.roiY else 0
        val roiW = if (viewModel.hasCustomRoi && viewModel.roiW > 0) viewModel.roiW else w
        val roiH = if (viewModel.hasCustomRoi && viewModel.roiH > 0) viewModel.roiH else h
        lineCutPreview.setPreview(
            bitmap = callbacks.refPreviewBitmap(),
            imageW = w,
            imageH = h,
            roiX = roiX,
            roiY = roiY,
            roiW = roiW,
            roiH = roiH,
            horizontal = viewModel.lineCutHorizontal,
            maskBytes = viewModel.roiMaskBytes,
        )
    }

    fun setRunSweepEnabled(enabled: Boolean) {
        if (!::btnRunSweep.isInitialized) return
        btnRunSweep.isEnabled = enabled
    }

    // ------------------------------------------------------------------
    // Wiring + commits
    // ------------------------------------------------------------------

    private fun wireControls() {
        rangeSubset.addOnChangeListener { slider, _, fromUser ->
            onSliderInput(fromUser) { commitSubsetRange(slider.values[0].toInt(), slider.values[1].toInt()) }
        }

        wireSweepField(etSubsetMinValue, { viewModel.subsetMin }) { commitSubsetMin(it) }
        wireSweepField(etSubsetMaxValue, { viewModel.subsetMax }) { commitSubsetMax(it) }
        rangeStrainWin.addOnChangeListener { slider, _, fromUser ->
            onSliderInput(fromUser) { commitStrainWinRange(slider.values[0].toInt(), slider.values[1].toInt()) }
        }
        wireSweepField(etStrainWinMinValue, { viewModel.strainWinMin }) { commitStrainWinMin(it) }
        wireSweepField(etStrainWinMaxValue, { viewModel.strainWinMax }) { commitStrainWinMax(it) }
        wireSweepField(etStepDepthValue, { viewModel.stepDenominator }) { commitStepDepth(it) }
        wireSweepOverlapField()
        wireSweepField(etSubsetSamplesValue, { viewModel.subsetSamples }) { commitSubsetSamples(it) }
        wireSweepField(etStrainWinSamplesValue, { viewModel.strainWinSamples }) { commitStrainWinSamples(it) }
    }

    private inline fun onSliderInput(fromUser: Boolean, body: () -> Unit) {
        if (bindingSweep) return
        if (fromUser) sweepUserModified = true
        body()
    }

    private fun wireSweepField(field: EditText, current: () -> Int, commit: (Int) -> Unit) {
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = field.text.toString().trim().toIntOrNull()
            if (typed == null) {
                callbacks.renderParamField(field, current())
            } else {
                sweepUserModified = true
                commit(typed)
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                field.clearFocus()
                activity.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(field.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun wireSweepInfoButtons() {
        activity.findViewById<View>(R.id.btnSweepInfo)
            .setOnClickListener { callbacks.showInfo(R.string.analysis_mode, R.string.info_analysis_mode) }
        activity.findViewById<View>(R.id.btnSubsetRangeInfo)
            .setOnClickListener { callbacks.showInfo(R.string.subset_range, R.string.info_subset_range) }
        activity.findViewById<View>(R.id.btnVsgMaxInfo)
            .setOnClickListener { callbacks.showInfo(R.string.strain_win_range, R.string.info_strain_win_range) }
        activity.findViewById<View>(R.id.btnSamplesInfo)
            .setOnClickListener { callbacks.showInfo(R.string.subset_samples, R.string.info_subset_samples) }
        activity.findViewById<View>(R.id.btnStepDepthInfo)
            .setOnClickListener { callbacks.showInfo(R.string.step_depth, R.string.info_step_depth) }
        activity.findViewById<View>(R.id.btnSweepOverlapInfo)
            .setOnClickListener { callbacks.showInfo(R.string.subset_overlap, R.string.info_subset_overlap) }
        activity.findViewById<View>(R.id.btnLineCutInfo)
            .setOnClickListener { callbacks.showInfo(R.string.line_cut_axis, R.string.info_line_cut_axis) }
    }

    private fun effectiveSubsetCeiling(): Int =
        minOf(SubsetRecommender.MAX_SUBSET, callbacks.maxSubsetForRoi())

    /**
     * Keeps the user's own subset range inside what the current ROI can
     * support -- same ceiling, same clamp [currentPlan] already applies when
     * generating nodes, just also written back to [viewModel] and the slider
     * so what's displayed matches what will actually be planned.
     */
    private fun reclampSubsetRangeToRoi() {
        val ceiling = effectiveSubsetCeiling()
        val clampedMax = viewModel.subsetMax.coerceAtMost(ceiling)
        val clampedMin = viewModel.subsetMin.coerceAtMost(clampedMax)
        if (clampedMin != viewModel.subsetMin || clampedMax != viewModel.subsetMax) {
            viewModel.subsetMin = clampedMin
            viewModel.subsetMax = clampedMax
            writeSubsetRange(clampedMin, clampedMax)
        }
    }

    private fun oddSubset(raw: Int): Int =
        raw.coerceIn(SubsetRecommender.MIN_SUBSET, SubsetRecommender.MAX_SUBSET) or 1

    private fun commitSubsetRange(rawLo: Int, rawHi: Int) {
        val ceiling = effectiveSubsetCeiling()
        val lo = oddSubset(rawLo).coerceIn(SubsetRecommender.MIN_SUBSET, ceiling)
        val hi = oddSubset(rawHi).coerceIn(lo, ceiling)
        viewModel.subsetMin = lo
        viewModel.subsetMax = hi
        writeSubsetRange(lo, hi)
        refreshSweepPlan()
    }

    private fun commitSubsetMin(raw: Int) {
        val currentMax = viewModel.subsetMax.takeIf { it > 0 } ?: effectiveSubsetCeiling()
        viewModel.subsetMin = oddSubset(raw).coerceAtMost(currentMax)
        writeSubsetRange(viewModel.subsetMin, viewModel.subsetMax)
        refreshSweepPlan()
    }

    private fun commitSubsetMax(raw: Int) {
        val floor = viewModel.subsetMin.coerceAtLeast(SubsetRecommender.MIN_SUBSET)
        viewModel.subsetMax = oddSubset(raw).coerceIn(floor, effectiveSubsetCeiling())
        writeSubsetRange(viewModel.subsetMin, viewModel.subsetMax)
        refreshSweepPlan()
    }

    private fun oddWindow(raw: Int): Int = VsgStudy.oddWindowPoints(raw)

    private fun commitStrainWinRange(rawLo: Int, rawHi: Int) {
        val lo = oddWindow(rawLo)
        val hi = oddWindow(rawHi).coerceAtLeast(lo)
        viewModel.strainWinMin = lo
        viewModel.strainWinMax = hi
        writeStrainWinRange(lo, hi)
        refreshSweepPlan()
    }

    private fun commitStrainWinMin(raw: Int) {
        val currentMax = viewModel.strainWinMax.takeIf { it > 0 } ?: STRAIN_WIN_MAX_INPUT
        viewModel.strainWinMin = oddWindow(raw).coerceAtMost(currentMax)
        writeStrainWinRange(viewModel.strainWinMin, viewModel.strainWinMax)
        refreshSweepPlan()
    }

    private fun commitStrainWinMax(raw: Int) {
        val floor = viewModel.strainWinMin.coerceAtLeast(STRAIN_WIN_MIN_INPUT)
        viewModel.strainWinMax = oddWindow(raw).coerceAtLeast(floor)
        writeStrainWinRange(viewModel.strainWinMin, viewModel.strainWinMax)
        refreshSweepPlan()
    }

    private fun writeStrainWinRange(lo: Int, hi: Int) {
        bindingSweep = true
        rangeStrainWin.values = listOf(
            lo.toFloat().coerceIn(rangeStrainWin.valueFrom, rangeStrainWin.valueTo),
            hi.toFloat().coerceIn(rangeStrainWin.valueFrom, rangeStrainWin.valueTo),
        )
        bindingSweep = false
        callbacks.renderParamField(etStrainWinMinValue, lo)
        callbacks.renderParamField(etStrainWinMaxValue, hi)
    }

    private fun commitStepDepth(raw: Int) {
        viewModel.stepDenominator = raw.coerceIn(VsgStudy.STEP_DENOM_MIN, VsgStudy.STEP_DENOM_MAX)
        viewModel.subsetOverlap = VsgStudy.overlapForDenominator(viewModel.stepDenominator)
        writeStepDepth(viewModel.stepDenominator)
        refreshSweepPlan()
    }

    private fun commitOverlap(raw: Double) {
        commitStepDepth(VsgStudy.denominatorForOverlap(raw))
    }

    private fun writeStepDepth(denominator: Int) {
        val n = denominator.coerceIn(VsgStudy.STEP_DENOM_MIN, VsgStudy.STEP_DENOM_MAX)
        viewModel.stepDenominator = n
        viewModel.subsetOverlap = VsgStudy.overlapForDenominator(n)
        if (!etStepDepthValue.hasFocus()) {
            callbacks.renderParamField(etStepDepthValue, n)
        }
        if (!tvSweepOverlapValue.hasFocus()) {
            tvSweepOverlapValue.setText(
                String.format(Locale.US, "%.2f", viewModel.subsetOverlap),
            )
        }
    }

    private fun wireSweepOverlapField() {
        val commit = {
            val typed = tvSweepOverlapValue.text.toString().trim().replace(',', '.').toDoubleOrNull()
            if (typed == null) {
                writeStepDepth(viewModel.stepDenominator)
            } else {
                sweepUserModified = true
                commitOverlap(typed)
            }
        }
        bindSweepCommitField(tvSweepOverlapValue, commit)
    }

    private fun bindSweepCommitField(field: EditText, commit: () -> Unit) {
        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                field.clearFocus()
                activity.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(field.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun commitSubsetSamples(raw: Int) {
        viewModel.subsetSamples = raw.coerceIn(VsgStudy.MIN_SAMPLES, VsgStudy.MAX_SAMPLES)
        callbacks.renderParamField(etSubsetSamplesValue, viewModel.subsetSamples)
        refreshSweepPlan()
    }

    private fun commitStrainWinSamples(raw: Int) {
        viewModel.strainWinSamples = raw.coerceIn(VsgStudy.MIN_SAMPLES, VsgStudy.MAX_SAMPLES)
        callbacks.renderParamField(etStrainWinSamplesValue, viewModel.strainWinSamples)
        refreshSweepPlan()
    }

    private fun writeSubsetRange(lo: Int, hi: Int) {
        bindingSweep = true
        rangeSubset.values = listOf(
            lo.toFloat().coerceIn(rangeSubset.valueFrom, rangeSubset.valueTo),
            hi.toFloat().coerceIn(rangeSubset.valueFrom, rangeSubset.valueTo),
        )
        bindingSweep = false
        callbacks.renderParamField(etSubsetMinValue, lo)
        callbacks.renderParamField(etSubsetMaxValue, hi)
    }

    /**
     * A subset window of [SUGGESTED_SUBSET_SPAN] centred on [rec], shifted whole
     * to fit inside `[MIN_SUBSET, ceiling]` so it never collapses to a single
     * value unless the valid range itself is that narrow.
     */
    private fun suggestedSubsetWindow(rec: Int, ceiling: Int): Pair<Int, Int> {
        val half = SUGGESTED_SUBSET_SPAN / 2
        var lo = rec - half
        var hi = rec + half
        if (lo < SubsetRecommender.MIN_SUBSET) {
            hi += SubsetRecommender.MIN_SUBSET - lo
            lo = SubsetRecommender.MIN_SUBSET
        }
        if (hi > ceiling) {
            lo -= hi - ceiling
            hi = ceiling
        }
        return oddSubset(lo.coerceAtLeast(SubsetRecommender.MIN_SUBSET)) to oddSubset(hi)
    }

    private fun frameLabel(index: Int): String =
        viewModel.defOriginalNames.getOrNull(index)?.substringAfterLast('/')
            ?: activity.getString(R.string.sweep_frame_btn_fmt, index + 1)

    private fun pickSweepFrameWithPreview() {
        val count = viewModel.defCount
        if (count <= 1) return
        var selected = resolvedSweepFrame().coerceIn(0, count - 1)
        val builder = MaterialAlertDialogBuilder(activity)
        // Inflate against the builder's context so the rows pick up the dialog
        // theme overlay rather than the activity's.
        val content = LayoutInflater.from(builder.context)
            .inflate(R.layout.dialog_sweep_frame_pick, null)
        val preview = content.findViewById<ImageView>(R.id.ivSweepFrameDialogPreview)
        val progress = content.findViewById<ProgressBar>(R.id.progressSweepFramePreview)
        val numberField = content.findViewById<EditText>(R.id.etSweepFrameNumber)
        content.findViewById<TextView>(R.id.tvSweepFrameTotal).text =
            activity.getString(R.string.sweep_frame_out_of_fmt, count)

        fun bindPreview(index: Int) {
            val path = viewModel.defFilePaths.getOrNull(index)
            framePreviewJob?.cancel()
            if (path.isNullOrBlank()) {
                preview.setImageDrawable(null)
                progress.isVisible = false
                return
            }
            progress.isVisible = true
            framePreviewJob = activity.lifecycleScope.launch {
                val bmp = decodeFramePreview(path, viewModel.defFrameSizes[path])
                if (index != selected) {
                    bmp?.recycle()
                    return@launch
                }
                progress.isVisible = false
                if (bmp != null) {
                    preview.setImageBitmap(bmp)
                } else {
                    preview.setImageDrawable(null)
                }
            }
        }
        bindPreview(selected)
        val rows = fillFrameChoices(content, count, selected) { which ->
            selected = which
            numberField.setText(frameNumberText(which + 1))
            bindPreview(which)
        }
        numberField.setText(frameNumberText(selected + 1))
        wireFrameNumberField(numberField, current = { selected + 1 }) { typed ->
            val index = (typed - 1).coerceIn(0, count - 1)
            rows.getOrNull(index)?.let { row ->
                row.isChecked = true
                scrollFrameRowIntoView(content, row)
            }
            // Also normalises what was typed — "007" or an out-of-range number.
            numberField.setText(frameNumberText(index + 1))
        }

        builder
            .setTitle(R.string.sweep_frame)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                framePreviewJob?.cancel()
                viewModel.vsgFrameIndex = selected
                refreshSweepPlan()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> framePreviewJob?.cancel() }
            .setOnDismissListener { framePreviewJob?.cancel() }
            .show()
    }

    /**
     * Fills the dialog's scrolling frame list and reports the picked index.
     * The row for [selected] is scrolled into view, so reopening the dialog on
     * frame 30 of 50 does not land the user at the top of the list.
     */
    private fun fillFrameChoices(
        content: View,
        count: Int,
        selected: Int,
        onPick: (Int) -> Unit,
    ): List<MaterialRadioButton> {
        val group = content.findViewById<RadioGroup>(R.id.rgSweepFrames)
        val density = activity.resources.displayMetrics.density
        val rowPadding = (8 * density).toInt()
        val rows = List(count) { index ->
            MaterialRadioButton(group.context).apply {
                id = View.generateViewId()
                text = frameLabel(index)
                tag = index
                minimumHeight = (48 * density).toInt()
                setPadding(paddingLeft, rowPadding, paddingRight, rowPadding)
                group.addView(this)
                isChecked = index == selected
            }
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val index = group.findViewById<View>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
            onPick(index)
        }
        rows.getOrNull(selected)?.let { scrollFrameRowIntoView(content, it) }
        return rows
    }

    /** ASCII digits, so the field round-trips through toIntOrNull() in any locale. */
    private fun frameNumberText(oneBased: Int): String = String.format(Locale.US, "%d", oneBased)

    private fun scrollFrameRowIntoView(content: View, row: View) {
        val scroll = content.findViewById<ScrollView>(R.id.scrollSweepFrames)
        scroll.post { scroll.scrollTo(0, row.top) }
    }

    /**
     * Frame number entry: commits on focus loss (IME Done just drops focus, as
     * the sweep fields do). Anything unparseable reverts to [current].
     */
    private fun wireFrameNumberField(field: EditText, current: () -> Int, onPick: (Int) -> Unit) {
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = field.text.toString().trim().toIntOrNull()
            if (typed == null) field.setText(frameNumberText(current())) else onPick(typed)
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                field.clearFocus()
                activity.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(field.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    /**
     * Decode a deformed-frame path for the pick dialog. Handles ordinary
     * containers, native-only formats (TIFF), and RAW RGBA blobs written at import
     * ([size] is the frame's measured size, for those).
     *
     * Each rung runs where it belongs: file reads on IO, the native decoder on
     * [SemperNativeLib.nativeDispatcher] (every JNI call is pinned there), the
     * JVM decodes on Default.
     */
    @Suppress("ReturnCount") // a ladder of decoders; each rung returns what it managed
    private suspend fun decodeFramePreview(path: String, size: Pair<Int, Int>?): Bitmap? {
        withContext(Dispatchers.IO) {
            BitmapDecode.decodeFileForView(path, PREVIEW_MAX_EDGE, PREVIEW_MAX_EDGE, PREVIEW_MAX_EDGE)
        }?.let { return it }

        val bytes = withContext(Dispatchers.IO) {
            File(path).takeIf(File::exists)?.let { runCatching { it.readBytes() }.getOrNull() }
        } ?: return null

        withContext(SemperNativeLib.nativeDispatcher) {
            runCatching { SemperNativeLib.getPreviewFromBytes(bytes, PREVIEW_MAX_EDGE) }.getOrNull()
        }?.let { return it }

        return withContext(Dispatchers.Default) {
            decodeRawRgba(bytes, size)
                // Last resort: bounds-free BitmapFactory (may still fail for RAW).
                ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    /** A RAW RGBA blob written at import, sampled down to preview size. */
    private fun decodeRawRgba(bytes: ByteArray, size: Pair<Int, Int>?): Bitmap? {
        val (w, h) = size ?: return null
        return RawRgba.preview(bytes, w, h, PREVIEW_MAX_EDGE)
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
            frameLabel(index),
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
