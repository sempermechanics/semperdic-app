package com.sempermechanics.semper.ui.analysis.wizard

import android.graphics.Rect
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.ParamClipboard
import com.sempermechanics.semper.databinding.WizardStepSettingsContentBinding
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommendationController
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.common.CollapsibleSection

/**
 * The settings page's parameter sliders: the value fields are editable, so
 * dragging writes into them and typing writes back into the slider. Values
 * are still read via `slider.value` everywhere. Also the correlation
 * section's Reset and Paste, and its Advanced section (overlap and
 * interpolation), closed until opened or until a Paste changes what it shows.
 */
class WizardParamFields(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val settings: WizardStepSettingsContentBinding,
    private val subsets: SubsetRecommendationController,
    private val host: AnalysisWizardHost,
    /** A slider moved: the step's point count and the run estimate follow. */
    onGeometryChanged: () -> Unit = {},
) {
    private val sheet = AnalysisSettingsSheetController(
        activity,
        settings,
        object : AnalysisSettingsSheetController.Listener {
            override fun onSubsetUserModified() {
                viewModel.subsetUserModified = true
                subsets.showSpeckleFeedback()
            }

            override fun onSweepInputsChanged() = host.onSweepInputsChanged()

            override fun onReset() = reset()

            override fun onPaste() = paste()

            override fun onParamsChanged() = host.clearRunStatus()
        },
        onGeometryChanged,
    )

    /** Overlap and interpolation; opens itself when a Paste changes either. */
    private val advanced = CollapsibleSection(
        header = settings.advancedHeader.root,
        chevron = settings.advancedHeader.imgAdvancedChevron,
        body = settings.advancedBody,
        container = settings.root as ViewGroup,
    )

    private val bicubic get() = activity.getString(R.string.label_4_4_bicubic)
    private val keys get() = activity.getString(R.string.label_6_6_keys)

    /** Wires the sliders, their fields, the interpolation dropdown and the section's buttons. */
    fun bind() {
        sheet.bind()
        val dropdown = settings.ddInterpolator
        dropdown.setSimpleItems(arrayOf(bicubic, keys))
        if (dropdown.text.isNullOrEmpty()) dropdown.setText(bicubic, false)
        dropdown.setOnItemClickListener { _, _, _, _ -> host.clearRunStatus() }
    }

    /** The subset size the slider holds, after landing a recommendation still gliding there. */
    fun subsetSize(): Int {
        subsets.settle()
        return settings.sliderSubsetSize.value.toInt()
    }

    private val stepSize: Int get() = settings.sliderStepSize.value.toInt()

    /** The VSG in px handed to the engine: the slider's window is in data points. */
    private val strainWindow: Int get() = SweepStudy.vsgFor(settings.sliderStrainWindow.value.toInt(), stepSize)

    /** The settings a run uses; the strain window in px, as the engine takes it. */
    fun dicParams(): DicParams = DicParams(subsetSize(), stepSize, strainWindow)

    fun isKeysInterpolatorSelected(): Boolean = settings.ddInterpolator.text.toString() == keys

    /** Flushes any in-progress typing into the sliders (focus loss commits). */
    fun commit() {
        settings.etSubsetValue.clearFocus()
        settings.etStepValue.clearFocus()
        settings.etOverlapValue.clearFocus()
        settings.etStrainValue.clearFocus()
    }

    /** Show Paste only when the sweep clipboard has values. */
    fun refreshPasteVisibility() = sheet.refreshPasteVisibility()

    /** Back to the recommended subset and the default step, window and interpolator. */
    internal fun reset() {
        host.commitParamFields()
        viewModel.subsetUserModified = false
        settings.sliderSubsetSize.value = subsets.defaultSubsetSize().toFloat()
        settings.sliderStepSize.value = DicParams.DEFAULT_STEP.toFloat()
        settings.sliderStrainWindow.value = SweepStudy.DEFAULT_WINDOW_POINTS.toFloat()
        settings.ddInterpolator.setText(bicubic, false)
        sheet.syncFromStep()
        subsets.showSpeckleFeedback()
        host.resetSweepInputs()
        host.clearRunStatus()
    }

    /**
     * Applies ParamClipboard subset/step/window into the analysis sliders, and
     * opens Advanced when that moves the overlap (or the interpolation) off
     * what it showed, so no pasted change is hidden.
     */
    internal fun paste() {
        val params = ParamClipboard.peek(activity) ?: return
        host.commitParamFields()
        val shown = settings.etOverlapValue.text.toString() to settings.ddInterpolator.text.toString()
        viewModel.subsetUserModified = true
        settings.sliderSubsetSize.value = snapToSlider(settings.sliderSubsetSize, params.subset).toFloat()
        settings.sliderStepSize.value = snapToSlider(settings.sliderStepSize, params.step).toFloat()
        // The clipboard holds a VSG in px; the slider takes points at the pasted step.
        settings.sliderStrainWindow.value = SweepStudy.nearestWindowPoints(params.vsg, stepSize).toFloat()
        sheet.syncFromStep()
        if (shown != settings.etOverlapValue.text.toString() to settings.ddInterpolator.text.toString()) {
            advanced.expand()
        }
        subsets.showSpeckleFeedback()
        host.onSweepInputsChanged()
        host.clearRunStatus()
        // Bring the correlation section into view so the pasted values are visible.
        val card = settings.advancedParamsCard
        card.post { card.requestRectangleOnScreen(Rect(0, 0, card.width, card.height), false) }
    }
}
