package com.sempermechanics.semper.report

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.report.PdfReportGenerator.Progress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The PDF report drawn by the platform's real PdfDocument, which Robolectric
 * cannot run: the stages, a document that starts `%PDF`, one cover per
 * readable frame, A4 pages, every frame's bitmaps released once it is drawn, and a
 * failed write reported as Error rather than Complete.
 */
@RunWith(AndroidJUnit4::class)
class PdfReportDeviceTest {

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun bitmap(): Bitmap = createBitmap(SIDE, SIDE)

    private fun field(key: String) = FieldResult(
        fieldName = key, fieldKey = key, unit = "px",
        minValue = -1f, maxValue = 1f, meanValue = 0f, meanType = "Simple Mean", stdDevValue = 0.5f,
        minCoordX = 0, minCoordY = 0, maxCoordX = SIDE - 1, maxCoordY = SIDE - 1,
        bakedHeatmap = bitmap(),
    )

    private fun data() = ReportData(
        sessionId = "s1", specimenName = "steel", analysisDate = "2026-09-24",
        subsetSize = 21, stepSize = 5, strainWindow = 15, strainMethod = "VSG",
        roiData = RoiData(16, 16, 600, 440),
        referenceImage = bitmap(), deformedImage = bitmap(),
        referenceImageName = "ref.png", deformedImageName = "def.png",
        fieldResults = listOf("U", "V", "Exx", "Eyy", "Exy").map(::field),
        engineStats = EngineStats.fromArray(FloatArray(EngineStats.CORE_SLOT_COUNT)),
        znssdHeatmap = bitmap(), solverPathMap = bitmap(), globalAvgZnssd = 0.01f,
    )

    private fun percents(events: List<Progress>) = events.filterIsInstance<Progress.Status>().map { it.percent }

    private fun pageCount(pdf: ByteArray): Int =
        Regex("/Type\\s*/Page[^s]").findAll(String(pdf, Charsets.ISO_8859_1)).count()

    /** Every page's MediaBox as written; A4 is 595 x 842 pt. */
    private fun mediaBoxes(pdf: ByteArray): Set<String> =
        Regex("/MediaBox\\s*\\[([^\\]]*)]").findAll(String(pdf, Charsets.ISO_8859_1))
            .map { it.groupValues[1].trim().split(Regex("\\s+")).joinToString(" ") }.toSet()

    @Test
    fun singleReportWritesAPdf() {
        val out = ByteArrayOutputStream()
        val events = runBlocking { PdfReportGenerator.generate(data(), out, resources).toList() }
        assertEquals(Progress.Complete, events.last())
        assertEquals(listOf(10, 30, 90, 98), percents(events))
        assertEquals("%PDF", String(out.toByteArray().copyOf(4), Charsets.US_ASCII))
        // Cover, three field pages (U+V, Exx+Eyy, Exy+ZNSSD), telemetry.
        assertEquals(SINGLE_PAGES, pageCount(out.toByteArray()))
        assertEquals(setOf("0 0 595 842"), mediaBoxes(out.toByteArray()))
    }

    @Test
    fun batchSkipsAnUnreadableFrameAndReleasesTheRest() {
        val frames = listOf(data(), null, data())
        val out = ByteArrayOutputStream()
        val events = runBlocking {
            PdfReportGenerator.generateBatch(frames.size, { frames[it] }, out, resources = resources).toList()
        }
        assertEquals(Progress.Complete, events.last())
        assertEquals(listOf(2, 32, 63, 96), percents(events))
        // Two readable frames of cover + three field pages, then one telemetry page.
        assertEquals(2 * (SINGLE_PAGES - 1) + 1, pageCount(out.toByteArray()))
        frames.filterNotNull().forEach { d ->
            assertTrue(d.referenceImage.isRecycled && d.deformedImage.isRecycled)
            assertTrue(d.znssdHeatmap.isRecycled && d.solverPathMap.isRecycled)
            assertTrue(d.fieldResults.all { it.bakedHeatmap.isRecycled })
        }
    }

    @Test
    fun aFailingStreamEndsInError() {
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
        }
        val events = runBlocking { PdfReportGenerator.generate(data(), broken).toList() }
        // PdfDocument itself swallows the IOException; the generator must not.
        val last = events.last()
        assertTrue("$events", last is Progress.Error)
        assertEquals("disk full", (last as Progress.Error).ex.message)
        assertEquals(listOf(10, 30, 90, 98), percents(events))
    }

    private companion object {
        const val SIDE = 8
        const val SINGLE_PAGES = 5
    }
}
