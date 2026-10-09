package com.sempermechanics.semper.settings

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.dynamicanimation.animation.SpringFrames
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.transfer.TransferBannerController
import com.sempermechanics.semper.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * Banner chrome under a real themed Activity (Material indicators need a theme).
 * SettingsActivity already binds one controller; we exercise a second on a
 * freshly inflated copy of the banner layout hosted in the same activity.
 */
@RunWith(RobolectricTestRunner::class)
class TransferBannerControllerTest {

    /**
     * Every Settings a test opens, destroyed after it: a live one left behind
     * would still observe the account-deletion and sign-out runs of later tests.
     */
    private val built = mutableListOf<ActivityController<SettingsActivity>>()

    /**
     * The banner here is never in a window, so nothing ends its bar's spring,
     * and one still moving would hold its Settings for the rest of the run
     * (TD-200).
     */
    @After
    fun destroySettings() {
        built.forEach { runCatching { it.pause().stop().destroy() } }
        SpringFrames.endAll()
    }

    private lateinit var activity: SettingsActivity
    private lateinit var root: View
    private lateinit var controller: TransferBannerController

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().also { built += it }.get()
        root = activity.layoutInflater.inflate(R.layout.view_transfer_banner, null)
        controller = TransferBannerController(root)
    }

    private val bar get() = root.findViewById<LinearProgressIndicator>(R.id.transferProgress)

    @Test
    fun `single transfer shows progress without pager chrome`() {
        controller.upsert(
            TransferBannerController.Transfer(id = "a", title = "Export my data", percent = 40.0),
        )
        assertEquals(View.VISIBLE, root.visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.btnTransferPrev).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.btnTransferNext).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.tvTransferPage).visibility)
    }

    @Test
    fun `two transfers show arrows and page indicator`() {
        controller.upsert(TransferBannerController.Transfer(id = "a", title = "A", percent = 10.0))
        controller.upsert(TransferBannerController.Transfer(id = "b", title = "B", percent = 20.0))
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.btnTransferPrev).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.btnTransferNext).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.tvTransferPage).visibility)
        assertTrue(root.findViewById<TextView>(R.id.tvTransferPage).text.contains("2"))
    }

    @Test
    fun `cancel removes only the targeted transfer`() {
        var cancelled = false
        controller.upsert(
            TransferBannerController.Transfer(
                id = "a",
                title = "A",
                percent = 10.0,
                onCancel = {
                    cancelled = true
                    controller.remove("a")
                },
            ),
        )
        controller.upsert(TransferBannerController.Transfer(id = "b", title = "B", percent = 50.0))
        // Newest page is focused; move to first then cancel.
        root.findViewById<View>(R.id.btnTransferPrev).performClick()
        root.findViewById<View>(R.id.btnTransferCancel).performClick()
        assertTrue(cancelled)
        assertEquals(1, controller.size())
        assertFalse(controller.contains("a"))
        assertTrue(controller.contains("b"))
    }

    private fun text(id: Int): TextView = root.findViewById(id)

    @Test
    fun `a page reads its status, the percent to a tenth and the time left`() {
        var now = 0L
        val banner = TransferBannerController(root, clock = { now })
        banner.upsert(TransferBannerController.Transfer(id = "a", title = "Report"))
        // Nothing reported yet: a spinning bar and "Working…", no percent.
        assertTrue(bar.isIndeterminate)
        assertEquals(activity.getString(R.string.transfer_banner_working), text(R.id.tvTransferStatus).text.toString())
        assertEquals(View.GONE, text(R.id.tvTransferPercent).visibility)

        banner.updateProgress("a", 0.0, "Frame 1 of 40 · heatmaps")
        while (now < 6_000L) {
            now += 500L
            banner.updateProgress("a", now / 600.0)
        }
        assertFalse(bar.isIndeterminate)
        assertEquals("Frame 1 of 40 · heatmaps", text(R.id.tvTransferStatus).text.toString())
        assertEquals("10.0%", text(R.id.tvTransferPercent).text.toString())
        assertEquals(View.VISIBLE, text(R.id.tvTransferEta).visibility)
        assertEquals("About 54 s left", text(R.id.tvTransferEta).text.toString())
        assertEquals(100, bar.progress)
    }

    @Test
    fun `each transfer keeps its own time left`() {
        var now = 0L
        val banner = TransferBannerController(root, clock = { now })
        banner.upsert(TransferBannerController.Transfer(id = "a", title = "A"))
        banner.upsert(TransferBannerController.Transfer(id = "b", title = "B"))
        while (now < 6_000L) {
            now += 500L
            banner.updateProgress("a", now / 600.0)
        }
        banner.updateProgress("b", 50.0, "4.0 of 8.0 MB")
        // "b" is on screen: one sample, no estimate yet.
        assertEquals("50.0%", text(R.id.tvTransferPercent).text.toString())
        assertEquals(View.GONE, text(R.id.tvTransferEta).visibility)

        root.findViewById<View>(R.id.btnTransferPrev).performClick()
        assertEquals("About 54 s left", text(R.id.tvTransferEta).text.toString())

        banner.remove("a")
        banner.upsert(TransferBannerController.Transfer(id = "a", title = "A again"))
        banner.updateProgress("a", 1.0)
        assertEquals("a removed transfer starts its estimate over", View.GONE, text(R.id.tvTransferEta).visibility)
    }

    @Test
    fun `progress from a worker thread updates on the main thread`() {
        controller.upsert(TransferBannerController.Transfer(id = "a", title = "A", percent = 1.0))
        val worker = Thread {
            controller.updateProgress("a", 50.0, "halfway")
        }
        worker.start()
        worker.join()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(
            "halfway",
            root.findViewById<TextView>(R.id.tvTransferStatus).text.toString(),
        )
    }

    @Test
    fun `a banner out of any window keeps its spring until the test ends it`() {
        controller.upsert(TransferBannerController.Transfer(id = "a", title = "A", percent = 40.0))
        val drawable = checkNotNull(bar.progressDrawable)
        assertTrue(SpringFrames.running(drawable))

        SpringFrames.step()
        assertTrue("nothing asked this spring to end", SpringFrames.running(drawable))

        SpringFrames.endAll()
        assertFalse(SpringFrames.running(drawable))
        assertTrue("the handler would not ask for frames again", SpringFrames.idle)
    }

    @Test
    fun `a banner taken out of its window ends its spring on the next frames`() {
        val host = activity.findViewById<ViewGroup>(android.R.id.content)
        host.addView(root)
        controller.upsert(TransferBannerController.Transfer(id = "a", title = "A", percent = 40.0))
        val drawable = checkNotNull(bar.progressDrawable)
        assertTrue("the bar springs while shown", SpringFrames.running(drawable))

        // Detaching jumps a view's drawables to their current state, which
        // asks Material's spring to end on its next frame.
        host.removeView(root)
        SpringFrames.step()
        assertFalse("a spring outlived the banner", SpringFrames.running(drawable))
    }
}
