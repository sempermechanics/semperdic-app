@file:Suppress("MagicNumber")

package com.indicvision.semper.ui.analysis.wizard

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.indicvision.semper.R
import com.indicvision.semper.data.prefs.CoachPrefs
import com.indicvision.semper.ui.common.CoachMarkController

/**
 * Per-step coach marks for the analysis wizard. Leaving a page dismisses
 * any open coach for that screen.
 */
class AnalysisWizardCoach(
    private val activity: AppCompatActivity,
    private val coach: CoachMarkController,
    private val refDropzone: View,
    private val defDropzone: View,
    private val btnDefineRoi: View,
) {

    fun maybeShow(step: Int) {
        coach.dismiss(markSeen = true)
        val root = activity.findViewById<View>(android.R.id.content)
        root.post {
            when (step) {
                1 -> coach.maybeShow(CoachPrefs.Screen.ANALYSIS_IMAGES, imagesSteps())
                2 -> coach.maybeShow(CoachPrefs.Screen.ANALYSIS_SETTINGS, settingsSteps())
                3 -> coach.maybeShow(CoachPrefs.Screen.ANALYSIS_SWEEP, sweepSteps())
                else -> Unit
            }
        }
    }

    private fun imagesSteps() = listOf(
        CoachMarkController.Step(refDropzone, activity.getString(R.string.coach_analysis_ref)),
        CoachMarkController.Step(defDropzone, activity.getString(R.string.coach_analysis_def)),
    )

    private fun settingsSteps(): List<CoachMarkController.Step> {
        val settingsAnchor = activity.findViewById<View>(R.id.advancedParamsCard)
            .takeIf { it.isVisible }
            ?: activity.findViewById(R.id.sweepSettingsHeader)
        return listOf(
            CoachMarkController.Step(
                activity.findViewById(R.id.rgAnalysisMode),
                activity.getString(R.string.coach_analysis_mode),
            ),
            CoachMarkController.Step(btnDefineRoi, activity.getString(R.string.coach_analysis_roi)),
            CoachMarkController.Step(settingsAnchor, activity.getString(R.string.coach_analysis_advanced)),
        )
    }

    private fun sweepSteps() = listOf(
        CoachMarkController.Step(
            activity.findViewById(R.id.plannedLatticeCard),
            activity.getString(R.string.coach_sweep_lattice),
        ),
        CoachMarkController.Step(
            activity.findViewById(R.id.lineCutPreviewCard),
            activity.getString(R.string.coach_sweep_line_cut),
        ),
        CoachMarkController.Step(
            activity.findViewById(R.id.btnRunSweep),
            activity.getString(R.string.coach_sweep_run),
        ),
    )
}
