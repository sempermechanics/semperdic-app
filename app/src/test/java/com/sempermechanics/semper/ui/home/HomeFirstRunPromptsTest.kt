package com.sempermechanics.semper.ui.home

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.prefs.DicSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * Home's first-run prompts come one at a time: the beta / data-use notice, then
 * the diagnostics choice. They used to open together, the notice on top.
 */
@RunWith(RobolectricTestRunner::class)
class HomeFirstRunPromptsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Home observes upload work in onCreate; the app starts WorkManager itself.
    @Before
    fun startWorkManager() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    // WorkManager is a static singleton and Robolectric keeps statics between
    // test classes; other tests rely on it not being started (RestoreStartTest).
    // The helper set it through setDelegate, so clearing that undoes it.
    @SuppressLint("RestrictedApi")
    @After
    fun stopWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun home(): HomeActivity {
        val activity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        shadowOf(activity.mainLooper).idle()
        return activity
    }

    private fun showing(): List<Dialog> = ShadowDialog.getShownDialogs().filter { it.isShowing }

    private fun Dialog.message(): String = findViewById<TextView>(android.R.id.message).text.toString()

    @Test
    fun `the diagnostics prompt waits for the beta notice`() {
        val activity = home()

        val first = showing()
        assertEquals("one prompt at a time", 1, first.size)
        assertEquals(activity.getString(R.string.beta_notice_body), first.single().message())

        (first.single() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(activity.mainLooper).idle()

        assertTrue(TokenStore.hasAckedBetaNotice(activity))
        val second = showing()
        assertEquals("one prompt at a time", 1, second.size)
        assertEquals(activity.getString(R.string.diagnostics_prompt_body), second.single().message())
    }

    @Test
    fun `with the notice acked the diagnostics prompt comes straight up`() {
        TokenStore.setBetaNoticeAcked(context)

        val activity = home()

        val shown = showing()
        assertEquals(1, shown.size)
        assertEquals(activity.getString(R.string.diagnostics_prompt_body), shown.single().message())
    }

    @Test
    fun `nothing is asked once both are answered`() {
        TokenStore.setBetaNoticeAcked(context)
        DicSettings.setDiagnosticsEnabled(context, false)
        assertTrue("setting the choice records it as asked", DicSettings.diagnosticsAsked(context))

        home()

        assertFalse(showing().any { it is AlertDialog })
    }
}
