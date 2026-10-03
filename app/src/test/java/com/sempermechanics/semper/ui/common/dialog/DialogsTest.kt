package com.sempermechanics.semper.ui.common.dialog

import android.app.Application
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.common.auth.confirm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The info and confirm alerts, the "i" button wiring and the sign-out confirm:
 * the title, body and button labels each screen used, and which button acts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DialogsTest {

    private lateinit var activity: AppCompatActivity

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        activity = built.setup().get()
        SignOutRun.resetForTest()
    }

    @After
    fun tearDown() {
        idle()
        SignOutRun.resetForTest()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun AlertDialog.title(): String =
        findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString()

    private fun AlertDialog.body(): String = findViewById<TextView>(android.R.id.message)?.text.toString()

    private fun AlertDialog.label(which: Int): String = getButton(which).text.toString()

    private fun AlertDialog.hasButton(which: Int): Boolean = getButton(which)?.visibility == View.VISIBLE

    @Test
    fun `info shows title, body and OK only`() {
        val dialog = Dialogs.info(activity, R.string.storage_auto_free, R.string.storage_auto_free_info)

        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.storage_auto_free), dialog.title())
        assertEquals(activity.getString(R.string.storage_auto_free_info), dialog.body())
        assertEquals(activity.getString(android.R.string.ok), dialog.label(DialogInterface.BUTTON_POSITIVE))
        assertFalse(dialog.hasButton(DialogInterface.BUTTON_NEGATIVE))
        assertFalse(dialog.hasButton(DialogInterface.BUTTON_NEUTRAL))

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `confirm runs only on its positive button`() {
        var confirmed = 0
        val cancel = Dialogs.confirm(
            activity,
            R.string.storage_free_up_title,
            R.string.storage_auto_free_info,
            R.string.storage_free_up_confirm,
        ) { confirmed++ }
        assertEquals(activity.getString(R.string.storage_free_up_title), cancel.title())
        assertEquals(
            activity.getString(R.string.storage_free_up_confirm),
            cancel.label(DialogInterface.BUTTON_POSITIVE),
        )
        assertEquals(activity.getString(R.string.action_cancel), cancel.label(DialogInterface.BUTTON_NEGATIVE))
        cancel.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(0, confirmed)
        assertFalse(cancel.isShowing)

        val ok = Dialogs.confirm(activity, "Exit?", "Unsaved work is lost.", R.string.exit) { confirmed++ }
        assertEquals("Exit?", ok.title())
        assertEquals("Unsaved work is lost.", ok.body())
        assertEquals(activity.getString(R.string.action_cancel), ok.label(DialogInterface.BUTTON_NEGATIVE))
        // The screens that used R.string.cancel lose no text by moving to action_cancel.
        assertEquals(activity.getString(R.string.cancel), activity.getString(R.string.action_cancel))
        ok.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(1, confirmed)
        assertFalse(ok.isShowing)
    }

    @Test
    fun `an info button opens its dialog on each tap`() {
        val button = View(activity)
        button.bindInfo(activity, R.string.setting_max_frames, R.string.setting_max_frames_info)

        button.performClick()
        val first = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(activity.getString(R.string.setting_max_frames), first.title())
        assertEquals(activity.getString(R.string.setting_max_frames_info), first.body())

        first.dismiss()
        button.performClick()
        val second = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(second.isShowing)
        assertFalse(first === second)
    }

    @Test
    fun `sign-out confirm starts the run for this screen only when confirmed`() {
        var signedOut = 0
        val dialog = SignOutRun.confirm(activity) { signedOut++ }
        assertEquals(activity.getString(R.string.logout_confirm_title), dialog.title())
        assertEquals(activity.getString(R.string.logout_confirm_body), dialog.body())
        assertEquals(activity.getString(R.string.action_sign_out), dialog.label(DialogInterface.BUTTON_POSITIVE))
        assertEquals(activity.getString(R.string.action_cancel), dialog.label(DialogInterface.BUTTON_NEGATIVE))

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertSame(SignOutRun.State.Idle, SignOutRun.state.value)

        val again = SignOutRun.confirm(activity, R.string.action_log_out) { signedOut++ }
        assertEquals(activity.getString(R.string.action_log_out), again.label(DialogInterface.BUTTON_POSITIVE))
        again.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(1, signedOut)
        assertEquals(SignOutRun.State.Done(activity.javaClass), SignOutRun.state.value)
    }
}
