@file:Suppress("MagicNumber")

package com.indicvision.semper.ui.analysis

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import com.indicvision.semper.data.CoachPrefs
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
                1 -> coach.maybeShow(
                    CoachPrefs.Screen.ANALYSIS_IMAGES,
                    listOf(
                        CoachMarkController.Step(
                            refDropzone,
                            activity.getString(R.string.coach_analysis_ref),
                        ),
                        CoachMarkController.Step(
                            defDropzone,
                            activity.getString(R.string.coach_analysis_def),
                        ),
                    ),
                )
                2 -> coach.maybeShow(
                    CoachPrefs.Screen.ANALYSIS_SETTINGS,
                    listOf(
                        CoachMarkController.Step(
                            activity.findViewById(R.id.rgAnalysisMode),
                            activity.getString(R.string.coach_analysis_mode),
                        ),
                        CoachMarkController.Step(
                            btnDefineRoi,
                            activity.getString(R.string.coach_analysis_roi),
                        ),
                        CoachMarkController.Step(
                            activity.findViewById(R.id.advancedParamsHeader),
                            activity.getString(R.string.coach_analysis_advanced),
                        ),
                    ),
                )
                3 -> coach.maybeShow(
                    CoachPrefs.Screen.ANALYSIS_SWEEP,
                    listOf(
                        CoachMarkController.Step(
                            activity.findViewById(R.id.subsetRangeBlock),
                            activity.getString(R.string.coach_sweep_subset),
                        ),
                        CoachMarkController.Step(
                            activity.findViewById(R.id.plannedLatticeCard),
                            activity.getString(R.string.coach_sweep_lattice),
                        ),
                        CoachMarkController.Step(
                            activity.findViewById(R.id.btnRunSweep),
                            activity.getString(R.string.coach_sweep_run),
                        ),
                    ),
                )
                else -> Unit
            }
        }
    }
}
