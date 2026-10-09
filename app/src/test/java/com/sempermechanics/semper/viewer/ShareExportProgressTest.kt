package com.sempermechanics.semper.viewer

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.launchViewer
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.report.PdfReportGenerator.Progress
import com.sempermechanics.semper.report.PdfReportGenerator.Stage
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import com.sempermechanics.semper.ui.viewer.share.ShareCenter
import com.sempermechanics.semper.ui.viewer.share.ShareExportBuilder
import com.sempermechanics.semper.ui.viewer.share.ShareKind
import com.sempermechanics.semper.ui.viewer.share.text
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The exports that used to spin forever report as they go: the CSV and the
 * animations frame by frame, the frame's photos field by field. Each run of
 * reports climbs from where it starts to 100% and names what it is on. The
 * PDF names its frames too, in string resources like the rest.
 */
@RunWith(RobolectricTestRunner::class)
class ShareExportProgressTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 3
        const val GRID = 4
        const val STEP = 4
        const val DENSE_GRID = 70
    }

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, FRAMES, GRID, STEP)
    }

    private fun viewer(): ResultViewerActivity =
        launchViewer(viewerArgs(batchDir, GRID, STEP)).also { activity ->
            idleUntil("the viewer") { activity.frameSetLoaded && activity.rawData != null }
        }

    private fun ResultViewerActivity.reports(
        kind: ShareKind,
        snapshot: ShareCenter.Snapshot = buildShareSnapshot()!!,
        seen: MutableList<Pair<Double, String>> = mutableListOf(),
    ): List<Pair<Double, String>> {
        runBlocking {
            ShareExportBuilder(snapshot, resources, ShareExportBuilder.newJobDir(cacheDir))
                .produce(kind) { percent, status -> seen += percent to status }
        }
        return seen
    }

    private fun assertClimbsToDone(seen: List<Pair<Double, String>>) {
        assertTrue("no progress reported", seen.isNotEmpty())
        val percents = seen.map { it.first }
        assertEquals(percents.sorted(), percents)
        assertEquals(100.0, percents.last(), 1e-9)
        assertTrue(percents.all { it in 0.0..100.0 })
    }

    @Test
    fun `the CSV reports each frame of its statistics and its point rows`() {
        val seen = viewer().reports(ShareKind.CSV)

        assertClimbsToDone(seen)
        val statuses = seen.map { it.second }
        assertEquals("Frame 1 of 3 · field statistics", statuses.first())
        assertTrue(statuses.toString(), "Frame 2 of 3 · point rows" in statuses)
        assertEquals("Frame 3 of 3 · point rows", statuses.last())
        // The statistics pass is the first tenth of the bar.
        assertEquals(10.0, seen.last { it.second.endsWith("field statistics") }.first, 1e-9)
    }

    @Test
    fun `the CSV moves inside a dense frame's point rows`() {
        // Past one report interval of rows a frame (70 x 70 = 4900 points).
        val dense = temp.newFolder("dense")
        writeGridBatch(dense, 2, DENSE_GRID, STEP)
        val activity = launchViewer(viewerArgs(dense, DENSE_GRID, STEP)).also { viewer ->
            idleUntil("the dense viewer") { viewer.frameSetLoaded && viewer.rawData != null }
        }
        val seen = activity.reports(ShareKind.CSV)

        assertClimbsToDone(seen)
        // Frame boundaries of the point rows sit at 10, 55 and 100 %; each frame
        // has one report strictly inside it, under that frame's label.
        val inside = seen.filter { (percent, _) -> percent !in listOf(10.0, 55.0, 100.0) && percent > 10.0 }
        assertEquals(seen.toString(), 2, inside.size)
        assertEquals("Frame 1 of 2 · point rows", inside[0].second)
        assertTrue(inside[0].first in 10.0..55.0)
        assertEquals("Frame 2 of 2 · point rows", inside[1].second)
        assertTrue(inside[1].first in 55.0..100.0)
    }

    @Test
    fun `the animations report each frame of each field`() {
        val activity = viewer()
        idleUntil("the summary ranges") {
            ShareExportBuilder.FIELDS.all { (_, index) -> activity.summary.boundsFor(index) != null }
        }
        val seen = activity.reports(ShareKind.GIFS)

        assertClimbsToDone(seen)
        val statuses = seen.map { it.second }.toSet()
        for (field in listOf("U", "V", "Exx", "Eyy", "Exy")) {
            assertTrue(statuses.toString(), "Frame 1 of 3 · $field animation" in statuses)
        }
    }

    @Test
    fun `the animations count the range pass when the viewer has not finished it`() {
        val activity = viewer()
        val snapshot = activity.buildShareSnapshot()!!.copy(summaryBounds = emptyMap())
        val seen = activity.reports(ShareKind.GIFS, snapshot)

        assertClimbsToDone(seen)
        assertEquals("Frame 1 of 3 · colour scale", seen.first().second)
    }

    @Test
    fun `the frame's photos report each field`() {
        val activity = viewer()
        val base = activity.buildShareSnapshot()!!
        // The fixture has no reference photo; draw over a plain one.
        val reference: Bitmap = createBitmap(GRID * STEP, GRID * STEP)
        val snapshot = base.copy(reportSource = base.reportSource.copy(displayBase = reference))
        val seen = activity.reports(ShareKind.PHOTOS, snapshot)

        assertClimbsToDone(seen)
        assertEquals(
            listOf(
                "Field 1 of 5 · U heatmap",
                "Field 2 of 5 · V heatmap",
                "Field 3 of 5 · Exx heatmap",
                "Field 4 of 5 · Eyy heatmap",
                "Field 5 of 5 · Exy heatmap",
                "Field 5 of 5 · Exy heatmap",
            ),
            seen.map { it.second },
        )
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), seen.map { it.first })
    }

    @Test
    fun `the PDF names each frame in the app's own words`() {
        val activity = viewer()
        val seen = mutableListOf<Pair<Double, String>>()
        // Robolectric's PdfDocument does not really draw or write pages, so the
        // job may fail before it completes (PdfReportDeviceTest draws them);
        // what it reported on the way is the status under test.
        runCatching { activity.reports(ShareKind.PDF, seen = seen) }

        val frames = (1..FRAMES).map { activity.getString(R.string.share_progress_pdf_frame_fmt, it, FRAMES) }
        assertEquals("Frame 1 of 3 · report pages", seen.first().second)
        val known = frames + activity.getString(R.string.share_progress_pdf_telemetry) +
            activity.getString(R.string.share_progress_pdf_finishing)
        assertTrue(seen.toString(), seen.all { it.second in known })
        val percents = seen.map { it.first }
        assertEquals(percents.sorted(), percents)
    }

    @Test
    fun `every PDF stage reads from a string resource`() {
        val res = RuntimeEnvironment.getApplication().resources
        val stages = mapOf(
            Stage.COVER to R.string.share_progress_pdf_cover,
            Stage.MAPS to R.string.share_progress_pdf_maps,
            Stage.TELEMETRY to R.string.share_progress_pdf_telemetry,
            Stage.FINISHING to R.string.share_progress_pdf_finishing,
        )
        for ((stage, string) in stages) {
            assertEquals(res.getString(string), Progress.Status(stage, 0).text(res))
        }
        assertEquals(
            "Frame 2 of 3 · report pages",
            Progress.Status(Stage.FRAME, 0, frame = 2, frameCount = 3).text(res),
        )
    }
}
