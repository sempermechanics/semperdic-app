@file:Suppress("TooManyFunctions")

package com.indicvision.semper.ui.settings

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R
import com.indicvision.semper.data.account.AuthRepository
import com.indicvision.semper.data.account.DevAuth
import com.indicvision.semper.data.cloud.CloudAccountExport
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.data.session.SessionEverythingExporter
import com.indicvision.semper.databinding.SettingsScrollContentBinding
import com.indicvision.semper.diagnostics.Diagnostics
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.ui.common.auth.AuthRoute
import com.indicvision.semper.ui.common.auth.ExternalLinks
import com.indicvision.semper.ui.common.dialog.Dialogs
import com.indicvision.semper.ui.common.dialog.Feedback
import com.indicvision.semper.ui.common.transfer.TransferBannerController
import com.indicvision.semper.ui.viewer.share.SendToSheet
import com.indicvision.semper.util.ProgressCount
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Export / erasure / diagnostics. Session restore / download / delete stay on
 * [SettingsActivity].
 */
class SettingsYourDataSection(
    private val activity: SettingsActivity,
    private val views: SettingsScrollContentBinding,
) {
    /** The progress dialog this screen shows while [AccountDeletionRun] runs. */
    private var deletionProgress: AlertDialog? = null

    fun wire() {
        views.btnExportData.setOnClickListener { exportMyData() }
        views.btnExportCloudData.setOnClickListener { exportCloudAccountData() }
        views.btnDeleteAccount.setOnClickListener { confirmDeleteAccount() }

        val switchDiagnostics = views.switchDiagnostics
        switchDiagnostics.isChecked = DicSettings.diagnosticsEnabled(activity)
        switchDiagnostics.setOnCheckedChangeListener { _, checked ->
            // Applies immediately in both directions: turning this off also
            // deletes any crash report still queued on disk.
            Diagnostics.setEnabled(activity, checked)
        }
        wireLegal()
        observeAccountDeletion()
    }

    /**
     * The product-improvement consent, kept apart from diagnostics and from
     * the Terms: it is optional, off until granted, and withdrawable here at
     * any time. The switch reflects the last value the server confirmed.
     */
    private fun wireLegal() {
        val switchImprove = views.switchImprovementConsent
        switchImprove.isChecked = TokenStore.improvementConsent(activity) == true
        switchImprove.setOnCheckedChangeListener { _, checked ->
            switchImprove.isEnabled = false
            activity.lifecycleScope.launch {
                val result = AuthRepository(activity).setImprovementConsent(checked)
                switchImprove.isEnabled = true
                result.onFailure {
                    Timber.w(it, "Improvement consent update failed")
                    // Revert silently: the server still holds the previous answer.
                    switchImprove.setOnCheckedChangeListener(null)
                    switchImprove.isChecked = !checked
                    wireLegal()
                    Feedback.toast(activity, R.string.terms_error_generic, long = true)
                }
            }
        }

        val accepted = TokenStore.termsAcceptedVersion(activity)
        views.tvTermsAccepted.text =
            if (accepted == null) {
                activity.getString(R.string.settings_terms_not_accepted)
            } else {
                activity.getString(R.string.settings_terms_accepted_fmt, accepted)
            }
        views.btnViewTerms.setOnClickListener {
            ExternalLinks.open(activity, activity.getString(R.string.legal_terms_url))
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
            Feedback.toast(activity, R.string.export_cloud_data_offline, long = true)
            return
        }
        runExport(CLOUD_EXPORT) { CloudAccountExport.download(activity.cacheDir, api) }
    }

    private fun exportMyData() {
        runExport(LOCAL_EXPORT) { onProgress ->
            SessionEverythingExporter.exportMasterZip(activity, onProgress)?.file
        }
    }

    /** What differs between the two "export my data" buttons; the flow is shared. */
    private class ExportKind(
        val key: String,
        val analyticsKind: String,
        val bannerTitle: Int,
        val failedMessage: Int,
        val mime: String,
    )

    /**
     * Banner, produce, share: one flow for both exports. [produce] returns the
     * file to share, or null on failure; an empty file counts as a failure.
     * Cancelling from the banner cancels [produce] — and says nothing, because
     * the user asked for it.
     */
    private fun runExport(
        kind: ExportKind,
        produce: suspend (onProgress: (done: Int, total: Int) -> Unit) -> java.io.File?,
    ) {
        if (activity.transferBanner.contains(kind.key)) {
            Feedback.toast(activity, R.string.download_analysis_already)
            return
        }
        var job: kotlinx.coroutines.Job? = null
        SemperAnalytics.event(activity, SemperAnalytics.EXPORT_STARTED, mapOf("kind" to kind.analyticsKind))
        activity.transferBanner.upsert(
            TransferBannerController.Transfer(
                id = kind.key,
                title = activity.getString(kind.bannerTitle),
                onCancel = { job?.cancel() },
            ),
        )
        job = activity.lifecycleScope.launch {
            try {
                val produced = produce { done, total ->
                    val pct = if (total > 0) done * SettingsActivity.PERCENT_MAX / total else 0
                    activity.runOnUiThread {
                        activity.transferBanner.updateProgress(
                            kind.key,
                            pct,
                            // done counts finished sessions; the label names the one in progress.
                            activity.getString(
                                R.string.export_progress_fmt,
                                ProgressCount.current(done, total),
                                total,
                            ),
                        )
                    }
                }
                activity.transferBanner.remove(kind.key)
                // Safety: only share a file that actually exists and has content.
                val file = produced?.takeIf { it.exists() && it.length() > 0L }
                if (file == null) {
                    SemperAnalytics.event(
                        activity,
                        SemperAnalytics.EXPORT_FAILED,
                        mapOf("kind" to kind.analyticsKind),
                    )
                    Feedback.toast(activity, kind.failedMessage, long = true)
                    return@launch
                }
                SemperAnalytics.event(
                    activity,
                    SemperAnalytics.EXPORT_COMPLETED,
                    mapOf("kind" to kind.analyticsKind),
                )
                SendToSheet.show(activity, file, kind.mime)
            } finally {
                activity.transferBanner.remove(kind.key)
            }
        }
    }

    private fun confirmDeleteAccount() {
        Dialogs.confirm(
            activity,
            R.string.delete_account_title,
            R.string.delete_account_body,
            R.string.delete_account_confirm,
        ) { verifyThenDeleteAccount() }
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

    /**
     * Starts the deletion in [AccountDeletionRun], outside this Activity, so a
     * rotation (which recreates Settings) cannot cancel it half-way. A tap
     * while one is already running does nothing: its dialog is up.
     */
    fun deleteAccount() {
        AccountDeletionRun.start(activity)
    }

    /**
     * Renders the run on whichever Settings instance is started: the dialog
     * while it runs, then the outcome once. An outcome that lands while
     * Settings is stopped waits for it; the window is never leaked, because
     * the dialog goes with the Activity that owns it.
     */
    private fun observeAccountDeletion() {
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) = dismissDeletionProgress()
            },
        )
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AccountDeletionRun.state.collect { state ->
                    when (state) {
                        AccountDeletionRun.State.Idle -> dismissDeletionProgress()
                        AccountDeletionRun.State.Running -> showDeletionProgress()
                        is AccountDeletionRun.State.Done -> {
                            dismissDeletionProgress()
                            AccountDeletionRun.consume()?.let { onAccountDeletion(it) }
                        }
                    }
                }
            }
        }
    }

    private fun showDeletionProgress() {
        if (deletionProgress?.isShowing == true) return
        deletionProgress = MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.delete_account_working)
            .setCancelable(false)
            .show()
    }

    private fun dismissDeletionProgress() {
        deletionProgress?.dismiss()
        deletionProgress = null
    }

    private fun onAccountDeletion(outcome: AccountDeletionRun.Outcome) {
        when (outcome) {
            AccountDeletionRun.Outcome.DELETED -> {
                activity.toast(activity.getString(R.string.delete_account_done))
                AuthRoute.toSignIn(activity)
            }
            AccountDeletionRun.Outcome.IDENTITY_KEPT -> {
                activity.toast(activity.getString(R.string.delete_account_identity_kept))
                AuthRoute.toSignIn(activity)
            }
            // The account is gone, so the session must not continue here.
            AccountDeletionRun.Outcome.PHONE_NOT_CLEARED -> {
                activity.toast(activity.getString(R.string.delete_account_phone_not_cleared))
                AuthRoute.toSignIn(activity)
            }
            AccountDeletionRun.Outcome.CLOUD_NOT_REACHED ->
                activity.toast(activity.getString(R.string.delete_account_failed))
        }
    }

    private companion object {
        val CLOUD_EXPORT = ExportKind(
            key = "export_cloud",
            analyticsKind = "cloud",
            bannerTitle = R.string.transfer_banner_export_cloud,
            failedMessage = R.string.export_cloud_data_failed,
            mime = SettingsActivity.JSON_MIME,
        )
        val LOCAL_EXPORT = ExportKind(
            key = "export_local",
            analyticsKind = "local",
            bannerTitle = R.string.transfer_banner_export_local,
            failedMessage = R.string.export_data_failed,
            mime = SettingsActivity.ZIP_MIME,
        )
    }
}
