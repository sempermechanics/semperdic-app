package com.indicvision.semper.ui.analysis.run

import android.view.View
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R

/**
 * What the wizard shows while it is [busy] with an import or a run: the
 * progress [overlay], the screen kept on during a run, and the overlay's
 * Cancel, which asks before it stops anything.
 */
class RunChrome(
    private val activity: AppCompatActivity,
    val overlay: ComputeOverlayHelper,
    private val cancelButton: View,
) {

    /** What the wizard is doing. */
    sealed interface Busy {
        data object Idle : Busy

        /** An import or a run; [cancel] is what the overlay's Cancel asks, and does. */
        class Working(val cancel: CancelPrompt) : Busy
    }

    /** The question Cancel asks, and what confirming it does. */
    class CancelPrompt(
        @StringRes val title: Int,
        @StringRes val body: Int,
        val onConfirm: () -> Unit,
    )

    var busy: Busy = Busy.Idle
        private set

    val isBusy: Boolean get() = busy != Busy.Idle

    /** An import has started; its helper drives the overlay. [onCancel] stops it. */
    fun beginImport(onCancel: () -> Unit) {
        begin(CancelPrompt(R.string.cancel_import_title, R.string.cancel_import_body, onCancel))
    }

    /**
     * A run has started: shows the overlay ([title] and [status] when given,
     * else the overlay's own), keeps the screen on, and arms Cancel with
     * [onCancel].
     */
    fun beginRun(title: String? = null, status: String? = null, onCancel: () -> Unit) {
        overlay.processingStartTime = System.currentTimeMillis()
        if (title != null && status != null) overlay.show(title, status) else overlay.show()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        begin(CancelPrompt(R.string.cancel_run_title, R.string.cancel_run_body, onCancel))
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
     * `Dialogs.confirm`: its negative button says "Keep running", not Cancel,
     * since Cancel is what the user just pressed.
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
            .setNegativeButton(R.string.keep_running, null)
            .show()
    }

    private fun begin(prompt: CancelPrompt) {
        busy = Busy.Working(prompt)
        cancelButton.isEnabled = true
        cancelButton.setOnClickListener { confirmCancel() }
    }
}
