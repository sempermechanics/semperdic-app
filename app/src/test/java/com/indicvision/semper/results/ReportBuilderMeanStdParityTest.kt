package com.indicvision.semper.results

import androidx.core.graphics.createBitmap
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.RoiData
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Guards the O(3n)->O(n) de-boxing of [ReportBuilder.buildReport]: the mean and
 * standard deviation printed for every field must be *bit-identical* to the original
 * boxed `List<Float>.average()` / `map{...}.average()` path (Robolectric is needed
 * because buildReport renders Bitmaps).
 */
@RunWith(RobolectricTestRunner::class)
class ReportBuilderMeanStdParityTest {

    private val step = 4
    private val cols = 20
    private val rows = 13

    /** A filled grid with signed displacements and signed strains, all accepted. */
    private fun gridField(): FloatArray {
        val out = FloatArray(cols * rows * DicResult.STRIDE)
        var p = 0
        var k = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                out[p + DicResult.IDX_X] = (c * step).toFloat()
                out[p + DicResult.IDX_Y] = (r * step).toFloat()
                out[p + DicResult.IDX_U] = (k - cols * rows / 2) * 0.031f
                out[p + DicResult.IDX_V] = (cols * rows / 2 - k) * 0.017f
                out[p + DicResult.IDX_EXX] = (k % 9 - 4) * 0.00042f
                out[p + DicResult.IDX_EYY] = (k % 6 - 3) * 0.00071f
                out[p + DicResult.IDX_EXY] = (k % 4 - 2) * 0.00023f
                out[p + DicResult.IDX_ZNSSD] = 0.01f
                p += DicResult.STRIDE
                k++
            }
        }
        return out
    }

    /** The original boxed computation, kept as the parity oracle. */
    private fun boxedMeanStd(data: FloatArray, dataIndex: Int): Pair<Float, Float> {
        val isStrain = DicResult.isStrainFieldIndex(dataIndex)
        val valid = mutableListOf<Float>()
        var i = 0
        while (i < data.size) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                val raw = data[i + dataIndex]
                valid.add(if (isStrain) abs(raw) else raw)
            }
            i += DicResult.STRIDE
        }
        val mean = valid.average().toFloat()
        val std = sqrt(valid.map { (it - mean) * (it - mean) }.average()).toFloat()
        return mean to std
    }

    @Test
    fun `buildReport mean and stdDev are bit-identical to the boxed path`() {
        val data = gridField()
        val w = cols * step
        val h = rows * step
        val base = createBitmap(w, h)
        val def = createBitmap(w, h)

        val report = ReportBuilder.buildReport(
            ReportBuilder.ReportBuildParams(
                data = data,
                baseImg = base,
                defImgForCover = def,
                imgW = w,
                imgH = h,
                step = step,
                sessionId = "test",
                specimenName = "test",
                analysisDate = "2026-01-01 00:00:00",
                subsetSize = 41,
                strainWindow = 15,
                strainMethod = "VSG",
                roiData = RoiData(0, 0, w, h),
                engineStats = EngineStats.fromArray(FloatArray(EngineStats.SLOT_COUNT)),
                referenceImageName = "ref.png",
                deformedImageName = "def.png",
            ),
        )

        val keyToIndex = mapOf(
            "U" to DicResult.IDX_U,
            "V" to DicResult.IDX_V,
            "Exx" to DicResult.IDX_EXX,
            "Eyy" to DicResult.IDX_EYY,
            "Exy" to DicResult.IDX_EXY,
        )

        for (field in report.fieldResults) {
            val dataIndex = keyToIndex.getValue(field.fieldKey)
            val (mean, std) = boxedMeanStd(data, dataIndex)
            val multiplier = DicResult.strainMultiplier(dataIndex)
            assertEquals("mean ${field.fieldKey}", mean * multiplier, field.meanValue, 0f)
            assertEquals("stdDev ${field.fieldKey}", std * multiplier, field.stdDevValue, 0f)
        }
    }
}
