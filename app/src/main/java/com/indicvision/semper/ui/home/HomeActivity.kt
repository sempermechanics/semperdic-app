// Home screen wires many list/menu/callback bindings in onCreate; kept together
// for locality, so LongMethod / TooManyFunctions are suppressed for this file.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.indicvision.semper.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.indicvision.semper.Diagnostics
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.CloudRestore
import com.indicvision.semper.data.CloudSync
import com.indicvision.semper.data.CoachPrefs
import com.indicvision.semper.data.DicRestoreWorker
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import com.indicvision.semper.ui.common.CoachMarkController
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.MediaSourceChooser
import com.indicvision.semper.ui.limit.SessionLimitActivity
import com.indicvision.semper.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home: the record of every analysis done on this phone (metadata from
 * [SessionStore]; heavy files per session dir, full copies in the cloud once
 * synced). The + button is the single entry point for a new analysis — it
 * opens the system media picker, and the selection type (image vs video)
 * decides the next screen. The gear opens the behavioral settings drawer.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var emptyState: android.view.View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: SessionListAdapter
    private lateinit var selection: SessionSelectionController
    private lateinit var fab: FloatingActionButton
    private lateinit var tvHomeQuota: TextView

    /** Upload WorkInfo ids already surfaced, so one failure isn't snackbar-spammed. */
    private val shownUploadFailures = mutableSetOf<java.util.UUID>()

    /** Upload WorkInfo ids already refreshed on success, so we refresh once each. */
    private val shownSucceededUploads = mutableSetOf<java.util.UUID>()

    private val shownRestoreOutcomes = mutableSetOf<java.util.UUID>()
    private var activeUploadProgress: Map<String, SessionListAdapter.RowProgress> = emptyMap()
    private var activeRestoreProgress: Map<String, SessionListAdapter.RowProgress> = emptyMap()

    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = selection.clearSelection()
    }

    /** Confirm before leaving Home (and the app). Selection-mode back is separate. */
    private val exitAppCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            MaterialAlertDialogBuilder(this@HomeActivity)
                .setTitle(R.string.exit_indic_title)
                .setMessage(R.string.exit_indic_message)
                .setPositiveButton(R.string.exit) { _, _ -> finish() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Source A: the system Photo Picker (gallery / Google Photos). */
    private val pickReference =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            routePickedMedia(uri)
        }

    /** Source B: the Storage Access Framework (Downloads, Drive, on-device files). */
    private val pickDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            routePickedMedia(uri)
        }

    /**
     * Route a picked photo/video into the analysis screen. Shared by both source
     * pickers so the two entry points behave identically; the mime type decides
     * whether we hand off a single reference image or a video to sample frames
     * from.
     */
    private fun routePickedMedia(uri: android.net.Uri?) {
        if (uri == null) return
        val mime = contentResolver.getType(uri) ?: ""
        val intent = Intent(this, StaticAnalysisActivity::class.java)
        if (mime.startsWith("video/")) {
            intent.putExtra(DicKeys.PICKED_VIDEO_URI, uri.toString())
        } else {
            intent.putExtra(DicKeys.PICKED_REF_URI, uri.toString())
        }
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Edge-to-edge (enforced on API 35+): drop the header below the status
        // bar, otherwise the bar swallows taps on the settings gear. The
        // selection bar replaces the title row, so it needs the same inset.
        Insets.padTop(findViewById(R.id.homeTopBar))
        Insets.padTop(findViewById(R.id.homeSelectionBar))

        list = findViewById(R.id.sessionList)
        emptyState = findViewById(R.id.emptyState)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        tvHomeQuota = findViewById(R.id.tvHomeQuota)
        swipeRefresh.setColorSchemeResources(R.color.sky_primary)
        // Pull down = deep re-check: verify the blobs really exist in Drive,
        // not just that the backend's index says so.
        swipeRefresh.setOnRefreshListener { refresh(deep = true) }
        list.layoutManager = LinearLayoutManager(this)

        fab = findViewById(R.id.fabNewAnalysis)
        positionFabAtThreeQuarters()
        fab.setOnClickListener {
            // At the account's analysis limit, block new work behind the persistent
            // limit screen (email support) instead of letting it fail on upload.
            if (TokenStore.isSessionLimitReached(this)) {
                openSessionLimitScreen()
                return@setOnClickListener
            }
            showSourceChooser()
        }
        findViewById<ImageButton>(R.id.btnHomeSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnEmptyRestore).setOnClickListener {
            findViewById<ImageButton>(R.id.btnHomeSettings).performClick()
        }

        // Consent first, then the coach mark — two overlays at once is noise, and
        // the diagnostics choice must be made before anything is collected.
        maybeAskDiagnostics {
            fab.post {
                CoachMarkController(this).maybeShow(
                    CoachPrefs.Screen.HOME,
                    listOf(
                        CoachMarkController.Step(
                            fab,
                            getString(R.string.coach_home_fab),
                        ),
                    ),
                )
            }
        }

        // Adapter callbacks close over selection; both must exist before the
        // list attaches so a early bind cannot hit an uninitialized controller.
        adapter = SessionListAdapter(
            isSelected = { id -> selection.isSelected(id) },
            onClick = { record ->
                if (selection.inSelectionMode) {
                    selection.toggleSelection(record)
                } else {
                    openSession(record)
                }
            },
            onLongClick = { record ->
                if (selection.inSelectionMode) {
                    selection.toggleSelection(record)
                } else {
                    selection.startSelection(record)
                }
            },
            onBadgeClick = { record -> retryOrBackup(record) },
        )
        selection = SessionSelectionController(
            activity = this,
            adapter = adapter,
            topBar = findViewById(R.id.homeTopBar),
            selectionBar = findViewById(R.id.homeSelectionBar),
            selectionCount = findViewById(R.id.tvSelectionCount),
            btnSelectionRename = findViewById(R.id.btnSelectionRename),
            selectAllBox = findViewById(R.id.cbSelectionAll),
            fab = fab,
            backCallback = backCallback,
            onRefresh = { refresh() },
            onDeviceOnlyDeleted = { showDeviceOnlyKeptSnackbar() },
        )
        selection.bindBarActions(
            btnClose = findViewById(R.id.btnSelectionClose),
            btnDelete = findViewById(R.id.btnSelectionDelete),
        )
        list.adapter = adapter

        // Exit confirm is always registered; selection back is layered on top and
        // enabled only while something is selected (LIFO: last added runs first).
        onBackPressedDispatcher.addCallback(this, exitAppCallback)
        onBackPressedDispatcher.addCallback(this, backCallback)

        maybeShowBetaNotice()
        // Cold start / return with an already-full quota → persistent support screen.
        lifecycleScope.launch {
            val localCount = withContext(Dispatchers.IO) {
                SessionStore.list(this@HomeActivity).size
            }
            TokenStore.refreshSessionLimit(this@HomeActivity, localCount)
            if (TokenStore.isSessionLimitReached(this@HomeActivity)) openSessionLimitScreen()
        }

        observeUploadFailures()
        observeRestoreProgress()
    }

    /**
     * Background uploads run in WorkManager, so a failure would otherwise be
     * silent (only the row badge changed). Watch the "upload" work tag and, when
     * a run ends in a terminal failure carrying a reason, tell the user with a
     * Retry action. Quota-full is excluded — it has its own persistent screen and
     * returns no reason.
     */
    private fun observeUploadFailures() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData("upload")
            .observe(this) { infos ->
                val list = infos.orEmpty()

                // Live per-row progress from every running backup.
                activeUploadProgress = list
                    .filter { it.state == WorkInfo.State.RUNNING }
                    .mapNotNull { info ->
                        val id = info.progress.getString(DicKeys.SESSION_LOCAL_ID) ?: return@mapNotNull null
                        val pct = info.progress.getInt(DicKeys.UPLOAD_PERCENT, -1)
                        if (pct < 0) return@mapNotNull null
                        val phase = info.progress.getString(DicKeys.UPLOAD_PHASE) ?: "upload"
                        id to SessionListAdapter.RowProgress(phase, pct)
                    }
                    .toMap()
                publishRowProgress()

                list.forEach { info ->
                    when (info.state) {
                        // A finished backup — flip the row's badge to "synced".
                        WorkInfo.State.SUCCEEDED -> if (shownSucceededUploads.add(info.id)) refresh()
                        WorkInfo.State.FAILED -> {
                            if (!shownUploadFailures.add(info.id)) return@forEach
                            val reason = info.outputData.getString(DicKeys.UPLOAD_FAIL_REASON)
                                ?: return@forEach // no reason = handled elsewhere (e.g. quota)
                            showUploadFailure(reason)
                        }
                        else -> Unit
                    }
                }
            }
    }

    private fun observeRestoreProgress() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData("restore")
            .observe(this) { infos ->
                val list = infos.orEmpty()
                activeRestoreProgress = list
                    .filter { it.state == WorkInfo.State.RUNNING }
                    .mapNotNull { info ->
                        val id = info.progress.getString(DicKeys.SESSION_LOCAL_ID) ?: return@mapNotNull null
                        val pct = info.progress.getInt(DicKeys.UPLOAD_PERCENT, -1)
                        if (pct < 0) return@mapNotNull null
                        id to SessionListAdapter.RowProgress(DicRestoreWorker.PHASE_DOWNLOAD, pct)
                    }
                    .toMap()
                publishRowProgress()

                list.forEach { info ->
                    when (info.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            if (shownRestoreOutcomes.add(info.id)) refresh()
                        }
                        WorkInfo.State.FAILED -> {
                            if (!shownRestoreOutcomes.add(info.id)) return@forEach
                            val reason = info.outputData.getString(DicRestoreWorker.KEY_ERROR)
                                ?: getString(R.string.restore_failed_generic)
                            Snackbar.make(
                                findViewById(R.id.homeRoot),
                                getString(R.string.restore_failed_fmt, reason),
                                Snackbar.LENGTH_LONG,
                            ).show()
                            refresh()
                        }
                        WorkInfo.State.CANCELLED -> {
                            if (shownRestoreOutcomes.add(info.id)) refresh()
                        }
                        else -> Unit
                    }
                }
            }
    }

    private fun publishRowProgress() {
        adapter.setUploadProgress(activeUploadProgress + activeRestoreProgress)
    }

    /**
     * Informative only — every reason that reaches here is terminal (device
     * conflict, too large, render OOM), so a one-tap Retry would just re-fail.
     * The badge remains the place to deliberately re-attempt (see [retryOrBackup]).
     */
    private fun showUploadFailure(reason: String) {
        Snackbar.make(
            findViewById(R.id.homeRoot),
            getString(R.string.cloud_backup_failed_fmt, reason),
            Snackbar.LENGTH_LONG,
        ).show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** One-time beta / data-use declaration after the account first reaches Home. */
    private fun maybeShowBetaNotice() {
        if (TokenStore.hasAckedBetaNotice(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.beta_notice_title)
            .setMessage(R.string.beta_notice_body)
            .setCancelable(false)
            .setPositiveButton(R.string.beta_notice_ack) { _, _ ->
                TokenStore.setBetaNoticeAcked(this)
            }
            .show()
    }

    /**
     * First-run diagnostics choice, then [next].
     *
     * Crashlytics and Analytics are disabled in the manifest, so nothing has been
     * collected before this point — the app previously started reporting on first
     * launch with no notice and no way to decline. Asked once: a "Not now" is
     * recorded, so this does not nag, and the toggle stays in Settings.
     */
    private fun maybeAskDiagnostics(next: () -> Unit) {
        if (DicSettings.diagnosticsAsked(this)) {
            next()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.diagnostics_prompt_title)
            .setMessage(R.string.diagnostics_prompt_body)
            .setPositiveButton(R.string.diagnostics_prompt_accept) { _, _ ->
                Diagnostics.setEnabled(this, true)
            }
            .setNegativeButton(R.string.diagnostics_prompt_decline) { _, _ ->
                Diagnostics.setEnabled(this, false)
            }
            .setCancelable(false)
            .setOnDismissListener { next() }
            .show()
    }

    private fun openSessionLimitScreen() {
        startActivity(Intent(this, SessionLimitActivity::class.java))
    }

    /**
     * Ask where to pick the reference from, in a styled sheet we control, before
     * opening the system picker. The system Photo Picker runs in its own window
     * and can't be labelled or overlaid, so the instruction and source choice
     * live here instead — Photos routes to the Photo Picker, Files to the Storage
     * Access Framework (Downloads, Drive, on-device storage).
     */
    private fun showSourceChooser() = MediaSourceChooser.show(
        activity = this,
        titleRes = R.string.new_analysis_title,
        captionRes = R.string.picker_select_reference,
        onPhotos = {
            pickReference.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        },
        onFiles = { pickDocument.launch(arrayOf("image/*", "video/*")) },
    )

    /**
     * @param deep verify blobs really exist in Drive (pull-to-refresh) rather
     *   than trusting the backend index (cheap resume check).
     */
    private fun refresh(deep: Boolean = false) {
        lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) { SessionStore.list(this@HomeActivity) }
            val cloudOnly = withContext(Dispatchers.IO) {
                sessions.filter {
                    it.syncState == SessionRecord.SyncState.SYNCED && !it.hasLocalData()
                }.map { it.id }.toSet()
            }
            adapter.submit(sessions)
            adapter.setCloudOnlyIds(cloudOnly)
            emptyState.isVisible = sessions.isEmpty()
            updateQuotaIndicator(sessions.size)
            // A refresh can drop rows out from under a selection.
            selection.updateSelectionBar()
            // Local count alone can trip the hard-stop flag (before cloud reconcile).
            TokenStore.refreshSessionLimit(this@HomeActivity, sessions.size)
            try {
                reconcileWithCloud(deep)
            } finally {
                // The spinner tracks the cloud check, not the local list read —
                // that's the part worth waiting for.
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun updateQuotaIndicator(localSessionCount: Int) {
        val max = TokenStore.effectiveQuotaMax(this)
        val used = TokenStore.quotaUsed(this).coerceAtLeast(localSessionCount)
        if (max <= 0) {
            if (AppRemoteConfig.shouldHintSyncBlocked(this) && IndicApi.get(this).enabled) {
                tvHomeQuota.isVisible = true
                tvHomeQuota.text = getString(R.string.home_sync_config_unavailable)
            } else {
                tvHomeQuota.isVisible = false
            }
            return
        }
        tvHomeQuota.isVisible = true
        tvHomeQuota.text = resources.getQuantityString(R.plurals.home_quota_fmt, used, used, max)
        tvHomeQuota.setTextColor(
            getColor(
                if (used >= max) R.color.semantic_danger else R.color.text_secondary,
            ),
        )
        tvHomeQuota.setOnClickListener {
            if (TokenStore.isSessionLimitReached(this)) {
                openSessionLimitScreen()
            } else {
                findViewById<ImageButton>(R.id.btnHomeSettings).performClick()
            }
        }
    }

    /**
     * Ask the backend what is actually backed up and repair any drift — a
     * session whose cloud copy was deleted stops claiming "Synced" and is
     * re-queued for upload.
     *
     * Offline and "not configured" stay silent (that's normal for an
     * offline-first app), but a real backend fault is surfaced: otherwise the
     * badges quietly go stale and the user trusts a backup that isn't there.
     */
    private suspend fun reconcileWithCloud(deep: Boolean) {
        when (val outcome = CloudSync.reconcile(this@HomeActivity, deep = deep)) {
            is CloudSync.Outcome.Ok -> {
                // Record the account's quota so the new-analysis gate and the
                // limit screen reflect the latest server truth.
                val wasLimited = TokenStore.isSessionLimitReached(this)
                val localCount = withContext(Dispatchers.IO) { SessionStore.list(this@HomeActivity).size }
                // Ceiling is owned by AppRemoteConfig (refreshed by the same
                // reconcile's config fetch); only the used count is stored here.
                TokenStore.setQuota(this, outcome.quotaUsed, localCount)
                // Newly at the cap → open the persistent "email support" screen.
                if (!wasLimited && TokenStore.isSessionLimitReached(this)) {
                    openSessionLimitScreen()
                }
                if (outcome.repaired > 0) {
                    // The rows changed underneath us — show the corrected state.
                    val sessions = withContext(Dispatchers.IO) { SessionStore.list(this@HomeActivity) }
                    adapter.submit(sessions)
                    Toast.makeText(
                        this,
                        resources.getQuantityString(R.plurals.cloud_resync_fmt, outcome.repaired, outcome.repaired),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            is CloudSync.Outcome.Failed ->
                Toast.makeText(
                    this,
                    getString(R.string.cloud_check_failed_fmt, outcome.reason),
                    Toast.LENGTH_LONG,
                ).show()
            // Normal for an offline-first app — don't nag. Skipped = checked
            // recently (reconcile is throttled to protect the Firestore budget).
            CloudSync.Outcome.Offline, CloudSync.Outcome.Disabled, CloudSync.Outcome.Skipped -> Unit
        }
    }

    // ── Row actions ──────────────────────────────────────────────────────

    private fun openSession(record: SessionRecord) {
        if (record.hasLocalData()) {
            startActivity(SessionOpenHelper.intentFor(this, record))
            return
        }
        val hasCloud = record.syncState == SessionRecord.SyncState.SYNCED ||
            record.cloudSessionId.isNotBlank()
        if (!hasCloud) {
            SessionOpenHelper.openOrExplain(this, record)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_analysis_title)
            .setMessage(R.string.download_analysis_body)
            .setPositiveButton(R.string.download_analysis_confirm) { _, _ ->
                enqueueDownload(record)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Queue a background restore and stay on Home. Row progress comes from
     * [observeRestoreProgress] (same badge/bar as uploads) so the list stays
     * interactive — no blocking "Downloading…" dialog.
     */
    private fun enqueueDownload(record: SessionRecord) {
        selection.clearSelection()
        lifecycleScope.launch {
            val cloudId = CloudSync.resolveCloudIdFor(this@HomeActivity, record)
            if (cloudId.isNullOrBlank()) {
                Toast.makeText(this@HomeActivity, R.string.download_analysis_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            CloudRestore.enqueueRestore(this@HomeActivity, cloudId, record.id)
            Toast.makeText(this@HomeActivity, R.string.restore_background_note, Toast.LENGTH_SHORT).show()
        }
    }

    /** Retry a failed/pending upload, or back up a local-only session when cloud is on. */
    private fun retryOrBackup(record: SessionRecord) {
        when (record.syncState) {
            // A terminal failure: explain why (from the retained WorkInfo) before
            // offering a deliberate retry, instead of silently re-queuing a doomed
            // upload every tap.
            SessionRecord.SyncState.FAILED -> showFailedBackupDialog(record)
            SessionRecord.SyncState.PENDING -> enqueueBackup(record, R.string.cloud_retry_backup)
            SessionRecord.SyncState.LOCAL_ONLY -> if (DicSettings.saveToCloud(this)) {
                enqueueBackup(record, R.string.cloud_backup_now)
            } else {
                findViewById<ImageButton>(R.id.btnHomeSettings).performClick()
            }
            SessionRecord.SyncState.SYNCED -> findViewById<ImageButton>(R.id.btnHomeSettings).performClick()
        }
    }

    private fun enqueueBackup(record: SessionRecord, toastRes: Int) {
        SessionStore.setSyncState(this, record.id, SessionRecord.SyncState.PENDING)
        CloudSync.enqueueUpload(this, record.id)
        adapter.rebindRow(record.id)
        Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show()
    }

    private fun showFailedBackupDialog(record: SessionRecord) {
        lifecycleScope.launch {
            val reason = withContext(Dispatchers.IO) { lastUploadFailureReason(record.id) }
            MaterialAlertDialogBuilder(this@HomeActivity)
                .setTitle(R.string.cloud_backup_failed_title)
                .setMessage(reason ?: getString(R.string.cloud_backup_failed_generic))
                .setPositiveButton(R.string.cloud_backup_retry_action) { _, _ ->
                    enqueueBackup(record, R.string.cloud_retry_backup)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** The reason attached to the last terminal upload failure for [localId], if still retained. */
    private fun lastUploadFailureReason(localId: String): String? = runCatching {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWork("upload-$localId")
            .get()
            .firstOrNull { it.state == WorkInfo.State.FAILED }
            ?.outputData?.getString(DicKeys.UPLOAD_FAIL_REASON)
    }.getOrNull()

    private fun showDeviceOnlyKeptSnackbar() {
        Snackbar.make(
            findViewById(R.id.homeRoot),
            R.string.delete_device_only_done,
            Snackbar.LENGTH_LONG,
        ).show()
    }

    private fun positionFabAtThreeQuarters() {
        val root = findViewById<View>(R.id.homeRoot)
        root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (fab.width == 0 || view.width == 0) return@addOnLayoutChangeListener
            val params = fab.layoutParams as CoordinatorLayout.LayoutParams
            params.gravity = Gravity.TOP or Gravity.START
            params.leftMargin = (view.width * 3 / 4) - fab.width / 2
            params.topMargin = (view.height * 3 / 4) - fab.height / 2
            fab.layoutParams = params
        }
    }

    override fun onDestroy() {
        if (::adapter.isInitialized) adapter.clearThumbCache()
        super.onDestroy()
    }
}
