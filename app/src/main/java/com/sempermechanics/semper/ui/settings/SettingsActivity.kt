// Settings Activity hosts per-analysis restore/download/delete. Account, cloud,
// analyses list, storage, preferences, your-data, help and footer live in
// section classes.

@file:Suppress("TooManyFunctions", "ReturnCount")

package com.sempermechanics.semper.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.SessionDeletes
import com.sempermechanics.semper.data.cloud.restore.CloudRestore
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.databinding.ActivitySettingsBinding
import com.sempermechanics.semper.databinding.SettingsScrollContentBinding
import com.sempermechanics.semper.databinding.SettingsSectionHeaderBinding
import com.sempermechanics.semper.ui.auth.AuthActivity
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.Motion
import com.sempermechanics.semper.ui.common.SettingsSectionHeader
import com.sempermechanics.semper.ui.common.auth.confirm
import com.sempermechanics.semper.ui.common.dialog.CrispToast
import com.sempermechanics.semper.ui.common.dialog.DeleteChoiceDialog
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.transfer.DeleteFeedback
import com.sempermechanics.semper.ui.common.transfer.TransferBannerController
import com.sempermechanics.semper.ui.home.SessionOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Settings: account, cloud preferences, per-analysis data management, data
 * export/erasure and analysis defaults. A page rather than a sheet — Home
 * refreshes in `onResume`, so anything changed here is reflected on return.
 */
@MainThread
class SettingsActivity : AppCompatActivity() {

    /**
     * Registered up front, as activity-result launchers must be: the delete flow
     * hands off to the sign-in screen and only proceeds if it answers OK.
     */
    private val reauthLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) yourDataSection.deleteAccount()
    }

    /**
     * Analyses Download: pick the destination document first, then enqueue the
     * background write. [CreateDocument] is the location confirmation.
     */
    private val createDownloadDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(ZIP_MIME),
    ) { uri ->
        val pending = pendingDownload
        pendingDownload = null
        if (uri == null || pending == null) return@registerForActivityResult
        startBundleDownloadToUri(pending, uri)
    }

    /** Stashed while the SAF save-as picker is open. */
    private var pendingDownload: PendingBundleDownload? = null

    private lateinit var binding: ActivitySettingsBinding

    /** The scroll content's sections ([SettingsScrollContentView.sections]). */
    private lateinit var views: SettingsScrollContentBinding
    private lateinit var analyses: SettingsAnalysesSection

    internal lateinit var transferBanner: TransferBannerController
    private lateinit var yourDataSection: SettingsYourDataSection
    private lateinit var deleteFeedback: DeleteFeedback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDownload = PendingBundleDownload.fromBundle(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        views = binding.settingsSections.sections
        yourDataSection = SettingsYourDataSection(this, views)
        // Edge-to-edge: without this the status bar swallows taps on the back arrow.
        Insets.padTop(binding.settingsTopBar)
        Insets.padBottom(binding.settingsScroll)
        transferBanner = TransferBannerController(binding.transferBannerRoot.root)

        binding.btnSettingsBack.setOnClickListener { finish() }

        analyses = SettingsAnalysesSection(this, views)
        analyses.attachList()

        wireCollapsible(views.headerAccount, R.string.account_section, views.bodyAccount)
        wireCollapsible(views.headerStorage, R.string.storage_section, views.bodyStorage)
        wireCollapsible(views.headerYourData, R.string.your_data_section, views.bodyYourData)
        wireCollapsible(views.headerAnalysisPrefs, R.string.analysis_preferences, views.bodyAnalysisPrefs)
        wireCollapsible(views.headerHelpSupport, R.string.help_support_section, views.bodyHelpSupport)
        wireCollapsible(views.headerCloud, R.string.cloud_section, views.bodyCloud)
        wireCollapsible(views.headerAnalysesData, R.string.analyses_data_management, views.bodyAnalysesData)

        SettingsAccountSection(this, views).wire()
        // Backup and restore are the licensed half of cloud. A demo account
        // records its analyses silently and cannot pull them back, so both
        // sections are absent rather than shown disabled.
        if (LicenseEntitlements.cloudBackupEnabled(this)) {
            analyses.observeTransfers()
            deleteFeedback = DeleteFeedback(this, binding.settingsRoot) { wireAnalysesDataSection() }
            deleteFeedback.observe()
            SettingsCloudSection(this, views).wire()
            wireAnalysesDataSection()
        } else {
            listOf(views.headerCloud.root, views.bodyCloud, views.headerAnalysesData.root, views.bodyAnalysesData)
                .forEach { it.isVisible = false }
        }
        SettingsStorageSection(this, views).wire()
        yourDataSection.wire()
        SettingsPreferencesSection(this, views).wire()
        SettingsHelpSupportSection(this, views).wire()
        SettingsFooterSection(this, views).wire()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingDownload?.writeTo(outState)
    }

    /**
     * Titles [header] and makes it open and close [body], starting closed.
     * The chevron is the header's own, never one looked up across the screen.
     */
    private fun wireCollapsible(header: SettingsSectionHeaderBinding, @StringRes title: Int, body: View) {
        SettingsSectionHeader.bind(header, title)
        val chevron = header.ivSectionChevron
        fun apply(expanded: Boolean) {
            body.isVisible = expanded
            chevron.rotation = if (expanded) CHEVRON_EXPANDED_DEG else 0f
        }
        apply(false)
        header.root.setOnClickListener {
            Motion.animateExpandCollapse(binding.settingsSections)
            apply(!body.isVisible)
        }
    }

    // ── Analyses data management ─────────────────────────────────────────

    /** Reloads the analyses list (a no-op on demo, which has no such section). */
    internal fun wireAnalysesDataSection() = analyses.refresh()

    /**
     * Open when the phone already has results; otherwise offer cloud restore when a
     * backup is attached to this management row.
     */
    internal fun openOrDownloadAnalysis(entry: AnalysisEntry) {
        val record = entry.record
        if (record != null && entry.hasLocalData) {
            SessionOpenHelper.openOrExplain(this, record, hasLocalData = true)
            return
        }
        if (entry.cloud != null) {
            confirmCloudRestore(entry)
            return
        }
        if (record != null) {
            SessionOpenHelper.openOrExplain(this, record, hasLocalData = false)
        }
    }

    internal fun confirmLocalDownload(entry: AnalysisEntry) {
        if (!entry.offersDownload()) return
        val key = entry.downloadKey()
        if (analyses.isBusy(key)) {
            Feedback.toast(this, R.string.download_analysis_already)
            return
        }
        // Location picker is the confirmation — download starts only after the
        // user chooses where the Session.zip should be saved.
        pendingDownload = PendingBundleDownload(
            cloudSessionId = entry.cloud?.sessionId.orEmpty(),
            displayName = entry.name,
            localSessionId = entry.record?.id.orEmpty(),
        )
        createDownloadDocument.launch(CloudRestore.suggestedBundleFileName(entry.name))
    }

    /**
     * Enqueue a background download that writes into [destUri]. Called only
     * after SAF CreateDocument returns a destination.
     */
    private fun startBundleDownloadToUri(pending: PendingBundleDownload, destUri: Uri) {
        if (pending.cloudSessionId.isBlank()) {
            Feedback.toast(this, R.string.download_analysis_failed, long = true)
            return
        }
        val key = pending.cloudSessionId
        if (analyses.isBusy(key)) {
            Feedback.toast(this, R.string.download_analysis_already)
            return
        }
        val granted = runCatching {
            contentResolver.takePersistableUriPermission(
                destUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Timber.e(it, "Could not persist write grant for destination") }
            .isSuccess
        if (!granted) {
            Feedback.toast(this, R.string.save_failed, long = true)
            return
        }
        // Marked before the enqueue, so the job is not lost if it finishes
        // before the observed list ever shows it running.
        analyses.markBusy(key)
        val enqueued = runCatching {
            CloudRestore.enqueueBundleDownload(
                this,
                pending.cloudSessionId,
                pending.displayName,
                destUri = destUri.toString(),
                localSessionId = pending.localSessionId,
            )
        }.onFailure { Timber.e(it, "Could not enqueue bundle download") }
            .isSuccess
        if (!enqueued) {
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    destUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            analyses.unmarkBusy(key)
            Feedback.toast(this, R.string.download_analysis_failed, long = true)
            return
        }
        transferBanner.upsert(
            TransferBannerController.Transfer(
                id = key,
                title = pending.displayName.ifBlank { getString(R.string.transfer_banner_download) },
                onCancel = { CloudRestore.cancelBundleDownload(this, pending.cloudSessionId) },
            ),
        )
        Feedback.toast(this, R.string.download_background_note)
    }

    internal fun confirmCloudRestore(entry: AnalysisEntry) {
        if (!entry.offersRestore()) return
        val key = entry.downloadKey()
        if (analyses.isBusy(key)) {
            analyses.markBusy(key)
            Feedback.toast(this, R.string.download_analysis_already)
            return
        }
        Dialogs.confirm(
            this,
            R.string.download_analysis_title,
            R.string.download_analysis_body,
            R.string.restore_action,
        ) { restoreBackup(entry) }
    }

    private fun restoreBackup(entry: AnalysisEntry) {
        val cloud = entry.cloud ?: return
        val key = entry.downloadKey()
        if (analyses.isBusy(key)) {
            analyses.markBusy(key)
            Feedback.toast(this, R.string.download_analysis_already)
            return
        }
        // Prefer the existing phone row id so a freed stub / re-download fills
        // in-place rather than creating a second "restored-…" id.
        val targetLocalId = entry.record?.id ?: CloudRestore.targetLocalId(cloud)
        // Marked before the IO hop: the restore can run and finish inside it.
        analyses.markBusy(key)
        lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) {
                RestoreStart.start(this@SettingsActivity, cloud.sessionId, targetLocalId, entry.name)
            }
            if (started == RestoreStart.Result.ALREADY_RUNNING) {
                Feedback.toast(this@SettingsActivity, R.string.download_analysis_already)
                return@launch
            }
            if (started != RestoreStart.Result.STARTED) {
                analyses.unmarkBusy(key)
                Feedback.toast(this@SettingsActivity, R.string.restore_failed_generic, long = true)
                return@launch
            }
            transferBanner.upsert(
                TransferBannerController.Transfer(
                    id = key,
                    title = entry.name.ifBlank { getString(R.string.transfer_banner_restore) },
                    cancellable = false,
                ),
            )
            Feedback.toast(this@SettingsActivity, R.string.restore_background_note)
            wireAnalysesDataSection()
        }
    }

    internal fun deleteBackup(entry: AnalysisEntry) {
        val cloud = entry.cloud ?: return
        val record = entry.record
        if (record != null) {
            showDeleteBackupChoice(record, cloud, entry)
        } else {
            confirmDeleteCloudBackup(cloud, entry)
        }
    }

    // ── Deletion, with an undo window ────────────────────────────────────

    /**
     * Deleting a cloud backup is irreversible — there is no trash on the
     * backend — so the confirm spells that out before anything is scheduled.
     */
    private fun confirmDeleteCloudBackup(session: CloudSessionDto, entry: AnalysisEntry) {
        Dialogs.confirm(
            this,
            getText(R.string.cloud_delete_forever_title),
            getString(R.string.cloud_delete_forever_body, entry.name),
            R.string.cloud_delete_forever_confirm,
        ) { scheduleDelete(entry, session.localSessionId, session.sessionId, SessionDeletes.Mode.CLOUD) }
    }

    private fun showDeleteBackupChoice(record: SessionRecord, cloud: CloudSessionDto, entry: AnalysisEntry) {
        DeleteChoiceDialog.show(
            activity = this,
            title = getString(R.string.delete_confirm_title),
            message = getString(R.string.delete_confirm_body_cloud),
            choices = listOf(
                DeleteChoiceDialog.Choice(getString(R.string.delete_choice_phone)) {
                    lifecycleScope.launch {
                        CloudSync.eraseLocalOnly(this@SettingsActivity, record.id)
                        toast(getString(R.string.delete_device_only_done))
                        wireAnalysesDataSection()
                    }
                },
                DeleteChoiceDialog.Choice(getString(R.string.delete_choice_cloud)) {
                    scheduleDelete(entry, record.id, cloud.sessionId, SessionDeletes.Mode.CLOUD)
                },
                DeleteChoiceDialog.Choice(getString(R.string.delete_choice_everywhere)) {
                    scheduleDelete(entry, record.id, cloud.sessionId, SessionDeletes.Mode.EVERYWHERE)
                },
            ),
        )
    }

    /**
     * The safety net: the row goes at once, but the deletion waits in
     * [SessionDeletes] for the undo window and only then reaches the backend,
     * so a mis-tapped bin followed by a reflexive confirm is still
     * recoverable. Leaving the page (or the app) does not abandon it: the user
     * confirmed, and the worker retries if the network is down.
     */
    private fun scheduleDelete(
        entry: AnalysisEntry,
        localSessionId: String,
        cloudSessionId: String,
        mode: SessionDeletes.Mode,
    ) {
        analyses.removeRow(entry)
        val workId = SessionDeletes.enqueue(this, listOf(SessionDeletes.Item(localSessionId, cloudSessionId, mode)))
        deleteFeedback.queued(workId, 1)
    }

    // Host helpers used by extracted sections.

    internal fun launchReauth() {
        reauthLauncher.launch(AuthActivity.reauthIntent(this))
    }

    internal fun toast(message: String) =
        CrispToast.show(this, message, long = true)

    private data class PendingBundleDownload(
        val cloudSessionId: String,
        val displayName: String,
        val localSessionId: String,
    ) {
        fun writeTo(out: Bundle) {
            out.putString(STATE_DL_CLOUD, cloudSessionId)
            out.putString(STATE_DL_NAME, displayName)
            out.putString(STATE_DL_LOCAL, localSessionId)
        }

        companion object {
            fun fromBundle(state: Bundle?): PendingBundleDownload? {
                val cloud = state?.getString(STATE_DL_CLOUD)?.takeIf { it.isNotBlank() } ?: return null
                return PendingBundleDownload(
                    cloudSessionId = cloud,
                    displayName = state.getString(STATE_DL_NAME).orEmpty(),
                    localSessionId = state.getString(STATE_DL_LOCAL).orEmpty(),
                )
            }
        }
    }

    companion object {
        private const val STATE_DL_CLOUD = "pending_dl_cloud"
        private const val STATE_DL_NAME = "pending_dl_name"
        private const val STATE_DL_LOCAL = "pending_dl_local"

        const val CHEVRON_EXPANDED_DEG = 180f
        const val ZIP_MIME = "application/zip"
        const val JSON_MIME = "application/json"
        const val PERCENT_MAX = 100
    }
}
