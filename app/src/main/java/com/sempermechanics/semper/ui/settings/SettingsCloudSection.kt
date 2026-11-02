package com.sempermechanics.semper.ui.settings

import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.CloudBackfill
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.sync.PhoneSyncWorker
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.databinding.ViewSettingsScrollContentBinding
import com.sempermechanics.semper.ui.common.SettingsSectionHeader
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.LicenseLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Cloud backup: the save-to-cloud, Wi-Fi-only and "Download from my
 * other phones" switches, the backup status line, and the offer to back up
 * what is still phone-only when backup is turned on. Wired for licensed
 * accounts; an account whose licence lacks backup sees it [lock]ed.
 */
internal class SettingsCloudSection(
    private val activity: SettingsActivity,
    private val views: ViewSettingsScrollContentBinding,
) {

    fun wire() {
        val switchSave = views.switchSaveCloud
        val switchWifi = views.switchWifiOnly
        val switchSync = views.rowSyncOtherPhones.switchSyncOtherPhones
        val status = views.tvCloudSyncStatus

        switchSave.isChecked = AppSettings.saveToCloudEnabled(activity)
        switchWifi.isChecked = AppSettings.wifiOnlyUploadEnabled(activity)
        switchSync.isChecked = AppSettings.syncFromOtherPhonesEnabled(activity)

        switchSave.setOnCheckedChangeListener { _, checked ->
            AppSettings.setSaveToCloudEnabled(activity, checked)
            if (checked) maybeOfferBackfill()
        }
        switchWifi.setOnCheckedChangeListener { _, checked -> AppSettings.setWifiOnlyUploadEnabled(activity, checked) }
        // Off stops future runs; restores already queued carry on.
        switchSync.setOnCheckedChangeListener { _, checked -> PhoneSyncWorker.setEnabled(activity, checked) }

        activity.lifecycleScope.launch {
            val states = withContext(Dispatchers.IO) {
                SessionStore.list(activity).map { it.syncState }
            }
            status.text = BackupStatus.text(activity.resources, states)
        }
    }

    /**
     * Locked, not hidden: the switches shown off and disabled, the status
     * line gone, the licence note in their place. A tap says the note.
     */
    fun lock() {
        SettingsSectionHeader.setLocked(views.headerCloud, true)
        views.switchSaveCloud.isChecked = false
        views.switchWifiOnly.isChecked = false
        val switchSync = views.rowSyncOtherPhones.switchSyncOtherPhones
        switchSync.isChecked = false
        views.tvCloudSyncStatus.isVisible = false
        LicenseLock.lock(
            views.bodyCloud,
            views.tvCloudLockNote,
            listOf(views.switchSaveCloud, views.switchWifiOnly, switchSync),
        )
    }

    private fun maybeOfferBackfill() {
        if (!SemperApi.get(activity).enabled) return
        activity.lifecycleScope.launch {
            // Not a row whose backup is in the recycle bin: it was deleted from the cloud on purpose.
            val localOnly = withContext(Dispatchers.IO) { CloudBackfill.candidates(activity) }
            if (localOnly.isEmpty()) return@launch
            Dialogs.confirm(
                activity,
                activity.getText(R.string.cloud_backfill_title),
                activity.resources.getQuantityString(R.plurals.cloud_backfill_body, localOnly.size, localOnly.size),
                R.string.cloud_backfill_confirm,
            ) { backUp(localOnly.map { it.id }) }
        }
    }

    /**
     * Stamps [ids] PENDING, then queues each: the worker uploads PENDING rows
     * only, so a queued LOCAL_ONLY row would do nothing. Same order as Home's
     * Back up ([CloudBackfill.stamp]).
     */
    private fun backUp(ids: List<String>) {
        activity.lifecycleScope.launch {
            val stamped = withContext(Dispatchers.IO) { CloudBackfill.stamp(activity, ids) }
            stamped.forEach { CloudSync.enqueueUpload(activity, it) }
        }
    }
}
