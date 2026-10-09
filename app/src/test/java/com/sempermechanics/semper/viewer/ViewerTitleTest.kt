package com.sempermechanics.semper.viewer

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.launchViewer
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import com.sempermechanics.semper.ui.viewer.ViewerArgs
import com.sempermechanics.semper.ui.viewer.ViewerCaptions
import com.sempermechanics.semper.ui.viewer.ViewerSweepArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/**
 * The viewer's edge title and the pill under the frame. The field chip names
 * the field, so the title names what is on screen — "Summary", the frame's
 * file name, or a sweep node's subset and window — and the pill only counts.
 */
@RunWith(RobolectricTestRunner::class)
class ViewerTitleTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, FRAMES, GRID, STEP)
    }

    private fun viewer(args: ViewerArgs): ResultViewerActivity =
        launchViewer(args).also { activity ->
            idleUntil("the viewer") { activity.frameSetLoaded && activity.rawData != null }
        }

    private fun ResultViewerActivity.title(): String = findViewById<TextView>(R.id.tvFinding).text.toString()

    private fun ResultViewerActivity.pill(): String = findViewById<TextView>(R.id.tvFrameCounter).text.toString()

    private fun ResultViewerActivity.click(id: Int) {
        findViewById<View>(id).performClick()
        shadowOf(mainLooper).idle()
    }

    // ── The words ────────────────────────────────────────────────────────

    @Test
    fun `a frame is titled by its file name without the extension`() {
        assertEquals("steel_03", ViewerCaptions.frameTitle(resources, "steel_03.tif"))
        assertEquals("plate.v2", ViewerCaptions.frameTitle(resources, "plate.v2.png"))
        assertEquals("an unnamed frame keeps its number", "Frame 3", ViewerCaptions.frameTitle(resources, "Frame 3"))
        assertEquals("a bare extension is not emptied", ".png", ViewerCaptions.frameTitle(resources, ".png"))
    }

    @Test
    fun `a sweep node is titled by its subset and its window in points`() {
        // A 3-point window at step 5 is an 11 px VSG; the label said W3, the engine got 11.
        val node = DicParams(subset = 15, step = 5, strainWindow = 11)
        assertEquals("Subset 15 · window 3", ViewerCaptions.frameTitle(resources, "S15 · St5 · W3", node))
        assertEquals(
            "Subset 35 · window 11",
            ViewerCaptions.frameTitle(resources, "S35 · St7 · W11", DicParams(35, 7, 71)),
        )
    }

    @Test
    fun `a sweep node from before windows were counted in points keeps its label`() {
        // 12 px at step 5 is no whole odd window.
        val node = DicParams(subset = 15, step = 5, strainWindow = 12)
        assertEquals("S15 · St5 · VSG 12", ViewerCaptions.frameTitle(resources, "S15 · St5 · VSG 12", node))
    }

    // ── On a real viewer ─────────────────────────────────────────────────

    @Test
    fun `a photo frame is titled by its name, and the pill counts it`() {
        val activity = viewer(viewerArgs(batchDir, GRID, STEP, (1..FRAMES).map { "steel_0$it.tif" }))
        idleUntil("the first title") { activity.title() == "steel_01" }
        assertEquals("1 / 3", activity.pill())

        activity.click(R.id.btnNextFrame)
        shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(100))
        idleUntil("the second frame's title") { activity.title() == "steel_02" }
        assertEquals("2 / 3", activity.pill())
    }

    @Test
    fun `the summary is titled Summary and its pill counts the frames it plays`() {
        val activity = viewer(viewerArgs(batchDir, GRID, STEP, (1..FRAMES).map { "steel_0$it.tif" }))

        activity.click(R.id.btnPrevFrame)

        assertTrue(activity.isShowingSummary)
        assertEquals("Summary", activity.title())
        assertEquals("3 frames", activity.pill())
    }

    @Test
    fun `a sweep node is titled by its parameters, not its stored label`() {
        val sweep = ViewerSweepArgs(
            subsets = listOf(15, 25, 35),
            steps = List(FRAMES) { STEP },
            // 3, 7 and 11-point windows at step 4.
            strainWindows = listOf(9, 25, 41),
            lineCutHorizontal = true,
            skippedJson = "[]",
        )
        val labels = listOf("S15 · St4 · W3", "S25 · St4 · W7", "S35 · St4 · W11")
        val activity = viewer(viewerArgs(batchDir, GRID, STEP, labels).copy(sweep = sweep))
        idleUntil("the node's title") { activity.title() == "Subset 15 · window 3" }
        assertEquals("1 / 3", activity.pill())

        activity.click(R.id.btnNextFrame)
        shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(100))
        idleUntil("the next node's title") { activity.title() == "Subset 25 · window 7" }
    }

    private companion object {
        const val FRAMES = 3
        const val GRID = 4
        const val STEP = 4
    }
}
