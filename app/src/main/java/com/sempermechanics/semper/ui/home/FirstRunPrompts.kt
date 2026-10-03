package com.sempermechanics.semper.ui.home

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.prefs.CoachPrefs
import com.sempermechanics.semper.data.prefs.DicSettings
import com.sempermechanics.semper.diagnostics.Diagnostics
import com.sempermechanics.semper.ui.common.CoachMarkController

/**
 * What Home shows on first arrival: the beta notice, then the diagnostics
 * choice, then the coach mark on the new-analysis button. One overlay at a
 * time, and the diagnostics choice is made before anything is collected.
 */
internal class FirstRunPrompts(private val activity: AppCompatActivity) {

    /** Shows whichever of the three are still due, in order, the coach mark pointing at [fab]. */
    fun show(fab: View) {
        maybeShowBetaNotice {
            maybeAskDiagnostics {
                fab.post {
                    CoachMarkController(activity).maybeShow(
                        CoachPrefs.Screen.HOME,
                        listOf(
                            CoachMarkController.Step(
                                fab,
                                activity.getString(R.string.coach_home_fab),
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * One-time beta / data-use declaration after the account first reaches Home,
     * then [next]. The only way out is "I understand", so [next] runs from there.
     */
    private fun maybeShowBetaNotice(next: () -> Unit) {
        if (TokenStore.hasAckedBetaNotice(activity)) {
            next()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.beta_notice_title)
            .setMessage(R.string.beta_notice_body)
            .setCancelable(false)
            .setPositiveButton(R.string.beta_notice_ack) { _, _ ->
                TokenStore.setBetaNoticeAcked(activity)
                next()
            }
            .show()
    }

    /**
     * First-run diagnostics choice, then [next].
     *
     * Crashlytics and Analytics are disabled in the manifest, so nothing has been
     * collected before this point — the app previously started reporting on first
     * launch with no notice and no way to decline. Asked once: a "Not now" is
     * recorded, so this does not nag, and the toggle stays in Settings.
     */
    private fun maybeAskDiagnostics(next: () -> Unit) {
        if (DicSettings.diagnosticsAsked(activity)) {
            next()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.diagnostics_prompt_title)
            .setMessage(R.string.diagnostics_prompt_body)
            .setPositiveButton(R.string.diagnostics_prompt_accept) { _, _ ->
                Diagnostics.setEnabled(activity, true)
            }
            .setNegativeButton(R.string.diagnostics_prompt_decline) { _, _ ->
                Diagnostics.setEnabled(activity, false)
            }
            .setCancelable(false)
            .setOnDismissListener { next() }
            .show()
    }
}
