// Home screen wires many list/menu/callback bindings in onCreate; kept together
// for locality, so LongMethod / TooManyFunctions / MagicNumber are suppressed.
@file:Suppress("LongMethod", "TooManyFunctions", "MagicNumber")

package com.indicvision.semper.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
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
import com.indicvision.semper.R
import com.indicvision.semper.data.account.LicenseEntitlements
import com.indicvision.semper.data.cloud.CloudBackupListing
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.cloud.SessionDeletes
import com.indicvision.semper.data.cloud.restore.RestoreFailureLedger
import com.indicvision.semper.data.cloud.restore.RestoreStart
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.prefs.CoachPrefs
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.diagnostics.Diagnostics
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import com.indicvision.semper.ui.analysis.wizard.AnalysisNavHelper
import com.indicvision.semper.ui.common.CoachMarkController
import com.indicvision.semper.ui.common.ConflatedRefresh
import com.indicvision.semper.ui.common.CrispToast
import com.indicvision.semper.ui.common.DeleteFeedback
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.MediaPickerSheet
import com.indicvision.semper.ui.common.MediaSourceChooser
import com.indicvision.semper.ui.limit.SessionLimitActivity
import com.indicvision.semper.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home: the record of every analysis done on this phone (metadata from
 * [SessionStore]; heavy files per session dir, full copies in the cloud once
 * synced). The + button opens the import source chooser straight away — there is
 * one acquisition path. The gear opens the behavioral settings drawer.
 */
@MainThread
class HomeActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var emptyState: android.view.View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: SessionListAdapter
    private lateinit var selection: SessionSelectionController
    private lateinit var fab: ImageButton
    private lateinit var tvHomeQuota: TextView
    private lateinit var tvHomeLicense: TextView
    private lateinit var tvEmptyTitle: TextView
    private lateinit var cloudBackups: CloudBackupsCard

    /** Upload WorkInfo ids already surfaced, so one failure isn't snackbar-spammed. */
    private val shownUploadFailures = mutableSetOf<java.util.UUID>()

    /** Upload WorkInfo ids already refreshed on success, so we refresh once each. */
    private val shownSucceededUploads = mutableSetOf<java.util.UUID>()

    /** Restore WorkInfo ids already refreshed for, so each refreshes the list once. */
    private val shownRestoreOutcomes = mutableSetOf<java.util.UUID>()

    private lateinit var deleteFeedback: DeleteFeedback

    /**
     * Rows just queued for deletion, hidden until WorkManager lists their job
     * (the enqueue lands asynchronously) or it ends.
     */
    private val justQueuedDeletes = mutableSetOf<String>()
    private var activeUploadProgress: Map<String, SessionListAdapter.RowProgress> = emptyMap()
    private var activeRestoreProgress: Map<String, SessionListAdapter.RowProgress> = emptyMap()

    /** The phone-list read in flight ([refreshList]). */
    private var listRefresh: Job? = null

    /**
     * The cloud check, one at a time: a burst of requests runs it at most once
     * more, deep if any asked for deep. It is not cancelled mid-call, and it
     * starts after the list read that was in flight when it began.
     */
    private val cloudCheck: ConflatedRefresh<Boolean> by lazy {
        ConflatedRefresh<Boolean>(lifecycleScope, merge = { a, b -> a || b }) { deep ->
            var completed = false
            try {
                listRefresh?.join()
                reconcileWithCloud(deep)
                completed = true
            } finally {
                // The spinner tracks the cloud check, not the local list read —
                // that's the part worth waiting for. After a check that ended
                // normally it stays while a pull-to-refresh waits its turn; a
                // check that threw or was cancelled takes the queue with it.
                if (!completed || !cloudCheck.hasPending) swipeRefresh.isRefreshing = false
            }
        }
    }

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

    /** Files tab: Storage Access Framework (Drive, storage, DNG). */
    private val pickDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            routePickedMedia(uri)
        }

    private var mediaPicker: MediaPickerSheet? = null
    private val requestMediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            mediaPicker?.onPermissionResult()
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
        window.decorView.post { reportFullyDrawn() }

        // Edge-to-edge (enforced on API 35+): drop the header below the status
        // bar, otherwise the bar swallows taps on the settings gear. The
        // selection bar replaces the title row, so it needs the same inset.
        Insets.padTop(findViewById(R.id.homeTopBar))
        Insets.padTop(findViewById(R.id.homeSelectionBar))

        list = findViewById(R.id.sessionList)
        emptyState = findViewById(R.id.emptyState)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        tvHomeQuota = findViewById(R.id.tvHomeQuota)
        tvHomeLicense = findViewById(R.id.tvHomeLicense)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        cloudBackups = CloudBackupsCard(
            card = findViewById(R.id.homeCloudBackups),
            text = findViewById(R.id.tvCloudBackups),
            restoreButton = findViewById(R.id.btnCloudBackupsRestore),
            hideButton = findViewById(R.id.btnCloudBackupsHide),
            onRestore = { targets -> queueRestores { targets } },
            onHide = { backups -> hideCloudBackups(backups) },
        )
        swipeRefresh.setColorSchemeResources(R.color.sky_primary)
        // Pull down = deep re-check: verify the blobs really exist in Drive,
        // not just that the backend's index says so.
        swipeRefresh.setOnRefreshListener { refresh(deep = true) }
        list.layoutManager = LinearLayoutManager(this)

        fab = findViewById(R.id.fabNewAnalysis)
        positionFabAtNineTenths()
        fab.setOnClickListener {
            // Two independent reasons new work cannot start. The seat check is
            // first because an institution member is licensed, so the quota
            // check below is false for them by definition and would wave them
            // through. btnEmptyRestore delegates here via performClick(), so
            // both entry points are covered by this one listener.
            if (LicenseEntitlements.seatRequiredToStart(this)) {
                AnalysisNavHelper.openSeatRequired(this)
                return@setOnClickListener
            }
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
            fab.performClick()
        }

        // Beta notice, then consent, then the coach mark: one overlay at a time,
        // and the diagnostics choice must be made before anything is collected.
        maybeShowBetaNotice {
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
            btnSelectionRestore = findViewById(R.id.btnSelectionRestore),
            selectAllBox = findViewById(R.id.cbSelectionAll),
            fab = fab,
            backCallback = backCallback,
            onRefresh = { refresh() },
            onDeviceOnlyDeleted = { showDeviceOnlyKeptSnackbar() },
            restoreEnabled = { showsCloudState() },
            onRestore = { records -> startRestore(records) },
            onDeleteQueued = { workId, items ->
                justQueuedDeletes += items.filter { it.mode == SessionDeletes.Mode.EVERYWHERE }.map { it.localId }
                deleteFeedback.queued(workId, items.size)
                refresh(reconcile = false)
            },
        )
        deleteFeedback = DeleteFeedback(this, findViewById(R.id.homeRoot)) {
            justQueuedDeletes.clear()
            refresh(reconcile = false)
        }
        selection.bindBarActions(
            btnClose = findViewById(R.id.btnSelectionClose),
            btnDelete = findViewById(R.id.btnSelectionDelete),
        )
        list.adapter = adapter

        // Exit confirm is always registered; selection back is layered on top and
        // enabled only while something is selected (LIFO: last added runs first).
        onBackPressedDispatcher.addCallback(this, exitAppCallback)
        onBackPressedDispatcher.addCallback(this, backCallback)

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
        deleteFeedback.observe()
    }

    /**
     * Background uploads run in WorkManager, so a failure would otherwise be
     * silent (only the row badge changed). Watch the "upload" work tag and, when
     * a run ends in a terminal failure carrying a reason, tell the user with a
     * Retry action. Quota-full returns no reason: it opens the persistent limit
     * screen instead.
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
                            if (reason == null) {
                                // No reason: a refusal at the account's limit, which
                                // forces the stop. Open the limit screen from here so
                                // it shows whether or not the worker also opens it
                                // (it is singleTop, so the two cannot stack).
                                if (TokenStore.isSessionLimitReached(this)) openSessionLimitScreen()
                                return@forEach
                            }
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
                        id to SessionListAdapter.RowProgress(DicKeys.PHASE_DOWNLOAD, pct)
                    }
                    .toMap()
                publishRowProgress()

                list.forEach { info ->
                    when (info.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            if (shownRestoreOutcomes.add(info.id)) refresh()
                        }
                        WorkInfo.State.FAILED -> {
                            if (shownRestoreOutcomes.add(info.id)) refresh()
                            // Once per failure across Home and Settings, not once per screen open.
                            if (!RestoreFailureLedger.claim(this@HomeActivity, info.id)) return@forEach
                            val reason = info.outputData.getString(DicKeys.DOWNLOAD_ERROR)
                                ?: getString(R.string.restore_failed_generic)
                            CrispToast.show(
                                this@HomeActivity,
                                reason,
                                long = true,
                            )
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
        if (!showsCloudState()) return
        CrispToast.show(
            this,
            getString(R.string.cloud_backup_failed_fmt, reason),
            long = true,
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * One-time beta / data-use declaration after the account first reaches Home,
     * then [next]. The only way out is "I understand", so [next] runs from there.
     */
    private fun maybeShowBetaNotice(next: () -> Unit) {
        if (TokenStore.hasAckedBetaNotice(this)) {
            next()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.beta_notice_title)
            .setMessage(R.string.beta_notice_body)
            .setCancelable(false)
            .setPositiveButton(R.string.beta_notice_ack) { _, _ ->
                TokenStore.setBetaNoticeAcked(this)
                next()
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
     * In-sheet Images gallery; Files opens SAF for Drive / storage / DNG.
     */
    private fun showSourceChooser() {
        mediaPicker = MediaSourceChooser.show(
            activity = this,
            mode = MediaSourceChooser.Mode.HOME_REFERENCE,
            requestPermission = {
                requestMediaPermission.launch(
                    MediaSourceChooser.requiredPermissions(includeVideo = true),
                )
            },
            onBrowseSaf = { pickDocument.launch(arrayOf("image/*", "video/*")) },
            onPicked = { uris -> routePickedMedia(uris.firstOrNull()) },
        )
    }

    /**
     * @param deep verify blobs really exist in Drive (pull-to-refresh) rather
     *   than trusting the backend index (cheap resume check).
     * @param reconcile false only re-reads the phone's list, for a change made
     *   here that the cloud check has nothing to add to.
     */
    private fun refresh(deep: Boolean = false, reconcile: Boolean = true) {
        refreshList()
        if (reconcile) cloudCheck.request(deep)
    }

    /**
     * Re-reads the phone's list. Latest wins: a newer call cancels the read
     * still in flight, so an older list can never be submitted after a newer
     * one (refresh runs on resume, on every finished transfer, delete, rename
     * and restore, often several within a second).
     */
    private fun refreshList() {
        listRefresh?.cancel()
        listRefresh = lifecycleScope.launch {
            val sessions = visibleSessions()
            submitSessions(sessions)
            // Demo: analyses are recorded silently and there is no restore, so
            // the list carries no sync badge, bar or "only in cloud" state.
            adapter.setSyncVisible(showsCloudState())
            emptyState.isVisible = sessions.isEmpty()
            updateCloudBackups()
            updateQuotaIndicator(sessions.size)
            updateLicenseNotice()
            // A refresh can drop rows out from under a selection.
            selection.updateSelectionBar()
            // Local count alone can trip the hard-stop flag (before cloud reconcile).
            TokenStore.refreshSessionLimit(this@HomeActivity, sessions.size)
        }
    }

    /**
     * Warn that a timed license is running out, or has run out and is inside
     * its grace window.
     *
     * Its own view rather than [tvHomeQuota]: a licensed account always has a
     * known quota, so it never reaches that view's unknown-quota hint branch.
     *
     * Advisory only. Entitlement is decided by the backend and arrives as
     * `mode`; this notice is suppressed entirely when the cached config is too
     * old to trust, so a renewal that landed while the device was offline
     * cannot show up here as a false alarm.
     */
    private fun updateLicenseNotice() {
        val days = LicenseEntitlements.expiryNoticeDays(this)
        if (days == null) {
            tvHomeLicense.isVisible = false
            return
        }
        val support = getString(R.string.support_email)
        tvHomeLicense.isVisible = true
        tvHomeLicense.text = when {
            LicenseEntitlements.inGrace(this) -> getString(R.string.license_grace, support)
            // Past its day on a config fetched before it ended: the cache
            // cannot say whether grace applies, only that the day has gone.
            days < 0L -> getString(R.string.license_expired, support)
            days == 0L -> getString(R.string.license_expiring_today, support)
            else -> resources.getQuantityString(
                R.plurals.license_expiring_fmt,
                days.toInt(),
                days.toInt(),
            )
        }
        tvHomeLicense.setTextColor(
            getColor(
                if (LicenseEntitlements.inGrace(this)) {
                    R.color.semantic_danger
                } else {
                    R.color.text_secondary
                },
            ),
        )
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
                // This check saved a fresh listing of the account's backups.
                updateCloudBackups()
                if (outcome.repaired > 0) {
                    // The rows changed underneath us — show the corrected state.
                    refreshList()
                    if (!showsCloudState()) return
                    Toast.makeText(
                        this,
                        resources.getQuantityString(R.plurals.cloud_resync_fmt, outcome.repaired, outcome.repaired),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            is CloudSync.Outcome.Failed -> if (showsCloudState()) {
                Toast.makeText(
                    this,
                    getString(R.string.cloud_check_failed_fmt, outcome.reason),
                    Toast.LENGTH_LONG,
                ).show()
            }
            // Normal for an offline-first app — don't nag. Skipped = checked
            // recently (reconcile is throttled to protect the Firestore budget).
            CloudSync.Outcome.Offline, CloudSync.Outcome.Disabled, CloudSync.Outcome.Skipped -> Unit
        }
    }

    /**
     * Whether this account sees any cloud state at all. Demo accounts record
     * analyses silently and have no restore, so every badge, bar, toast and
     * download offer tied to backup is withheld rather than shown greyed out.
     */
    private fun showsCloudState(): Boolean = LicenseEntitlements.cloudBackupEnabled(this)

    // ── Row actions ──────────────────────────────────────────────────────

    private fun openSession(record: SessionRecord) {
        val hasLocal = adapter.hasLocalData(record.id)
        if (hasLocal) {
            startActivity(SessionOpenHelper.intentFor(this, record))
            return
        }
        val hasCloud = record.syncState == SessionRecord.SyncState.SYNCED ||
            record.cloudSessionId.isNotBlank()
        // A demo account cannot pull its recorded copy back, so a row with
        // no local data is simply unopenable — no download offer.
        if (!hasCloud || !showsCloudState()) {
            SessionOpenHelper.openOrExplain(this, record, hasLocal)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_analysis_title)
            .setMessage(R.string.download_analysis_body)
            .setPositiveButton(R.string.restore_action) { _, _ ->
                startRestore(listOf(record))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Queue background restores and stay on Home. Row progress comes from
     * [observeRestoreProgress] (same badge/bar as uploads) so the list stays
     * interactive — no blocking "Downloading…" dialog. Rows go through
     * [RestoreStart], the same path Settings uses.
     */
    private fun startRestore(records: List<SessionRecord>) {
        if (records.isEmpty()) return
        selection.clearSelection()
        queueRestores {
            records.map { record ->
                val cloudId = CloudSync.resolveCloudIdFor(this@HomeActivity, record).orEmpty()
                RestoreStart.Target(cloudId, record.id, record.name)
            }
        }
    }

    /** Queue [targets] (rows, or backups from [cloudBackups]) and say what happened in one toast. */
    private fun queueRestores(targets: suspend () -> List<RestoreStart.Target>) {
        lifecycleScope.launch {
            val batch = targets()
            if (batch.isEmpty()) return@launch
            val counts = withContext(Dispatchers.IO) { RestoreStart.startAll(this@HomeActivity, batch) }
            refresh(reconcile = false)
            val summary = RestoreSummary.of(resources, batch.size, counts.started, counts.alreadyRunning)
            val length = if (summary.failed) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(this@HomeActivity, summary.text, length).show()
        }
    }

    /**
     * Offer the backups this phone has no row for, from the listing the last
     * reconcile saved. Demo accounts have no restore, so they are offered nothing.
     */
    private fun updateCloudBackups() {
        lifecycleScope.launch {
            val offered = if (showsCloudState()) {
                withContext(Dispatchers.IO) { CloudBackupListing.offered(this@HomeActivity) }
            } else {
                emptyList()
            }
            cloudBackups.show(offered)
            tvEmptyTitle.setText(if (offered.isEmpty()) R.string.home_empty_title else R.string.home_empty_title_cloud)
        }
    }

    private fun hideCloudBackups(backups: List<CloudBackupListing.Backup>) {
        CloudBackupListing.hide(this, backups.map { it.cloudId })
        updateCloudBackups()
        Toast.makeText(this, R.string.cloud_backups_hidden, Toast.LENGTH_LONG).show()
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
        if (!IndicApi.get(this).enabled) {
            Toast.makeText(this, R.string.cloud_backup_no_backend, Toast.LENGTH_LONG).show()
            return
        }
        // The index write is a file read-modify-write, and this runs from a tap.
        // Order is preserved rather than made optimistic: the PENDING stamp has
        // to land before the worker is queued, or an upload that finishes first
        // would have its SYNCED stamp overwritten by this one.
        lifecycleScope.launch {
            SessionStore.setSyncStateAsync(this@HomeActivity, record.id, SessionRecord.SyncState.PENDING)
            CloudSync.enqueueUpload(this@HomeActivity, record.id)
            adapter.rebindRow(record.id)
            Toast.makeText(this@HomeActivity, toastRes, Toast.LENGTH_SHORT).show()
        }
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

    /**
     * Shows [sessions] with which of them still have frames on this phone,
     * read here on IO so binding, selecting and opening a row never do.
     */
    private suspend fun submitSessions(sessions: List<SessionRecord>) {
        val withoutLocalData = withContext(Dispatchers.IO) {
            sessions.filterNot { it.hasLocalData() }.map { it.id }.toSet()
        }
        adapter.submit(sessions, withoutLocalData)
    }

    /** The phone's analyses, less any a queued delete is about to remove. */
    private suspend fun visibleSessions(): List<SessionRecord> {
        val justQueued = justQueuedDeletes.toSet()
        return withContext(Dispatchers.IO) {
            val hidden = justQueued + SessionDeletes.pendingRowIds(this@HomeActivity)
            SessionStore.list(this@HomeActivity).filterNot { it.id in hidden }
        }
    }

    private fun showDeviceOnlyKeptSnackbar() {
        CrispToast.show(this, getString(R.string.delete_device_only_done), long = true)
    }

    private fun positionFabAtNineTenths() {
        val root = findViewById<View>(R.id.homeRoot)
        // Only assign layoutParams when margins actually change. Setting them on
        // every layout pass retriggers layout (and with the FAB menu overlay on
        // homeRoot that becomes an infinite requestLayout loop).
        root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (fab.width == 0 || view.width == 0) return@addOnLayoutChangeListener
            val params = fab.layoutParams as CoordinatorLayout.LayoutParams
            val left = (view.width / 2) - fab.width / 2
            val top = (view.height * 9 / 10) - fab.height / 2
            val gravity = Gravity.TOP or Gravity.START
            if (params.gravity == gravity &&
                params.leftMargin == left &&
                params.topMargin == top
            ) {
                return@addOnLayoutChangeListener
            }
            params.gravity = gravity
            params.leftMargin = left
            params.topMargin = top
            fab.layoutParams = params
        }
    }

    override fun onDestroy() {
        if (::adapter.isInitialized) adapter.clearThumbCache()
        super.onDestroy()
    }
}
