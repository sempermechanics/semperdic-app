package com.sempermechanics.semper.viewer

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.ui.common.InlineBusy
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import com.sempermechanics.semper.ui.viewer.ViewerCaptions
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** While its first frame loads, the viewer says what it is opening; the pill goes once that frame draws. */
@RunWith(RobolectricTestRunner::class)
class ViewerOpeningTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `the pill names the analysis and counts its frames`() {
        assertEquals("Opening steel_00 · 40 frames", ViewerCaptions.openingText(resources, "steel_00.tif", 40))
        assertEquals("Opening steel_00 · 1 frame", ViewerCaptions.openingText(resources, "steel_00.tif", 1))
        assertEquals("Opening steel_00", ViewerCaptions.openingText(resources, "steel_00", 0))
        assertEquals("Opening analysis · 3 frames", ViewerCaptions.openingText(resources, "", 3))
    }

    @Test
    fun `a slow open shows the pill, and the first frame drawn takes it away`() {
        val batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, FRAMES, GRID, STEP)
        val names = (1..FRAMES).map { "frame_$it.png" }
        val intent = viewerArgs(batchDir, GRID, STEP, names).toIntent(ApplicationProvider.getApplicationContext())
        val controller = Robolectric.buildActivity(ResultViewerActivity::class.java, intent)
        // Hold the frame listing, so the open lasts as long as the test says.
        val held = mutableListOf<Runnable>()
        controller.get().frameSetDispatcher = Executor { held += it }.asCoroutineDispatcher()
        val activity = controller.setup().get()
        val pill = activity.findViewById<View>(R.id.viewerOpening)
        val looper = shadowOf(activity.mainLooper)

        // Setup itself moves the clock a little, so check before the first 300 ms is up, then after.
        assertEquals("nothing for the first 300 ms", View.GONE, pill.visibility)
        looper.idleFor(InlineBusy.SHOW_AFTER_MS, TimeUnit.MILLISECONDS)
        assertEquals(View.VISIBLE, pill.visibility)
        assertEquals(
            "Opening analysis · 3 frames",
            activity.findViewById<TextView>(R.id.tvViewerOpening).text.toString(),
        )

        held.toList().forEach { it.run() }
        idleUntil("the first frame drawn") {
            activity.findViewById<ImageView>(R.id.imgHeatmapOverlay).drawable != null
        }
        assertEquals(View.GONE, pill.visibility)
    }

    private companion object {
        const val FRAMES = 3
        const val GRID = 4
        const val STEP = 4
    }
}
