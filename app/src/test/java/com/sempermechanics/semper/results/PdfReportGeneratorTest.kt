package com.sempermechanics.semper.results

import android.graphics.Bitmap
import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.report.FieldResult
import com.sempermechanics.semper.report.PdfReportGenerator
import com.sempermechanics.semper.report.PdfReportGenerator.Progress
import com.sempermechanics.semper.report.ReportData
import com.sempermechanics.semper.report.RoiData
import com.sempermechanics.semper.report.TelemetrySummary
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * The report's progress contract and failure handling: the share sheet drives
 * its bar from these percents and treats [Progress.Error] as the only failure
 * signal, so a throw inside a render must arrive as Error, never escape.
 *
 * Robolectric's PdfDocument has no native document (`startPage` throws
 * "document is closed"), so drawing pages is covered on a device by
 * `PdfReportDeviceTest`; these cases never start a page.
 */
@RunWith(RobolectricTestRunner::class)
class PdfReportGeneratorTest {

    private fun statuses(events: List<Progress>) = events.filterIsInstance<Progress.Status>()

    @Test
    fun `batch progress spreads frames over the middle of the bar`() {
        val events = runBlocking {
            PdfReportGenerator.generateBatch(4, { null }, ByteArrayOutputStream()).toList()
        }
        assertEquals(listOf(2, 25, 48, 71), statuses(events).map { it.percent })
        assertEquals("Frame 1 of 4…", statuses(events).first().message)
    }

    @Test
    fun `batch with every frame unreadable has no telemetry page`() {
        val events = runBlocking {
            PdfReportGenerator.generateBatch(2, { null }, ByteArrayOutputStream()).toList()
        }
        assertTrue(statuses(events).none { it.message.startsWith("Compiling") })
    }

    @Test
    fun `a throwing frame source ends in Error, not an exception`() {
        val events = runBlocking {
            PdfReportGenerator.generateBatch(2, { error("unreadable .dat") }, ByteArrayOutputStream()).toList()
        }
        val last = events.last()
        assertTrue(last is Progress.Error)
        assertEquals("unreadable .dat", (last as Progress.Error).ex.message)
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    private val stats = EngineStats(
        totalPointsAttempted = 100,
        totalPointsSolved = 90,
        totalPointsRejected = 10,
        pathAPoints = 80,
        pathBPoints = 10,
        simplexCalls = 3,
        simplexSaved = 2,
        finalDeadPoints = 10,
        avgIcgnIterations = 1.5f,
        wallTimeMs = 1234.5f,
        akazeRansacMs = 12.5f,
        hessianPrepassMs = 3.25f,
        delaunayMs = 7.5f,
        strainMs = 2.5f,
        avgThroughputPtsPerMs = 0.75f,
        convergencePercent = 87.5f,
    )

    private fun reportData() = ReportData(
        sessionId = "s",
        specimenName = "dogbone",
        analysisDate = "2026-10-01 12:00:00",
        subsetSize = 41,
        stepSize = 5,
        strainWindow = 15,
        strainMethod = "VSG",
        roiData = RoiData(0, 0, 10, 10),
        referenceImage = bitmap(),
        deformedImage = bitmap(),
        referenceImageName = "ref.png",
        deformedImageName = "def.png",
        fieldResults = listOf(
            FieldResult("U Displacement", "U", "px", 0f, 1f, 0.5f, "Simple Mean", 0.1f, 0, 0, 1, 1, bitmap()),
        ),
        engineStats = stats,
        znssdHeatmap = bitmap(),
        solverPathMap = bitmap(),
        globalAvgZnssd = 0.01f,
    )

    @Test
    fun `a frame whose pages fail to draw still has its images freed`() {
        // No native document here, so the cover page's startPage throws.
        val data = reportData()
        val events = runBlocking {
            PdfReportGenerator.generateBatch(1, { data }, ByteArrayOutputStream()).toList()
        }

        assertTrue(events.last() is Progress.Error)
        val images = listOf(data.referenceImage, data.deformedImage, data.znssdHeatmap, data.solverPathMap) +
            data.fieldResults.map { it.bakedHeatmap }
        assertTrue("every page image recycled", images.all { it.isRecycled })
    }

    @Test
    fun `telemetry numbers read the same on a German phone`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val summary = TelemetrySummary.single(reportData())

            assertEquals(
                listOf("0.01000", "87.50 %", "1.50"),
                summary.qualityRows(stats).map { it[1] },
            )
            assertEquals(
                listOf("12.5 ms", "3.3 ms", "7.5 ms", "2.5 ms", "1234.5 ms", "0.75 pts/ms"),
                TelemetrySummary.timingRows(stats).map { it[1] },
            )
        } finally {
            Locale.setDefault(before)
        }
    }
}
