// Home owns its row actions, refreshes and pickers as small private steps.
@file:Suppress("TooManyFunctions")

package com.sempermechanics.semper.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.cloud.CloudBackupListing
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.SessionDeletes
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.isRestorable
import com.sempermechanics.semper.databinding.ActivityHomeBinding
import com.sempermechanics.semper.navigation.DicKeys
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisNavHelper
import com.sempermechanics.semper.ui.common.ConflatedRefresh
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.SerialJob
import com.sempermechanics.semper.ui.common.auth.AuthRoute
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.common.dialog.CrispToast
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.media.MediaPickerSheet
import com.sempermechanics.semper.ui.common.media.MediaSourceChooser
import com.sempermechanics.semper.ui.common.transfer.DeleteFeedback
import com.sempermechanics.semper.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
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

    private lateinit var binding: ActivityHomeBinding
    private lateinit var adapter: SessionListAdapter
    private lateinit var selection: SessionSelectionController
    private lateinit var cloudBackups: CloudBackupsCard
    private lateinit var quotaCard: HomeQuotaCard
    private lateinit var backupBadge: BackupBadgeActions

    private lateinit var deleteFeedback: DeleteFeedback

    /**
     * Rows just queued for deletion, hidden until WorkManager lists their job
     * (the enqueue lands asynchronously) or it ends.
     */
    private val justQueuedDeletes = mutableSetOf<String>()

    /** The phone-list read in flight ([refreshList]); a newer one replaces it. */
    private val listRefresh = SerialJob()

    /**
     * The cloud check, one at a time: a burst of requests runs it at most once
     * more, deep if any asked for deep. It is not cancelled mid-call, and it
     * starts after the list read that was in flight when it began.
     */
    private val cloudCheck: ConflatedRefresh<Boolean> by lazy {
        ConflatedRefresh<Boolean>(lifecycleScope, merge = { a, b -> a || b }) { deep ->
            var completed = false
            try {
                listRefresh.join()
                reconcileWithCloud(deep)
                completed = true
            } finally {
                // The spinner tracks the cloud check, not the local list read —
                // that's the part worth waiting for. After a check that ended
                // normally it stays while a pull-to-refresh waits its turn; a
                // check that threw or was cancelled takes the queue with it.
                if (!completed || !cloudCheck.hasPending) binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = selection.clearSelection()
    }

    /** Confirm before leaving Home (and the app). Selection-mode back is separate. */
    private val exitAppCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            Dialogs.confirm(
                this@HomeActivity,
                R.string.exit_semper_title,
                R.string.exit_semper_message,
                R.string.exit,
            ) {
                finish()
            }
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
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.decorView.post { reportFullyDrawn() }

        // Edge-to-edge (enforced on API 35+): drop the header below the status
        // bar, otherwise the bar swallows taps on the settings gear. The
        // selection bar replaces the title row, so it needs the same inset.
        Insets.padTop(binding.homeTopBar)
        Insets.padTop(binding.homeSelectionBar)

        cloudBackups = cloudBackupsCard()
        wirePullToRefresh()
        quotaCard = HomeQuotaCard(
            activity = this,
            quotaView = binding.tvHomeQuota,
            licenseView = binding.tvHomeLicense,
            openSettings = { binding.btnHomeSettings.performClick() },
        )
        wireButtons()

        // Beta notice, then consent, then the coach mark: one overlay at a time,
        // and the diagnostics choice must be made before anything is collected.
        FirstRunPrompts(this).show(binding.fabNewAnalysis)

        wireSessionList()

        // Exit confirm is always registered; selection back is layered on top and
        // enabled only while something is selected (LIFO: last added runs first).
        onBackPressedDispatcher.addCallback(this, exitAppCallback)
        onBackPressedDispatcher.addCallback(this, backCallback)

        openLimitScreenIfFull()
        HomeTransferWatch(this, adapter, quotaCard, ::showsCloudState) { refresh() }.observe()
        deleteFeedback.observe()
    }

    private fun cloudBackupsCard() = CloudBackupsCard(
        card = binding.homeCloudBackups,
        text = binding.tvCloudBackups,
        restoreButton = binding.btnCloudBackupsRestore,
        hideButton = binding.btnCloudBackupsHide,
        onRestore = { targets -> queueRestores { targets } },
        onHide = { backups -> hideCloudBackups(backups) },
    )

    private fun wirePullToRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.sky_primary)
        // Pull down = deep re-check: verify the blobs really exist in Drive,
        // not just that the backend's index says so.
        binding.swipeRefresh.setOnRefreshListener { refresh(deep = true) }
        binding.sessionList.layoutManager = LinearLayoutManager(this)
    }

    /** The + button, the gear, and the empty state's button (which is the + button). */
    private fun wireButtons() {
        val fab = binding.fabNewAnalysis
        HomeFabLayout.pinAtNineTenths(binding.homeRoot, fab)
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
            if (!quotaCard.openLimitScreenIfReached()) showSourceChooser()
        }
        binding.btnHomeSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnEmptyRestore.setOnClickListener {
            fab.performClick()
        }
    }

    /**
     * The list, its selection and its delete feedback. Adapter callbacks close
     * over selection; both must exist before the list attaches so an early
     * bind cannot hit an uninitialized controller.
     */
    private fun wireSessionList() {
        adapter = sessionListAdapter()
        backupBadge = BackupBadgeActions(this, adapter) { binding.btnHomeSettings.performClick() }
        selection = selectionController()
        deleteFeedback = DeleteFeedback(this, binding.homeRoot) {
            justQueuedDeletes.clear()
            refresh(reconcile = false)
        }
        selection.bindBarActions(
            btnClose = binding.btnSelectionClose,
            btnDelete = binding.btnSelectionDelete,
        )
        binding.sessionList.adapter = adapter
    }

    private fun sessionListAdapter() = SessionListAdapter(
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
        onBadgeClick = { record -> backupBadge.retryOrBackup(record) },
    )

    private fun selectionController() = SessionSelectionController(
        activity = this,
        adapter = adapter,
        topBar = binding.homeTopBar,
        selectionBar = binding.homeSelectionBar,
        selectionCount = binding.tvSelectionCount,
        btnSelectionRename = binding.btnSelectionRename,
        btnSelectionRestore = binding.btnSelectionRestore,
        selectAllBox = binding.cbSelectionAll,
        fab = binding.fabNewAnalysis,
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

    /** Cold start / return with an already-full quota → persistent support screen. */
    private fun openLimitScreenIfFull() {
        lifecycleScope.launch {
            val localCount = withContext(Dispatchers.IO) {
                SessionStore.list(this@HomeActivity).size
            }
            TokenStore.refreshSessionLimit(this@HomeActivity, localCount)
            quotaCard.openLimitScreenIfReached()
        }
    }

    override fun onStart() {
        super.onStart()
        // A sign-out finished with no screen left to route, and Android
        // refused the background start to sign-in: route from here.
        if (SignOutRun.claimUnclaimed()) AuthRoute.toSignIn(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        listRefresh.launch(lifecycleScope) {
            val sessions = visibleSessions()
            submitSessions(sessions)
            // Demo: analyses are recorded silently and there is no restore, so
            // the list carries no sync badge, bar or "only in cloud" state.
            adapter.setSyncVisible(showsCloudState())
            binding.emptyState.isVisible = sessions.isEmpty()
            updateCloudBackups()
            quotaCard.render(sessions.size)
            // A refresh can drop rows out from under a selection.
            selection.updateSelectionBar()
            // Local count alone can trip the hard-stop flag (before cloud reconcile).
            TokenStore.refreshSessionLimit(this@HomeActivity, sessions.size)
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
                // Record the account's quota; newly at the cap → open the
                // persistent "email support" screen.
                quotaCard.recordReconciled(outcome.quotaUsed)
                // This check saved a fresh listing of the account's backups.
                updateCloudBackups()
                if (outcome.repaired > 0) {
                    // The rows changed underneath us — show the corrected state.
                    refreshList()
                    if (!showsCloudState()) return
                    val repaired = outcome.repaired
                    val text = resources.getQuantityString(R.plurals.cloud_resync_fmt, repaired, repaired)
                    Feedback.toast(this, text, long = true)
                }
            }
            is CloudSync.Outcome.Failed -> if (showsCloudState()) {
                Feedback.toast(this, getString(R.string.cloud_check_failed_fmt, outcome.reason), long = true)
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
        when {
            hasLocal -> startActivity(SessionOpenHelper.intentFor(this, record))
            // A demo account cannot pull its recorded copy back, so a row with
            // no local data is simply unopenable — no download offer.
            record.isRestorable(hasLocal) && showsCloudState() -> Dialogs.confirm(
                this,
                R.string.download_analysis_title,
                R.string.download_analysis_body,
                R.string.restore_action,
            ) { startRestore(listOf(record)) }
            else -> SessionOpenHelper.openOrExplain(this, record, hasLocal)
        }
    }

    /**
     * Queue background restores and stay on Home. Row progress comes from
     * [HomeTransferWatch] (same badge/bar as uploads) so the list stays
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
            Feedback.toast(this@HomeActivity, summary.text, long = summary.failed)
        }
    }

    /**
     * Offer the backups this phone has no row for, from the listing the last
     * reconcile saved. Demo accounts have no restore, so they are offered nothing.
     */
    private fun updateCloudBackups() {
        cloudBackups.refresh(lifecycleScope, read = ::offeredBackups) { offered ->
            val emptyTitle = if (offered.isEmpty()) R.string.home_empty_title else R.string.home_empty_title_cloud
            binding.tvEmptyTitle.setText(emptyTitle)
        }
    }

    private suspend fun offeredBackups(): List<CloudBackupListing.Backup> = if (showsCloudState()) {
        withContext(Dispatchers.IO) { CloudBackupListing.offered(this@HomeActivity) }
    } else {
        emptyList()
    }

    private fun hideCloudBackups(backups: List<CloudBackupListing.Backup>) {
        CloudBackupListing.hide(this, backups.map { it.cloudId })
        updateCloudBackups()
        Feedback.toast(this, R.string.cloud_backups_hidden, long = true)
    }

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

    override fun onDestroy() {
        if (::adapter.isInitialized) adapter.clearThumbCache()
        super.onDestroy()
    }
}
