// Percentile picks: the p02/p98 literals read clearest inline, and each pass
// over the points keeps its loop whole.
@file:Suppress("MagicNumber", "CyclomaticComplexMethod", "NestedBlockDepth")

package com.indicvision.semper.report

import com.indicvision.semper.field.DicResult
import com.indicvision.semper.report.ReportBuilder.FieldExtrema
import kotlin.math.abs

/**
 * Where a field's MAX and MIN markers go: the extreme accepted points inside
 * its p02..p98 band. [ReportBuilder.computeFieldExtrema] is its public face;
 * [ReportBuilder.buildReport] calls [extremaFromScratch] on values it has
 * already collected.
 */
internal object ReportFieldExtrema {

    /** Percentile-clamped global max/min indices for one field column. */
    fun computeFieldExtrema(
        data: FloatArray,
        dataIndex: Int,
        absoluteStrainValues: Boolean = true,
    ): FieldExtrema =
        computeFieldExtrema(data, dataIndex, absoluteStrainValues, FloatArray(data.size / DicResult.STRIDE))

    /**
     * As [computeFieldExtrema], but collects accepted values into the caller-supplied
     * [scratch] (must hold at least the accepted-point count) instead of a boxed
     * `List<Float>`, so a report build can reuse a single primitive buffer across
     * fields. Sorting a primitive `FloatArray` uses the same total order as
     * `List<Float>.sort()` (`-0.0 < 0.0`, NaN greatest), so the p02/p98 picks — and
     * therefore the returned indices — are identical to the boxed path.
     */
    fun computeFieldExtrema(
        data: FloatArray,
        dataIndex: Int,
        absoluteStrainValues: Boolean,
        scratch: FloatArray,
    ): FieldExtrema {
        val isStrain = DicResult.isStrainFieldIndex(dataIndex)
        val isCorrelation = dataIndex == DicResult.IDX_ZNSSD

        var count = 0
        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr, isCorrelation)) {
                val rawVal = data[i + dataIndex]
                scratch[count++] = if (isStrain && absoluteStrainValues) abs(rawVal) else rawVal
            }
        }
        return extremaFromScratch(data, dataIndex, absoluteStrainValues, scratch, count)
    }

    /**
     * The sort + percentile + index passes of [computeFieldExtrema], given a [scratch]
     * already filled with the first [count] accepted field values (in the same
     * `absoluteStrainValues` convention). Split out so [buildReport] can fill the buffer
     * once — for both mean/std and extrema — instead of walking `data` twice per field.
     */
    internal fun extremaFromScratch(
        data: FloatArray,
        dataIndex: Int,
        absoluteStrainValues: Boolean,
        scratch: FloatArray,
        count: Int,
    ): FieldExtrema {
        if (count == 0) return FieldExtrema(-1, -1)
        val isStrain = DicResult.isStrainFieldIndex(dataIndex)
        val isCorrelation = dataIndex == DicResult.IDX_ZNSSD
        fun fieldValue(rawVal: Float): Float = if (isStrain && absoluteStrainValues) abs(rawVal) else rawVal

        // Only two order statistics are needed out of scratch — quickSelect finds
        // each in expected O(n) instead of paying O(n log n) to fully sort it (same
        // change, same reasoning, as VisualizationEngine.computeSigmaClampedRange).
        val p02Index = (count * 0.02).toInt().coerceIn(0, count - 1)
        val p98Index = (count * 0.98).toInt().coerceIn(0, count - 1)
        val p02 = VisualizationEngine.quickSelect(scratch, p02Index, 0, count)
        val p98 = VisualizationEngine.quickSelect(scratch, p98Index, p02Index, count)

        var maxV = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxIdx = -1
        var minIdx = -1
        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr, isCorrelation)) {
                val valToCheck = fieldValue(data[i + dataIndex])
                if (valToCheck in p02..p98) {
                    if (valToCheck > maxV) {
                        maxV = valToCheck
                        maxIdx = i
                    }
                    if (valToCheck < minV) {
                        minV = valToCheck
                        minIdx = i
                    }
                }
            }
        }

        if (maxIdx == -1 || minIdx == -1) {
            for (i in data.indices step DicResult.STRIDE) {
                val corr = data[i + DicResult.IDX_ZNSSD]
                if (DicResult.isAcceptedPoint(corr, isCorrelation)) {
                    val valToCheck = fieldValue(data[i + dataIndex])
                    if (valToCheck > maxV) {
                        maxV = valToCheck
                        maxIdx = i
                    }
                    if (valToCheck < minV) {
                        minV = valToCheck
                        minIdx = i
                    }
                }
            }
        }

        return FieldExtrema(maxIdx, minIdx)
    }
}
