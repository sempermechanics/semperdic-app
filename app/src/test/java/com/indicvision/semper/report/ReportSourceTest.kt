@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.indicvision.semper.data.cloud.SessionUploadBundler
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.fixtures.sessionRecord
import com.indicvision.semper.ui.viewer.ViewerArgs
import com.indicvision.semper.ui.viewer.ViewerSweepArgs
import com.indicvision.semper.ui.viewer.share.ViewerReportFactory
import com.indicvision.semper.ui.viewer.share.nameIndexAt
import com.indicvision.semper.ui.viewer.share.toReportSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ReportSource] and the [EngineStats] helpers against the report-input
 * assembly they replace in the cloud bundle and the viewer.
 */
@RunWith(RobolectricTestRunner::class)
class ReportSourceTest {

    private val step = 4
    private val cols = 12
    private val rows = 9
    private val base: Bitmap = createBitmap(cols * step, rows * step)
    private val cover: Bitmap = createBitmap(cols * step, rows * step)
    private val date = "2026-10-01 12:00:00"

    private fun field(): FloatArray {
        val out = FloatArray(cols * rows * DicResult.STRIDE)
        var p = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                out[p + DicResult.IDX_X] = (c * step).toFloat()
                out[p + DicResult.IDX_Y] = (r * step).toFloat()
                out[p + DicResult.IDX_U] = c * 0.1f
                out[p + DicResult.IDX_V] = r * -0.05f
                out[p + DicResult.IDX_EXX] = c * 0.0004f
                out[p + DicResult.IDX_EYY] = r * 0.0003f
                out[p + DicResult.IDX_EXY] = (c - r) * 0.0001f
                out[p + DicResult.IDX_ZNSSD] = 0.02f
                p += DicResult.STRIDE
            }
        }
        return out
    }

    // ── EngineStats helpers ────────────────────────────────────────────────

    @Test
    fun `fromList is the cloud bundle's reportEngineStats for every stored length`() {
        for (n in 0..EngineStats.SLOT_COUNT + 3) {
            val stats = List(n) { it * 1.5f + 1f }
            assertEquals("n=$n", SessionUploadBundler.reportEngineStats(stats), EngineStats.fromList(stats))
        }
        assertEquals(EngineStats.EMPTY, EngineStats.fromList(null))
    }

    @Test
    fun `EMPTY is what fromArray makes of no stats`() {
        assertEquals(EngineStats.fromArray(FloatArray(0)), EngineStats.EMPTY)
        assertEquals(EngineStats.fromArray(FloatArray(EngineStats.CORE_SLOT_COUNT)), EngineStats.EMPTY)
        assertEquals(EngineStats.MESH_SEEDING_UNKNOWN, EngineStats.EMPTY.meshSeedingQuality)
    }

    @Test
    fun `newMetrics is the run's metrics catcher`() {
        val metrics = EngineStats.newMetrics()
        val catcher = FloatArray(EngineStats.SLOT_COUNT).also {
            it[EngineStats.SLOT_MESH_SEEDING] = EngineStats.MESH_SEEDING_UNKNOWN.toFloat()
        }
        assertTrue(catcher.contentEquals(metrics))
        assertEquals(EngineStats.MESH_SEEDING_UNKNOWN, EngineStats.fromArray(metrics).meshSeedingQuality)
        assertFalse("each call is a fresh array", metrics === EngineStats.newMetrics())
    }

    // ── The cloud bundle ───────────────────────────────────────────────────

    /** `SessionUploadBundler.renderFrame`'s params, copied as the oracle (the function is private). */
    private fun bundlerParams(
        record: SessionRecord,
        frameIndex: Int,
        data: FloatArray,
    ) = ReportBuilder.ReportBuildParams(
        data = data,
        baseImg = base,
        defImgForCover = cover,
        imgW = record.imgW,
        imgH = record.imgH,
        step = record.sweepSteps.getOrElse(frameIndex) { record.step },
        sessionId = record.id,
        specimenName = ReportImageNames.specimen(record.refName),
        analysisDate = date,
        subsetSize = record.sweepSubsets.getOrElse(frameIndex) { record.subset },
        strainWindow = record.sweepStrainWindows.getOrElse(frameIndex) { record.strainWindow },
        strainMethod = record.strainMethod.ifBlank { "VSG" },
        roiData = RoiData(record.roiX, record.roiY, record.roiW, record.roiH),
        engineStats = SessionUploadBundler.reportEngineStats(record.engineStats),
        referenceImageName = ReportImageNames.reference(record.refName),
        deformedImageName = ReportImageNames.deformed(record.frameNames, frameIndex),
        drawMinMarker = false,
    )

    @Test
    fun `forRecord builds the cloud bundle's params, batch and sweep`() {
        val data = field()
        val batch = sessionRecord(
            id = "b1",
            frameCount = 2,
            defNames = listOf("a.tif", ""),
            refName = "spec.tif",
            roiX = 3,
            roiY = 4,
            roiW = 40,
            roiH = 30,
        ).copy(engineStats = List(17) { it.toFloat() }, strainMethod = "")
        val sweep = batch.copy(
            id = "s1",
            sweepSubsets = listOf(21, 31),
            sweepSteps = listOf(5),
            sweepStrainWindows = listOf(41, 85, 99),
            sweepLabels = listOf("first", "second"),
            strainMethod = "LSQ",
        )
        for (record in listOf(batch, sweep)) {
            for (i in 0..2) {
                assertEquals(
                    "${record.id} frame $i",
                    bundlerParams(record, i, data),
                    ReportSource.forRecord(record).forFrame(i, data, base, cover, analysisDate = date),
                )
            }
        }
    }

    // ── The viewer ─────────────────────────────────────────────────────────

    private fun viewerSource(sweep: Boolean, stats: List<Float>?, sessionId: String?) = ViewerReportFactory.Source(
        args = ViewerArgs(
            imgW = cols * step,
            imgH = rows * step,
            step = step,
            refName = "spec.png",
            refPath = "",
            batchDirPath = null,
            frameNames = if (sweep) listOf("S21", "S31", "S41") else listOf("f0.png", "f1.png", "f2.png"),
            stopCode = 0,
            plannedFrames = 3,
            sessionId = sessionId,
            sessionLocalId = "l1",
            subsetSize = 25,
            strainWindow = 9,
            engineStats = stats,
            roiX = 0,
            roiY = 0,
            roiW = cols * step,
            roiH = rows * step,
            sweep = if (sweep) {
                ViewerSweepArgs(
                    subsets = listOf(21, 31),
                    steps = listOf(step, step),
                    strainWindows = listOf(41),
                    lineCutHorizontal = true,
                    skippedJson = "[]",
                )
            } else {
                null
            },
            strainMethod = "VSG",
        ),
        imageSize = ImageSize(cols * step, rows * step),
        plannedFrames = if (sweep) emptyList() else listOf(0, 2, 5),
        defImagePaths = emptyList(),
        displayBase = base,
    )

    /** What a report prints, without its bitmaps or its clock. */
    private fun printed(r: ReportData) = listOf(
        r.sessionId, r.specimenName, r.subsetSize, r.stepSize, r.strainWindow, r.strainMethod, r.roiData,
        r.referenceImageName, r.deformedImageName, r.engineStats, r.globalAvgZnssd, r.znssdAcceptedPoints,
        r.fieldResults.map {
            listOf(it.fieldKey, it.minValue, it.maxValue, it.meanValue, it.stdDevValue, it.minCoordX, it.maxCoordY)
        },
    )

    @Test
    fun `the viewer's source prints what ViewerReportFactory prints`() {
        val data = field()
        val cases = listOf(
            Triple(false, List(EngineStats.SLOT_COUNT) { it + 0.5f }, "cloud-1"),
            Triple(false, null, null),
            Triple(false, List(10) { 9f }, "cloud-2"),
            Triple(true, List(EngineStats.CORE_SLOT_COUNT) { 1f }, null),
        )
        for ((sweep, stats, sessionId) in cases) {
            val source = viewerSource(sweep, stats, sessionId)
            for (i in 0..1) {
                val viaFactory = checkNotNull(ViewerReportFactory.buildReportData(source, i, data))
                val params = source.toReportSource().forFrame(i, data, base, base, nameIndex = source.nameIndexAt(i))
                val viaSource = ReportBuilder.buildReport(params)

                assertEquals("sweep=$sweep stats=${stats?.size} frame $i", printed(viaFactory), printed(viaSource))
                assertTrue(params.drawMinMarker)
            }
        }
    }

    @Test
    fun `a frame past a per-frame list uses the session value`() {
        val source = ReportSource(
            sessionId = "s",
            refName = "r.png",
            frameNames = emptyList(),
            imgW = 1,
            imgH = 1,
            roi = RoiData(0, 0, 1, 1),
            strainMethod = "VSG",
            subset = 41,
            step = 5,
            strainWindow = 15,
            subsetPerFrame = listOf(21),
            stepPerFrame = listOf(3, 4),
        )
        assertEquals(21, source.subsetAt(0))
        assertEquals(41, source.subsetAt(1))
        assertEquals(4, source.stepAt(1))
        assertEquals(5, source.stepAt(2))
        assertEquals(15, source.strainWindowAt(0))
        assertEquals("Frame_3", source.forFrame(2, FloatArray(0), base, cover, analysisDate = date).deformedImageName)
    }
}
