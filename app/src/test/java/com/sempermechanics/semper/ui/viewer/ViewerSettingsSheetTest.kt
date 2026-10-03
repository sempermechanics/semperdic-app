package com.sempermechanics.semper.ui.viewer

import android.text.Spanned
import android.text.style.ImageSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.launchViewer
import com.sempermechanics.semper.fixtures.viewerArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The viewer's provenance sheet: the rows it reads off the viewer's arguments
 * for the frame on screen. It is where "what produced this result, and why is
 * it short?" gets answered months later, so a row that shows the run's first
 * setting instead of the frame's, or hides why a run stopped, misleads.
 */
@RunWith(RobolectricTestRunner::class)
class ViewerSettingsSheetTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 3
        const val GRID = 4
        const val STEP = 4
    }

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        for (f in 0 until FRAMES) {
            val points = GRID * GRID
            val buffer = ByteBuffer.allocate(points * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
            for (i in 0 until points) {
                buffer.putFloat(((i % GRID) * STEP).toFloat())
                buffer.putFloat(((i / GRID) * STEP).toFloat())
                buffer.putFloat(f.toFloat()).putFloat(0f)
                buffer.putFloat(0.001f * (i % GRID)).putFloat(0.002f).putFloat(0f)
                buffer.putFloat(0.01f)
            }
            File(batchDir, "frame_%03d.dat".format(f)).writeBytes(buffer.array())
        }
    }

    /** A plain three-frame run over the whole 16×16 image, opened on frame 1. */
    private fun baseArgs() = viewerArgs(batchDir, GRID, STEP).copy(strainWindow = 25, refName = "Dogbone A")

    private fun viewer(args: ViewerArgs = baseArgs()): ResultViewerActivity {
        val activity = launchViewer(args)
        idleUntil("the viewer") { activity.rawData != null }
        return activity
    }

    private fun ResultViewerActivity.rows(): Map<String, String> = ViewerSettingsSheet.entriesFor(this).toMap()

    @Test
    fun `a plain run lists its settings, ROI and image size in order`() {
        val host = viewer()
        val entries = ViewerSettingsSheet.entriesFor(host)

        assertEquals(
            listOf(
                host.getString(R.string.setting_subset) to "41 px",
                host.getString(R.string.setting_step) to "4 px",
                // VSG 25 px at step 4 is a 7-point window.
                host.getString(R.string.setting_strain_window) to "7-point window · VSG 25 px",
                host.getString(R.string.setting_strain_method) to "VSG",
                host.getString(R.string.setting_roi) to "16 × 16 at (0, 0)",
                host.getString(R.string.setting_image_size) to "16 × 16 px",
            ),
            entries,
        )
    }

    @Test
    fun `no ROI row when none was recorded`() {
        val host = viewer(baseArgs().copy(roiW = 0, roiH = 0))
        assertTrue(host.getString(R.string.setting_roi) !in host.rows())
    }

    @Test
    fun `a run that stopped early says why and how far it got`() {
        val code = 3
        val host = viewer(baseArgs().copy(stopCode = code, plannedFrames = 5))
        val entries = ViewerSettingsSheet.entriesFor(host)
        val labels = entries.map { it.first }
        val rows = entries.toMap()

        assertEquals(
            "Unknown engine error (code 3)",
            rows[host.getString(R.string.setting_stopped_early)],
        )
        assertEquals("3 of 5 frames", rows[host.getString(R.string.setting_frames_solved)])
        assertEquals(
            "stop rows sit between the method and the ROI",
            labels.indexOf(host.getString(R.string.setting_strain_method)) + 1,
            labels.indexOf(host.getString(R.string.setting_stopped_early)),
        )
    }

    @Test
    fun `an old record with no planned count still says why it stopped`() {
        val host = viewer(baseArgs().copy(stopCode = 3, plannedFrames = 0))
        val rows = host.rows()

        assertTrue(host.getString(R.string.setting_stopped_early) in rows)
        assertTrue(host.getString(R.string.setting_frames_solved) !in rows)
    }

    @Test
    fun `a finished run has no stop rows`() {
        val rows = viewer().rows()
        assertTrue(rows.keys.none { it == "Stopped early" || it == "Frames solved" })
    }

    private fun sweepArgs(startFrame: Int) = baseArgs().copy(
        startFrame = startFrame,
        sweep = ViewerSweepArgs(
            subsets = listOf(21, 31, 41),
            steps = listOf(STEP, STEP, STEP),
            strainWindows = listOf(17, 33, 41),
            lineCutHorizontal = true,
            skippedJson = "[]",
        ),
    )

    @Test
    fun `a sweep describes the combination on screen, not the first one`() {
        val host = viewer(sweepArgs(startFrame = 1))
        val rows = host.rows()

        assertEquals("31 px", rows[host.getString(R.string.setting_subset)])
        assertEquals("9-point window · VSG 33 px", rows[host.getString(R.string.setting_strain_window)])
    }

    @Test
    fun `the sheet shows the specimen and one row per entry, divided`() {
        val host = viewer()
        ViewerSettingsSheet.show(host)
        val sheet = ShadowDialog.getLatestDialog()
        val rows = sheet.findViewById<LinearLayout>(R.id.settingsUsedRows)
        val entries = ViewerSettingsSheet.entriesFor(host)

        assertTrue(sheet.isShowing)
        assertEquals("Dogbone A", sheet.findViewById<TextView>(R.id.tvSettingsUsedSpecimen).text.toString())
        assertEquals(entries.size * 2 - 1, rows.childCount)
        val first = rows.getChildAt(0) as LinearLayout
        assertEquals(entries[0].first, (first.getChildAt(0) as TextView).text.toString())
        assertEquals(entries[0].second, (first.getChildAt(1) as TextView).text.toString())
        assertEquals(
            "a still frame shows its histogram",
            View.VISIBLE,
            sheet.findViewById<View>(R.id.distributionSection).visibility,
        )
    }

    @Test
    fun `a sweep's sheet adds the line cut through the ROI centre`() {
        val host = viewer(sweepArgs(startFrame = 0))
        ViewerSettingsSheet.show(host)
        val sheet = ShadowDialog.getLatestDialog()

        assertEquals(View.VISIBLE, sheet.findViewById<View>(R.id.lineCutSection).visibility)
        val legend = sheet.findViewById<TextView>(R.id.tvLineCutLegend).text.toString()
        val axis = host.getString(R.string.axis_x)
        assertTrue(legend, legend.startsWith("Cut along $axis through the ROI centre at 8 px."))
    }

    @Test
    fun `the line cut legend gives each strain component its own swatch`() {
        val host = viewer()
        val legend = ViewerSettingsSheet.lineCutLegend(host, "y", 12f)
        val text = legend.toString()

        assertTrue(text, text.startsWith("Cut along y through the ROI centre at 12 px."))
        val swatches = (legend as Spanned).getSpans(0, legend.length, ImageSpan::class.java)
        assertEquals(3, swatches.size)
        // Each swatch sits right before its label.
        listOf("Exx", "Eyy", "Exy").forEachIndexed { i, label ->
            val end = legend.getSpanEnd(swatches[i])
            assertEquals(" $label", text.substring(end, end + label.length + 1))
        }
    }
}
