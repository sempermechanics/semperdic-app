package com.sempermechanics.semper.data.cloud

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.fixtures.sessionRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Upload staging with the report renderer, which needs the platform's
 * PdfDocument: one PDF and five field maps per readable frame, sweep labels
 * made path-safe, and the scratch PDF gone afterwards. The CSV's two sections
 * stay apart with rendering running between frames (TD-66); the rest of the
 * CSV and progress contract is JVM-tested in SessionUploadBundlerTest.
 */
@RunWith(AndroidJUnit4::class)
class SessionUploadBundlerDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "bundler_device_test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun png(file: File) {
        val bmp = createBitmap(SIDE, SIDE)
        for (y in 0 until SIDE) for (x in 0 until SIDE) bmp.setPixel(x, y, if ((x + y) % 2 == 0) Color.WHITE else Color.BLACK)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun dat(file: File) {
        val grid = SIDE / STEP
        val buffer = ByteBuffer.allocate(grid * grid * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        for (i in 0 until grid * grid) {
            buffer.putFloat(((i % grid) * STEP).toFloat()).putFloat(((i / grid) * STEP).toFloat())
            buffer.putFloat(i * 0.01f).putFloat(-i * 0.01f)
            buffer.putFloat(i * 0.0001f).putFloat(0f).putFloat(0f)
            buffer.putFloat(0.01f)
        }
        file.writeBytes(buffer.array())
    }

    @Test
    fun aSweepStagesOneReportAndFiveMapsPerCombination() {
        val sessionDir = File(root, "session").apply { mkdirs() }
        val rawDir = File(root, "raw").apply { mkdirs() }
        val ref = File(root, "ref.png").also(::png)
        png(File(rawDir, "speckle.png"))
        dat(SessionPaths.frameDat(sessionDir, 0))
        dat(SessionPaths.frameDat(sessionDir, 1))
        val record = sessionRecord(
            id = "bundler_device", name = "sweep", createdAt = 0, frameCount = 2, subset = 21, step = STEP,
            imgW = SIDE, imgH = SIDE, refPath = ref.path, sessionDir = sessionDir.path,
            defNames = listOf("speckle.png", "speckle.png"),
        ).copy(
            sweepSubsets = listOf(21, 31),
            sweepSteps = listOf(STEP, STEP),
            sweepStrainWindows = listOf(15, 19),
            sweepLabels = listOf("S21/W15", "S31\\W19"),
        )
        val staging = File(root, "staging")
        val csv = File(root, "a.csv")
        val counts = runBlocking {
            SessionUploadBundler.stageCsvAndBundles(
                context,
                record,
                sessionDir,
                ref,
                rawDir,
                staging,
                csv,
                writeReports = true,
            )
        }

        assertEquals(SessionUploadBundler.BundleCounts(reports = 2, processed = 10), counts)
        for (label in listOf("S21-W15", "S31-W19")) {
            val pdf = File(staging, "reports/Master_Report_$label.pdf")
            assertTrue("$pdf", pdf.length() > 0)
            assertEquals("%PDF", String(pdf.readBytes().copyOf(4), Charsets.US_ASCII))
            val maps = File(staging, "processed/$label").list()?.sorted()
            assertEquals(listOf("Exx.png", "Exy.png", "Eyy.png", "U.png", "V.png"), maps)
        }
        val lines = csv.readLines()
        val header = lines.indexOfFirst { it.startsWith("image,") }
        assertEquals(10, lines.subList(0, header).count { it.startsWith("# speckle.png,") })
        assertTrue("stats among the points", lines.subList(header, lines.size).none { it.startsWith("#") })
        assertFalse("points staging file left", File(root, "a.csv.points.tmp").exists())
        assertFalse("sweeps get no animations", File(staging, "processed/animations").exists())
        assertFalse("scratch pdf removed", File(context.cacheDir, "upload_bundler_device_frame.pdf").exists())
    }

    private companion object {
        const val SIDE = 64
        const val STEP = 8
    }
}
