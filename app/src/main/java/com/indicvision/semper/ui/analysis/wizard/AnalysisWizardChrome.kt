package com.indicvision.semper.ui.analysis.wizard

import android.view.View
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.indicvision.semper.R
import com.indicvision.semper.databinding.ActivityStaticAnalysisBinding
import com.indicvision.semper.databinding.WizardStepSettingsBinding
import com.indicvision.semper.databinding.WizardStepSweepBinding

/**
 * Wizard page visibility, toolbar subtitle, and bottom-nav labels for the
 * three-step analysis setup (images → settings → optional sweep).
 *
 * Step-specific side effects (card refresh, recommendations, plan rebuild)
 * stay in the Activity after [applyStep] returns.
 */
class AnalysisWizardChrome(
    private val activity: AppCompatActivity,
    private val wizard: ActivityStaticAnalysisBinding,
    settingsPage: WizardStepSettingsBinding,
    sweepPage: WizardStepSweepBinding,
) {
    private val pages: Map<WizardStep, View> = mapOf(
        WizardStep.IMAGES to wizard.scrollStepImages,
        WizardStep.SETTINGS to settingsPage.root,
        WizardStep.SWEEP to sweepPage.root,
    )

    /**
     * Sets page visibility, toolbar subtitle, and bottom-nav labels for
     * [requested], or the page shown in its place outside sweep mode.
     * Returns the step that was applied.
     */
    fun applyStep(
        previous: WizardStep,
        requested: WizardStep,
        sweepMode: Boolean,
        animate: Boolean,
    ): WizardStep {
        val target = WizardStep.shown(requested, sweepMode)

        pages.forEach { (step, page) -> page.isVisible = step == target }
        if (animate && previous != target) {
            val forward = target > previous
            pages.getValue(target).startAnimation(
                AnimationUtils.loadAnimation(
                    activity,
                    if (forward) R.anim.slide_in_right else R.anim.slide_in_left,
                ),
            )
        }
        updateBottomNav(target, sweepMode)
        return target
    }

    /** Toolbar subtitle, and bottom nav labels and visibility, for [step] in this mode. */
    fun updateBottomNav(step: WizardStep, sweepMode: Boolean) {
        wizard.toolbar.subtitle = activity.getString(R.string.step_of_fmt, step.number, WizardStep.count(sweepMode))
        val next = when (step) {
            WizardStep.IMAGES -> R.string.next_settings
            WizardStep.SETTINGS -> R.string.next_sweep.takeIf { sweepMode }
            WizardStep.SWEEP -> null
        }
        wizard.btnNext.isVisible = next != null
        next?.let { wizard.btnNext.setText(it) }
        wizard.btnBack.isVisible = step != WizardStep.IMAGES
        wizard.btnCalculateFullField.isVisible = step == WizardStep.SETTINGS && !sweepMode
        wizard.btnRunSweep.isVisible = step == WizardStep.SWEEP
    }
}
