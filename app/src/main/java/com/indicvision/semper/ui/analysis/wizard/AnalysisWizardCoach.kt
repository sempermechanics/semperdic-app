package com.indicvision.semper.ui.analysis.wizard

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.indicvision.semper.R
import com.indicvision.semper.data.prefs.CoachPrefs
import com.indicvision.semper.databinding.ActivityStaticAnalysisBinding
import com.indicvision.semper.databinding.WizardStepSettingsContentBinding
import com.indicvision.semper.databinding.WizardStepSweepBinding
import com.indicvision.semper.ui.common.CoachMarkController

/**
 * Per-step coach marks for the analysis wizard. Leaving a page dismisses
 * any open coach for that screen.
 */
class AnalysisWizardCoach(
    private val activity: AppCompatActivity,
    private val coach: CoachMarkController,
    private val wizard: ActivityStaticAnalysisBinding,
    private val settings: WizardStepSettingsContentBinding,
    private val sweepPage: WizardStepSweepBinding,
) {

    fun maybeShow(step: WizardStep) {
        coach.dismiss(markSeen = true)
        wizard.root.post {
            when (step) {
                WizardStep.IMAGES -> coach.maybeShow(CoachPrefs.Screen.ANALYSIS_IMAGES, imagesSteps())
                WizardStep.SETTINGS -> coach.maybeShow(CoachPrefs.Screen.ANALYSIS_SETTINGS, settingsSteps())
                WizardStep.SWEEP -> coach.maybeShow(CoachPrefs.Screen.ANALYSIS_SWEEP, sweepSteps())
            }
        }
    }

    private fun imagesSteps() = listOf(
        CoachMarkController.Step(wizard.refDropzone, activity.getString(R.string.coach_analysis_ref)),
        CoachMarkController.Step(wizard.defDropzone, activity.getString(R.string.coach_analysis_def)),
    )

    private fun settingsSteps(): List<CoachMarkController.Step> {
        val settingsAnchor: View = settings.advancedParamsCard
            .takeIf { it.isVisible }
            ?: settings.sweepSettingsHeader
        return listOf(
            CoachMarkController.Step(
                settings.rgAnalysisMode,
                activity.getString(R.string.coach_analysis_mode),
            ),
            CoachMarkController.Step(settings.btnDefineRoi, activity.getString(R.string.coach_analysis_roi)),
            CoachMarkController.Step(settingsAnchor, activity.getString(R.string.coach_analysis_advanced)),
        )
    }

    private fun sweepSteps() = listOf(
        CoachMarkController.Step(
            sweepPage.plannedLatticeCard,
            activity.getString(R.string.coach_sweep_lattice),
        ),
        CoachMarkController.Step(
            sweepPage.lineCutPreviewCard,
            activity.getString(R.string.coach_sweep_line_cut),
        ),
        CoachMarkController.Step(
            wizard.btnRunSweep,
            activity.getString(R.string.coach_sweep_run),
        ),
    )
}
