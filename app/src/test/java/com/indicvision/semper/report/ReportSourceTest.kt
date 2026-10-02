@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.indicvision.semper.data.cloud.SessionUploadBundler
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.fixtures.sessionRecord
import com.indicvision.semper.ui.viewer.ViewerArgs
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

    /**
     * The cloud bundle's own rule before it moved onto [EngineStats.fromList]:
     * every stored slot kept, never padded past what was stored, short lists
     * padded to the core slots.
     */
    private fun bundleRuleStats(stats: List<Float>): EngineStats {
        val size = stats.size.coerceIn(EngineStats.CORE_SLOT_COUNT, EngineStats.SLOT_COUNT)
        return EngineStats.fromArray(FloatArray(size) { stats.getOrElse(it) { 0f } })
    }

    @Test
    fun `fromList is the cloud bundle's former stats rule for every stored length`() {
        for (n in 0..EngineStats.SLOT_COUNT + 3) {
            val stats = List(n) { it * 1.5f + 1f }
            assertEquals("n=$n", bundleRuleStats(stats), EngineStats.fromList(stats))
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

    /** The live cloud bundle's params for [record]'s frame [frameIndex], stamped with the test's date. */
    private fun bundlerParams(record: SessionRecord, frameIndex: Int, data: FloatArray) =
        SessionUploadBundler.reportParams(record, frameIndex, data, base, cover).copy(analysisDate = date)

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
        // Every page names the analysis, its specimen, its reference and its ROI.
        for ((record, id) in listOf(batch to "b1", sweep to "s1")) {
            for (i in 0..2) {
                val params = bundlerParams(record, i, data)
                assertEquals("$id frame $i", id, params.sessionId)
                assertEquals("spec", params.specimenName)
                assertEquals("spec.tif", params.referenceImageName)
                assertEquals(RoiData(3, 4, 40, 30), params.roiData)
            }
        }
        // What the bundle prints, spelled out: the MAX marker only, the default
        // method for a record that stored none, the session value past a short
        // per-frame list, and every stored stats slot.
        val batch0 = bundlerParams(batch, 0, data)
        assertEquals("a.tif", batch0.deformedImageName)
        assertEquals(listOf(41, 5, 15), listOf(batch0.subsetSize, batch0.step, batch0.strainWindow))
        assertFalse(batch0.drawMinMarker)
        assertEquals("VSG", batch0.strainMethod)
        assertEquals(EngineStats.fromArray(FloatArray(17) { it.toFloat() }), batch0.engineStats)
        val sweep2 = bundlerParams(sweep, 2, data)
        assertEquals(listOf(41, 5, 99), listOf(sweep2.subsetSize, sweep2.step, sweep2.strainWindow))
        assertEquals("LSQ", sweep2.strainMethod)
        assertEquals("second", bundlerParams(sweep, 1, data).deformedImageName)
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
            frameNames = listOf("f0.png", "f1.png", "f2.png"),
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
            strainMethod = "VSG",
        ),
        imgW = cols * step,
        imgH = rows * step,
        baseStep = step,
        sweepSteps = if (sweep) intArrayOf(step, step) else null,
        sweepSubsets = if (sweep) intArrayOf(21, 31) else null,
        sweepStrainWins = if (sweep) intArrayOf(41) else null,
        roi = RoiData(0, 0, cols * step, rows * step),
        frameNames = if (sweep) listOf("S21", "S31", "S41") else listOf("f0.png", "f1.png", "f2.png"),
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
