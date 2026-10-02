// The jet colour stops and the p02/p98 percentiles read clearest as literals.
@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import android.graphics.Color
import com.indicvision.semper.field.DicResult

/**
 * The heatmap's colour scale: the jet ramp every render maps values onto, and
 * the percentile-clamped value range ([computeSigmaClampedRange]) a field is
 * mapped against, with the quickselect behind its two percentile picks.
 * [VisualizationEngine] is the public face of all of it.
 */
internal object HeatmapColorScale {

    /** Below this many values, [ninther] falls back to a plain median of three. */
    private const val NINTHER_MIN_SIZE = 40

    // PRECOMPUTED LOOKUP TABLE: Jet Colormap (256 colors). Built on first use so
    // the value-range helpers stay callable without an Android graphics stack.
    internal val JET_LUT: IntArray by lazy {
        IntArray(256) { i ->
            val v = i / 255.0f
            val r = (clamp(minOf(4f * v - 1.5f, -4f * v + 4.5f)) * 255).toInt()
            val g = (clamp(minOf(4f * v - 0.5f, -4f * v + 3.5f)) * 255).toInt()
            val b = (clamp(minOf(4f * v + 0.5f, -4f * v + 2.5f)) * 255).toInt()
            Color.rgb(r, g, b)
        }
    }

    private fun clamp(v: Float) = v.coerceIn(0f, 1f)

    /**
     * Robust percentile clamping, aligned with the Max/Min button: sorts and clamps
     * the first [count] entries of [values] in place. `FloatArray.sort` uses the same
     * total order as `List<Float>.sort()`, so the p02/p98 picks are identical to the
     * boxed collector this replaced.
     */
    internal fun computeSigmaClampedRange(values: FloatArray, count: Int, valIndex: Int): Pair<Float, Float> {
        if (count == 0) return Pair(0f, 1f)

        val p02Index = (count * 0.02).toInt().coerceIn(0, count - 1)
        val p98Index = (count * 0.98).toInt().coerceIn(0, count - 1)

        // Only two order statistics are needed out of this scratch array — quickSelect
        // finds each in expected O(n) instead of paying O(n log n) to fully sort it.
        // p98's search range starts at p02Index: quickSelect's postcondition (every
        // element before the found index is <= it, every element after is >=) means
        // everything from p02Index onward already excludes values known to be below
        // the p02 cut, without narrowing away the true p98 value.
        val p02 = quickSelect(values, p02Index, 0, count)
        val p98 = quickSelect(values, p98Index, p02Index, count)

        return clampSpan(p02, p98, valIndex)
    }

    /**
     * The k-th smallest value of `values[fromIndex, toIndex)` — the same value
     * `values.sort(fromIndex, toIndex); values[k]` would produce, using `Float`'s
     * total order ([Float.compareTo] — `-0.0 < 0.0`, NaN greatest, same as
     * `Arrays.sort(float[])`) rather than IEEE `<`, so a percentile landing exactly
     * on `-0.0`/`0.0` picks the identical element a full sort would have. Partially
     * reorders that range as a side effect (same contract a full sort would have —
     * every caller here treats the array as scratch, consumed after the call).
     *
     * [ReportFieldExtrema] reuses it for the same p02/p98 pick.
     */
    internal fun quickSelect(values: FloatArray, k: Int, fromIndex: Int, toIndex: Int): Float {
        var lo = fromIndex
        var hi = toIndex - 1
        // Introselect: an input that defeats the pivot choice gets a bounded number of
        // passes, then the rest of the range is sorted, capping the worst case at O(n log n).
        var passesLeft = 2 * (Int.SIZE_BITS - (toIndex - fromIndex).countLeadingZeroBits())
        while (lo < hi && passesLeft-- > 0) {
            val pivot = values[ninther(values, lo, hi)]
            // Three-way partition: [lo, lt) < pivot, [lt, gt] == pivot, (gt, hi] > pivot.
            // A two-way partition on strict `<` settled one copy of a repeated value per
            // pass, so a field of equal values made this quadratic.
            var lt = lo
            var gt = hi
            var i = lo
            while (i <= gt) {
                val c = values[i].compareTo(pivot)
                if (c < 0) {
                    values.swapInPlace(i++, lt++)
                } else if (c > 0) {
                    values.swapInPlace(i, gt--)
                } else {
                    i++
                }
            }
            when {
                k < lt -> hi = lt - 1
                k > gt -> lo = gt + 1
                else -> return pivot
            }
        }
        // lo <= k <= hi throughout, so a finished search has lo == hi == k.
        if (lo < hi) values.sort(lo, hi + 1)
        return values[k]
    }

    /**
     * Tukey's ninther: the median of three medians of three, spread over the range.
     * Median-of-three at the ends and middle, which this replaced, went wrong on sorted
     * and staircase fields (a V field in grid order is a staircase): after the first
     * partition those three stop being representative, and the p98 pick touched about
     * n^1.5 elements (~370n at 1.2 M points). The ninther stays near 4n on every shape
     * tried. It only reads, so the partition sees the range as it is.
     */
    private fun ninther(values: FloatArray, lo: Int, hi: Int): Int {
        val mid = lo + (hi - lo) / 2
        val size = hi - lo + 1
        if (size < NINTHER_MIN_SIZE) return medianOfThree(values, lo, mid, hi)
        val e = size / 8
        return medianOfThree(
            values,
            medianOfThree(values, lo, lo + e, lo + 2 * e),
            medianOfThree(values, mid - e, mid, mid + e),
            medianOfThree(values, hi - 2 * e, hi - e, hi),
        )
    }

    /** The index, of a, b and c, holding the median value in [Float.compareTo] order. */
    private fun medianOfThree(values: FloatArray, a: Int, b: Int, c: Int): Int {
        val ab = values[a].compareTo(values[b]) < 0
        val bc = values[b].compareTo(values[c]) < 0
        val ac = values[a].compareTo(values[c]) < 0
        return when {
            ab == bc -> b // a < b < c, or a >= b >= c
            ab -> if (ac) c else a // b is the largest
            else -> if (ac) a else c // b is the smallest
        }
    }

    private fun FloatArray.swapInPlace(i: Int, j: Int) {
        val tmp = this[i]
        this[i] = this[j]
        this[j] = tmp
    }

    /** Shared min-span floor for [computeSigmaClampedRange]. */
    private fun clampSpan(p02: Float, p98: Float, valIndex: Int): Pair<Float, Float> {
        var finalMin = p02
        var finalMax = p98

        // 3. Minimum span floor to prevent the colors from glitching on completely flat/zero fields
        val minSpan = if (valIndex > 3) 0.0001f else 0.01f // 0.1mε or 0.01px
        if ((finalMax - finalMin) < minSpan) {
            val mid = (finalMax + finalMin) / 2f
            finalMin = mid - (minSpan / 2f)
            finalMax = mid + (minSpan / 2f)
        }

        return Pair(finalMin, finalMax)
    }

    /**
     * [valueRanges] over the first [floatCount] floats of [data], filling [scratch]'s
     * columns when there is one per field and each holds `floatCount / STRIDE` values
     * (else new ones). The same values land in the same order, so the ranges are
     * identical; a caller walking a batch reuses one set of columns (TD-87).
     */
    internal fun valueRanges(
        data: FloatArray,
        floatCount: Int,
        valIndices: IntArray,
        scratch: Array<FloatArray>?,
    ): Map<Int, Pair<Float, Float>?> {
        // One primitive column per field, holding the same values in the same order as
        // the boxed MutableList<Float> collectors this replaced — so the sort and the
        // p02/p98 pick below are bit-identical. Five boxed columns cost ~20 B/value
        // (~100 MB at n=1M); these cost 4 B/value and allocate nothing per point.
        val pointCount = floatCount / DicResult.STRIDE
        val columns = scratch?.takeIf { s -> s.size == valIndices.size && s.all { it.size >= pointCount } }
            ?: Array(valIndices.size) { FloatArray(pointCount) }
        var count = 0
        for (i in 0 until floatCount step DicResult.STRIDE) {
            if (!DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) continue
            for (c in valIndices.indices) {
                columns[c][count] = data[i + valIndices[c]]
            }
            count++
        }
        // Every column has the same accepted-point count, so one emptiness test covers all.
        val ranges = LinkedHashMap<Int, Pair<Float, Float>?>(valIndices.size)
        for (c in valIndices.indices) {
            val valIndex = valIndices[c]
            ranges[valIndex] = if (count == 0) null else computeSigmaClampedRange(columns[c], count, valIndex)
        }
        return ranges
    }
}
