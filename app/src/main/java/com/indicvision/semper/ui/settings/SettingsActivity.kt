// Settings Activity hosts cloud backup and per-analysis restore/download/delete.
// Account, storage, preferences, your-data, and help live in section classes.

@file:Suppress("TooManyFunctions", "LargeClass", "LongMethod", "CyclomaticComplexMethod", "MagicNumber", "ReturnCount")

package com.indicvision.semper.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.R
import com.indicvision.semper.data.AuthRepository
import com.indicvision.semper.data.BackupDeleteWorker
import com.indicvision.semper.data.CloudRestore
import com.indicvision.semper.data.CloudSync
import com.indicvision.semper.data.DicBundleDownloadWorker
import com.indicvision.semper.data.DicRestoreWorker
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.ui.auth.AuthActivity
import com.indicvision.semper.ui.common.AuthRoute
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.TransferBannerController
import com.indicvision.semper.ui.home.SessionOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Settings: account, cloud preferences, per-analysis data management, data
 * export/erasure and analysis defaults. A page rather than a sheet — Home
 * refreshes in `onResume`, so anything changed here is reflected on return.
 */
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

    private lateinit var analysesList: RecyclerView
    private lateinit var analysesAdapter: AnalysisDataAdapter
    private lateinit var analysesProgress: ProgressBar
    private lateinit var analysesState: TextView

    /** Restore WorkInfo ids already surfaced, so one outcome isn't shown twice. */
    private val shownRestoreOutcomes = mutableSetOf<java.util.UUID>()

    /** Restore / Save-to-Files download keys currently busy — disables row actions. */
    private val downloadingKeys = mutableSetOf<String>()

    internal lateinit var transferBanner: TransferBannerController
    private lateinit var yourDataSection: SettingsYourDataSection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDownload = PendingBundleDownload.fromBundle(savedInstanceState)
        setContentView(R.layout.activity_settings)
        yourDataSection = SettingsYourDataSection(this)
        // Edge-to-edge: without this the status bar swallows taps on the back arrow.
        Insets.padTop(findViewById(R.id.settingsTopBar))
        Insets.padBottom(findViewById(R.id.settingsScroll))
        transferBanner = TransferBannerController(findViewById(R.id.transferBannerRoot))

        findViewById<ImageButton>(R.id.btnSettingsBack).setOnClickListener { finish() }

        analysesAdapter = AnalysisDataAdapter(
            stateLine = ::stateLine,
            backupLabel = ::backupLabel,
            onOpen = ::openOrDownloadAnalysis,
            onBackup = ::startBackup,
            onLocalDownload = ::confirmLocalDownload,
            onCloudRestore = ::confirmCloudRestore,
            onDelete = ::deleteBackup,
        )
        analysesList = findViewById<RecyclerView>(R.id.analysesDataList).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = analysesAdapter
        }
        analysesProgress = findViewById(R.id.progressAnalysesData)
        analysesState = findViewById(R.id.tvAnalysesDataState)

        wireCollapsible(R.id.headerAccount, R.id.bodyAccount, R.id.ivAccountChevron)
        wireCollapsible(R.id.headerCloud, R.id.bodyCloud, R.id.ivCloudChevron)
        wireCollapsible(R.id.headerAnalysesData, R.id.bodyAnalysesData, R.id.ivAnalysesDataChevron)
        wireCollapsible(R.id.headerStorage, R.id.bodyStorage, R.id.ivStorageChevron)
        wireCollapsible(R.id.headerYourData, R.id.bodyYourData, R.id.ivYourDataChevron)
        wireCollapsible(R.id.headerAnalysisPrefs, R.id.bodyAnalysisPrefs, R.id.ivAnalysisPrefsChevron)
        wireCollapsible(R.id.headerHelpSupport, R.id.bodyHelpSupport, R.id.ivHelpSupportChevron)

        observeRestoreOutcomes()
        observeBundleDownloadOutcomes()

        SettingsAccountSection(this).wire()
        wireCloudSection()
        wireAnalysesDataSection()
        SettingsStorageSection(this).wire()
        yourDataSection.wire()
        SettingsPreferencesSection(this).wire()
        SettingsHelpSupportSection(this).wire()
        wireFooter()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingDownload?.writeTo(outState)
    }

    private fun wireCollapsible(headerId: Int, bodyId: Int, chevronId: Int, startExpanded: Boolean = false) {
        val header = findViewById<View>(headerId)
        val body = findViewById<View>(bodyId)
        val chevron = findViewById<ImageView>(chevronId)
        fun apply(expanded: Boolean) {
            body.isVisible = expanded
            chevron.rotation = if (expanded) CHEVRON_EXPANDED_DEG else 0f
        }
        apply(startExpanded)
        header.setOnClickListener { apply(body.visibility != View.VISIBLE) }
    }

    // ── Account ──────────────────────────────────────────────────────────

    // ── Cloud backup ─────────────────────────────────────────────────────

    private fun wireCloudSection() {
        val switchSave = findViewById<SwitchMaterial>(R.id.switchSaveCloud)
        val switchWifi = findViewById<SwitchMaterial>(R.id.switchWifiOnly)
        val sub = findViewById<TextView>(R.id.tvSaveCloudSub)
        val status = findViewById<TextView>(R.id.tvCloudSyncStatus)

        switchSave.isChecked = DicSettings.saveToCloud(this)
        switchWifi.isChecked = DicSettings.uploadWifiOnly(this)
        sub.setText(if (switchSave.isChecked) R.string.setting_save_cloud_sub else R.string.setting_save_cloud_sub_off)

        switchSave.setOnCheckedChangeListener { _, checked ->
            DicSettings.setSaveToCloud(this, checked)
            sub.setText(if (checked) R.string.setting_save_cloud_sub else R.string.setting_save_cloud_sub_off)
            if (checked) maybeOfferBackfill()
        }
        switchWifi.setOnCheckedChangeListener { _, checked -> DicSettings.setUploadWifiOnly(this, checked) }

        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                SessionStore.list(this@SettingsActivity).count { it.syncState == SessionRecord.SyncState.PENDING }
            }
            status.setText(if (pending > 0) R.string.badge_pending else R.string.sync_status_up_to_date)
        }
    }

    private fun maybeOfferBackfill() {
        lifecycleScope.launch {
            val localOnly = withContext(Dispatchers.IO) {
                SessionStore.list(this@SettingsActivity)
                    .filter { it.syncState == SessionRecord.SyncState.LOCAL_ONLY }
            }
            if (localOnly.isEmpty()) return@launch
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.cloud_backfill_title)
                .setMessage(resources.getQuantityString(R.plurals.cloud_backfill_body, localOnly.size, localOnly.size))
                .setPositiveButton(R.string.cloud_backfill_confirm) { _, _ ->
                    localOnly.forEach { CloudSync.enqueueUpload(this@SettingsActivity, it.id) }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    // ── Analyses data management ─────────────────────────────────────────

    internal fun wireAnalysesDataSection() {
        analysesProgress.isVisible = true
        analysesState.isVisible = false

        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) { SessionStore.list(this@SettingsActivity) }
            // Full COMPLETED list (not listRestorable): stubs without local .dat
            // still need a Download action when the cloud copy exists.
            val result = CloudRestore.listCompleted(this@SettingsActivity)
            analysesProgress.isVisible = false

            cloudStateMessage(result)?.let {
                analysesState.isVisible = true
                analysesState.text = it
            }
            val cloud = (result as? CloudRestore.ListResult.Ready)?.sessions.orEmpty()
            val entries = withContext(Dispatchers.IO) {
                AnalysisEntries.merge(records, cloud).map { entry ->
                    val id = entry.record?.id ?: return@map entry
                    entry.copy(localBytes = SessionStore.sizeOf(this@SettingsActivity, id))
                }
            }
            if (entries.isEmpty()) {
                analysesState.isVisible = true
                analysesState.setText(R.string.analyses_data_empty)
            }
            analysesAdapter.submit(entries)
        }
    }

    /**
     * Why the cloud half is missing, or null when it answered. Silence would be
     * wrong here: without this line a backed-up analysis looks phone-only, and
     * the user would read that as "my backup is gone".
     */
    private fun cloudStateMessage(result: CloudRestore.ListResult): String? = when (result) {
        is CloudRestore.ListResult.Ready, CloudRestore.ListResult.Empty -> null
        CloudRestore.ListResult.NeedSignIn -> getString(R.string.restore_need_sign_in)
        CloudRestore.ListResult.ApiOff -> getString(R.string.restore_api_off)
        is CloudRestore.ListResult.Failed -> getString(R.string.restore_load_error, result.reason)
    }

    /**
     * Open when the phone already has results; otherwise offer cloud restore when a
     * backup is attached to this management row.
     */
    private fun openOrDownloadAnalysis(entry: AnalysisEntry) {
        val record = entry.record
        if (record?.hasLocalData() == true) {
            SessionOpenHelper.openOrExplain(this, record)
            return
        }
        if (entry.cloud != null) {
            confirmCloudRestore(entry)
            return
        }
        if (record != null) {
            SessionOpenHelper.openOrExplain(this, record)
        }
    }

    private fun confirmLocalDownload(entry: AnalysisEntry) {
        if (!entry.offersDownload()) return
        val key = entry.downloadKey()
        if (key in downloadingKeys || isBundleDownloadRunning(key) || isRestoreWorkRunning(key)) {
            Toast.makeText(this, R.string.download_analysis_already, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, R.string.download_analysis_failed, Toast.LENGTH_LONG).show()
            return
        }
        val key = pending.cloudSessionId
        if (key in downloadingKeys || isBundleDownloadRunning(key)) {
            Toast.makeText(this, R.string.download_analysis_already, Toast.LENGTH_SHORT).show()
            return
        }
        val granted = runCatching {
            contentResolver.takePersistableUriPermission(
                destUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Timber.e(it, "Could not persist write grant for %s", destUri) }
            .isSuccess
        if (!granted) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
            return
        }
        val enqueued = runCatching {
            CloudRestore.enqueueBundleDownload(
                this,
                pending.cloudSessionId,
                pending.displayName,
                destUri = destUri.toString(),
                localSessionId = pending.localSessionId,
            )
        }.onFailure { Timber.e(it, "Could not enqueue bundle download %s", pending.cloudSessionId) }
            .isSuccess
        if (!enqueued) {
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    destUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            Toast.makeText(this, R.string.download_analysis_failed, Toast.LENGTH_LONG).show()
            return
        }
        markDownloading(key, true)
        transferBanner.upsert(
            TransferBannerController.Transfer(
                id = key,
                title = pending.displayName.ifBlank { getString(R.string.transfer_banner_download) },
                onCancel = { CloudRestore.cancelBundleDownload(this, pending.cloudSessionId) },
            ),
        )
        Toast.makeText(this, R.string.download_background_note, Toast.LENGTH_SHORT).show()
    }

    private fun confirmCloudRestore(entry: AnalysisEntry) {
        if (!entry.offersRestore()) return
        val key = entry.downloadKey()
        if (key in downloadingKeys || isBundleDownloadRunning(key) || isRestoreWorkRunning(key)) {
            markDownloading(key, true)
            Toast.makeText(this, R.string.download_analysis_already, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_analysis_title)
            .setMessage(R.string.download_analysis_body)
            .setPositiveButton(R.string.restore_action) { _, _ ->
                restoreBackup(entry)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun restoreBackup(entry: AnalysisEntry) {
        val cloud = entry.cloud ?: return
        val key = entry.downloadKey()
        if (isRestoreWorkRunning(cloud.sessionId) || key in downloadingKeys) {
            markDownloading(key, true)
            Toast.makeText(this, R.string.download_analysis_already, Toast.LENGTH_SHORT).show()
            return
        }
        // Prefer the existing phone row id so a freed stub / re-download fills
        // in-place rather than creating a second "restored-…" id.
        val targetLocalId = entry.record?.id ?: CloudRestore.targetLocalId(cloud)
        lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) {
                enqueueRestoreWithStub(entry, cloud, targetLocalId)
            }
            if (!started) {
                Toast.makeText(this@SettingsActivity, R.string.restore_failed_generic, Toast.LENGTH_LONG).show()
                return@launch
            }
            markDownloading(key, true)
            transferBanner.upsert(
                TransferBannerController.Transfer(
                    id = key,
                    title = entry.name.ifBlank { getString(R.string.transfer_banner_restore) },
                    cancellable = false,
                ),
            )
            Toast.makeText(this@SettingsActivity, R.string.restore_background_note, Toast.LENGTH_SHORT).show()
            wireAnalysesDataSection()
        }
    }

    private fun isRestoreWorkRunning(cloudSessionId: String): Boolean {
        if (cloudSessionId.isBlank()) return false
        val wm = runCatching { WorkManager.getInstance(this) }.getOrNull()
        return wm != null &&
            runCatching {
                wm.getWorkInfosForUniqueWork(CloudRestore.workName(cloudSessionId)).get()
                    .any { !it.state.isFinished }
            }.getOrDefault(false)
    }

    private fun isBundleDownloadRunning(cloudSessionId: String): Boolean {
        if (cloudSessionId.isBlank()) return false
        val wm = runCatching { WorkManager.getInstance(this) }.getOrNull()
        return wm != null &&
            runCatching {
                wm.getWorkInfosForUniqueWork(CloudRestore.bundleDownloadWorkName(cloudSessionId)).get()
                    .any { !it.state.isFinished }
            }.getOrDefault(false)
    }

    private fun markDownloading(key: String, busy: Boolean) {
        if (busy) downloadingKeys.add(key) else downloadingKeys.remove(key)
        analysesAdapter.setDownloadingKeys(downloadingKeys.toSet())
    }

    /** Drop finished restore / download keys. */
    private fun syncDownloadingKeys() {
        val next = mutableSetOf<String>()
        downloadingKeys.filter { isRestoreWorkRunning(it) || isBundleDownloadRunning(it) }.forEach { next += it }
        downloadingKeys.clear()
        downloadingKeys.addAll(next)
        analysesAdapter.setDownloadingKeys(downloadingKeys.toSet())
    }

    @Suppress("ReturnCount")
    private fun enqueueRestoreWithStub(
        entry: AnalysisEntry,
        cloud: CloudSessionDto,
        targetLocalId: String,
    ): Boolean {
        val existing = SessionStore.get(this, targetLocalId)
        // Restore is only offered when local frames are missing.
        if (existing?.hasLocalData() == true) return false
        val stub = restoreStub(entry, cloud, targetLocalId, existing)
        if (!SessionStore.upsert(this, stub, allowOverLimit = true)) return false
        return try {
            CloudRestore.enqueueRestore(this, cloud.sessionId, targetLocalId)
            true
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.e(e, "Could not enqueue restore %s", cloud.sessionId)
            if (existing == null) {
                SessionStore.delete(this, targetLocalId)
            } else {
                SessionStore.upsert(this, existing, allowOverLimit = true)
            }
            false
        }
    }

    private fun restoreStub(
        entry: AnalysisEntry,
        cloud: CloudSessionDto,
        targetLocalId: String,
        existing: SessionRecord?,
    ): SessionRecord {
        val now = System.currentTimeMillis()
        return existing?.copy(
            name = existing.name.ifBlank { entry.name },
            updatedAt = now,
            cloudSessionId = cloud.sessionId,
            syncState = SessionRecord.SyncState.SYNCED,
        ) ?: SessionRecord(
            id = targetLocalId,
            name = entry.name,
            createdAt = now,
            updatedAt = now,
            frameCount = 0,
            subset = 0,
            step = 0,
            strainWindow = 0,
            imgW = 0,
            imgH = 0,
            roiX = 0,
            roiY = 0,
            roiW = 0,
            roiH = 0,
            refPath = "",
            refName = "",
            sessionDir = SessionStore.dirFor(this, targetLocalId).absolutePath,
            cloudSessionId = cloud.sessionId,
            syncState = SessionRecord.SyncState.SYNCED,
        )
    }

    /**
     * A restore runs in [com.indicvision.semper.data.DicRestoreWorker], so without
     * this its outcome would be silent — the user taps Restore, sees "continues in
     * background", and is never told if it failed (backup gone / not theirs / gave
     * up). Watch the "restore" work tag and surface each terminal outcome once:
     * failure with its reason, success with a confirmation + a refreshed list.
     */
    private fun observeRestoreOutcomes() {
        // Best-effort: WorkManager is always initialized in production (its startup
        // provider runs before any Activity), but not in a unit-test harness that
        // skips that provider. Missing WorkManager must not crash onCreate — and if
        // it were truly absent, restore couldn't be enqueued in the first place.
        val workManager = runCatching { WorkManager.getInstance(this) }.getOrNull() ?: return
        workManager
            .getWorkInfosByTagLiveData("restore")
            .observe(this) { infos ->
                infos.orEmpty().forEach { info ->
                    val cloudId = info.tags.firstOrNull { it.startsWith("restore-") }
                        ?.removePrefix("restore-")
                        ?: info.outputData.getString(CloudRestore.KEY_CLOUD_SESSION_ID)
                        ?: info.progress.getString(CloudRestore.KEY_CLOUD_SESSION_ID)
                    val key = cloudId.orEmpty()
                    when (info.state) {
                        WorkInfo.State.RUNNING -> {
                            if (key.isNotBlank()) {
                                val pct = info.progress.getInt(com.indicvision.semper.DicKeys.UPLOAD_PERCENT, 0)
                                if (!transferBanner.contains(key)) {
                                    transferBanner.upsert(
                                        TransferBannerController.Transfer(
                                            id = key,
                                            title = getString(R.string.transfer_banner_restore),
                                            cancellable = false,
                                            percent = pct,
                                        ),
                                    )
                                } else {
                                    transferBanner.updateProgress(key, pct)
                                }
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            if (key.isNotBlank()) transferBanner.remove(key)
                            if (shownRestoreOutcomes.add(info.id)) {
                                syncDownloadingKeys()
                                val reason = info.outputData.getString(DicRestoreWorker.KEY_ERROR)
                                    ?: getString(R.string.restore_failed_generic)
                                Snackbar.make(
                                    findViewById(android.R.id.content),
                                    getString(R.string.restore_failed_fmt, reason),
                                    Snackbar.LENGTH_LONG,
                                ).show()
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            if (key.isNotBlank()) transferBanner.remove(key)
                            // Silent on purpose (uploads don't toast success either): just
                            // refresh so the restored session appears. Deduped so a retained
                            // old success doesn't reload on every screen open.
                            if (shownRestoreOutcomes.add(info.id)) {
                                syncDownloadingKeys()
                                wireAnalysesDataSection()
                            }
                        }
                        WorkInfo.State.CANCELLED -> {
                            if (key.isNotBlank()) transferBanner.remove(key)
                            syncDownloadingKeys()
                        }
                        else -> Unit
                    }
                }
            }
    }

    /**
     * Save-to-Files downloads run in [DicBundleDownloadWorker]. Observe the
     * tag so progress survives leaving Analyses data management, and so a
     * finished write still toasts success when the user returns.
     */
    private fun observeBundleDownloadOutcomes() {
        val workManager = runCatching { WorkManager.getInstance(this) }.getOrNull() ?: return
        workManager
            .getWorkInfosByTagLiveData(CloudRestore.TAG_BUNDLE_DOWNLOAD)
            .observe(this) { infos ->
                infos.orEmpty().forEach { info ->
                    val cloudId = info.tags
                        .firstOrNull { it.startsWith("${CloudRestore.TAG_BUNDLE_DOWNLOAD}-") }
                        ?.removePrefix("${CloudRestore.TAG_BUNDLE_DOWNLOAD}-")
                        ?: info.outputData.getString(CloudRestore.KEY_CLOUD_SESSION_ID)
                    val key = cloudId.orEmpty()
                    when (info.state) {
                        WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                            if (key.isNotBlank()) {
                                val pct = info.progress.getInt(com.indicvision.semper.DicKeys.UPLOAD_PERCENT, 0)
                                if (!transferBanner.contains(key)) {
                                    transferBanner.upsert(
                                        TransferBannerController.Transfer(
                                            id = key,
                                            title = getString(R.string.transfer_banner_download),
                                            percent = pct,
                                            onCancel = {
                                                CloudRestore.cancelBundleDownload(this, key)
                                            },
                                        ),
                                    )
                                } else if (info.state == WorkInfo.State.RUNNING) {
                                    transferBanner.updateProgress(key, pct)
                                }
                                markDownloading(key, true)
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            if (key.isNotBlank()) transferBanner.remove(key)
                            if (presentedBundleDownloads.add(info.id)) {
                                syncDownloadingKeys()
                                Toast.makeText(
                                    this,
                                    R.string.download_analysis_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            if (key.isNotBlank()) transferBanner.remove(key)
                            if (presentedBundleDownloads.add(info.id)) {
                                syncDownloadingKeys()
                                Toast.makeText(this, R.string.save_success, Toast.LENGTH_LONG).show()
                            }
                        }
                        WorkInfo.State.CANCELLED -> {
                            if (key.isNotBlank()) transferBanner.remove(key)
                            syncDownloadingKeys()
                        }
                    }
                }
            }
    }

    private fun deleteBackup(entry: AnalysisEntry, row: View) {
        val cloud = entry.cloud ?: return
        val record = entry.record
        if (record != null) {
            showDeleteBackupChoice(record, cloud, row)
        } else {
            confirmDeleteCloudBackup(cloud, entry.name, row)
        }
    }

    /** Which backup action a row offers, or null when none applies. */
    private fun backupLabel(entry: AnalysisEntry): Int? {
        val record = entry.record ?: return null
        return when (record.syncState) {
            SessionRecord.SyncState.FAILED, SessionRecord.SyncState.PENDING -> R.string.cloud_retry_backup
            SessionRecord.SyncState.LOCAL_ONLY ->
                if (DicSettings.saveToCloud(this)) R.string.cloud_backup_now else null
            // Backed up, but this run could not list the cloud: offer nothing
            // rather than a "back up" that would duplicate an existing copy.
            SessionRecord.SyncState.SYNCED -> null
        }
    }

    private fun startBackup(entry: AnalysisEntry) {
        val record = entry.record ?: return
        val label = backupLabel(entry) ?: return
        SessionStore.setSyncState(this, record.id, SessionRecord.SyncState.PENDING)
        CloudSync.enqueueUpload(this, record.id)
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        wireAnalysesDataSection()
    }

    private fun stateLine(entry: AnalysisEntry): String = when (entry.location) {
        AnalysisLocation.CLOUD_ONLY ->
            getString(R.string.analysis_state_cloud_only_fmt, humanSize(entry.cloud?.totalBytes ?: 0L))
        AnalysisLocation.PHONE_AND_CLOUD ->
            getString(R.string.analysis_state_phone_and_cloud_fmt, humanSize(entry.cloud?.totalBytes ?: 0L))
        AnalysisLocation.PHONE_ONLY ->
            if (entry.localBytes > 0) {
                getString(R.string.analysis_state_on_phone_fmt, humanSize(entry.localBytes))
            } else {
                getString(R.string.analysis_state_phone_only)
            }
        // The cloud could not confirm this one: keep its badge rather than
        // claiming the backup is gone.
        AnalysisLocation.PHONE_SYNC_STATE ->
            entry.record?.let { syncLabel(it.syncState) }.orEmpty()
    }

    // ── Deletion, with an undo window ────────────────────────────────────

    /**
     * Deleting a cloud backup is irreversible — there is no trash on the
     * backend — so the confirm spells that out before anything is scheduled.
     */
    private fun confirmDeleteCloudBackup(session: CloudSessionDto, name: String, row: View) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_delete_forever_title)
            .setMessage(getString(R.string.cloud_delete_forever_body, name))
            .setPositiveButton(R.string.cloud_delete_forever_confirm) { _, _ ->
                scheduleDelete(row, session.sessionId, session.localSessionId, alsoLocal = false)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDeleteBackupChoice(record: SessionRecord, cloud: CloudSessionDto, row: View) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_delete_backup_title)
            .setMessage(R.string.cloud_delete_backup_body)
            // Addressed by backend id: this row only exists because the cloud
            // listed that backup, so no lookup is needed.
            .setPositiveButton(R.string.cloud_delete_backup_only) { _, _ ->
                scheduleDelete(row, cloud.sessionId, record.id, alsoLocal = false)
            }
            .setNeutralButton(R.string.cloud_delete_backup_and_local) { _, _ ->
                scheduleDelete(row, cloud.sessionId, record.id, alsoLocal = true)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * The safety net: the row goes at once, but the deletion sits in
     * [BackupDeleteWorker] for the undo window and only then reaches the
     * backend — so a mis-tapped bin followed by a reflexive confirm is still
     * recoverable. Leaving the page (or the app) does not abandon it: the user
     * confirmed, and the worker retries if the network is down.
     */
    private fun scheduleDelete(row: View, cloudSessionId: String, localSessionId: String, alsoLocal: Boolean) {
        analysesAdapter.removeAt(analysesList.getChildAdapterPosition(row))
        BackupDeleteWorker.enqueue(this, cloudSessionId, localSessionId, alsoLocal)
        Snackbar.make(findViewById(R.id.settingsRoot), R.string.cloud_delete_pending, UNDO_WINDOW_MS)
            .setAction(R.string.action_undo) {
                BackupDeleteWorker.cancel(this, cloudSessionId)
                wireAnalysesDataSection()
            }
            .show()
    }

    // Host helpers used by extracted sections.

    internal fun launchReauth() {
        reauthLauncher.launch(AuthActivity.reauthIntent(this))
    }

    internal fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** Opens a https URL in the browser; toast if nothing can handle it. */
    internal fun openExternalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No browser to open %s", url)
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    private fun wireFooter() {
        findViewById<View>(R.id.btnAbout).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(
                    getString(
                        R.string.about_message,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                )
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.legal_privacy) { _, _ ->
                    openExternalUrl(getString(R.string.legal_privacy_url))
                }
                .setNegativeButton(R.string.legal_terms) { _, _ ->
                    openExternalUrl(getString(R.string.legal_terms_url))
                }
                .show()
        }
        findViewById<View>(R.id.btnSignOut).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.logout_confirm_title)
                .setMessage(R.string.logout_confirm_body)
                .setPositiveButton(R.string.action_sign_out) { _, _ ->
                    AuthRepository(this).signOut()
                    AuthRoute.toSignIn(this)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    // ── Formatting ───────────────────────────────────────────────────────

    private fun syncLabel(state: SessionRecord.SyncState): String = when (state) {
        SessionRecord.SyncState.SYNCED -> getString(R.string.badge_synced)
        SessionRecord.SyncState.PENDING -> getString(R.string.badge_pending)
        SessionRecord.SyncState.LOCAL_ONLY -> getString(R.string.badge_local)
        SessionRecord.SyncState.FAILED -> getString(R.string.badge_not_backed_up)
    }

    internal fun humanSize(bytes: Long): String = when {
        bytes >= BYTES_PER_GB -> String.format(Locale.US, "%.1f GB", bytes / BYTES_PER_GB.toDouble())
        bytes >= BYTES_PER_MB -> String.format(Locale.US, "%.0f MB", bytes / BYTES_PER_MB.toDouble())
        else -> String.format(Locale.US, "%.0f KB", bytes / BYTES_PER_KB)
    }

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

        /** Work ids whose Save-to-Files outcome was already shown (process-wide). */
        private val presentedBundleDownloads =
            java.util.Collections.synchronizedSet(mutableSetOf<java.util.UUID>())

        const val CHEVRON_EXPANDED_DEG = 180f
        const val BYTES_PER_KB = 1024.0
        const val BYTES_PER_MB = 1_048_576L
        const val BYTES_PER_GB = 1_073_741_824L
        const val ZIP_MIME = "application/zip"
        const val JSON_MIME = "application/json"
        const val PERCENT_MAX = 100

        /** Snackbar shows for exactly as long as the delete stays cancellable. */
        val UNDO_WINDOW_MS = TimeUnit.SECONDS.toMillis(BackupDeleteWorker.UNDO_WINDOW_SECONDS).toInt()
    }
}
