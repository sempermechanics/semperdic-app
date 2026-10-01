@file:Suppress("MagicNumber")

package com.indicvision.semper.field

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Histogram of one field over accepted points, already in display units
 * (px for U/V, millistrain for strain). Bin count follows Scott's rule
 * (`3.49 σ n^(-1/3)`), clamped to [MIN_BINS]–[MAX_BINS].
 */
class FieldHistogram(
    val min: Float,
    val max: Float,
    val counts: IntArray,
    val n: Int,
) {
    val binCount: Int get() = counts.size

    /** Inclusive left edge of bin [index]. */
    fun binStart(index: Int): Float {
        if (binCount <= 1 || min == max) return min
        return min + (max - min) * index / binCount
    }

    /** Exclusive right edge of bin [index], inclusive for the last bin. */
    fun binEnd(index: Int): Float {
        if (binCount <= 1 || min == max) return max
        return min + (max - min) * (index + 1) / binCount
    }

    companion object {
        internal const val MIN_BINS = 8
        internal const val MAX_BINS = 48
        private const val SCOTT_FACTOR = 3.49

        /**
         * Two walks over [data]: first for min/max/σ, then counts.
         * Null when no point is accepted.
         */
        fun from(data: FloatArray, dataIndex: Int): FieldHistogram? {
            val multiplier = DicResult.strainMultiplier(dataIndex)
            var maxV = Float.NEGATIVE_INFINITY
            var minV = Float.POSITIVE_INFINITY
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            var i = 0
            while (i < data.size) {
                if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                    val v = data[i + dataIndex] * multiplier
                    if (v > maxV) maxV = v
                    if (v < minV) minV = v
                    val d = v.toDouble()
                    sum += d
                    sumSq += d * d
                    n++
                }
                i += DicResult.STRIDE
            }
            if (n == 0) return null
            val binCount = scottBinCount(n, minV, maxV, sum, sumSq)
            val counts = IntArray(binCount)
            if (binCount != 1 && minV != maxV) {
                val span = maxV - minV
                i = 0
                while (i < data.size) {
                    if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                        val v = data[i + dataIndex] * multiplier
                        val idx = ((v - minV) / span * binCount).toInt()
                            .coerceIn(0, binCount - 1)
                        counts[idx]++
                    }
                    i += DicResult.STRIDE
                }
            } else {
                counts[0] = n
            }
            return FieldHistogram(minV, maxV, counts, n)
        }

        /** Scott's rule, then a phone-readable clamp. One bin when there is no scatter. */
        internal fun scottBinCount(
            n: Int,
            minV: Float,
            maxV: Float,
            sum: Double,
            sumSq: Double,
        ): Int {
            if (n < 2 || minV == maxV) return 1
            val mean = sum / n
            val variance = (sumSq - sum * mean) / (n - 1)
            val sigma = if (variance > 0.0 && variance.isFinite()) sqrt(variance) else 0.0
            val width = SCOTT_FACTOR * sigma * n.toDouble().pow(-1.0 / 3.0)
            return if (width <= 0.0 || !width.isFinite()) {
                1
            } else {
                ceil((maxV - minV) / width).toInt().coerceIn(MIN_BINS, MAX_BINS)
            }
        }
    }
}
