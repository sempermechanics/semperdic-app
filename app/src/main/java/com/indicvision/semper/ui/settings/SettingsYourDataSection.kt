@file:Suppress("TooManyFunctions", "LongMethod")

package com.indicvision.semper.ui.settings

import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.indicvision.semper.Diagnostics
import com.indicvision.semper.R
import com.indicvision.semper.analytics.SemperAnalytics
import com.indicvision.semper.data.CloudSync
import com.indicvision.semper.data.DevAuth
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.SessionEverythingExporter
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.ui.common.AuthRoute
import com.indicvision.semper.ui.common.TransferBannerController
import com.indicvision.semper.ui.viewer.SendToSheet
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Export / erasure / diagnostics. Session restore / download / delete stay on
 * [SettingsActivity].
 */
class SettingsYourDataSection(
    private val activity: SettingsActivity,
) {
    fun wire() {
        activity.findViewById<View>(R.id.btnExportData).setOnClickListener { exportMyData() }
        activity.findViewById<View>(R.id.btnExportCloudData).setOnClickListener { exportCloudAccountData() }
        activity.findViewById<View>(R.id.btnDeleteAccount).setOnClickListener { confirmDeleteAccount() }

        val switchDiagnostics = activity.findViewById<SwitchMaterial>(R.id.switchDiagnostics)
        switchDiagnostics.isChecked = DicSettings.diagnosticsEnabled(activity)
        switchDiagnostics.setOnCheckedChangeListener { _, checked ->
            // Applies immediately in both directions: turning this off also
            // deletes any crash report still queued on disk.
            Diagnostics.setEnabled(activity, checked)
        }
    }

    /**
     * Download what the *cloud* holds about this account (GDPR Art. 20).
     *
     * Separate from [exportMyData], which bundles the sessions on this device.
     * The policy has always promised this; until now it existed only as an API
     * endpoint with no way for a user to reach it.
     */
    private fun exportCloudAccountData() {
        val api = IndicApi.get(activity)
        if (!api.enabled) {
            Toast.makeText(activity, R.string.export_cloud_data_offline, Toast.LENGTH_LONG).show()
            return
        }
        val key = "export_cloud"
        if (activity.transferBanner.contains(key)) {
            Toast.makeText(activity, R.string.download_analysis_already, Toast.LENGTH_SHORT).show()
            return
        }
        var job: kotlinx.coroutines.Job? = null
        SemperAnalytics.event(activity, SemperAnalytics.EXPORT_STARTED, mapOf("kind" to "cloud"))
        activity.transferBanner.upsert(
            TransferBannerController.Transfer(
                id = key,
                title = activity.getString(R.string.transfer_banner_export_cloud),
                onCancel = { job?.cancel() },
            ),
        )
        job = activity.lifecycleScope.launch {
            try {
                val dest = java.io.File(activity.cacheDir, "semper-account-export.json")
                val ok = runCatching {
                    val idToken = TokenProvider.usableIdToken() ?: error("not signed in")
                    api.exportAccount(idToken, dest)
                }.onFailure { Timber.w(it, "Cloud account export failed") }.isSuccess
                activity.transferBanner.remove(key)
                if (!ok || !dest.exists() || dest.length() == 0L) {
                    SemperAnalytics.event(
                        activity,
                        SemperAnalytics.EXPORT_FAILED,
                        mapOf("kind" to "cloud"),
                    )
                    Toast.makeText(
                        activity,
                        R.string.export_cloud_data_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                SemperAnalytics.event(
                    activity,
                    SemperAnalytics.EXPORT_COMPLETED,
                    mapOf("kind" to "cloud"),
                )
                SendToSheet.show(activity, dest, SettingsActivity.JSON_MIME)
            } catch (e: kotlinx.coroutines.CancellationException) {
                activity.transferBanner.remove(key)
                throw e
            } finally {
                activity.transferBanner.remove(key)
            }
        }
    }

    private fun exportMyData() {
        val key = "export_local"
        if (activity.transferBanner.contains(key)) {
            Toast.makeText(activity, R.string.download_analysis_already, Toast.LENGTH_SHORT).show()
            return
        }
        var job: kotlinx.coroutines.Job? = null
        SemperAnalytics.event(activity, SemperAnalytics.EXPORT_STARTED, mapOf("kind" to "local"))
        activity.transferBanner.upsert(
            TransferBannerController.Transfer(
                id = key,
                title = activity.getString(R.string.transfer_banner_export_local),
                onCancel = { job?.cancel() },
            ),
        )
        job = activity.lifecycleScope.launch {
            try {
                val export = SessionEverythingExporter.exportMasterZip(activity) { done, total ->
                    val pct = if (total > 0) done * SettingsActivity.PERCENT_MAX / total else 0
                    activity.runOnUiThread {
                        activity.transferBanner.updateProgress(
                            key,
                            pct,
                            activity.getString(R.string.export_progress_fmt, done, total),
                        )
                    }
                }
                activity.transferBanner.remove(key)
                // Safety: only share a file that actually exists and has content.
                val file = export?.file?.takeIf { it.exists() && it.length() > 0L }
                if (file == null) {
                    SemperAnalytics.event(
                        activity,
                        SemperAnalytics.EXPORT_FAILED,
                        mapOf("kind" to "local"),
                    )
                    Toast.makeText(activity, R.string.export_data_failed, Toast.LENGTH_LONG).show()
                    return@launch
                }
                SemperAnalytics.event(
                    activity,
                    SemperAnalytics.EXPORT_COMPLETED,
                    mapOf("kind" to "local"),
                )
                SendToSheet.show(activity, file, SettingsActivity.ZIP_MIME)
            } catch (e: kotlinx.coroutines.CancellationException) {
                activity.transferBanner.remove(key)
                throw e
            } finally {
                activity.transferBanner.remove(key)
            }
        }
    }

    private fun confirmDeleteAccount() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_account_title)
            .setMessage(R.string.delete_account_body)
            .setPositiveButton(R.string.delete_account_confirm) { _, _ -> verifyThenDeleteAccount() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Erasing an identity is the one action a stolen unlocked phone must not be
     * able to perform on an old session, so we prove who is holding it first.
     */
    private fun verifyThenDeleteAccount() {
        if (DevAuth.active) {
            deleteAccount()
            return
        }
        activity.launchReauth()
    }

    fun deleteAccount() {
        val progress = MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.delete_account_working)
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val outcome = CloudSync.deleteAccount(activity)
            progress.dismiss()
            when (outcome) {
                CloudSync.AccountDeletion.DELETED -> {
                    activity.toast(activity.getString(R.string.delete_account_done))
                    AuthRoute.toSignIn(activity)
                }
                CloudSync.AccountDeletion.IDENTITY_KEPT -> {
                    activity.toast(activity.getString(R.string.delete_account_identity_kept))
                    AuthRoute.toSignIn(activity)
                }
                CloudSync.AccountDeletion.CLOUD_UNREACHABLE ->
                    activity.toast(activity.getString(R.string.delete_account_failed))
            }
        }
    }
}
