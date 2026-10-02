package com.indicvision.semper.ui.analysis.wizard

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.run.RunChrome
import com.indicvision.semper.ui.common.dialog.Dialogs

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

    private fun confirmLeave() {
        Dialogs.confirm(activity, R.string.exit_analysis_title, R.string.exit_analysis_message, R.string.exit) {
            activity.finish()
        }
    }
}
