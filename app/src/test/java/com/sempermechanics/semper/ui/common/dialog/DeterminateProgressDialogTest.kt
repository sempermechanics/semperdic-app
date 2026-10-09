package com.sempermechanics.semper.ui.common.dialog

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.dynamicanimation.animation.SpringFrames
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.sempermechanics.semper.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The bar springs to each value while the dialog shows, and a dismissed
 * dialog's spring ends on the next frames instead of running on through its
 * settle, holding the Activity (TD-200). Nothing in the dialog does that:
 * dismissing detaches the bar, and a detached view jumps its drawables to
 * their current state, which asks Material's spring to end on its next frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeterminateProgressDialogTest {

    private val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        .also { it.get().setTheme(R.style.Theme_Semper) }
        .setup()

    @After
    fun tearDown() {
        controller.close()
        SpringFrames.endAll()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun View.bar(): LinearProgressIndicator? = when (this) {
        is LinearProgressIndicator -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).bar() }
        else -> null
    }

    @Test
    fun `a dismissed dialog's spring ends on the next frames`() {
        val dialog = DeterminateProgressDialog(controller.get(), "Exporting")
        dialog.show()
        idle()
        val bar = checkNotNull(ShadowDialog.getLatestDialog().window?.decorView?.bar())
        val drawable = checkNotNull(bar.progressDrawable)
        // The first value turns the bar determinate; idling settles it.
        dialog.update(30.0)
        idle()

        // Not idled: the spring is still moving when the dialog goes, as when
        // a job finishes right after its last progress.
        dialog.update(60.0)
        assertEquals(600, bar.progress)
        assertTrue("the bar springs while the dialog shows", SpringFrames.running(drawable))

        dialog.dismiss()
        SpringFrames.step()
        assertFalse("a spring outlived the dialog", SpringFrames.running(drawable))
    }

    /** Every TextView's text under [this] that is on screen. */
    private fun View.shownTexts(): List<String> = when {
        visibility != View.VISIBLE -> emptyList()
        this is TextView -> listOf(text.toString())
        this is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).shownTexts() }
        else -> emptyList()
    }

    @Test
    fun `the status, the percent to a tenth and the time left once it is known`() {
        var now = 0L
        val dialog = DeterminateProgressDialog(controller.get(), "Exporting", clock = { now })
        dialog.show()
        idle()
        val root = checkNotNull(ShadowDialog.getLatestDialog().window?.decorView)
        val bar = checkNotNull(root.bar())
        assertTrue("spins until the first report", bar.isIndeterminate)

        dialog.update(0.0, "Frame 1 of 40 · heatmaps")
        idle()
        assertFalse(bar.isIndeterminate)
        var texts = root.shownTexts()
        assertTrue(texts.toString(), "Frame 1 of 40 · heatmaps" in texts && "0.0%" in texts)
        assertTrue("no time left before the estimate", texts.none { it.endsWith("left") })

        // A steady minute-long job, reported every half second for six seconds.
        while (now < 6_000L) {
            now += 500L
            dialog.update(now / 600.0)
        }
        idle()
        texts = root.shownTexts()
        assertTrue(texts.toString(), "10.0%" in texts && "About 54 s left" in texts)
        assertTrue("the status stays when an update has none", "Frame 1 of 40 · heatmaps" in texts)
        assertEquals(100, bar.progress)
    }
}
