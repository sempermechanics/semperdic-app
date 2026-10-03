package com.sempermechanics.semper.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.DicSettings
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog

/**
 * The sections split out of SettingsActivity, on the real screen of a
 * licensed account with cloud backup: Cloud backup's switches and the
 * footer's About and Sign out. The Analyses data section lists the cloud
 * over the network, so its transfer reactions are pinned through the
 * screen in TransferReactionsTest instead.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsSectionsTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<SettingsActivity>
    private val settings: SettingsActivity get() = controller.get()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AppRemoteConfig.apply(context, AppConfigDto(mode = "licensed", cloudBackupEnabled = true, maxSessions = 50))
        SignOutRun.resetForTest()
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        runCatching { controller.pause().stop().destroy() }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        AppRemoteConfig.clear(context)
        SignOutRun.resetForTest()
    }

    private fun open() {
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        shadowOf(settings.mainLooper).idle()
    }

    private fun idle() = shadowOf(settings.mainLooper).idle()

    // ── SettingsCloudSection ─────────────────────────────────────────────

    @Test
    fun `the cloud switches show and store the backup preferences`() {
        DicSettings.setSaveToCloudEnabled(context, false)
        open()
        val save = settings.findViewById<CompoundButton>(R.id.switchSaveCloud)
        val wifi = settings.findViewById<CompoundButton>(R.id.switchWifiOnly)
        val sub = settings.findViewById<TextView>(R.id.tvSaveCloudSub)
        assertFalse(save.isChecked)
        assertEquals(settings.getString(R.string.settings_save_cloud_sub_off), sub.text)

        save.isChecked = true
        idle()
        assertTrue(DicSettings.saveToCloudEnabled(context))
        assertEquals(settings.getString(R.string.settings_save_cloud_sub), sub.text)

        val wifiBefore = DicSettings.wifiOnlyUploadEnabled(context)
        wifi.isChecked = !wifi.isChecked
        assertEquals(!wifiBefore, DicSettings.wifiOnlyUploadEnabled(context))
    }

    // ── SettingsFooterSection ────────────────────────────────────────────

    @Test
    fun `About names the version, and Sign out asks first`() {
        open()

        settings.findViewById<android.view.View>(R.id.btnAbout).performClick()
        idle()
        val about = ShadowDialog.getLatestDialog() as AlertDialog
        val message = about.findViewById<TextView>(android.R.id.message)?.text.toString()
        assertTrue(message, message.contains(BuildConfig.VERSION_NAME))
        about.dismiss()

        settings.findViewById<android.view.View>(R.id.btnSignOut).performClick()
        idle()
        val confirm = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(settings.getString(R.string.action_sign_out), confirm.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertEquals("nothing runs before the confirm", SignOutRun.State.Idle, SignOutRun.state.value)
    }
}
