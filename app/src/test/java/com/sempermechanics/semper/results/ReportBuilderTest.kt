package com.sempermechanics.semper.results

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.ReportBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportBuilderTest {

    private fun syntheticField(n: Int): FloatArray {
        val data = FloatArray(n * DicResult.STRIDE)
        for (i in 0 until n) {
            val offset = i * DicResult.STRIDE
            data[offset + DicResult.IDX_X] = i.toFloat()
            data[offset + DicResult.IDX_Y] = 0f
            data[offset + DicResult.IDX_U] = i * 0.1f
            data[offset + DicResult.IDX_V] = 0f
            data[offset + DicResult.IDX_EXX] = i * 0.001f
            data[offset + DicResult.IDX_EYY] = 0f
            data[offset + DicResult.IDX_EXY] = 0f
            data[offset + DicResult.IDX_ZNSSD] = 0.01f
        }
        return data
    }

    @Test
    fun `computeFieldExtrema max and min indices are within percentile bounds`() {
        val n = 100
        val data = syntheticField(n)
        val extrema = ReportBuilder.computeFieldExtrema(data, DicResult.IDX_U, absoluteStrainValues = false)
        assertTrue(extrema.maxIdx >= 0)
        assertTrue(extrema.minIdx >= 0)

        val values = (0 until n).map { data[it * DicResult.STRIDE + DicResult.IDX_U] }.sorted()
        val p02 = values[(values.size * 0.02).toInt().coerceIn(0, values.size - 1)]
        val p98 = values[(values.size * 0.98).toInt().coerceIn(0, values.size - 1)]
        val maxVal = data[extrema.maxIdx + DicResult.IDX_U]
        val minVal = data[extrema.minIdx + DicResult.IDX_U]
        assertTrue(maxVal in p02..p98)
        assertTrue(minVal in p02..p98)
    }

    @Test
    fun `the reported max and min are the marked points' values, in display units`() {
        val data = syntheticField(100)
        val extrema = ReportBuilder.computeFieldExtrema(data, DicResult.IDX_EXX, absoluteStrainValues = false)

        val max = extrema.maxValue(data, DicResult.IDX_EXX)!!
        assertEquals(data[extrema.maxIdx + DicResult.IDX_EXX] * DicResult.STRAIN_TO_MILLISTRAIN, max, 0f)
        // A real point's value, so the table's value and location agree.
        assertTrue((0 until 100).any { it * 0.001f * DicResult.STRAIN_TO_MILLISTRAIN == max })
        assertEquals(null, ReportBuilder.FieldExtrema(-1, -1).maxValue(data, DicResult.IDX_EXX))
    }

    @Test
    fun `a signed search marks the most negative strain, not the one nearest zero`() {
        val data = syntheticField(100)
        for (i in 0 until 100) data[i * DicResult.STRIDE + DicResult.IDX_EXX] = (i - 50) * 0.001f
        val signed = ReportBuilder.computeFieldExtrema(data, DicResult.IDX_EXX, absoluteStrainValues = false)
        assertTrue(data[signed.minIdx + DicResult.IDX_EXX] < -0.04f)
    }

    @Test
    fun `empty or all-rejected field returns FieldExtrema -1 -1`() {
        val empty = ReportBuilder.computeFieldExtrema(FloatArray(0), DicResult.IDX_U)
        assertEquals(ReportBuilder.FieldExtrema(-1, -1), empty)

        val rejected = syntheticField(5)
        for (i in 0 until 5) {
            rejected[i * DicResult.STRIDE + DicResult.IDX_ZNSSD] = -1f
        }
        val extrema = ReportBuilder.computeFieldExtrema(rejected, DicResult.IDX_U)
        assertEquals(ReportBuilder.FieldExtrema(-1, -1), extrema)
    }

    @Test
    fun `formatMetric uses scientific notation for very small values`() {
        val formatted = ReportBuilder.formatMetric(1e-5f)
        assertTrue("expected scientific notation, got $formatted", formatted.contains("e", ignoreCase = true))
    }

    /** A field with signed displacements, signed strains, and a few rejected points. */
    private fun variedField(n: Int): FloatArray {
        val data = FloatArray(n * DicResult.STRIDE)
        for (i in 0 until n) {
            val o = i * DicResult.STRIDE
            data[o + DicResult.IDX_X] = i.toFloat()
            data[o + DicResult.IDX_Y] = (i % 7).toFloat()
            data[o + DicResult.IDX_U] = (i - n / 2) * 0.037f // spans negative and positive
            data[o + DicResult.IDX_V] = (n / 2 - i) * 0.019f
            data[o + DicResult.IDX_EXX] = (i - n / 3) * 0.0004f
            data[o + DicResult.IDX_EYY] = (i % 5 - 2) * 0.0007f
            data[o + DicResult.IDX_EXY] = (i % 3 - 1) * 0.0002f
            data[o + DicResult.IDX_ZNSSD] = if (i % 11 == 0) -1f else 0.01f // some rejected
        }
        return data
    }

    /**
     * Running max/min for the oracle. Deliberately mutable and shared between the two
     * passes: the original carried maxV/minV over into the fallback pass rather than
     * resetting them, which affects tie-breaking, so the oracle must do the same.
     */
    private class ExtremaAcc {
        var maxV = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxIdx = -1
        var minIdx = -1

        fun offer(v: Float, i: Int) {
            if (v > maxV) {
                maxV = v
                maxIdx = i
            }
            if (v < minV) {
                minV = v
                minIdx = i
            }
        }
    }

    /** The field value as the oracle sees it (abs only for strain fields when asked). */
    private fun oracleValue(data: FloatArray, i: Int, dataIndex: Int, absoluteStrainValues: Boolean): Float {
        val raw = data[i + dataIndex]
        return if (DicResult.isStrainFieldIndex(dataIndex) && absoluteStrainValues) kotlin.math.abs(raw) else raw
    }

    /** One max/min pass over accepted points, optionally restricted to [window]. */
    private fun boxedScan(
        data: FloatArray,
        dataIndex: Int,
        absoluteStrainValues: Boolean,
        window: ClosedFloatingPointRange<Float>?,
        acc: ExtremaAcc,
    ) {
        val isCorrelation = dataIndex == DicResult.IDX_ZNSSD
        for (i in data.indices step DicResult.STRIDE) {
            if (!DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD], isCorrelation)) continue
            val v = oracleValue(data, i, dataIndex, absoluteStrainValues)
            if (window == null || v in window) acc.offer(v, i)
        }
    }

    /** The original boxed-List implementation, kept here as the parity oracle. */
    private fun boxedExtrema(
        data: FloatArray,
        dataIndex: Int,
        absoluteStrainValues: Boolean,
    ): ReportBuilder.FieldExtrema {
        val isCorrelation = dataIndex == DicResult.IDX_ZNSSD
        val valid = mutableListOf<Float>()
        for (i in data.indices step DicResult.STRIDE) {
            if (!DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD], isCorrelation)) continue
            valid.add(oracleValue(data, i, dataIndex, absoluteStrainValues))
        }
        if (valid.isEmpty()) return ReportBuilder.FieldExtrema(-1, -1)

        valid.sort()
        val p02 = valid[(valid.size * 0.02).toInt().coerceIn(0, valid.size - 1)]
        val p98 = valid[(valid.size * 0.98).toInt().coerceIn(0, valid.size - 1)]

        val acc = ExtremaAcc()
        boxedScan(data, dataIndex, absoluteStrainValues, p02..p98, acc)
        if (acc.maxIdx == -1 || acc.minIdx == -1) {
            boxedScan(data, dataIndex, absoluteStrainValues, null, acc)
        }
        return ReportBuilder.FieldExtrema(acc.maxIdx, acc.minIdx)
    }

    @Test
    fun `de-boxed computeFieldExtrema is identical to the boxed oracle for every field`() {
        val data = variedField(257)
        val fields = listOf(
            DicResult.IDX_U,
            DicResult.IDX_V,
            DicResult.IDX_EXX,
            DicResult.IDX_EYY,
            DicResult.IDX_EXY,
            DicResult.IDX_ZNSSD,
        )
        for (idx in fields) {
            for (abs in listOf(true, false)) {
                assertEquals(
                    "field=$idx abs=$abs",
                    boxedExtrema(data, idx, abs),
                    ReportBuilder.computeFieldExtrema(data, idx, abs),
                )
            }
        }
    }

    @Test
    fun `the scratch overload matches the allocating wrapper`() {
        val data = variedField(129)
        val scratch = FloatArray(data.size / DicResult.STRIDE)
        for (idx in listOf(DicResult.IDX_U, DicResult.IDX_EXX)) {
            assertEquals(
                ReportBuilder.computeFieldExtrema(data, idx, absoluteStrainValues = false),
                ReportBuilder.computeFieldExtrema(data, idx, absoluteStrainValues = false, scratch),
            )
        }
    }
}
