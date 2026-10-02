package com.indicvision.semper.ui.analysis

import android.graphics.Rect
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import com.indicvision.semper.data.prefs.ParamClipboard
import com.indicvision.semper.databinding.WizardStepSettingsContentBinding
import com.indicvision.semper.field.DicParams
import com.indicvision.semper.ui.analysis.sweep.VsgStudy
import com.indicvision.semper.ui.analysis.wizard.AnalysisSettingsSheetHelper
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.analysis.wizard.snapToSlider

/**
 * The settings page's parameter sliders: the value fields are editable, so
 * dragging writes into them and typing writes back into the slider. Values
 * are still read via `slider.value` everywhere. Also the advanced card's
 * Reset and Paste.
 */
class WizardParamFields(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val settings: WizardStepSettingsContentBinding,
    private val subsets: SubsetRecommendationController,
    private val host: AnalysisWizardHost,
) {
    private val sheet = AnalysisSettingsSheetHelper(
        activity,
        settings,
        object : AnalysisSettingsSheetHelper.Listener {
            override fun onSubsetUserModified() {
                viewModel.subsetUserModified = true
                subsets.showSpeckleFeedback()
            }

            override fun onSweepInputsChanged() = host.onSweepInputsChanged()

            override fun onReset() = reset()

            override fun onPaste() = paste()

            override fun onParamsChanged() = host.clearRunStatus()
        },
    )

    /** Wires the sliders, their fields and the advanced card's buttons. */
    fun bind() = sheet.bind()

    fun subsetSize(): Int = settings.etSubsetSize.value.toInt()

    private fun stepSize(): Int = settings.etStepSize.value.toInt()

    /** The settings a run uses; the strain window in px, as the engine takes it. */
    fun dicParams(): DicParams = DicParams(subsetSize(), stepSize(), strainWindow())

    /** The VSG in px handed to the engine: the slider's window is in data points. */
    private fun strainWindow(): Int = VsgStudy.vsgFor(settings.etStrainWindow.value.toInt(), stepSize())

    fun useKeysInterpolator(): Boolean = settings.rgInterpolator.checkedButtonId == R.id.rbKeys

    /** Flushes any in-progress typing into the sliders (focus loss commits). */
    fun commit() {
        settings.tvSubsetValue.clearFocus()
        settings.tvStepValue.clearFocus()
        settings.tvOverlapValue.clearFocus()
        settings.tvStrainValue.clearFocus()
    }

    /** Show Paste only when the sweep clipboard has values. */
    fun refreshPasteVisibility() = sheet.refreshPasteVisibility()

    /** Back to the recommended subset and the default step, window and interpolator. */
    internal fun reset() {
        host.commitParamFields()
        viewModel.subsetUserModified = false
        settings.etSubsetSize.value = subsets.defaultSubsetSize().toFloat()
        settings.etStepSize.value = DicParams.DEFAULT_STEP.toFloat()
        settings.etStrainWindow.value = VsgStudy.DEFAULT_WINDOW_POINTS.toFloat()
        settings.rgInterpolator.check(R.id.rbBicubic)
        sheet.syncFromStep()
        subsets.showSpeckleFeedback()
        host.resetSweepInputs()
        host.clearRunStatus()
    }

    /** Applies ParamClipboard subset/step/window into the analysis sliders. */
    internal fun paste() {
        val params = ParamClipboard.peek(activity) ?: return
        host.commitParamFields()
        viewModel.subsetUserModified = true
        settings.etSubsetSize.value = snapToSlider(settings.etSubsetSize, params.subset).toFloat()
        settings.etStepSize.value = snapToSlider(settings.etStepSize, params.step).toFloat()
        // The clipboard holds a VSG in px; the slider takes points at the pasted step.
        settings.etStrainWindow.value = VsgStudy.nearestWindowPoints(params.vsg, stepSize()).toFloat()
        sheet.syncFromStep()
        subsets.showSpeckleFeedback()
        host.onSweepInputsChanged()
        host.clearRunStatus()
        // Bring the advanced-params card into view so the pasted values are visible.
        val card = settings.advancedParamsCard
        card.post { card.requestRectangleOnScreen(Rect(0, 0, card.width, card.height), false) }
    }
}
