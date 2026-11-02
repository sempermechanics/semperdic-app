package com.sempermechanics.semper.ui.analysis.run

import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisNavHelper
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.FaqRedirect
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.viewer.ViewerArgs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Observes [AnalysisViewModel]'s batch and sweep progress, and shows how each
 * run ended ([RunEnd]) in one place for [StaticAnalysisActivity]: a result
 * opens, from its saved row, or the failure is told, by the same dialog for a
 * batch run and a sweep.
 *
 * Both runs live on the view model's scope, so this is the only place that
 * knows whether an Activity is still around to be told how they ended. An
 * outcome that arrives while the screen is stopped waits in the view model
 * and is handled, once, when the screen starts again.
 */
class BatchRunController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val chrome: RunChrome,
    private val status: RunStatusLine,
    /** Re-checks what the wizard's buttons allow; once the run's chrome is down. */
    private val recheck: () -> Unit,
    /** How long a solved run's sheet says so before the viewer opens; 0 with animations off. */
    private val doneHoldMs: Long = defaultDoneHoldMs(activity),
) {

    private val overlayController get() = chrome.overlay

    fun observe() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.progress.collect { progress ->
                        if (progress == null) return@collect
                        overlayController.updateRun(progress)
                    }
                }
                launch { viewModel.batchOutcome.consumeEach(::render) }
                launch {
                    viewModel.sweepProgress.collect { progress ->
                        if (progress != null) overlayController.updateSweep(progress)
                    }
                }
                launch { viewModel.sweepOutcome.consumeEach(::render) }
            }
        }
    }

    /**
     * Shows how a run ended. First, whatever the ending: the run's chrome
     * comes down and the buttons are re-checked. Compute was disabled for the
     * run, and a branch that forgot to re-check left it disabled: a failed
     * run could not be re-run after changing a setting, since the sliders
     * never re-check.
     */
    @VisibleForTesting
    internal fun render(end: RunEnd) {
        chrome.end()
        recheck()
        when (end) {
            // A cancelled run kept nothing (and never touched a saved
            // analysis), so there is nothing to show: the wizard just comes back.
            RunEnd.Cancelled -> Unit
            RunEnd.SessionLimit -> AnalysisNavHelper.openSessionLimit(activity)
            RunEnd.NotSaved -> showNotSaved()
            RunEnd.TooLarge -> showTooLarge()
            is RunEnd.Unexpected -> showUnexpected()
            is RunEnd.Failed -> showFailure(end)
            is RunEnd.Partial -> showPartial(end)
            is RunEnd.Solved -> showDoneThenOpen(end)
            is RunEnd.SweepSaved -> showSweep(end)
            // Each node of the lattice keeps its own reason, to tap for details.
            is RunEnd.SweepFailed -> openResults(end.args)
        }
    }

    /**
     * The run threw: the one dialog a batch run and a sweep share, in the app's words.
     * What the exception said is in the log only (ADR-018).
     */
    private fun showUnexpected() {
        status.setFaq(null)
        status.show(activity.getString(R.string.analysis_unexpected_title))
        Dialogs.info(activity, R.string.analysis_unexpected_title, R.string.analysis_unexpected_body)
    }

    /** The grid did not fit this phone's memory: nothing was kept, and the dialog says how to make it fit. */
    private fun showTooLarge() {
        status.setFaq(null)
        status.show(activity.getString(R.string.run_too_large_title))
        Dialogs.info(activity, R.string.run_too_large_title, R.string.run_too_large_body)
    }

    /** The run solved, but the session index could not be read or written, so nothing was saved. */
    private fun showNotSaved() {
        status.setFaq(null)
        status.show(activity.getString(R.string.analysis_not_saved_title))
        Dialogs.info(activity, R.string.analysis_not_saved_title, R.string.analysis_index_unavailable_body)
    }

    /**
     * The run kept nothing: the dialog names the frame and the reason, and
     * leaves its FAQ beside the status line once it is gone.
     */
    private fun showFailure(failed: RunEnd.Failed) {
        val notice = failed.notice(activity)
        status.show(activity.getString(R.string.run_failed_status_fmt, notice.message))
        status.setFaq(notice.faqUrlRes)
        FaqRedirect.errorDialog(
            activity,
            activity.getString(R.string.analysis_failed_title),
            notice.message,
            notice.faqUrlRes,
            notice.why,
        )
    }

    /**
     * A run that stopped itself partway: the images decorrelated, but the frames
     * solved before that are valid and the session already holds them.
     *
     * Told plainly and then opened. The alternative — a failure dialog — left a
     * session appearing on Home that the user had just been told was a failure,
     * with no route to it from here. Two short sentences say how many frames
     * were kept and why it stopped; **Why?** has the rest (the frame it stopped
     * on, the engine's full reason, what the frames kept are).
     */
    private fun showPartial(partial: RunEnd.Partial) {
        status.setFaq(null)
        val kept = partial.record.frameCount
        val planned = partial.record.plannedFrameCount
        val code = partial.code
        val res = activity.resources
        status.show(activity.getString(R.string.run_stopped_early_fmt, partial.stoppedAtFrame, planned))
        val title = activity.getString(R.string.run_stopped_early_title_fmt, partial.stoppedAtFrame)
        val message = res.getQuantityString(R.plurals.run_stopped_early_body, kept, kept, planned) +
            " " + EngineFailure.stopReason(activity, code)
        val why = listOfNotNull(
            res.getQuantityString(R.plurals.run_stopped_early_why, kept, kept),
            engineFailureMessage(activity, code, partial.frameIndex, partial.frameName),
            EngineFailure.whyRes(code)?.let(activity::getString),
        ).joinToString(System.lineSeparator() + System.lineSeparator())
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            // The dialog explains, it does not ask: the frames are saved either
            // way, so dismissing it back onto the settings page would strand the
            // user one screen away from the data the run just produced.
            .setCancelable(false)
            .setPositiveButton(R.string.run_stopped_early_view) { _, _ -> openResults(ViewerArgs.of(partial.record)) }
            .setNeutralButton(R.string.action_why, null)
            .show()
        // Set after show so Why? leaves this dialog up: View results stays its only way out.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { Dialogs.info(activity, title, why) }
    }

    /**
     * The run solved every frame: the status line counts them, and the sheet
     * says so for [doneHoldMs] (frames, time, lowest convergence from the
     * run's ticks), then the viewer opens: once the screen is started again
     * if it was left in the background meanwhile, since a stopped screen may
     * not start another. Straight to the viewer with no hold, or when the
     * screen is already going. A screen destroyed mid-hold for a
     * configuration change still opens it; one destroyed for any other reason
     * does not.
     */
    private fun showDoneThenOpen(solved: RunEnd.Solved) {
        status.setFaq(null)
        val record = solved.record
        // Frames that kept no points are skipped, so kept can be below planned.
        status.show(RunSummaryText.computed(activity.resources, record.frameCount, record.plannedFrameCount))
        val args = ViewerArgs.of(record)
        if (doneHoldMs <= 0L || activity.isFinishing || activity.isDestroyed) {
            openResults(args)
            return
        }
        // The convergence is the overlay's own snapshot of the run's ticks: the
        // view model's progress replay is already null here (the run's finally).
        overlayController.showDone(record.frameCount, solved.executionTimeMs)
        activity.lifecycleScope.launch {
            var opened = false
            try {
                delay(doneHoldMs)
                activity.withStarted {
                    overlayController.hide()
                    opened = true
                    openResults(args)
                }
            } finally {
                if (!opened) {
                    overlayController.hide()
                    if (activity.isChangingConfigurations) openResults(args)
                }
            }
        }
    }

    /**
     * A finished sweep is an ordinary session whose frames happen to be
     * settings rather than images, so it opens on its lattice like any saved
     * sweep. A partial one is still worth browsing; a toast says what was dropped.
     */
    private fun showSweep(saved: RunEnd.SweepSaved) {
        val record = saved.record
        val skipped = record.sweepSkipCount
        if (skipped > 0) {
            Feedback.toast(
                activity,
                activity.resources.getQuantityString(
                    R.plurals.sweep_partial_fmt,
                    skipped,
                    skipped,
                    skipped + record.frameCount,
                ),
                long = true,
            )
        }
        openResults(ViewerArgs.of(record))
    }

    /**
     * Opens [args], the run that just finished, and closes the wizard: the
     * results' Back goes to Home, never back into the wizard (owner decision,
     * 2026-10-15). The analysis is saved and finished; another starts from
     * Home, or from its row's Re-run. Finishing also drops the wizard's draft
     * (`StaticAnalysisActivity.onDestroy`), so New analysis starts empty.
     */
    private fun openResults(args: ViewerArgs) {
        activity.startActivity(args.toIntent(activity))
        activity.finish()
    }
}

/** [BatchRunController]'s done moment: about 800 ms, none when the system's animator duration scale is 0. */
private fun defaultDoneHoldMs(activity: AppCompatActivity): Long {
    val scale = Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return if (scale == 0f) 0L else DONE_HOLD_MS
}

private const val DONE_HOLD_MS = 800L
