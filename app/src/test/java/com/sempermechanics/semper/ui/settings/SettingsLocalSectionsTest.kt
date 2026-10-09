package com.sempermechanics.semper.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.android.material.slider.Slider
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.DeviceKeys
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.admin.AdminActivity
import com.sempermechanics.semper.ui.common.ByteSize
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.File

/**
 * The Settings sections [SettingsSectionsTest] leaves out — Account, Analysis
 * defaults, Storage, Your data and the Analyses list — on the real screen.
 * Each test sets the account up first (demo or licensed, admin or not), since
 * the sections show different rows for each.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsLocalSectionsTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<SettingsActivity>? = null
    private val settings: SettingsActivity get() = controller!!.get()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        SignOutRun.resetForTest()
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        AppRemoteConfig.clear(context)
        SignOutRun.resetForTest()
    }

    private fun licensed(prefix: String = "", maxFrames: Int = 150) = AppRemoteConfig.apply(
        context,
        AppConfigDto(
            mode = "licensed",
            cloudBackupEnabled = true,
            maxSessions = 50,
            maxFrames = maxFrames,
            licensePrefix = prefix,
        ),
    )

    private fun demo() = AppRemoteConfig.apply(context, AppConfigDto(mode = "demo", maxSessions = 5, maxFrames = 150))

    private fun open() {
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        idle()
    }

    private fun idle() = shadowOf(settings.mainLooper).idle()

    private inline fun <reified T : View> view(id: Int): T = settings.findViewById(id)

    // ── SettingsAccountSection ───────────────────────────────────────────

    @Test
    fun `account shows the email and device, and hides the licence row and admin on demo`() {
        demo()
        AccountCache.saveIdentity(context, "uid-1", "ada@example.com")
        AccountCache.setRole(context, "user")
        open()

        assertEquals("ada@example.com", view<TextView>(R.id.tvAccountEmail).text.toString())
        val device = view<TextView>(R.id.tvAccountDevice).text.toString()
        assertTrue(device, device.contains(DeviceKeys.deviceId(context)))
        assertEquals(View.GONE, view<View>(R.id.tvAccountLicense).visibility)
        assertEquals(View.GONE, view<View>(R.id.btnAdmin).visibility)
    }

    @Test
    fun `a licensed admin sees the licence prefix and the admin entry, which opens the admin screen`() {
        licensed(prefix = "SM-7F3A")
        AccountCache.setRole(context, "admin")
        open()

        val licence = view<TextView>(R.id.tvAccountLicense)
        assertEquals(View.VISIBLE, licence.visibility)
        assertTrue(licence.text.toString(), licence.text.contains("SM-7F3A"))

        val admin = view<View>(R.id.btnAdmin)
        assertEquals(View.VISIBLE, admin.visibility)
        admin.performClick()
        assertEquals(AdminActivity::class.java.name, shadowOf(settings).nextStartedActivity.component?.className)
    }

    // ── SettingsPreferencesSection ───────────────────────────────────────

    @Test
    fun `the frame cap slider tops out at the account's limit and stores what is chosen`() {
        licensed(maxFrames = 120)
        AppSettings.setMaxFrames(context, 40, 120)
        open()

        val slider = view<Slider>(R.id.sliderMaxFrames)
        assertEquals(120f, slider.valueTo)
        assertEquals(40f, slider.value)
        assertEquals("40", view<TextView>(R.id.tvMaxFramesValue).text.toString())

        slider.value = 90f
        assertEquals("90", view<TextView>(R.id.tvMaxFramesValue).text.toString())
        assertEquals(90, AppSettings.maxFrames(context, 120))
    }

    // ── SettingsStorageSection ───────────────────────────────────────────

    /** A [state] analysis whose frame and raw image (2560 bytes) a free-up would drop. */
    private fun seedSession(id: String, state: SessionRecord.SyncState) {
        val dir = SessionStore.dirFor(context, id)
        File(dir, "frame_0000.dat").writeText("d".repeat(512))
        File(dir, "reference.png").writeText("ref")
        File(dir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }
            .let { File(it, "specimen.png").writeText("i".repeat(2048)) }
        val record = sessionRecord(
            id = id,
            refPath = File(dir, "reference.png").absolutePath,
            sessionDir = dir.absolutePath,
            syncState = state,
        )
        assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
    }

    /** Waits for the off-thread measure to fill in the sizes. */
    private fun awaitStorageTotals() {
        val measuring = settings.getString(R.string.storage_measuring)
        idleUntil("the storage sizes to load") {
            view<TextView>(R.id.tvStorageAnalysesSize).text.toString() != measuring
        }
    }

    @Test
    fun `a demo account has no free-up or auto-free controls, even with a backed-up analysis`() {
        demo()
        seedSession("synced", SessionRecord.SyncState.SYNCED)
        open()
        awaitStorageTotals()

        listOf(R.id.btnStorageFreeUp, R.id.rowAutoFreeHeader, R.id.tvAutoFreeValue, R.id.sliderAutoFree)
            .forEach { assertEquals("view $it", View.GONE, view<View>(it).visibility) }
        assertEquals(View.VISIBLE, view<View>(R.id.tvStorageCacheSize).visibility)
    }

    @Test
    fun `Clear sits on the temporary files row and goes once nothing is left`() {
        demo()
        val leftover = File(context.cacheDir, CacheJanitor.TEMP_ROI_REF).apply { writeBytes(ByteArray(4096)) }
        open()
        val clear = view<TextView>(R.id.btnStorageClearCache)
        idleUntil("the cache size to load") { clear.visibility == View.VISIBLE }
        assertTrue(clear.isEnabled)
        assertEquals(settings.getString(R.string.storage_clear), clear.text.toString())
        assertEquals(settings.getString(R.string.storage_clear_cache), clear.contentDescription)
        assertFalse(view<TextView>(R.id.tvStorageCacheSize).text.isNullOrBlank())

        clear.performClick()
        idleUntil("the clear to finish") { !leftover.exists() && clear.visibility == View.GONE }
        assertFalse(clear.isEnabled)
    }

    @Test
    fun `with nothing backed up there is no free-up row, and auto-free reads Off`() {
        licensed()
        seedSession("local", SessionRecord.SyncState.LOCAL_ONLY)
        AppSettings.setAutoFreeBudgetGb(context, AppSettings.AUTO_FREE_OFF)
        open()
        awaitStorageTotals()

        assertEquals(View.GONE, view<View>(R.id.btnStorageFreeUp).visibility)
        assertEquals("Off", view<TextView>(R.id.tvAutoFreeValue).text.toString())
        assertEquals(View.VISIBLE, view<View>(R.id.btnAutoFreeInfo).visibility)
    }

    @Test
    fun `a backed-up analysis shows one free-up row naming what it frees`() {
        licensed()
        seedSession("synced", SessionRecord.SyncState.SYNCED)
        seedSession("local", SessionRecord.SyncState.LOCAL_ONLY)
        open()

        val freeUp = view<TextView>(R.id.btnStorageFreeUp)
        idleUntil("the reclaimable size to load") { freeUp.visibility == View.VISIBLE }
        // Only the backed-up analysis's frame and raw image count.
        assertEquals("Free up ${ByteSize.format(512L + 2048L)}", freeUp.text.toString())
    }

    @Test
    fun `the auto-free value is short, Over N GB, with its meaning in the info dialog`() {
        licensed()
        AppSettings.setAutoFreeBudgetGb(context, 8)
        open()

        assertEquals("Over 8 GB", view<TextView>(R.id.tvAutoFreeValue).text.toString())
        val info = settings.getString(R.string.storage_auto_free_info)
        assertTrue(info, info.contains("stay on this phone until you remove them"))
    }

    @Test
    fun `moving the auto-free slider in code relabels it without storing a budget`() {
        licensed()
        AppSettings.setAutoFreeBudgetGb(context, AppSettings.AUTO_FREE_OFF)
        open()

        view<Slider>(R.id.sliderAutoFree).value = 8f
        assertEquals("Over 8 GB", view<TextView>(R.id.tvAutoFreeValue).text.toString())
        // Only a drag by the user stores (and applies) a budget.
        assertEquals(AppSettings.AUTO_FREE_OFF, AppSettings.autoFreeBudgetGb(context))
    }

    // ── SettingsYourDataSection ──────────────────────────────────────────

    @Test
    fun `the diagnostics switch shows the stored choice and stores a change at once`() {
        demo()
        val before = AppSettings.diagnosticsEnabled(context)
        open()

        val switch = view<CompoundButton>(R.id.switchDiagnostics)
        assertEquals(before, switch.isChecked)
        switch.isChecked = !before
        assertEquals(!before, AppSettings.diagnosticsEnabled(context))
    }

    @Test
    fun `the Terms row names the accepted version, or says none was accepted`() {
        demo()
        open()
        assertEquals(
            settings.getString(R.string.settings_terms_not_accepted),
            view<TextView>(R.id.tvTermsAccepted).text.toString(),
        )
        controller!!.pause().stop().destroy()

        AccountCache.setTermsAccepted(context, "2026-09", synced = true)
        open()
        assertEquals(
            settings.getString(R.string.settings_terms_accepted_fmt, "2026-09"),
            view<TextView>(R.id.tvTermsAccepted).text.toString(),
        )
    }

    @Test
    fun `the improvement consent switch shows the answer the server confirmed`() {
        demo()
        AccountCache.setImprovementConsent(context, true)
        open()
        assertTrue(view<CompoundButton>(R.id.switchImprovementConsent).isChecked)
    }

    // ── SettingsAnalysesSection ──────────────────────────────────────────

    @Test
    fun `a phone with no cloud API lists its own analyses and says why the cloud half is missing`() {
        // With an API configured (a developer's local.properties), the signed-out
        // list waits on Firebase, which Robolectric never answers. CI builds
        // without one, which is the case this pins: the cloud is off, and the
        // list still shows what is on the phone.
        assumeTrue(BuildConfig.SEMPER_API_BASE_URL.isBlank())
        licensed()
        assertTrue(SessionStore.upsert(context, sessionRecord(id = "local-1"), allowOverLimit = true))
        assertTrue(SessionStore.upsert(context, sessionRecord(id = "local-2", createdAt = 2L), allowOverLimit = true))
        open()

        val count = view<TextView>(R.id.tvAnalysesDataCount)
        idleUntil("the analyses list to load") { count.visibility == View.VISIBLE }
        assertEquals(settings.resources.getQuantityString(R.plurals.analyses_data_count, 2, 2), count.text.toString())

        val state = view<TextView>(R.id.tvAnalysesDataState)
        assertEquals(View.VISIBLE, state.visibility)
        assertEquals(settings.getString(R.string.restore_api_off), state.text.toString())
    }
}
