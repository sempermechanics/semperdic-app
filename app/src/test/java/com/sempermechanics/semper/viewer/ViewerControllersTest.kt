package com.sempermechanics.semper.viewer

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.launchViewer
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import com.sempermechanics.semper.ui.viewer.ViewerSweepArgs
import com.sempermechanics.semper.ui.viewer.share.ShareExportBuilder
import com.sempermechanics.semper.ui.viewer.share.ShareKind
import com.sempermechanics.semper.util.Mime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration
import java.util.zip.ZipFile

/**
 * The viewer's controllers, each through what it owns on a real viewer:
 * [com.sempermechanics.semper.ui.viewer.FrameJumpController]'s Prev / Next and
 * summary slot, [com.sempermechanics.semper.ui.viewer.ViewerScaleController]'s
 * fixed colour scale, [com.sempermechanics.semper.ui.viewer.ViewerChromeController]'s
 * auto-hide, [com.sempermechanics.semper.ui.viewer.ViewerShareController]'s
 * export snapshot, and [ShareExportBuilder] for the kinds Robolectric can draw
 * (the PDF kinds need the platform's PdfDocument; PdfReportDeviceTest).
 */
@RunWith(RobolectricTestRunner::class)
class ViewerControllersTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 3
        const val GRID = 4
        const val STEP = 4

        /** Past the chrome's 2.5 s auto-hide and its fade. */
        val PAST_AUTO_HIDE: Duration = Duration.ofMillis(3_500)
    }

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, FRAMES, GRID, STEP)
    }

    private fun viewer(frameNames: List<String> = emptyList()): ResultViewerActivity =
        launchViewer(viewerArgs(batchDir, GRID, STEP, frameNames)).also { activity ->
            idleUntil("the viewer") { activity.frameSetLoaded && activity.rawData != null }
        }

    private fun ResultViewerActivity.idle() = shadowOf(mainLooper).idle()

    private fun ResultViewerActivity.click(id: Int) {
        findViewById<View>(id).performClick()
        idle()
    }

    private fun ResultViewerActivity.frameField(): String = findViewById<EditText>(R.id.etFrameNumber).text.toString()

    // ── FrameJumpController ──────────────────────────────────────────────

    @Test
    fun `Next walks the frames and stops at the last, which disables it`() {
        val activity = viewer()
        val next = activity.findViewById<View>(R.id.btnNextFrame)

        repeat(FRAMES + 2) { activity.click(R.id.btnNextFrame) }

        assertEquals(FRAMES - 1, activity.currentFrameIndex)
        assertEquals(FRAMES.toString(), activity.frameField())
        assertFalse(next.isEnabled)
        assertEquals(0.5f, next.alpha)
        assertTrue(activity.findViewById<View>(R.id.btnPrevFrame).isEnabled)
    }

    @Test
    fun `Prev from the first frame opens the summary, and Next leaves it for frame 1`() {
        val activity = viewer()

        activity.click(R.id.btnPrevFrame)
        assertTrue(activity.isShowingSummary)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.layoutFrameJump).visibility)
        assertEquals("the frame field is empty on the summary", "", activity.frameField())
        assertFalse("nothing comes before the summary", activity.findViewById<View>(R.id.btnPrevFrame).isEnabled)

        activity.click(R.id.btnNextFrame)
        assertFalse(activity.isShowingSummary)
        assertEquals(0, activity.currentFrameIndex)
        assertEquals("1", activity.frameField())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.layoutFrameJump).visibility)
    }

    @Test
    fun `a burst of Next decodes only the frame it settles on`() {
        val activity = viewer()
        val firstFrame = activity.rawData

        activity.findViewById<View>(R.id.btnNextFrame).performClick()
        activity.findViewById<View>(R.id.btnNextFrame).performClick()
        // The number follows the buttons at once; the decode waits out the debounce.
        assertEquals("3", activity.frameField())
        assertTrue("decoded before the burst settled", activity.rawData === firstFrame)
        shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(100))
        idleUntil("the settled frame") { activity.rawData !== firstFrame }

        val u = activity.rawData!![DicResult.IDX_U]
        assertEquals("the settled frame's own field (u = frame index)", (FRAMES - 1).toFloat(), u)
    }

    // ── ViewerScaleController ────────────────────────────────────────────

    private fun ResultViewerActivity.openScaleDialog(): AlertDialog {
        scale.showCustomScaleDialog()
        idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    @Test
    fun `a fixed scale is stored in the field's own unit and Auto clears it`() {
        val activity = viewer()
        activity.currentDataIndex = DicResult.IDX_EXX
        activity.idle()

        val dialog = activity.openScaleDialog()
        dialog.findViewById<EditText>(R.id.etScaleMin)!!.setText("-2")
        dialog.findViewById<EditText>(R.id.etScaleMax)!!.setText("3")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.idle()

        // Typed in millistrain, kept in strain — what the renderer compares.
        val fixed = activity.customBoundsFor(DicResult.IDX_EXX)
        assertNotNull(fixed)
        assertEquals(-0.002f, fixed!!.min, 1e-7f)
        assertEquals(0.003f, fixed.max, 1e-7f)
        assertNull("other fields keep their auto scale", activity.customBoundsFor(DicResult.IDX_U))

        activity.openScaleDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        activity.idle()
        assertNull(activity.customBoundsFor(DicResult.IDX_EXX))
    }

    @Test
    fun `a scale whose max is not above its min is refused`() {
        val activity = viewer()
        val dialog = activity.openScaleDialog()
        dialog.findViewById<EditText>(R.id.etScaleMin)!!.setText("5")
        dialog.findViewById<EditText>(R.id.etScaleMax)!!.setText("5")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.idle()

        assertNull(activity.customBoundsFor(activity.currentDataIndex))
    }

    @Test
    fun `the scale's ends are labelled with the field's unit once a frame is drawn`() {
        val activity = viewer()
        val max = activity.findViewById<TextView>(R.id.tvScaleMax)
        idleUntil("the heatmap") { activity.scale.cachedHeatmap != null }

        val unit = activity.getString(R.string.scale_unit_px)
        assertTrue(max.text.toString(), max.text.contains(unit))
    }

    // ── ViewerChromeController ───────────────────────────────────────────

    @Test
    fun `the chrome hides itself after a pause and a tap brings it back`() {
        val activity = viewer()
        val top = activity.findViewById<View>(R.id.chromeTop)

        activity.bumpChrome()
        shadowOf(activity.mainLooper).idleFor(PAST_AUTO_HIDE)
        assertEquals(View.INVISIBLE, top.visibility)

        activity.bumpChrome()
        activity.idle()
        assertEquals(View.VISIBLE, top.visibility)
    }

    // ── ViewerShareController ────────────────────────────────────────────

    @Test
    fun `exports are named after the first frame, made filename-safe`() {
        val activity = viewer(frameNames = listOf("My Sample (1).png", "b.png", "c.png"))
        val snapshot = activity.buildShareSnapshot()!!

        assertEquals("My_Sample_1", snapshot.baseName)
        assertEquals(FRAMES, snapshot.batchFiles.size)
        assertEquals(activity.currentFrameIndex, snapshot.frameIndex)
        assertNotNull("a single run carries its summary", snapshot.summary)
    }

    // ── ShareExportBuilder ───────────────────────────────────────────────

    private fun ResultViewerActivity.produce(kind: ShareKind): Pair<File, String> {
        val snapshot = buildShareSnapshot()!!
        return runBlocking {
            ShareExportBuilder(snapshot, resources, ShareExportBuilder.newJobDir(cacheDir)).produce(kind) { _, _ -> }
        }
    }

    @Test
    fun `the batch CSV is one file with a row per point of every frame`() {
        val (file, mime) = viewer().produce(ShareKind.CSV)
        assertEquals(Mime.CSV, mime)
        val rows = file.readLines().count { it.startsWith("Frame_") }
        assertEquals(FRAMES * GRID * GRID, rows)
    }

    @Test
    fun `the field photos refuse a session with no reference image rather than draw on nothing`() {
        // The fixture batch has frames but no reference photo to draw over.
        val activity = viewer()
        try {
            activity.produce(ShareKind.PHOTOS)
            fail("photos were produced without a reference image")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message, expected.message!!.contains("reference"))
        }
    }

    @Test
    fun `the animations are one GIF per field, zipped`() {
        val activity = viewer()
        // The GIFs take each field's sequence range; let the summary find them.
        idleUntil("the summary ranges") {
            ShareExportBuilder.FIELDS.all { (_, index) -> activity.summary.boundsFor(index) != null }
        }
        val (file, mime) = activity.produce(ShareKind.GIFS)

        assertEquals(Mime.ZIP, mime)
        val names = ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue(names.toString(), names.isNotEmpty() && names.all { it.endsWith(".gif") })
    }

    @Test
    fun `each job gets a directory of its own`() {
        val cache = temp.newFolder("cache")
        val a = ShareExportBuilder.newJobDir(cache)
        val b = ShareExportBuilder.newJobDir(cache)
        assertTrue(a.isDirectory && b.isDirectory)
        assertFalse(a == b)
        assertEquals(a.parentFile, b.parentFile)
    }

    @Test
    fun `a sweep is refused animations rather than handed an empty file`() {
        val activity = viewer()
        val base = activity.buildShareSnapshot()!!
        val sweepArgs = ViewerSweepArgs(
            subsets = emptyList(),
            steps = listOf(3, 5, 7),
            strainWindows = emptyList(),
            lineCutHorizontal = true,
            skippedJson = "[]",
        )
        val source = base.reportSource
        val sweep = base.copy(reportSource = source.copy(args = source.args.copy(sweep = sweepArgs)))
        assertTrue(sweep.isSweep)
        try {
            runBlocking {
                ShareExportBuilder(sweep, activity.resources, ShareExportBuilder.newJobDir(activity.cacheDir))
                    .produce(ShareKind.GIFS) { _, _ -> }
            }
            fail("a snapshot with no summary produced animations")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message, expected.message!!.isNotBlank())
        }
    }
}
