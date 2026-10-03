package com.sempermechanics.semper.ui.home

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.WorkTags
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.prefs.DicSettings
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.navigation.DicKeys
import com.sempermechanics.semper.ui.limit.SessionLimitActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The pieces split out of HomeActivity, each on a bare screen: the quota
 * line and limit gate ([HomeQuotaCard]), the new-analysis button's place
 * ([HomeFabLayout]) and the sync badge's tap ([BackupBadgeActions]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HomeControllersTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var activity: AppCompatActivity

    /** Fails with its input as output; a retained backup failure for [BackupBadgeActions] to read. */
    class FailingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.failure(inputData)
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        activity = built.setup().get()
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        AppRemoteConfig.clear(context)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun startedScreen(): String? = shadowOf(activity).nextStartedActivity?.component?.className

    // ── HomeQuotaCard ────────────────────────────────────────────────────

    private var settingsOpened = 0
    private val quotaView by lazy { TextView(activity) }
    private val licenseView by lazy { TextView(activity) }
    private val card by lazy { HomeQuotaCard(activity, quotaView, licenseView) { settingsOpened++ } }

    @Test
    fun `with no known ceiling the quota line stays hidden`() {
        card.render(localSessionCount = 3)
        assertEquals(View.GONE, quotaView.visibility)
        assertEquals("no license expiry to warn of", View.GONE, licenseView.visibility)
    }

    @Test
    fun `the quota line counts the phone's analyses and turns red at the ceiling`() {
        AppRemoteConfig.apply(context, AppConfigDto(maxSessions = 5))
        TokenStore.setQuota(context, used = 2)

        card.render(localSessionCount = 3)
        assertEquals(View.VISIBLE, quotaView.visibility)
        assertEquals(activity.resources.getQuantityString(R.plurals.home_quota_fmt, 3, 3, 5), quotaView.text)
        assertEquals(activity.getColor(R.color.text_secondary), quotaView.currentTextColor)

        card.render(localSessionCount = 5)
        assertEquals(activity.getColor(R.color.semantic_danger), quotaView.currentTextColor)
    }

    @Test
    fun `a tap on the quota line opens Settings under the limit and the limit screen at it`() {
        AppRemoteConfig.apply(context, AppConfigDto(maxSessions = 5))
        card.render(localSessionCount = 1)

        quotaView.performClick()
        assertEquals(1, settingsOpened)
        assertNull(startedScreen())

        TokenStore.setSessionLimitReached(context, true)
        quotaView.performClick()
        assertEquals(1, settingsOpened)
        assertEquals(SessionLimitActivity::class.java.name, startedScreen())
    }

    @Test
    fun `a reconcile opens the limit screen only when it newly reaches the limit`() {
        AppRemoteConfig.apply(context, AppConfigDto(maxSessions = 5))

        runBlocking { card.recordReconciled(quotaUsed = 5) }
        assertEquals(SessionLimitActivity::class.java.name, startedScreen())

        runBlocking { card.recordReconciled(quotaUsed = 6) }
        assertNull("already held at the limit", startedScreen())
        assertEquals(6, TokenStore.quotaUsed(context))
    }

    // ── HomeFabLayout ────────────────────────────────────────────────────

    @Test
    fun `the button sits centred nine tenths of the way down`() {
        val root = CoordinatorLayout(activity)
        val fab = View(activity)
        root.addView(fab, CoordinatorLayout.LayoutParams(100, 100))
        HomeFabLayout.pinAtNineTenths(root, fab)

        fun layOut() {
            root.measure(
                MeasureSpec.makeMeasureSpec(1000, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(2000, MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 1000, 2000)
        }
        layOut()
        layOut()

        val params = fab.layoutParams as CoordinatorLayout.LayoutParams
        assertEquals(Gravity.TOP or Gravity.START, params.gravity)
        assertEquals(1000 / 2 - 50, params.leftMargin)
        assertEquals(2000 * 9 / 10 - 50, params.topMargin)
    }

    // ── BackupBadgeActions ───────────────────────────────────────────────

    private val badge by lazy {
        BackupBadgeActions(activity, SessionListAdapter({ false }, {}, {})) { settingsOpened++ }
    }

    private fun record(state: SessionRecord.SyncState) = sessionRecord(id = "a", syncState = state)

    @Test
    fun `a synced row, or a phone-only one with backup off, goes to Settings`() {
        DicSettings.setSaveToCloudEnabled(context, false)

        badge.retryOrBackup(record(SessionRecord.SyncState.SYNCED))
        badge.retryOrBackup(record(SessionRecord.SyncState.LOCAL_ONLY))

        assertEquals(2, settingsOpened)
    }

    @Test
    fun `a failed row explains the retained failure before offering a retry`() {
        val failed = OneTimeWorkRequestBuilder<FailingWorker>()
            .setInputData(workDataOf(DicKeys.UPLOAD_FAIL_REASON to "Too large (ref r9)"))
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(WorkTags.uploadName("a"), ExistingWorkPolicy.REPLACE, failed).result.get()
        assertEquals(WorkInfo.State.FAILED, workManager.getWorkInfoById(failed.id).get()?.state)

        badge.retryOrBackup(record(SessionRecord.SyncState.FAILED))
        idleUntil("the failed-backup dialog") { ShadowDialog.getLatestDialog()?.isShowing == true }

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("Too large (ref r9)", dialog.findViewById<TextView>(android.R.id.message)?.text.toString())
        val retry = dialog.getButton(AlertDialog.BUTTON_POSITIVE).text
        assertEquals(activity.getString(R.string.cloud_backup_retry_action), retry)
    }

    @Test
    fun `a failed row with no retained failure says so generically`() {
        badge.retryOrBackup(record(SessionRecord.SyncState.FAILED))
        idleUntil("the failed-backup dialog") { ShadowDialog.getLatestDialog()?.isShowing == true }

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(
            activity.getString(R.string.cloud_backup_failed_generic),
            dialog.findViewById<TextView>(android.R.id.message)?.text.toString(),
        )
        assertEquals(0, settingsOpened)
    }
}
