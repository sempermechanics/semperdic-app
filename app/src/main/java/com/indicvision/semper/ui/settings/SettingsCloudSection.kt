package com.indicvision.semper.ui.settings

import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.databinding.SettingsScrollContentBinding
import com.indicvision.semper.ui.common.Dialogs
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
    private val views: SettingsScrollContentBinding,
) {

    fun wire() {
        val switchSave = views.switchSaveCloud
        val switchWifi = views.switchWifiOnly
        val sub = views.tvSaveCloudSub
        val status = views.tvCloudSyncStatus

        switchSave.isChecked = DicSettings.saveToCloud(activity)
        switchWifi.isChecked = DicSettings.uploadWifiOnly(activity)
        sub.setText(if (switchSave.isChecked) R.string.setting_save_cloud_sub else R.string.setting_save_cloud_sub_off)

        switchSave.setOnCheckedChangeListener { _, checked ->
            DicSettings.setSaveToCloud(activity, checked)
            sub.setText(if (checked) R.string.setting_save_cloud_sub else R.string.setting_save_cloud_sub_off)
            if (checked) maybeOfferBackfill()
        }
        switchWifi.setOnCheckedChangeListener { _, checked -> DicSettings.setUploadWifiOnly(activity, checked) }

        activity.lifecycleScope.launch {
            val states = withContext(Dispatchers.IO) {
                SessionStore.list(activity).map { it.syncState }
            }
            status.text = BackupStatus.text(activity.resources, states)
        }
    }

    private fun maybeOfferBackfill() {
        if (!IndicApi.get(activity).enabled) return
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
