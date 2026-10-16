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
 * section's Reset and Paste, and its Advanced section (overlap,
 * interpolation and reference), closed until opened or until a Paste or a
 * Re-run changes what it shows. The reference's choices follow the frames:
 * seconds of the clip for a video, frame counts for picked images
 * ([ReferenceIntervals]).
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

    /** Overlap, interpolation and reference; opens itself when a Paste or a Re-run changes them. */
    private val advanced = CollapsibleSection(
        header = settings.advancedHeader.root,
        chevron = settings.advancedHeader.imgAdvancedChevron,
        body = settings.advancedBody,
        container = settings.root as ViewGroup,
    )

    private val bicubic get() = activity.getString(R.string.label_4_4_bicubic)
    private val keys get() = activity.getString(R.string.label_6_6_keys)

    /** The reference: seconds of the clip for a video, frame counts for picked images. */
    val reference = ReferenceDropdown(activity, settings.ddReference) { viewModel.defFrameTimesMs }

    /** Wires the sliders, their fields, the two dropdowns and the section's buttons. */
    fun bind() {
        sheet.bind()
        val dropdown = settings.ddInterpolator
        dropdown.setSimpleItems(arrayOf(bicubic, keys))
        if (dropdown.text.isNullOrEmpty()) dropdown.setText(bicubic, false)
        dropdown.setOnItemClickListener { _, _, _, _ -> host.clearRunStatus() }
        reference.bind(onPicked = host::clearRunStatus)
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

    /** How often the reference moves, in frames ([RunSpec.refUpdateInterval]); 0, the fixed reference, by default. */
    val refUpdateInterval: Int get() = reference.interval

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
        reference.interval = 0
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
        setSliders(params.subset, params.step, params.vsg)
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

    /**
     * A Re-run's settings, as the user's own: [subset], [step] and the strain
     * window [vsg] (px) on the sliders, the Keys interpolator when [use6x6],
     * and the run's reference [refUpdateInterval] (frames), on the choices
     * its frames, already loaded, offer. The overlap follows the step, as it
     * always does.
     */
    internal fun applyRerun(subset: Int, step: Int, vsg: Int, use6x6: Boolean, refUpdateInterval: Int = 0) {
        host.commitParamFields()
        viewModel.subsetUserModified = true
        settings.ddInterpolator.setText(if (use6x6) keys else bicubic, false)
        reference.onFramesChanged()
        reference.interval = refUpdateInterval
        if (refUpdateInterval > 0) advanced.expand()
        setSliders(subset, step, vsg)
    }

    /** [subset] and [step] snapped onto their sliders, then the window in points at that step. */
    private fun setSliders(subset: Int, step: Int, vsg: Int) {
        settings.sliderSubsetSize.value = snapToSlider(settings.sliderSubsetSize, subset).toFloat()
        // The step slider's range follows the subset: widen it first, or a
        // larger step than the old subset allowed would be clamped.
        sheet.syncFromStep()
        settings.sliderStepSize.value = snapToSlider(settings.sliderStepSize, step).toFloat()
        // A VSG in px; the slider takes points at the step just set.
        settings.sliderStrainWindow.value = SweepStudy.nearestWindowPoints(vsg, stepSize).toFloat()
        sheet.syncFromStep()
    }
}
