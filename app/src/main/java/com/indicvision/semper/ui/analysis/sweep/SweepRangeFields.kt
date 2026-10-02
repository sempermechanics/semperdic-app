package com.indicvision.semper.ui.analysis.sweep

import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.RangeSlider
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.recommend.SubsetRecommender
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.common.commitOnDone
import com.indicvision.semper.ui.common.showUnlessEditing
import java.util.Locale

/**
 * The sweep's range inputs: the subset and strain-window range sliders with
 * their min/max fields, the step depth and its linked overlap, and the two
 * sample counts. Each commit clamps what was typed or slid, writes it to
 * [viewModel] and back to the controls, then runs [onChanged].
 *
 * The values follow the app's suggestions ([seed]) until the user edits one
 * ([userModified]); after that the user's values stand, only clamped.
 */
@Suppress("TooManyFunctions") // one commit and one write per control
internal class SweepRangeFields(
    activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val callbacks: SweepSetupHelper.Callbacks,
    private val onChanged: () -> Unit,
) {
    private val rangeSubset: RangeSlider = activity.findViewById(R.id.rangeSubset)
    private val etSubsetMinValue: EditText = activity.findViewById(R.id.etSubsetMinValue)
    private val etSubsetMaxValue: EditText = activity.findViewById(R.id.etSubsetMaxValue)
    private val rangeStrainWin: RangeSlider = activity.findViewById(R.id.rangeStrainWin)
    private val etStrainWinMinValue: EditText = activity.findViewById(R.id.etStrainWinMinValue)
    private val etStrainWinMaxValue: EditText = activity.findViewById(R.id.etStrainWinMaxValue)
    private val etStepDepthValue: EditText = activity.findViewById(R.id.etStepDepthValue)
    private val tvSweepOverlapValue: EditText = activity.findViewById(R.id.tvSweepOverlapValue)
    private val etSubsetSamplesValue: EditText = activity.findViewById(R.id.etSubsetSamplesValue)
    private val etStrainWinSamplesValue: EditText = activity.findViewById(R.id.etVsgSamplesValue)

    /** True while a suggestion/clamp is driving the sweep sliders, not the user. */
    private var bindingSweep = false

    /**
     * Set once the user edits any sweep control. Until then the three sweep
     * inputs — min subset, max subset, Max VSG — follow the app's suggestions,
     * which track the SSSIG recommendation. After it, the user is in charge and
     * the app only clamps their input to safe bounds.
     */
    var userModified = false

    init {
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

    /** Clears focus on the numeric fields so in-progress typing commits. */
    fun clearFocus() {
        etSubsetMinValue.clearFocus()
        etSubsetMaxValue.clearFocus()
        etStrainWinMinValue.clearFocus()
        etStrainWinMaxValue.clearFocus()
        etStepDepthValue.clearFocus()
        tvSweepOverlapValue.clearFocus()
    }

    /** Shows the two sample counts and the step depth / overlap pair as the view model holds them. */
    fun showCountsAndStepDepth() {
        callbacks.renderParamField(etSubsetSamplesValue, viewModel.subsetSamples)
        callbacks.renderParamField(etStrainWinSamplesValue, viewModel.strainWinSamples)
        writeStepDepth(viewModel.stepDenominator)
    }

    /**
     * Writes the app's suggestion — a subset window centred on the
     * recommendation and the default strain windows — unless the user has
     * taken over. Then their values stand, but an ROI edit can still shrink
     * what it's physically possible to solve, so the displayed subset range
     * keeps up: without this, the plan silently clamped subsetMax while the
     * slider kept showing the old value.
     */
    fun seed() {
        if (userModified) {
            reclampSubsetRangeToRoi()
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
    }

    private inline fun onSliderInput(fromUser: Boolean, body: () -> Unit) {
        if (bindingSweep) return
        if (fromUser) userModified = true
        body()
    }

    /** Commits a typed value on focus loss (blank or unparseable puts [current] back); Done drops focus. */
    private fun wireSweepField(field: EditText, current: () -> Int, commit: (Int) -> Unit) {
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = field.text.toString().trim().toIntOrNull()
            if (typed == null) {
                callbacks.renderParamField(field, current())
            } else {
                userModified = true
                commit(typed)
            }
        }
        field.commitOnDone()
    }

    private fun effectiveSubsetCeiling(): Int =
        minOf(SubsetRecommender.MAX_SUBSET, callbacks.maxSubsetForRoi())

    /**
     * Keeps the user's own subset range inside what the current ROI can
     * support -- same ceiling, same clamp the plan already applies when
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
        onChanged()
    }

    private fun commitSubsetMin(raw: Int) {
        val currentMax = viewModel.subsetMax.takeIf { it > 0 } ?: effectiveSubsetCeiling()
        viewModel.subsetMin = oddSubset(raw).coerceAtMost(currentMax)
        writeSubsetRange(viewModel.subsetMin, viewModel.subsetMax)
        onChanged()
    }

    private fun commitSubsetMax(raw: Int) {
        val floor = viewModel.subsetMin.coerceAtLeast(SubsetRecommender.MIN_SUBSET)
        viewModel.subsetMax = oddSubset(raw).coerceIn(floor, effectiveSubsetCeiling())
        writeSubsetRange(viewModel.subsetMin, viewModel.subsetMax)
        onChanged()
    }

    private fun oddWindow(raw: Int): Int = VsgStudy.oddWindowPoints(raw)

    private fun commitStrainWinRange(rawLo: Int, rawHi: Int) {
        val lo = oddWindow(rawLo)
        val hi = oddWindow(rawHi).coerceAtLeast(lo)
        viewModel.strainWinMin = lo
        viewModel.strainWinMax = hi
        writeStrainWinRange(lo, hi)
        onChanged()
    }

    private fun commitStrainWinMin(raw: Int) {
        val currentMax = viewModel.strainWinMax.takeIf { it > 0 } ?: SweepSetupHelper.STRAIN_WIN_MAX_INPUT
        viewModel.strainWinMin = oddWindow(raw).coerceAtMost(currentMax)
        writeStrainWinRange(viewModel.strainWinMin, viewModel.strainWinMax)
        onChanged()
    }

    private fun commitStrainWinMax(raw: Int) {
        val floor = viewModel.strainWinMin.coerceAtLeast(SweepSetupHelper.STRAIN_WIN_MIN_INPUT)
        viewModel.strainWinMax = oddWindow(raw).coerceAtLeast(floor)
        writeStrainWinRange(viewModel.strainWinMin, viewModel.strainWinMax)
        onChanged()
    }

    private fun writeStrainWinRange(lo: Int, hi: Int) {
        writeRange(rangeStrainWin, lo, hi)
        callbacks.renderParamField(etStrainWinMinValue, lo)
        callbacks.renderParamField(etStrainWinMaxValue, hi)
    }

    private fun commitStepDepth(raw: Int) {
        viewModel.stepDenominator = raw.coerceIn(VsgStudy.STEP_DENOM_MIN, VsgStudy.STEP_DENOM_MAX)
        viewModel.subsetOverlap = VsgStudy.overlapForDenominator(viewModel.stepDenominator)
        writeStepDepth(viewModel.stepDenominator)
        onChanged()
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
        tvSweepOverlapValue.showUnlessEditing(String.format(Locale.US, "%.2f", viewModel.subsetOverlap))
    }

    /** The overlap field: a decimal (comma or point) that sets the step depth; unparseable puts it back. */
    private fun wireSweepOverlapField() {
        tvSweepOverlapValue.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = tvSweepOverlapValue.text.toString().trim().replace(',', '.').toDoubleOrNull()
            if (typed == null) {
                writeStepDepth(viewModel.stepDenominator)
            } else {
                userModified = true
                commitOverlap(typed)
            }
        }
        tvSweepOverlapValue.commitOnDone()
    }

    private fun commitSubsetSamples(raw: Int) {
        viewModel.subsetSamples = raw.coerceIn(VsgStudy.MIN_SAMPLES, VsgStudy.MAX_SAMPLES)
        callbacks.renderParamField(etSubsetSamplesValue, viewModel.subsetSamples)
        onChanged()
    }

    private fun commitStrainWinSamples(raw: Int) {
        viewModel.strainWinSamples = raw.coerceIn(VsgStudy.MIN_SAMPLES, VsgStudy.MAX_SAMPLES)
        callbacks.renderParamField(etStrainWinSamplesValue, viewModel.strainWinSamples)
        onChanged()
    }

    private fun writeSubsetRange(lo: Int, hi: Int) {
        writeRange(rangeSubset, lo, hi)
        callbacks.renderParamField(etSubsetMinValue, lo)
        callbacks.renderParamField(etSubsetMaxValue, hi)
    }

    /** Moves [slider]'s thumbs to [lo]..[hi], inside its own range, without it counting as the user's edit. */
    private fun writeRange(slider: RangeSlider, lo: Int, hi: Int) {
        bindingSweep = true
        slider.values = listOf(
            lo.toFloat().coerceIn(slider.valueFrom, slider.valueTo),
            hi.toFloat().coerceIn(slider.valueFrom, slider.valueTo),
        )
        bindingSweep = false
    }

    /**
     * A subset window of [SweepSetupHelper.SUGGESTED_SUBSET_SPAN] centred on
     * [rec], shifted whole to fit inside `[MIN_SUBSET, ceiling]` so it never
     * collapses to a single value unless the valid range itself is that narrow.
     */
    private fun suggestedSubsetWindow(rec: Int, ceiling: Int): Pair<Int, Int> {
        val half = SweepSetupHelper.SUGGESTED_SUBSET_SPAN / 2
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
}
