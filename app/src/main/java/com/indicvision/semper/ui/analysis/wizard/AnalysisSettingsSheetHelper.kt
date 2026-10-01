package com.indicvision.semper.ui.analysis.wizard

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.indicvision.semper.R
import com.indicvision.semper.data.prefs.ParamClipboard
import com.indicvision.semper.ui.analysis.recommend.StrainWindowText
import com.indicvision.semper.ui.analysis.sweep.VsgStudy
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Wires the analysis settings sheet listeners (param fields, info buttons,
 * slider label sync). Reset / paste / recommendation logic stays in the Activity
 * so it can touch ViewModel + sweep state without putting disk or network on Main.
 */
@Suppress("LongParameterList", "MagicNumber") // sheet owns a fixed set of named views + callbacks
class AnalysisSettingsSheetHelper(
    private val root: View,
    private val subset: Slider,
    private val step: Slider,
    private val overlap: Slider,
    private val strain: Slider,
    private val subsetValue: EditText,
    private val stepValue: EditText,
    private val overlapValue: EditText,
    private val strainValue: EditText,
    /** The VSG in px the window in points gives at the current step. */
    private val strainVsg: TextView,
    private val renderParamField: (EditText, Int) -> Unit,
    private val bindParamField: (EditText, Slider, (() -> Unit)?) -> Unit,
    private val showInfo: (titleRes: Int, bodyRes: Int) -> Unit,
    private val onSubsetUserModified: () -> Unit,
    private val onSubsetRecommendationRefresh: () -> Unit,
    private val onAdvancedReset: () -> Unit,
    private val onPasteParams: () -> Unit,
    private val onParamsChanged: () -> Unit = {},
) {
    /** True while code is writing the overlap/step pair, not the user. */
    private var bindingOverlap = false

    fun bind() {
        val updateLabels = {
            renderParamField(subsetValue, subset.value.toInt())
            renderParamField(stepValue, step.value.toInt())
            renderParamField(strainValue, strain.value.toInt())
            strainVsg.text = StrainWindowText.vsgAt(strainVsg.context, strain.value.toInt(), step.value.toInt())
        }
        applyStepRangeForSubset()
        syncOverlapFromStep()
        updateLabels()

        bindParamField(subsetValue, subset) {
            onSubsetUserModified()
            onParamsChanged()
        }
        bindParamField(stepValue, step) { onParamsChanged() }
        bindParamField(strainValue, strain) { onParamsChanged() }
        bindOverlapField()

        root.findViewById<View>(R.id.btnAdvancedReset).setOnClickListener { onAdvancedReset() }
        val pasteChip = root.findViewById<View>(R.id.btnPasteParams)
        pasteChip.setOnClickListener { onPasteParams() }
        refreshPasteVisibility()
        root.findViewById<View>(R.id.btnSubsetInfo)
            .setOnClickListener { showInfo(R.string.subset_size, R.string.info_subset) }
        root.findViewById<View>(R.id.btnStepInfo)
            .setOnClickListener { showInfo(R.string.step_size_density, R.string.info_step) }
        root.findViewById<View>(R.id.btnOverlapInfo)
            .setOnClickListener { showInfo(R.string.subset_overlap, R.string.info_subset_overlap) }
        root.findViewById<View>(R.id.btnStrainInfo)
            .setOnClickListener { showInfo(R.string.strain_window, R.string.info_strain_window) }

        subset.addOnChangeListener { _, _, fromUser ->
            if (!bindingOverlap) applyStepRangeForSubset()
            if (fromUser) {
                onSubsetUserModified()
                onSubsetRecommendationRefresh()
                onParamsChanged()
            }
            if (!bindingOverlap) syncOverlapFromStep()
            updateLabels()
        }
        step.addOnChangeListener { _, _, fromUser ->
            if (fromUser) onParamsChanged()
            if (!bindingOverlap) syncOverlapFromStep()
            updateLabels()
        }
        overlap.addOnChangeListener { _, value, fromUser ->
            if (bindingOverlap) return@addOnChangeListener
            if (fromUser) {
                applyOverlapToStep(overlapFromSlider(value))
                onParamsChanged()
            }
            renderOverlapField()
        }
        strain.addOnChangeListener { _, _, fromUser ->
            if (fromUser) onParamsChanged()
            updateLabels()
        }
    }

    /** Recompute overlap after Reset or Paste writes subset/step. */
    fun syncFromStep() {
        applyStepRangeForSubset()
        syncOverlapFromStep()
        renderParamField(stepValue, step.value.toInt())
        strainVsg.text = StrainWindowText.vsgAt(strainVsg.context, strain.value.toInt(), step.value.toInt())
    }

    /** Show Paste only when the sweep clipboard has values. */
    fun refreshPasteVisibility() {
        val pasteChip = root.findViewById<View>(R.id.btnPasteParams)
        pasteChip.visibility =
            if (ParamClipboard.peek(root.context) != null) View.VISIBLE else View.GONE
    }

    private fun applyStepRangeForSubset() {
        val maxStep = VsgStudy.maxStepFor(subset.value.toInt()).toFloat()
        if (step.value > maxStep) step.value = maxStep
        if (step.valueTo != maxStep) step.valueTo = maxStep
    }

    private fun syncOverlapFromStep() {
        bindingOverlap = true
        overlap.value = overlapSliderValue(
            VsgStudy.overlapFor(subset.value.toInt(), step.value.toInt()),
        )
        renderOverlapField()
        bindingOverlap = false
    }

    private fun applyOverlapToStep(overlapVal: Double) {
        bindingOverlap = true
        applyStepRangeForSubset()
        step.value = VsgStudy.stepSizeFor(subset.value.toInt(), overlapVal).toFloat()
        overlap.value = overlapSliderValue(
            VsgStudy.overlapFor(subset.value.toInt(), step.value.toInt()),
        )
        renderOverlapField()
        renderParamField(stepValue, step.value.toInt())
        bindingOverlap = false
    }

    private fun bindOverlapField() {
        val commit = {
            val typed = overlapValue.text.toString().trim().replace(',', '.').toDoubleOrNull()
            val value = typed ?: overlap.value.toDouble()
            applyOverlapToStep(value)
            onParamsChanged()
        }
        overlapValue.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commit()
                overlapValue.clearFocus()
                overlapValue.context.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(overlapValue.windowToken, 0)
                true
            } else {
                false
            }
        }
        overlapValue.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
    }

    private fun renderOverlapField() {
        if (!overlapValue.hasFocus()) {
            overlapValue.setText(
                String.format(Locale.US, "%.2f", overlapFromSlider(overlap.value)),
            )
        }
    }

    private fun overlapSliderValue(raw: Double): Float {
        val hundredths = (VsgStudy.clampOverlap(raw) * 100.0).roundToInt()
        return hundredths.coerceIn(
            (VsgStudy.MIN_OVERLAP * 100).toInt(),
            (VsgStudy.MAX_OVERLAP * 100).toInt(),
        ).toFloat()
    }

    private fun overlapFromSlider(sliderValue: Float): Double =
        VsgStudy.clampOverlap(sliderValue.toDouble() / 100.0)
}
