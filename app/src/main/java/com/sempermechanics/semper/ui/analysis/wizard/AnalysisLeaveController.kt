package com.sempermechanics.semper.ui.analysis.wizard

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.run.RunChrome

/**
 * Back on the wizard: asks before it stops a busy import or run, steps back a
 * page through [goToStep], asks before it drops loaded images, and otherwise
 * leaves. Construct it in `onCreate`: it registers the back callback.
 */
class AnalysisLeaveController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val chrome: RunChrome,
    private val goToStep: (WizardStep) -> Unit,
) {
    init {
        activity.onBackPressedDispatcher.addCallback(
            activity,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = onBack()
            },
        )
    }

    internal fun onBack() {
        val previous = viewModel.step.previous
        when {
            chrome.isBusy -> chrome.confirmCancel()
            previous != null -> goToStep(previous)
            viewModel.refBytes != null || viewModel.defFilePaths.isNotEmpty() -> confirmLeave()
            else -> activity.finish()
        }
    }

    /** Leave or Stay, not Cancel: "Cancel" would read as cancelling the setup. */
    private fun confirmLeave() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.exit_analysis_title)
            .setMessage(R.string.exit_analysis_message)
            .setPositiveButton(R.string.action_leave) { _, _ -> activity.finish() }
            .setNegativeButton(R.string.action_stay, null)
            .show()
    }
}
