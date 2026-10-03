package com.sempermechanics.semper.ui.analysis.wizard

import android.app.Activity
import android.view.View
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.ParamClipboard
import com.sempermechanics.semper.databinding.WizardStepSettingsContentBinding
import com.sempermechanics.semper.ui.analysis.recommend.StrainWindowText
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudy
import com.sempermechanics.semper.ui.common.commitOnDone
import com.sempermechanics.semper.ui.common.dialog.bindInfo
import com.sempermechanics.semper.ui.common.showUnlessEditing
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Wires the analysis settings sheet listeners (param fields, info buttons,
 * slider label sync). Reset / paste / recommendation logic stays with the
 * [Listener], which can touch ViewModel + sweep state.
 */
class AnalysisSettingsSheetController(
    private val activity: Activity,
    private val settings: WizardStepSettingsContentBinding,
    private val listener: Listener,
) {
    /** What the sheet's controls ask of the wizard. */
    interface Listener {
        /** The user set a subset size of their own. */
        fun onSubsetUserModified()

        /** The subset moved under the user's hand: the sweep's suggestions follow it. */
        fun onSweepInputsChanged()

        /** The advanced card's Reset. */
        fun onReset()

        /** The advanced card's Paste. */
        fun onPaste()

        /** Any parameter changed. */
        fun onParamsChanged()
    }

    private val subset = settings.sliderSubsetSize
    private val step = settings.sliderStepSize
    private val overlap = settings.sliderOverlap
    private val strain = settings.sliderStrainWindow
    private val subsetValue = settings.etSubsetValue
    private val stepValue = settings.etStepValue
    private val overlapValue = settings.etOverlapValue
    private val strainValue = settings.etStrainValue

    /** The VSG in px the window in points gives at the current step. */
    private val strainVsg = settings.tvStrainVsg

    /** True while code is writing the overlap/step pair, not the user. */
    private var bindingOverlap = false

    fun bind() {
        val updateLabels = {
            subsetValue.showUnlessEditing(subset.value.toInt().toString())
            stepValue.showUnlessEditing(step.value.toInt().toString())
            strainValue.showUnlessEditing(strain.value.toInt().toString())
            strainVsg.text = StrainWindowText.vsgAt(strainVsg.context, strain.value.toInt(), step.value.toInt())
        }
        applyStepRangeForSubset()
        syncOverlapFromStep()
        updateLabels()

        subsetValue.bindToSlider(subset) {
            listener.onSubsetUserModified()
            listener.onParamsChanged()
        }
        stepValue.bindToSlider(step) { listener.onParamsChanged() }
        strainValue.bindToSlider(strain) { listener.onParamsChanged() }
        bindOverlapField()

        settings.btnAdvancedReset.setOnClickListener { listener.onReset() }
        settings.btnPasteParams.setOnClickListener { listener.onPaste() }
        refreshPasteVisibility()
        settings.btnSubsetInfo.bindInfo(activity, R.string.subset_size, R.string.info_subset)
        settings.btnStepInfo.bindInfo(activity, R.string.step_size_density, R.string.info_step)
        settings.btnOverlapInfo.bindInfo(activity, R.string.subset_overlap, R.string.info_subset_overlap)
        settings.btnStrainInfo.bindInfo(activity, R.string.strain_window, R.string.info_strain_window)

        subset.addOnChangeListener { _, _, fromUser ->
            if (!bindingOverlap) applyStepRangeForSubset()
            if (fromUser) {
                listener.onSubsetUserModified()
                listener.onSweepInputsChanged()
                listener.onParamsChanged()
            }
            if (!bindingOverlap) syncOverlapFromStep()
            updateLabels()
        }
        step.addOnChangeListener { _, _, fromUser ->
            if (fromUser) listener.onParamsChanged()
            if (!bindingOverlap) syncOverlapFromStep()
            updateLabels()
        }
        overlap.addOnChangeListener { _, value, fromUser ->
            if (bindingOverlap) return@addOnChangeListener
            if (fromUser) {
                applyOverlapToStep(overlapFromSlider(value))
                listener.onParamsChanged()
            }
            renderOverlapField()
        }
        strain.addOnChangeListener { _, _, fromUser ->
            if (fromUser) listener.onParamsChanged()
            updateLabels()
        }
    }

    /** Recompute overlap after Reset or Paste writes subset/step. */
    fun syncFromStep() {
        applyStepRangeForSubset()
        syncOverlapFromStep()
        stepValue.showUnlessEditing(step.value.toInt().toString())
        strainVsg.text = StrainWindowText.vsgAt(strainVsg.context, strain.value.toInt(), step.value.toInt())
    }

    /** Show Paste only when the sweep clipboard has values. */
    fun refreshPasteVisibility() {
        settings.btnPasteParams.visibility =
            if (ParamClipboard.peek(activity) != null) View.VISIBLE else View.GONE
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
        stepValue.showUnlessEditing(step.value.toInt().toString())
        bindingOverlap = false
    }

    private fun bindOverlapField() {
        val commit = {
            val typed = overlapValue.text.toString().trim().replace(',', '.').toDoubleOrNull()
            val value = typed ?: overlap.value.toDouble()
            applyOverlapToStep(value)
            listener.onParamsChanged()
        }
        overlapValue.commitOnDone(onDone = commit)
        overlapValue.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
    }

    private fun renderOverlapField() {
        overlapValue.showUnlessEditing(String.format(Locale.US, "%.2f", overlapFromSlider(overlap.value)))
    }

    /** The overlap slider counts hundredths. */
    private fun overlapSliderValue(raw: Double): Float {
        val hundredths = (VsgStudy.clampOverlap(raw) * HUNDREDTHS).roundToInt()
        return hundredths.coerceIn(
            (VsgStudy.MIN_OVERLAP * HUNDREDTHS).toInt(),
            (VsgStudy.MAX_OVERLAP * HUNDREDTHS).toInt(),
        ).toFloat()
    }

    private fun overlapFromSlider(sliderValue: Float): Double =
        VsgStudy.clampOverlap(sliderValue.toDouble() / HUNDREDTHS)

    private companion object {
        const val HUNDREDTHS = 100.0
    }
}
