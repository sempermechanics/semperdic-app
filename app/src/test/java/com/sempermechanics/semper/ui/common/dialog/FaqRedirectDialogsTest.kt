package com.sempermechanics.semper.ui.common.dialog

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.auth.confirm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * The FAQ hop's dialogs: the leave-the-app confirm, what it opens, and the
 * error alert with and without its **Why?** button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FaqRedirectDialogsTest {

    private lateinit var activity: AppCompatActivity
    private val url = "https://example.com/faq#codec"

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        activity = built.setup().get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun latest(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    private fun AlertDialog.title(): String =
        findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString()

    private fun AlertDialog.body(): String = findViewById<TextView>(android.R.id.message)?.text.toString()

    private fun AlertDialog.shows(which: Int): Boolean = getButton(which)?.visibility == View.VISIBLE

    @Test
    fun `confirm asks before leaving, and Open goes to the page`() {
        FaqRedirect.confirm(activity, url)
        val dialog = latest()

        assertEquals(activity.getString(R.string.faq_redirect_title), dialog.title())
        assertEquals(activity.getString(R.string.faq_redirect_body), dialog.body())
        assertEquals(activity.getString(R.string.faq_redirect_open), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertNull("nothing opens before the choice", shadowOf(activity).nextStartedActivity)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(url, opened.dataString)
    }

    @Test
    fun `Cancel leaves the user here`() {
        FaqRedirect.confirm(activity, url)
        latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()

        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `with no browser the address is shown instead`() {
        // Nothing resolves ACTION_VIEW, so starting it throws, as with no browser.
        shadowOf(activity.application).checkActivities(true)
        FaqRedirect.confirm(activity, url)
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        assertEquals(url, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `an error with a FAQ link offers Why, which confirms first`() {
        FaqRedirect.errorDialog(activity, "Title", "Body", R.string.legal_terms_url)
        val dialog = latest()

        assertEquals("Title", dialog.title())
        assertEquals("Body", dialog.body())
        assertEquals(activity.getString(android.R.string.ok), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertTrue(dialog.shows(AlertDialog.BUTTON_NEUTRAL))
        assertEquals(activity.getString(R.string.action_why), dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text)

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        assertEquals(activity.getString(R.string.faq_redirect_title), latest().title())
    }

    @Test
    fun `an error with no FAQ link has only OK`() {
        FaqRedirect.errorDialog(activity, "Title", "Body", null)
        val dialog = latest()

        assertEquals("Title", dialog.title())
        assertEquals("Body", dialog.body())
        assertTrue(dialog.shows(AlertDialog.BUTTON_POSITIVE))
        assertFalse(dialog.shows(AlertDialog.BUTTON_NEUTRAL))
        assertFalse(dialog.shows(AlertDialog.BUTTON_NEGATIVE))
    }
}
