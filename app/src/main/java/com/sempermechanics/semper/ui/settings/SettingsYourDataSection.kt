@file:Suppress("TooManyFunctions")

package com.sempermechanics.semper.ui.settings

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.DevAuth
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.databinding.ViewSettingsScrollContentBinding
import com.sempermechanics.semper.diagnostics.Diagnostics
import com.sempermechanics.semper.ui.common.auth.AuthRoute
import com.sempermechanics.semper.ui.common.auth.ExternalLinks
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.transfer.TransferBannerController
import com.sempermechanics.semper.ui.recyclebin.RecycleBinActivity
import com.sempermechanics.semper.ui.viewer.share.SendToSheet
import com.sempermechanics.semper.util.ProgressCount
import kotlinx.coroutines.launch

/**
 * The recycle bin, export / erasure / diagnostics. Session restore / download /
 * delete stay on [SettingsActivity].
 */
class SettingsYourDataSection(
    private val activity: SettingsActivity,
    private val views: ViewSettingsScrollContentBinding,
) {
    /** The progress dialog this screen shows while [AccountDeletionRun] runs. */
    private var deletionProgress: AlertDialog? = null

    fun wire() {
        views.btnRecycleBin.setOnClickListener { RecycleBinActivity.open(activity) }
        views.btnExportData.setOnClickListener { exportMyData() }
        views.btnExportCloudData.setOnClickListener { exportCloudAccountData() }
        views.btnDeleteAccount.setOnClickListener { confirmDeleteAccount() }

        val switchDiagnostics = views.switchDiagnostics
        switchDiagnostics.isChecked = AppSettings.diagnosticsEnabled(activity)
        switchDiagnostics.setOnCheckedChangeListener { _, checked ->
            // Applies immediately in both directions: turning this off also
            // deletes any crash report still queued on disk.
            Diagnostics.setEnabled(activity, checked)
        }
        wireLegal()
        observeConsent()
        observeExports()
        observeAccountDeletion()
    }

    /**
     * The product-improvement consent, kept apart from diagnostics and from
     * the Terms: it is optional, off until granted, and withdrawable here at
     * any time. The switch shows the answer this phone holds, or the one being
     * sent ([SettingsViewModel.setImprovementConsent], which a rotation does
     * not cancel), and waits while it is sent.
     */
    private fun wireLegal() {
        showConsent(activity.viewModel.consentSending.value)

        val accepted = AccountCache.termsAcceptedVersion(activity)
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
     * The switch at [sending] while that change is sent, and waiting; else at
     * the answer this phone holds. Set with no listener, so showing a value
     * never sends it.
     */
    private fun showConsent(sending: Boolean?) {
        val switchImprove = views.switchImprovementConsent
        switchImprove.setOnCheckedChangeListener(null)
        switchImprove.isChecked = sending ?: (AccountCache.improvementConsent(activity) == true)
        switchImprove.isEnabled = sending == null
        switchImprove.setOnCheckedChangeListener { _, checked ->
            switchImprove.isEnabled = false
            activity.viewModel.setImprovementConsent(checked)
        }
    }

    /**
     * Each consent change's answer once, on whichever Settings is started.
     * A change the server did not take is said; either way the switch shows
     * the answer this phone holds, which the repository stores only once the
     * server took it, so a refused change puts the switch back (TD-306).
     */
    private fun observeConsent() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activity.viewModel.consentOutcomes.collect { answer ->
                    showConsent(null)
                    if (!answer.saved) Feedback.toast(activity, R.string.terms_error_generic, long = true)
                }
            }
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
        val api = SemperApi.get(activity)
        if (!api.enabled) {
            Feedback.toast(activity, R.string.export_cloud_data_offline, long = true)
            return
        }
        runExport(CLOUD_EXPORT)
    }

    private fun exportMyData() {
        runExport(LOCAL_EXPORT)
    }

    /** What differs between the two "export my data" buttons on screen; the flow is shared. */
    private class ExportKind(
        val export: SettingsViewModel.DataExport,
        val key: String,
        val bannerTitle: Int,
        val failedMessage: Int,
        val mime: String,
    )

    /** The exports this screen's banner shows now. */
    private val shownExports = mutableSetOf<SettingsViewModel.DataExport>()

    /**
     * Banner, produce, share: one flow for both exports. The export runs in
     * [SettingsViewModel.exportData], so a rotation does not cancel it; the
     * banner's Cancel does, and says nothing, because the user asked for it.
     */
    private fun runExport(kind: ExportKind) {
        if (!activity.viewModel.exportData(kind.export)) {
            Feedback.toast(activity, R.string.download_analysis_already)
        }
    }

    /** The running exports in the banner, and each export's end once, on whichever Settings is started. */
    private fun observeExports() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { activity.viewModel.exports.collect(::showExports) }
                activity.viewModel.exportOutcomes.collect(::onExportFinished)
            }
        }
    }

    private fun showExports(running: Map<SettingsViewModel.DataExport, SettingsViewModel.ExportProgress?>) {
        val banner = activity.transferBanner
        (shownExports - running.keys).forEach { banner.remove(kindOf(it).key) }
        shownExports.retainAll(running.keys)
        running.forEach { (export, progress) ->
            val kind = kindOf(export)
            if (shownExports.add(export)) {
                banner.upsert(
                    TransferBannerController.Transfer(
                        id = kind.key,
                        title = activity.getString(kind.bannerTitle),
                        onCancel = { activity.viewModel.cancelExport(export) },
                    ),
                )
            }
            if (progress != null) {
                val (done, total) = progress
                val pct = if (total > 0) done * SettingsActivity.PERCENT.toDouble() / total else 0.0
                // done counts finished sessions; the label names the one in progress.
                val label = activity.getString(R.string.export_progress_fmt, ProgressCount.current(done, total), total)
                banner.updateProgress(kind.key, pct, label)
            }
        }
    }

    /** Shares what an export produced; only a file with content gets this far. */
    private fun onExportFinished(finished: SettingsViewModel.ExportFinished) {
        val kind = kindOf(finished.export)
        val file = finished.file
        if (file == null) {
            Feedback.toast(activity, kind.failedMessage, long = true)
        } else {
            SendToSheet.show(activity, file, kind.mime)
        }
    }

    private fun kindOf(export: SettingsViewModel.DataExport): ExportKind = when (export) {
        SettingsViewModel.DataExport.CLOUD -> CLOUD_EXPORT
        SettingsViewModel.DataExport.LOCAL -> LOCAL_EXPORT
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
            export = SettingsViewModel.DataExport.CLOUD,
            key = "export_cloud",
            bannerTitle = R.string.transfer_banner_export_cloud,
            failedMessage = R.string.export_cloud_data_failed,
            mime = SettingsActivity.JSON_MIME,
        )
        val LOCAL_EXPORT = ExportKind(
            export = SettingsViewModel.DataExport.LOCAL,
            key = "export_local",
            bannerTitle = R.string.transfer_banner_export_local,
            failedMessage = R.string.export_data_failed,
            mime = SettingsActivity.ZIP_MIME,
        )
    }
}
