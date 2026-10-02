package com.indicvision.semper.ui.common

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.indicvision.semper.R
import com.indicvision.semper.data.account.LicenseErrors
import com.indicvision.semper.data.cloud.UploadErrors
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.fixtures.CleanAppState
import com.indicvision.semper.fixtures.idleUntil
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.home.HomeActivity
import com.indicvision.semper.ui.limit.SessionLimitActivity
import com.indicvision.semper.ui.settings.SettingsActivity
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What Home and Settings do when a backup, restore or Save-to-Files job
 * ends: real jobs run to an outcome through a test WorkManager, and each
 * screen's reaction is read off the screen. Pins the reactions the
 * hand-written WorkInfo observers had, for [TransferWorkObserver] to keep.
 */
@RunWith(RobolectricTestRunner::class)
class TransferReactionsTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** Ends as its input says: [FAIL] fails with the rest of the input as output, else succeeds. */
    class OutcomeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = if (inputData.getBoolean(FAIL, false)) {
            Result.failure(inputData)
        } else {
            Result.success(inputData)
        }
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // Licensed with cloud backup: the observers and outcome toasts are its.
        // A known ceiling, so the account can be held at its limit.
        AppRemoteConfig.apply(context, AppConfigDto(mode = "licensed", cloudBackupEnabled = true, maxSessions = 50))
        ShadowToast.reset()
    }

    // WorkManager is a static singleton Robolectric keeps between classes.
    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        screens.forEach { runCatching { it.pause().stop().destroy() } }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        AppRemoteConfig.clear(context)
    }

    private fun run(
        tags: Set<String>,
        output: Data = Data.EMPTY,
        fail: Boolean = false,
        delayed: Boolean = false,
    ): UUID {
        val input = Data.Builder().putAll(output).putBoolean(FAIL, fail).build()
        val request = OneTimeWorkRequestBuilder<OutcomeWorker>()
            .setInputData(input)
            .apply { tags.forEach { addTag(it) } }
            .apply { if (delayed) setInitialDelay(1, TimeUnit.HOURS) }
            .build()
        workManager.enqueue(request).result.get()
        return request.id
    }

    private val screens = mutableListOf<ActivityController<out Activity>>()

    /** Every CrispToast pill any launched screen showed, in order. */
    private val pills = mutableListOf<String>()

    private fun <A : Activity> launch(type: Class<A>): A {
        val controller = Robolectric.buildActivity(type).setup()
        screens += controller
        val activity = controller.get()
        activity.findViewById<ViewGroup>(android.R.id.content)
            .setOnHierarchyChangeListener(
                object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View, child: View) {
                        if (child.tag != CRISP_TAG) return
                        pills += child.findViewById<TextView>(R.id.tvToast).text.toString()
                    }

                    override fun onChildViewRemoved(parent: View, child: View) = Unit
                },
            )
        shadowOf(activity.mainLooper).idle()
        return activity
    }

    /** Closes [activity]'s screen, as leaving it would. */
    private fun close(activity: Activity) {
        screens.first { it.get() === activity }.pause().stop().destroy()
        shadowOf(activity.mainLooper).idle()
    }

    // ── Home ─────────────────────────────────────────────────────────────

    @Test
    fun `Home tells a failed backup's reason once`() {
        val home = launch(HomeActivity::class.java)
        val first = "Too large (ref r1)"
        run(setOf("upload", "upload-a"), workDataOf(DicKeys.UPLOAD_FAIL_REASON to first), fail = true)
        idleUntil("the backup failure pill") { pills.isNotEmpty() }

        val second = "Device conflict (ref r2)"
        run(setOf("upload", "upload-b"), workDataOf(DicKeys.UPLOAD_FAIL_REASON to second), fail = true)
        idleUntil("the second failure") { pills.size >= 2 }
        assertEquals(
            "each failure told once",
            listOf(first, second).map { home.getString(R.string.cloud_backup_failed_fmt, it) },
            pills,
        )
    }

    @Test
    fun `Home opens the limit screen when a backup stops at the limit`() {
        val home = launch(HomeActivity::class.java)
        // Drop whatever Home started on the way up.
        do {
            val started = shadowOf(home).nextStartedActivity
        } while (started != null)
        TokenStore.setSessionLimitReached(context, true)

        val quotaStop = workDataOf(
            UploadErrors.UPLOAD_FAIL_KIND to UploadErrors.FAIL_KIND_QUOTA,
            DicKeys.SESSION_LOCAL_ID to "a",
        )
        run(setOf("upload", "upload-a"), quotaStop, fail = true)

        idleUntil("the limit screen") {
            shadowOf(home).peekNextStartedActivity()?.component?.className == SessionLimitActivity::class.java.name
        }
        assertEquals("a quota stop is not told as a failure", emptyList<String>(), pills)
    }

    @Test
    fun `a backup that fails without a reason for another cause leaves the limit screen shut`() {
        val home = launch(HomeActivity::class.java)
        do {
            val started = shadowOf(home).nextStartedActivity
        } while (started != null)
        // Held at the limit from earlier; this backup then fails for a reason
        // of its own: its analysis was deleted before it ran.
        TokenStore.setSessionLimitReached(context, true)
        run(setOf("upload", "upload-gone"), fail = true)

        // A later failure that is told: by then Home has read the first one.
        val later = "Device conflict (ref r3)"
        run(setOf("upload", "upload-b"), workDataOf(DicKeys.UPLOAD_FAIL_REASON to later), fail = true)
        idleUntil("the later failure") { pills.isNotEmpty() }

        assertNull("only a quota stop opens the limit screen", shadowOf(home).nextStartedActivity)
    }

    @Test
    fun `Home tells a failed restore's reason once across screens`() {
        val home = launch(HomeActivity::class.java)
        val reason = "Backup gone"
        run(setOf("restore", "restore-c1"), workDataOf(DicKeys.DOWNLOAD_ERROR to reason), fail = true)
        idleUntil("the restore failure pill") { pills.isNotEmpty() }

        // A new Home sees the same retained job: the ledger keeps it quiet.
        close(home)
        launch(HomeActivity::class.java)
        run(setOf("restore", "restore-c2"), fail = true)
        idleUntil("the second restore failure") { pills.size >= 2 }
        assertEquals(listOf(reason, home.getString(R.string.restore_failed_generic)), pills)
    }

    // ── Settings ─────────────────────────────────────────────────────────

    @Test
    fun `Settings tells a failed restore's reason once`() {
        val settings = launch(SettingsActivity::class.java)
        val reason = "Not your backup"
        run(setOf("restore", "restore-s1"), workDataOf(DicKeys.DOWNLOAD_ERROR to reason), fail = true)
        idleUntil("the restore failure pill") { pills.isNotEmpty() }

        run(setOf("restore", "restore-s2"), fail = true)
        idleUntil("the second restore failure") { pills.size >= 2 }
        assertEquals(listOf(reason, settings.getString(R.string.restore_failed_generic)), pills)
    }

    @Test
    fun `Settings tells each Save-to-Files outcome once per process`() {
        val settings = launch(SettingsActivity::class.java)
        val boom = workDataOf(DicKeys.DOWNLOAD_ERROR to "boom")
        run(setOf("download-bundle", "download-bundle-d1"), boom, fail = true)
        val failed = LicenseErrors.downloadMessage(settings, "boom")
        idleUntil("the download failure toast") { ShadowToast.shownToastCount() >= 1 }
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals(failed, ShadowToast.getTextOfLatestToast())

        run(setOf("download-bundle", "download-bundle-d2"))
        val saved = settings.getString(R.string.save_success)
        idleUntil("the download success toast") { ShadowToast.shownToastCount() >= 2 }
        assertEquals(2, ShadowToast.shownToastCount())
        assertEquals(saved, ShadowToast.getTextOfLatestToast())

        // A new Settings screen sees both retained jobs; neither is told again.
        // Every job in one list is handled in one pass, so a repeat would
        // already show in the count when the third toast lands.
        close(settings)
        launch(SettingsActivity::class.java)
        run(setOf("download-bundle", "download-bundle-d3"))
        idleUntil("the third download") { ShadowToast.shownToastCount() >= 3 }
        assertEquals(3, ShadowToast.shownToastCount())
        assertEquals(saved, ShadowToast.getTextOfLatestToast())
        assertEquals("downloads are not told in a pill", emptyList<String>(), pills)
    }

    @Test
    fun `a queued Save-to-Files download shows in the banner until it ends`() {
        val settings = launch(SettingsActivity::class.java)
        val id = run(setOf("download-bundle", "download-bundle-q1"), delayed = true)
        idleUntil("the queued download's banner") { settings.transferBanner.contains("q1") }

        workManager.cancelWorkById(id).result.get()
        idleUntil("the cancelled download leaves the banner") { !settings.transferBanner.contains("q1") }
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfoById(id).get()?.state)
    }

    private companion object {
        const val FAIL = "test_fail"
        const val CRISP_TAG = "semper_crisp_toast"
    }
}
