package com.indicvision.semper.data.cloud

import android.app.Application
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.LogCapture
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.fixtures.sessionRecord
import com.indicvision.semper.report.EngineStats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Upload staging without the PDF renderer (which needs a device; see
 * `SessionUploadBundlerDeviceTest`): the progress callback, the combined CSV,
 * and what happens when a frame's `.dat` or the report inputs are missing.
 *
 * A plain Application, not SemperApp: its startup sweep clears cacheDir, which
 * this test reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionUploadBundlerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun datBytes(points: Int = 4): ByteArray {
        val buffer = ByteBuffer.allocate(points * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        for (i in 0 until points) {
            buffer.putFloat(i * 5f).putFloat(i * 5f) // x, y
            buffer.putFloat(0.5f).putFloat(-0.25f) // u, v
            buffer.putFloat(0.001f).putFloat(0f).putFloat(0f) // exx, eyy, exy
            buffer.putFloat(0.01f) // znssd — solved
        }
        return buffer.array()
    }

    private fun record(sessionDir: File, vararg names: String, imgW: Int = 64, sweep: Boolean = false) = sessionRecord(
        id = "s_test", name = "test", createdAt = 0, frameCount = names.size, subset = 21,
        imgW = imgW, imgH = 64, roiW = 64, roiH = 64, sessionDir = sessionDir.path, defNames = names.toList(),
    ).copy(
        sweepSubsets = if (sweep) listOf(21, 31) else emptyList(),
        sweepSteps = if (sweep) listOf(5, 7) else emptyList(),
        sweepStrainWindows = if (sweep) listOf(15, 19) else emptyList(),
        sweepLabels = if (sweep) listOf("S21/5", "S31/7") else emptyList(),
    )

    private class Staged(val counts: SessionUploadBundler.BundleCounts, val ticks: List<Pair<Int, Int>>)

    private fun stage(record: SessionRecord, csv: File?, writeReports: Boolean, staging: File): Staged {
        val ticks = mutableListOf<Pair<Int, Int>>()
        val counts = runBlocking {
            SessionUploadBundler.stageCsvAndBundles(
                context,
                record,
                File(record.sessionDir),
                File(record.sessionDir, "missing_ref.png"),
                temp.newFolder(),
                staging,
                csv,
                writeReports,
            ) { done, total -> ticks += done to total }
        }
        return Staged(counts, ticks)
    }

    @Test
    fun `progress ticks once up front and once per frame, missing dat included`() {
        val dir = temp.newFolder("session")
        SessionPaths.frameDat(dir, 0).writeBytes(datBytes())
        SessionPaths.frameDat(dir, 2).writeBytes(datBytes())
        val staged = stage(record(dir, "a.png", "b.png", "c.png"), temp.newFile("a.csv"), false, temp.newFolder())
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), staged.ticks)
        assertEquals(SessionUploadBundler.BundleCounts(0, 0), staged.counts)
    }

    @Test
    fun `the csv has one point header and rows for readable frames only`() {
        val dir = temp.newFolder("session")
        SessionPaths.frameDat(dir, 0).writeBytes(datBytes())
        SessionPaths.frameDat(dir, 1).writeBytes(byteArrayOf(1, 2, 3)) // not a whole point
        SessionPaths.frameDat(dir, 2).writeBytes(datBytes())
        val csv = temp.newFile("a.csv")
        stage(record(dir, "a.png", "b.png", "c.png"), csv, false, temp.newFolder())

        val lines = csv.readLines()
        assertEquals(1, lines.count { it.startsWith("image,") })
        // TD-66: both frames' stats rows sit above the point section, not among it.
        val header = lines.indexOfFirst { it.startsWith("image,") }
        assertTrue(lines.subList(0, header).count { it.startsWith("# c.png,") } > 0)
        assertTrue(lines.subList(header, lines.size).none { it.startsWith("#") })
        assertTrue(csv.parentFile!!.listFiles()!!.none { it.name.endsWith(".points.tmp") })
        val points = lines.filter { !it.startsWith("#") && it.isNotBlank() && !it.startsWith("image,") }
        assertEquals(4, points.count { it.startsWith("a.png,") })
        assertEquals(4, points.count { it.startsWith("c.png,") })
        assertTrue(points.none { it.startsWith("b.png,") })
    }

    @Test
    fun `a sweep's rows carry each combination's own settings`() {
        val dir = temp.newFolder("session")
        SessionPaths.frameDat(dir, 0).writeBytes(datBytes())
        SessionPaths.frameDat(dir, 1).writeBytes(datBytes())
        val csv = temp.newFile("sweep.csv")
        stage(record(dir, "speckle.png", "speckle.png", sweep = true), csv, false, temp.newFolder())

        val points = csv.readLines().filter { it.startsWith("speckle.png,") }
        // Stored windows are VSGs in px; 15 at step 5 and 19 at step 7 are no
        // whole count of points, so strain_window is empty and vsg_px has them.
        assertEquals(4, points.count { it.startsWith("speckle.png,21,5,,15,") })
        assertEquals(4, points.count { it.startsWith("speckle.png,31,7,,19,") })
    }

    @Test
    fun `reports are skipped, not attempted, when the image size is unknown`() {
        val dir = temp.newFolder("session")
        SessionPaths.frameDat(dir, 0).writeBytes(datBytes())
        val staging = temp.newFolder()
        val staged = stage(record(dir, "a.png", imgW = 0), null, true, staging)
        assertEquals(SessionUploadBundler.BundleCounts(0, 0), staged.counts)
        assertEquals(listOf(0 to 1, 1 to 1), staged.ticks)
        assertEquals(0, File(staging, "reports").list()?.size ?: 0)
    }

    @Test
    fun `an undecodable reference is logged without its path`() {
        val dir = temp.newFolder("Jane_Doe_specimen")
        SessionPaths.frameDat(dir, 0).writeBytes(datBytes())
        LogCapture().use { log ->
            stage(record(dir, "a.png"), null, true, temp.newFolder())
            // ERROR reaches Crashlytics; the path names the user's files.
            assertTrue(log.warnings.any { it.startsWith("No decodable base image") })
            assertTrue(log.warnings.none { it.contains("Jane_Doe") })
        }
    }

    /** The engine stats a bundled report of a record holding [stored] prints. */
    private fun bundledStats(stored: List<Float>): EngineStats {
        val bitmap = createBitmap(1, 1)
        val record = sessionRecord(defNames = listOf("a.png")).copy(engineStats = stored)
        return SessionUploadBundler.reportParams(record, 0, FloatArray(0), bitmap, bitmap).engineStats
    }

    @Test
    fun `the report keeps every engine stats slot the run stored`() {
        val stored = MutableList(EngineStats.SLOT_COUNT) { 1f }.apply {
            this[EngineStats.SLOT_MESH_SEEDING] = 2f
            this[EngineStats.SLOT_SIMPLEX_MS] = 12.5f
            this[EngineStats.SLOT_ICGN_MS] = 40f
        }
        val stats = bundledStats(stored)
        assertEquals(2, stats.meshSeedingQuality)
        assertEquals(12.5f, stats.simplexMs)
        assertEquals(40f, stats.icgnMs)
    }

    @Test
    fun `legacy engine stats keep reading as they did`() {
        // 16 core slots: mesh seeding was never recorded, so it stays unknown
        // rather than reading a padded 0 as "Fallback".
        val core = bundledStats(List(EngineStats.CORE_SLOT_COUNT) { 3f })
        assertEquals(EngineStats.MESH_SEEDING_UNKNOWN, core.meshSeedingQuality)
        assertEquals(3, core.totalPointsAttempted)
        // 17 slots: mesh seeding present, simplex / ICGN times absent.
        val withMesh = bundledStats(List(EngineStats.CORE_SLOT_COUNT) { 3f } + 1f)
        assertEquals(1, withMesh.meshSeedingQuality)
        assertEquals(0f, withMesh.icgnMs)
        // Nothing stored: zeros and an unknown mesh, as before.
        val none = bundledStats(emptyList())
        assertEquals(0, none.totalPointsAttempted)
        assertEquals(EngineStats.MESH_SEEDING_UNKNOWN, none.meshSeedingQuality)
    }

    @Test
    fun `no decodable base image means no reports and no scratch pdf`() {
        val dir = temp.newFolder("session")
        SessionPaths.frameDat(dir, 0).writeBytes(datBytes())
        val staged = stage(record(dir, "a.png"), null, true, temp.newFolder())
        assertEquals(SessionUploadBundler.BundleCounts(0, 0), staged.counts)
        assertFalse(File(context.cacheDir, "upload_s_test_frame.pdf").exists())
    }
}
