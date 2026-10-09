package com.sempermechanics.semper.ui.analysis.wizard

import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ActivityStaticAnalysisBinding
import com.sempermechanics.semper.databinding.WizardStepSettingsBinding
import com.sempermechanics.semper.databinding.WizardStepSweepBinding

/**
 * Wizard page visibility, toolbar title and step dots, and bottom-nav labels for the
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
     * Sets page visibility, toolbar title and dots, and bottom-nav labels for
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

    /** Toolbar title and step dots, and bottom nav labels and visibility, for [step] in this mode. */
    fun updateBottomNav(step: WizardStep, sweepMode: Boolean) {
        val count = WizardStep.count(sweepMode)
        wizard.toolbar.title = activity.getString(
            when {
                step == WizardStep.IMAGES -> R.string.new_analysis_title
                step == WizardStep.SWEEP -> R.string.sweep_settings
                sweepMode -> R.string.wizard_title_sweep_setup
                else -> R.string.parameters
            },
        )
        wizard.toolbar.subtitle = null
        wizard.tvStepDots.text = stepDots(step.number, count)
        wizard.tvStepDots.contentDescription = activity.getString(R.string.step_of_fmt, step.number, count)
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

    /** One dot per page, the current one in the accent colour. */
    private fun stepDots(current: Int, count: Int): CharSequence {
        val dots = SpannableStringBuilder()
        val on = ContextCompat.getColor(activity, R.color.sky_primary)
        val off = ContextCompat.getColor(activity, R.color.viewer_chrome_muted)
        for (page in 1..count) {
            if (page > 1) dots.append(' ')
            val start = dots.length
            dots.append(DOT)
            dots.setSpan(ForegroundColorSpan(if (page == current) on else off), start, dots.length, 0)
        }
        return dots
    }

    private companion object {
        const val DOT = '\u25CF'
    }
}
