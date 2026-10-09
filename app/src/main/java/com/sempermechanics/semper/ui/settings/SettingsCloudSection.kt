package com.sempermechanics.semper.ui.settings

import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.databinding.ViewSettingsScrollContentBinding
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Cloud backup: the save-to-cloud and Wi-Fi-only switches, the
 * backup status line, and the offer to back up what is still phone-only
 * when backup is turned on. Licensed accounts only.
 */
internal class SettingsCloudSection(
    private val activity: SettingsActivity,
    private val views: ViewSettingsScrollContentBinding,
) {

    fun wire() {
        val switchSave = views.switchSaveCloud
        val switchWifi = views.switchWifiOnly
        val status = views.tvCloudSyncStatus

        switchSave.isChecked = AppSettings.saveToCloudEnabled(activity)
        switchWifi.isChecked = AppSettings.wifiOnlyUploadEnabled(activity)

        switchSave.setOnCheckedChangeListener { _, checked ->
            AppSettings.setSaveToCloudEnabled(activity, checked)
            if (checked) maybeOfferBackfill()
        }
        switchWifi.setOnCheckedChangeListener { _, checked -> AppSettings.setWifiOnlyUploadEnabled(activity, checked) }

        activity.lifecycleScope.launch {
            val states = withContext(Dispatchers.IO) {
                SessionStore.list(activity).map { it.syncState }
            }
            status.text = BackupStatus.text(activity.resources, states)
        }
    }

    private fun maybeOfferBackfill() {
        if (!SemperApi.get(activity).enabled) return
        activity.lifecycleScope.launch {
            val localOnly = withContext(Dispatchers.IO) {
                SessionStore.list(activity)
                    .filter { it.syncState == SessionRecord.SyncState.LOCAL_ONLY }
            }
            if (localOnly.isEmpty()) return@launch
            Dialogs.confirm(
                activity,
                activity.getText(R.string.cloud_backfill_title),
                activity.resources.getQuantityString(R.plurals.cloud_backfill_body, localOnly.size, localOnly.size),
                R.string.cloud_backfill_confirm,
            ) {
                localOnly.forEach { CloudSync.enqueueUpload(activity, it.id) }
            }
        }
    }
}
