package com.indicvision.semper.ui.common.transfer

import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.snackbar.Snackbar
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.SessionDeletes
import com.indicvision.semper.data.prefs.PrefFiles
import com.indicvision.semper.data.prefs.get
import com.indicvision.semper.data.prefs.privatePrefs
import com.indicvision.semper.data.prefs.put
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What a screen says about a queued delete, from confirm to outcome: an Undo
 * during the undo window, "Deleting 4 of 10…" while it runs, and then what
 * actually happened, with Retry for anything still in the cloud.
 *
 * Home and Settings both host one. The outcome is shown once per job across
 * both screens and across recreation, so leaving the page mid-delete neither
 * loses the result nor repeats it.
 *
 * @param onChanged the rows may have changed: reload the list.
 */
class DeleteFeedback(
    private val activity: AppCompatActivity,
    private val root: View,
    private val onChanged: () -> Unit,
) {
    private var progressBar: Snackbar? = null

    /** A delete was just queued: offer Undo until it starts. */
    fun queued(workId: UUID, count: Int) {
        val text = activity.resources.getQuantityString(R.plurals.delete_multi_working, count, count)
        Snackbar.make(root, text, UNDO_WINDOW_MS)
            .setAction(R.string.action_undo) {
                SessionDeletes.cancel(activity, workId)
                onChanged()
            }
            .show()
    }

    /** Follow every queued delete for as long as the screen is alive. */
    fun observe() {
        WorkManager.getInstance(activity.applicationContext)
            .getWorkInfosByTagLiveData(SessionDeletes.TAG)
            .observe(activity) { infos -> render(infos.orEmpty()) }
    }

    private fun render(infos: List<WorkInfo>) {
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        if (running != null) {
            val progress = running.progress
            showProgress(progress.getInt(SessionDeletes.KEY_DONE, 0), progress.getInt(SessionDeletes.KEY_TOTAL, 0))
        } else {
            progressBar?.dismiss()
            progressBar = null
        }
        val shown = shownOutcomes(activity)
        // WorkManager prunes finished work after a day; forget those ids too.
        val known = infos.mapTo(mutableSetOf()) { it.id.toString() }
        val fresh = infos.filter { it.state.isFinished && it.id.toString() !in shown }
        if (fresh.isNotEmpty()) onChanged()
        fresh.filter { it.state == WorkInfo.State.SUCCEEDED }.forEach { showOutcome(it) }
        saveShownOutcomes(activity, (shown intersect known) + fresh.map { it.id.toString() })
    }

    private fun showProgress(done: Int, total: Int) {
        if (total <= 0) return
        val text = activity.getString(R.string.delete_progress_fmt, (done + 1).coerceAtMost(total), total)
        val bar = progressBar
        if (bar != null && bar.isShownOrQueued) {
            bar.setText(text)
        } else {
            progressBar = Snackbar.make(root, text, Snackbar.LENGTH_INDEFINITE).also { it.show() }
        }
    }

    private fun showOutcome(info: WorkInfo) {
        val done = info.outputData.getInt(SessionDeletes.KEY_DONE, 0)
        val left = SessionDeletes.decode(info.outputData.getString(SessionDeletes.KEY_STILL_IN_CLOUD))
        val res = activity.resources
        if (left.isEmpty()) {
            val text = if (info.outputData.getBoolean(SessionDeletes.KEY_CLOUD_ONLY, false)) {
                res.getQuantityString(R.plurals.delete_cloud_multi_done, done, done)
            } else {
                res.getQuantityString(R.plurals.delete_multi_done, done, done)
            }
            Snackbar.make(root, text, Snackbar.LENGTH_LONG).show()
            return
        }
        val text = res.getQuantityString(R.plurals.delete_multi_partial, done, done, left.size)
        Snackbar.make(root, text, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.cloud_backup_retry_action) {
                queued(SessionDeletes.enqueue(activity, left), left.size)
                onChanged()
            }
            .show()
    }

    private companion object {
        val UNDO_WINDOW_MS = TimeUnit.SECONDS.toMillis(SessionDeletes.UNDO_WINDOW_SECONDS).toInt()

        fun prefs(context: Context) = privatePrefs(context, PrefFiles.SessionDeletes.NAME)

        fun shownOutcomes(context: Context): Set<String> = prefs(context)[PrefFiles.SessionDeletes.SHOWN_OUTCOMES]

        fun saveShownOutcomes(context: Context, ids: Set<String>) {
            prefs(context).edit { put(PrefFiles.SessionDeletes.SHOWN_OUTCOMES, ids) }
        }
    }
}
