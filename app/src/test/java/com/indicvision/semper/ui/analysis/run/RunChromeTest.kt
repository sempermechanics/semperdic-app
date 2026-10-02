package com.indicvision.semper.ui.analysis.run

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The wizard is busy from the start of an import or run to its end, and its
 * Cancel asks first: confirming stops the work, "Keep running" leaves it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RunChromeTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var overlay: View
    private lateinit var cancel: Button
    private lateinit var chrome: RunChrome

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
            .get()
        overlay = View(activity).apply { visibility = View.GONE }
        cancel = Button(activity)
        val helper = ComputeOverlayHelper(
            overlay = overlay,
            title = TextView(activity),
            progress = ProgressBar(activity),
            percent = TextView(activity),
            status = TextView(activity),
            elapsed = TextView(activity),
        )
        chrome = RunChrome(activity, helper, cancel)
    }

    private fun screenKeptOn() =
        shadowOf(activity.window).getFlag(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog

    @Test
    fun `a run shows the overlay, keeps the screen on and arms Cancel until it ends`() {
        assertFalse(chrome.isBusy)
        chrome.beginRun {}

        assertTrue(chrome.isBusy)
        assertEquals(View.VISIBLE, overlay.visibility)
        assertTrue(screenKeptOn())
        assertTrue(cancel.isEnabled)

        chrome.end()
        assertFalse(chrome.isBusy)
        assertEquals(View.GONE, overlay.visibility)
        assertFalse(screenKeptOn())
        assertFalse(cancel.isEnabled)
    }

    @Test
    fun `Cancel asks about the run, and confirming stops it`() {
        var stopped = false
        chrome.beginRun { stopped = true }
        cancel.performClick()

        val asked = dialog()
        assertEquals(
            activity.getString(R.string.cancel_run_title),
            asked.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString(),
        )
        assertEquals(activity.getString(R.string.keep_running), asked.getButton(AlertDialog.BUTTON_NEGATIVE).text)
        asked.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(stopped)
        assertFalse("Cancel is spent once confirmed", cancel.isEnabled)
    }

    @Test
    fun `keep running leaves the import going`() {
        var stopped = false
        chrome.beginImport { stopped = true }
        chrome.confirmCancel()

        val asked = dialog()
        assertEquals(
            activity.getString(R.string.cancel_import_body),
            asked.findViewById<TextView>(android.R.id.message)?.text.toString(),
        )
        asked.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(stopped)
        assertTrue(chrome.isBusy)
        assertFalse("an import keeps the screen's own setting", screenKeptOn())
    }

    @Test
    fun `nothing to cancel asks nothing`() {
        val before = ShadowDialog.getLatestDialog()
        chrome.confirmCancel()
        assertEquals(before, ShadowDialog.getLatestDialog())
    }
}
