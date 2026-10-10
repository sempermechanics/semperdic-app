package com.sempermechanics.semper.ui.analysis.run

import android.view.View
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy

/**
 * What the wizard shows while it is [busy] with an import or a run: the
 * progress [overlay], the screen kept on during a run, and the overlay's
 * Cancel, which asks before it stops anything.
 */
class RunChrome(
    private val activity: AppCompatActivity,
    val overlay: ComputeOverlayController,
    private val cancelButton: View,
) {

    /** What the wizard is doing. */
    sealed interface Busy {
        data object Idle : Busy

        /** An import or a run; [cancel] is what the overlay's Cancel asks, and does. */
        class Working(val cancel: CancelPrompt) : Busy
    }

    /** The question Cancel asks, the button that declines it, and what confirming it does. */
    class CancelPrompt(
        @StringRes val title: Int,
        @StringRes val body: Int,
        @StringRes val keep: Int,
        val onConfirm: () -> Unit,
    )

    var busy: Busy = Busy.Idle
        private set

    val isBusy: Boolean get() = busy != Busy.Idle

    /** An import has started; its helper drives the overlay. [onCancel] stops it. */
    fun beginImport(onCancel: () -> Unit) {
        begin(
            CancelPrompt(R.string.cancel_import_title, R.string.cancel_import_body, R.string.keep_importing, onCancel),
        )
    }

    /**
     * A run has started: shows the overlay, keeps the screen on, and arms
     * Cancel with [onCancel]. A batch run passes no [title], [status] or
     * [sweepPlan] and gets the overlay's own, with its convergence graph; a
     * sweep passes all three and gets its lattice instead, since its solves
     * are combinations, not frames.
     */
    fun beginRun(
        title: String? = null,
        status: String? = null,
        sweepPlan: List<SweepStudy.Point> = emptyList(),
        onCancel: () -> Unit,
    ) {
        overlay.processingStartTime = System.currentTimeMillis()
        if (title != null && status != null) {
            overlay.show(title, status, showConvergence = false, sweepPlan = sweepPlan)
        } else {
            overlay.show()
        }
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        begin(CancelPrompt(R.string.cancel_run_title, R.string.cancel_run_body, R.string.keep_running, onCancel))
    }

    /** The import or run is over: hides the overlay, lets the screen sleep, disarms Cancel. */
    fun end() {
        busy = Busy.Idle
        overlay.hide()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cancelButton.isEnabled = false
        cancelButton.setOnClickListener(null)
    }

    /**
     * Asks whether to stop what is running. Hand-built rather than
     * `Dialogs.confirm`: its negative button says "Keep running" (or "Keep
     * importing"), not Cancel, since Cancel is what the user just pressed.
     */
    fun confirmCancel() {
        val prompt = (busy as? Busy.Working)?.cancel ?: return
        MaterialAlertDialogBuilder(activity)
            .setTitle(prompt.title)
            .setMessage(prompt.body)
            .setPositiveButton(R.string.action_cancel) { _, _ ->
                prompt.onConfirm()
                cancelButton.isEnabled = false
            }
            .setNegativeButton(prompt.keep, null)
            .show()
    }

    private fun begin(prompt: CancelPrompt) {
        busy = Busy.Working(prompt)
        cancelButton.isEnabled = true
        cancelButton.setOnClickListener { confirmCancel() }
    }
}
