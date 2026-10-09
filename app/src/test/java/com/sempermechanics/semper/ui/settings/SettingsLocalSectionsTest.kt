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
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.admin.AdminActivity
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
    fun `a held licence that is not active shows no licence row`() {
        // An older backend sends the prefix of a revoked, lapsed or unseated
        // key while the account runs as demo (TD-145).
        AppRemoteConfig.apply(context, AppConfigDto(mode = "demo", maxSessions = 5, licensePrefix = "SEMP-DEMO"))
        open()

        assertEquals(View.GONE, view<View>(R.id.tvAccountLicense).visibility)
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

    @Test
    fun `a demo account has no free-up or auto-free controls, only the cache`() {
        demo()
        open()

        listOf(R.id.btnStorageFreeUp, R.id.tvStorageFreeUpSub, R.id.rowAutoFreeHeader, R.id.sliderAutoFree)
            .forEach { assertEquals("view $it", View.GONE, view<View>(it).visibility) }
        assertEquals(View.VISIBLE, view<View>(R.id.btnStorageClearCache).visibility)
    }

    @Test
    fun `Clear deletes the temporary files and disables itself once nothing is left`() {
        demo()
        val leftover = File(context.cacheDir, CacheJanitor.TEMP_ROI_REF).apply { writeBytes(ByteArray(4096)) }
        open()
        val clear = view<View>(R.id.btnStorageClearCache)
        idleUntil("the cache size to load") { clear.isEnabled }
        assertFalse(view<TextView>(R.id.tvStorageCacheSize).text.isNullOrBlank())

        clear.performClick()
        idleUntil("the clear to finish") { !leftover.exists() && !clear.isEnabled }
    }

    @Test
    fun `with nothing backed up, free-up is disabled and says so, and auto-free reads off`() {
        licensed()
        AppSettings.setAutoFreeBudgetGb(context, AppSettings.AUTO_FREE_OFF)
        open()

        val sub = view<TextView>(R.id.tvStorageFreeUpSub)
        idleUntil("the reclaimable size to load") { sub.text == settings.getString(R.string.storage_free_up_none) }
        assertFalse(view<View>(R.id.btnStorageFreeUp).isEnabled)
        assertEquals(settings.getString(R.string.storage_auto_free_off), view<TextView>(R.id.tvAutoFreeValue).text)
    }

    @Test
    fun `moving the auto-free slider in code relabels it without storing a budget`() {
        licensed()
        AppSettings.setAutoFreeBudgetGb(context, AppSettings.AUTO_FREE_OFF)
        open()

        view<Slider>(R.id.sliderAutoFree).value = 8f
        assertEquals(
            settings.getString(R.string.storage_auto_free_on_fmt, 8),
            view<TextView>(R.id.tvAutoFreeValue).text,
        )
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
