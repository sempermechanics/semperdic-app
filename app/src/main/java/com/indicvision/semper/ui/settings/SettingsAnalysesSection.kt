// Moved out of SettingsActivity with its suppressions: one section, one small
// function per row action and transfer outcome; guard returns read clearest.
@file:Suppress("TooManyFunctions", "ReturnCount")

package com.indicvision.semper.ui.settings

import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkManager
import com.indicvision.semper.R
import com.indicvision.semper.data.account.LicenseEntitlements
import com.indicvision.semper.data.account.LicenseErrors
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.cloud.TransferWork
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.databinding.SettingsScrollContentBinding
import com.indicvision.semper.ui.common.ByteSize
import com.indicvision.semper.ui.common.ConflatedRefresh
import com.indicvision.semper.ui.common.Feedback
import com.indicvision.semper.ui.common.RestoreFailureNotice
import com.indicvision.semper.ui.common.TransferBannerController
import com.indicvision.semper.ui.common.TransferWorkObserver
import com.indicvision.semper.ui.home.SessionListAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Analyses data management: one row per analysis on the phone, in
 * the cloud or both, which of them have a restore or Save-to-Files download
 * running, and the transfer banner that follows those jobs. A row's open,
 * download, restore and delete stay on [SettingsActivity]; backing a row up
 * is here.
 */
internal class SettingsAnalysesSection(
    private val activity: SettingsActivity,
    private val views: SettingsScrollContentBinding,
) {
    private val analysesAdapter = AnalysisDataAdapter(
        stateLine = ::stateLine,
        backupLabel = ::backupLabel,
        onOpen = activity::openOrDownloadAnalysis,
        onBackup = ::startBackup,
        onLocalDownload = activity::confirmLocalDownload,
        onCloudRestore = activity::confirmCloudRestore,
        onDelete = activity::deleteBackup,
    )

    /** Restore / Save-to-Files download keys currently busy — disables row actions. */
    private val busy = BusyTransfers()

    private val transferBanner: TransferBannerController get() = activity.transferBanner

    /**
     * The per-analysis list load, one at a time: it lists the cloud over the
     * network and is asked for from nine places (restore, delete, backup,
     * storage cleanup, finished work…). Overlapping loads used to land out of
     * order; now a burst runs at most one more load, after the current one.
     */
    private val analysesRefresh by lazy {
        ConflatedRefresh<Unit>(activity.lifecycleScope, merge = { _, _ -> }) { loadAnalysesData() }
    }

    /** Attaches the (empty) list; demo accounts get it too, hidden with the section. */
    fun attachList() {
        views.analysesDataList.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = analysesAdapter
        }
    }

    fun refresh() {
        // Storage cleanup re-enters here; the section does not exist on demo.
        if (!LicenseEntitlements.cloudBackupEnabled(activity)) return
        analysesRefresh.request(Unit)
    }

    /** Takes [row] out of the list at once, ahead of its queued delete. */
    /** Drops [entry]'s row at once; its deletion waits out the undo window. */
    fun removeRow(entry: AnalysisEntry) = analysesAdapter.remove(entry.downloadKey())

    fun isBusy(key: String): Boolean = busy.isBusy(key)

    fun markBusy(key: String) {
        busy.mark(key)
        publishBusy()
    }

    fun unmarkBusy(key: String) {
        busy.unmark(key)
        publishBusy()
    }

    private fun publishBusy() = analysesAdapter.setDownloadingKeys(busy.keys())

    private suspend fun loadAnalysesData() {
        views.progressAnalysesData.isVisible = true
        views.tvAnalysesDataState.isVisible = false
        val records = withContext(Dispatchers.IO) { SessionStore.list(activity) }
        // Full COMPLETED list (not listRestorable): stubs without local .dat
        // still need a Download action when the cloud copy exists.
        val result = CloudRestore.listCompleted(activity)
        views.progressAnalysesData.isVisible = false

        cloudStateMessage(result)?.let {
            views.tvAnalysesDataState.isVisible = true
            views.tvAnalysesDataState.text = it
        }
        val cloud = (result as? CloudRestore.ListResult.Ready)?.sessions.orEmpty()
        val entries = withContext(Dispatchers.IO) {
            AnalysisEntries.merge(records, cloud).map { entry ->
                val id = entry.record?.id ?: return@map entry
                entry.copy(localBytes = SessionStore.sizeOf(activity, id))
            }
        }
        if (entries.isEmpty()) {
            views.tvAnalysesDataState.isVisible = true
            views.tvAnalysesDataState.setText(R.string.analyses_data_empty)
        }
        analysesAdapter.submit(entries)
    }

    /**
     * Why the cloud half is missing, or null when it answered. Silence would be
     * wrong here: without this line a backed-up analysis looks phone-only, and
     * the user would read that as "my backup is gone".
     */
    private fun cloudStateMessage(result: CloudRestore.ListResult): String? = when (result) {
        is CloudRestore.ListResult.Ready, CloudRestore.ListResult.Empty -> null
        CloudRestore.ListResult.NeedSignIn -> activity.getString(R.string.restore_need_sign_in)
        CloudRestore.ListResult.ApiOff -> activity.getString(R.string.restore_api_off)
        is CloudRestore.ListResult.Failed -> activity.getString(R.string.restore_load_error, result.reason)
    }

    // ── Transfers ────────────────────────────────────────────────────────

    /**
     * Restores ([com.indicvision.semper.data.DicRestoreWorker]) and
     * Save-to-Files downloads ([com.indicvision.semper.data.DicBundleDownloadWorker])
     * run in WorkManager. Watch both so
     * their rows go busy, the banner follows them even after leaving Analyses
     * data management, and each outcome is told once.
     */
    fun observeTransfers() {
        // Best-effort: WorkManager is always initialized in production (its startup
        // provider runs before any Activity), but not in a unit-test harness that
        // skips that provider. Missing WorkManager must not crash onCreate — and if
        // it were truly absent, restore couldn't be enqueued in the first place.
        val workManager = runCatching { WorkManager.getInstance(activity) }.getOrNull() ?: return
        TransferWorkObserver(TransferWork.Kind.RESTORE).observe(activity, workManager) { update ->
            busy.onRestoreWork(update.jobs)
            publishBusy()
            update.jobs.forEach(::showRestoreInBanner)
            update.newlyFinished.forEach(::reportRestore)
        }
        TransferWorkObserver(TransferWork.Kind.BUNDLE_DOWNLOAD, presentedBundleDownloads)
            .observe(activity, workManager) { update ->
                // Running, queued and blocked rows go busy through this list.
                busy.onDownloadWork(update.jobs)
                publishBusy()
                update.jobs.forEach(::showDownloadInBanner)
                update.newlyFinished.forEach(::reportDownload)
            }
    }

    /** A running restore's progress in the banner; a finished one leaves it. */
    private fun showRestoreInBanner(job: TransferWorkObserver.Job) {
        val key = job.cloudSessionId?.takeIf { it.isNotBlank() } ?: return
        val state = job.state
        when {
            state is TransferWork.State.Running -> {
                val percent = state.percent ?: 0
                if (!transferBanner.contains(key)) {
                    transferBanner.upsert(
                        TransferBannerController.Transfer(
                            id = key,
                            title = activity.getString(R.string.transfer_banner_restore),
                            cancellable = false,
                            percent = percent,
                        ),
                    )
                } else {
                    transferBanner.updateProgress(key, percent)
                }
            }
            job.isFinished -> transferBanner.remove(key)
        }
    }

    /** A restore that succeeded reloads the list so the session appears; one that failed says so once. */
    private fun reportRestore(job: TransferWorkObserver.Job) {
        when (job.state) {
            TransferWork.State.Succeeded -> refresh()
            is TransferWork.State.Failed -> RestoreFailureNotice.showIfNew(activity, job)
            else -> Unit
        }
    }

    /** A queued or running download in the banner (progress once it runs); a finished one leaves it. */
    private fun showDownloadInBanner(job: TransferWorkObserver.Job) {
        val key = job.cloudSessionId?.takeIf { it.isNotBlank() } ?: return
        val state = job.state
        when {
            job.isFinished -> transferBanner.remove(key)
            !transferBanner.contains(key) -> transferBanner.upsert(
                TransferBannerController.Transfer(
                    id = key,
                    title = activity.getString(R.string.transfer_banner_download),
                    percent = (state as? TransferWork.State.Running)?.percent ?: 0,
                    onCancel = { CloudRestore.cancelBundleDownload(activity, key) },
                ),
            )
            state is TransferWork.State.Running -> transferBanner.updateProgress(key, state.percent ?: 0)
        }
    }

    /** A finished Save-to-Files write, told once per process ([presentedBundleDownloads]). */
    private fun reportDownload(job: TransferWorkObserver.Job) {
        when (val state = job.state) {
            TransferWork.State.Succeeded -> Feedback.toast(activity, R.string.save_success, long = true)
            is TransferWork.State.Failed ->
                Feedback.toast(activity, LicenseErrors.downloadMessage(activity, state.reason), long = true)
            else -> Unit
        }
    }

    // ── Rows ─────────────────────────────────────────────────────────────

    /** Which backup action a row offers, or null when none applies. */
    private fun backupLabel(entry: AnalysisEntry): Int? {
        val record = entry.record ?: return null
        return when (record.syncState) {
            SessionRecord.SyncState.FAILED, SessionRecord.SyncState.PENDING -> R.string.cloud_retry_backup
            SessionRecord.SyncState.LOCAL_ONLY ->
                if (DicSettings.saveToCloud(activity)) R.string.cloud_backup_now else null
            // Backed up, but this run could not list the cloud: offer nothing
            // rather than a "back up" that would duplicate an existing copy.
            SessionRecord.SyncState.SYNCED -> null
        }
    }

    private fun startBackup(entry: AnalysisEntry) {
        val record = entry.record ?: return
        val label = backupLabel(entry) ?: return
        if (!IndicApi.get(activity).enabled) {
            Feedback.toast(activity, R.string.cloud_backup_no_backend, long = true)
            return
        }
        // Same ordering as Home's: the PENDING stamp before the worker, so a
        // fast upload cannot have its SYNCED stamp overwritten by this one.
        activity.lifecycleScope.launch {
            SessionStore.setSyncStateAsync(activity, record.id, SessionRecord.SyncState.PENDING)
            CloudSync.enqueueUpload(activity, record.id)
            Feedback.toast(activity, label)
            refresh()
        }
    }

    private fun stateLine(entry: AnalysisEntry): String = when (entry.location) {
        AnalysisLocation.CLOUD_ONLY -> activity.getString(
            R.string.analysis_state_cloud_only_fmt,
            ByteSize.format(entry.cloud?.totalBytes ?: 0L),
        )
        AnalysisLocation.PHONE_AND_CLOUD -> activity.getString(
            R.string.analysis_state_phone_and_cloud_fmt,
            ByteSize.format(entry.cloud?.totalBytes ?: 0L),
        )
        AnalysisLocation.PHONE_ONLY ->
            if (entry.localBytes > 0) {
                activity.getString(R.string.analysis_state_on_phone_fmt, ByteSize.format(entry.localBytes))
            } else {
                activity.getString(R.string.analysis_state_phone_only)
            }
        // The cloud could not confirm this one: keep its badge rather than
        // claiming the backup is gone.
        AnalysisLocation.PHONE_SYNC_STATE ->
            entry.record?.let { activity.getString(SessionListAdapter.syncStateLabel(it.syncState)) }.orEmpty()
    }

    private companion object {
        /** Work ids whose Save-to-Files outcome was already shown (process-wide). */
        val presentedBundleDownloads: MutableSet<java.util.UUID> =
            java.util.Collections.synchronizedSet(mutableSetOf())
    }
}
