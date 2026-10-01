// Heatmap rendering / colour-mapping: literal colour stops, grid math and long
// interpolation loops are inherent to the pixel work and read clearest inline,
// so the structural and magic-number rules are suppressed for this whole file.
@file:Suppress(
    "MagicNumber",
    "ComplexCondition",
    "LongMethod",
    "CyclomaticComplexMethod",
    "LongParameterList",
    "NestedBlockDepth",
    "TooManyFunctions", // quickSelect + its ninther/median/swap helpers stay next to their one caller
)

package com.indicvision.semper.report

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.indicvision.semper.field.DicResult
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a full-field result array into heatmap bitmaps: grid interpolation,
 * percentile-based color scaling, and the jet colormap shared by the on-screen
 * viewer and the PDF report.
 */
object VisualizationEngine {

    /** Longest-edge cap for on-screen scrub heatmaps (export paths omit this). */
    const val DISPLAY_MAX_EDGE = 1080

    /**
     * Longest-edge cap for report/upload compositing. The PDF and cloud heatmaps
     * are downscaled to 600 px wide by [ReportBuilder.compressForPdf] anyway, so
     * this is far above the visible output — it exists purely to stop the
     * intermediate full-resolution ARGB_8888 bitmaps from OOMing on large
     * (e.g. 26 MP) references.
     */
    const val REPORT_MAX_EDGE = 1280

    /**
     * Scale factor that shrinks [imgW]×[imgH] so its longest edge is ≤ [maxEdge]
     * (1f when already within). This is the identical formula
     * [generateHeatmapIndices] uses for its own downscale, so a caller that composes
     * at `imgW*scale × imgH*scale` lines up pixel-for-pixel with the capped heatmap.
     */
    fun cappedRenderScale(imgW: Int, imgH: Int, maxEdge: Int): Float {
        val longest = max(imgW, imgH).coerceAtLeast(1)
        return if (longest > maxEdge) maxEdge.toFloat() / longest else 1f
    }

    /** [w]×[h] shrunk so its longest edge is ≤ [maxEdge]; unchanged if already within. */
    fun cappedDims(w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
        val scale = cappedRenderScale(w, h, maxEdge)
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }

    /**
     * Palette slot for "no correlated data here" — transparent on screen, the
     * animation's background colour in a GIF. It costs the colour ramp its top
     * entry (values map to 0..[LAST_COLOR]), which is one 255th of the scale and
     * buys a single render path shared by the viewer, the report and the GIF.
     */
    const val TRANSPARENT_INDEX = 255
    private const val LAST_COLOR = TRANSPARENT_INDEX - 1

    /** Below this many values, [ninther] falls back to a plain median of three. */
    private const val NINTHER_MIN_SIZE = 40

    /**
     * One byte per pixel, each an index into [JET_LUT] or [TRANSPARENT_INDEX],
     * with the value range the colours were mapped against.
     */
    class IndexPlane(
        val indices: ByteArray,
        val width: Int,
        val height: Int,
        val min: Float,
        val max: Float,
    )

    /**
     * The jet ramp as a GIF global colour table: [TRANSPARENT_INDEX] takes
     * [background], every other slot is the colour the viewer would draw.
     */
    fun gifPalette(background: Int): IntArray =
        IntArray(JET_LUT.size) { if (it == TRANSPARENT_INDEX) background else JET_LUT[it] }

    /**
     * The colours values are mapped to, lowest value first: every [JET_LUT] slot
     * but [TRANSPARENT_INDEX]. Evenly spaced gradient stops over these draw a
     * colour bar that matches the map.
     */
    fun rampColors(): IntArray = JET_LUT.copyOf(LAST_COLOR + 1)

    // PRECOMPUTED LOOKUP TABLE: Jet Colormap (256 colors). Built on first use so
    // the value-range helpers stay callable without an Android graphics stack.
    private val JET_LUT: IntArray by lazy {
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
    private fun computeSigmaClampedRange(values: FloatArray, count: Int, valIndex: Int): Pair<Float, Float> {
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
     * Package-visible (not just private to this file) — [ReportBuilder] reuses it
     * for the same p02/p98 pick.
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
     * The displayed value range of several fields at once, in one pass over the
     * points — the same percentile-clamped bounds [generateHeatmap] would pick
     * for each. Null for a field with no correlated points.
     *
     * Per-frame heatmaps use this. The summary GIF widens these same ends
     * across the batch: lowest scale-min, highest scale-max.
     */
    fun valueRanges(data: FloatArray, valIndices: IntArray): Map<Int, Pair<Float, Float>?> =
        valueRanges(data, data.size, valIndices, scratch = null)

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

    /**
     * @param maxLongEdge when set and smaller than the image's longest edge, the
     *   bitmap is generated at display scale (viewer scrub). Pass null / omit for
     *   full-resolution PDF and share export.
     */
    fun generateHeatmap(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null, // Optional Custom Bounds
        customMax: Float? = null,
        maxLongEdge: Int? = null,
    ): Triple<Bitmap, Float, Float> {
        val plane = generateHeatmapIndices(data, imgW, imgH, valIndex, step, customMin, customMax, maxLongEdge)
        return Triple(toBitmap(plane), plane.min, plane.max)
    }

    /** [plane] in [JET_LUT] colours, [TRANSPARENT_INDEX] left fully transparent. */
    private fun toBitmap(plane: IndexPlane): Bitmap {
        val bitmap = createBitmap(plane.width, plane.height, Bitmap.Config.ARGB_8888)
        // Expand one row at a time into a reused buffer instead of materialising a
        // full-image IntArray next to the bitmap: peak goes from 8 bytes/px to
        // 4 bytes/px + one row. Same pixels, written in the same order.
        val row = IntArray(plane.width)
        for (y in 0 until plane.height) {
            val rowStart = y * plane.width
            for (x in 0 until plane.width) {
                val index = plane.indices[rowStart + x].toInt() and 0xFF
                row[x] = if (index == TRANSPARENT_INDEX) 0 else JET_LUT[index]
            }
            bitmap.setPixels(row, 0, plane.width, 0, y, plane.width, 1)
        }
        return bitmap
    }

    /**
     * The same render as [generateHeatmap], stopping one step earlier: one byte
     * per pixel, holding an index into [JET_LUT] — or [TRANSPARENT_INDEX] where
     * no correlated data covers the pixel.
     *
     * The GIF summary animation consumes this directly, so its colours are the
     * viewer's colours by construction rather than by quantisation. Both callers
     * share this one implementation of the interpolation.
     */
    fun generateHeatmapIndices(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null,
        customMax: Float? = null,
        maxLongEdge: Int? = null,
    ): IndexPlane {
        val longest = max(imgW, imgH).coerceAtLeast(1)
        val scale = if (maxLongEdge != null && longest > maxLongEdge) {
            maxLongEdge.toFloat() / longest
        } else {
            1f
        }
        val outW = (imgW * scale).toInt().coerceAtLeast(1)
        val outH = (imgH * scale).toInt().coerceAtLeast(1)

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        // Primitive collector (same values, same order as the previous List<Float>) so
        // the sort + percentile pick in computeSigmaClampedRange is bit-identical.
        val validValues = FloatArray(data.size / DicResult.STRIDE)
        var validCount = 0

        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr)) {
                val x = data[i].toInt()
                val y = data[i + 1].toInt()
                val v = data[i + valIndex]

                validValues[validCount++] = v
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        if (validCount == 0) {
            return IndexPlane(ByteArray(outW * outH) { TRANSPARENT_INDEX.toByte() }, outW, outH, 0f, 0f)
        }

        // THE SCALING LOGIC: Use Custom Bounds if provided, else use Mean ± 3σ Statistical Clamping
        val minV: Float
        val maxV: Float
        if (customMin != null && customMax != null) {
            minV = customMin
            maxV = customMax
        } else {
            val bounds = computeSigmaClampedRange(validValues, validCount, valIndex)
            minV = bounds.first
            maxV = bounds.second
        }

        val range = if (maxV - minV == 0f) 0.0001f else maxV - minV

        val plane = ByteArray(outW * outH) { TRANSPARENT_INDEX.toByte() }
        val cols = ((maxX - minX) / step) + 1
        val rows = ((maxY - minY) / step) + 1
        val grid = FloatArray(cols * rows) { Float.NaN }

        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr)) {
                val x = data[i].toInt()
                val y = data[i + 1].toInt()
                val c = (x - minX) / step
                val r = (y - minY) / step
                if (c in 0 until cols && r in 0 until rows) {
                    grid[r * cols + c] = data[i + valIndex]
                }
            }
        }

        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                val v00 = grid[r * cols + c]
                val v10 = grid[r * cols + (c + 1)]
                val v01 = grid[(r + 1) * cols + c]
                val v11 = grid[(r + 1) * cols + (c + 1)]

                if (!v00.isNaN() && !v10.isNaN() && !v01.isNaN() && !v11.isNaN()) {
                    val x0 = minX + c * step
                    val y0 = minY + r * step
                    val x1 = x0 + step
                    val y1 = y0 + step

                    val ox0 = (x0 * scale).toInt().coerceIn(0, outW)
                    val oy0 = (y0 * scale).toInt().coerceIn(0, outH)
                    val ox1 = (x1 * scale).toInt().coerceIn(0, outW)
                    val oy1 = (y1 * scale).toInt().coerceIn(0, outH)
                    val dw = (ox1 - ox0).coerceAtLeast(1)
                    val dh = (oy1 - oy0).coerceAtLeast(1)

                    for (oy in oy0 until oy1) {
                        val wy = (oy - oy0).toFloat() / dh
                        val rowOffset = oy * outW
                        val leftEdgeV = v00 + wy * (v01 - v00)
                        val rightEdgeV = v10 + wy * (v11 - v10)

                        for (ox in ox0 until ox1) {
                            val wx = (ox - ox0).toFloat() / dw
                            val v = leftEdgeV + wx * (rightEdgeV - leftEdgeV)
                            val norm = ((v.coerceIn(minV, maxV) - minV) / range * LAST_COLOR).toInt()
                            plane[rowOffset + ox] = norm.coerceIn(0, LAST_COLOR).toByte()
                        }
                    }
                }
            }
        }

        return IndexPlane(plane, outW, outH, minV, maxV)
    }

    /**
     * [generateHeatmap] in the deformed configuration: each point is drawn where
     * it moved to, (x + u, y + v), so the map lies on that frame's own photo the
     * way [generateHeatmap] lies on the reference. The colour range is taken
     * over the same accepted values, so both renders share one scale.
     *
     * The viewer's frames only. The summary GIF and the report keep the
     * reference render, whose bytes are pinned.
     */
    fun generateDeformedHeatmap(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null,
        customMax: Float? = null,
        maxLongEdge: Int? = null,
    ): Triple<Bitmap, Float, Float> {
        val plane = generateDeformedHeatmapIndices(data, imgW, imgH, valIndex, step, customMin, customMax, maxLongEdge)
        return Triple(toBitmap(plane), plane.min, plane.max)
    }

    /**
     * The index plane behind [generateDeformedHeatmap]. The point grid is the one
     * [generateHeatmapIndices] builds. Each cell with four solved corners becomes
     * the quad through their displaced positions, and every pixel inside it takes
     * the bilinear value at its inverse-bilinear (s, t). With no displacement
     * that is the reference render's own interpolation.
     */
    fun generateDeformedHeatmapIndices(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null,
        customMax: Float? = null,
        maxLongEdge: Int? = null,
    ): IndexPlane {
        val scale = cappedRenderScale(imgW, imgH, maxLongEdge ?: Int.MAX_VALUE)
        val outW = (imgW * scale).toInt().coerceAtLeast(1)
        val outH = (imgH * scale).toInt().coerceAtLeast(1)

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        val validValues = FloatArray(data.size / DicResult.STRIDE)
        var validCount = 0
        for (i in data.indices step DicResult.STRIDE) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                val x = data[i].toInt()
                val y = data[i + 1].toInt()
                validValues[validCount++] = data[i + valIndex]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (validCount == 0) {
            return IndexPlane(ByteArray(outW * outH) { TRANSPARENT_INDEX.toByte() }, outW, outH, 0f, 0f)
        }

        val (minV, maxV) = if (customMin != null && customMax != null) {
            customMin to customMax
        } else {
            computeSigmaClampedRange(validValues, validCount, valIndex)
        }
        val range = if (maxV - minV == 0f) 0.0001f else maxV - minV

        val plane = ByteArray(outW * outH) { TRANSPARENT_INDEX.toByte() }
        val cols = ((maxX - minX) / step) + 1
        val rows = ((maxY - minY) / step) + 1
        val grid = FloatArray(cols * rows) { Float.NaN }
        // Where each grid point sits in the deformed frame, in output pixels.
        val px = FloatArray(cols * rows)
        val py = FloatArray(cols * rows)
        for (i in data.indices step DicResult.STRIDE) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                val x = data[i].toInt()
                val y = data[i + 1].toInt()
                val c = (x - minX) / step
                val r = (y - minY) / step
                if (c in 0 until cols && r in 0 until rows) {
                    val k = r * cols + c
                    grid[k] = data[i + valIndex]
                    px[k] = (data[i] + data[i + DicResult.IDX_U]) * scale
                    py[k] = (data[i + 1] + data[i + DicResult.IDX_V]) * scale
                }
            }
        }

        val cell = DeformedCell(plane, outW, outH, minV, maxV, range)
        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                val k00 = r * cols + c
                val k10 = k00 + 1
                val k01 = k00 + cols
                val k11 = k01 + 1
                if (!grid[k00].isNaN() && !grid[k10].isNaN() && !grid[k01].isNaN() && !grid[k11].isNaN()) {
                    cell.fill(px, py, grid, k00, k10, k01, k11)
                }
            }
        }
        return IndexPlane(plane, outW, outH, minV, maxV)
    }

    /**
     * Fills one displaced grid cell of a [generateDeformedHeatmapIndices] plane.
     * The quad is `a + e·s + f·t + g·s·t` with a = corner 00, e along 00→10 and
     * f along 00→01; a pixel inside it is found by inverting that map.
     */
    private class DeformedCell(
        private val plane: ByteArray,
        private val outW: Int,
        private val outH: Int,
        private val minV: Float,
        private val maxV: Float,
        private val range: Float,
    ) {
        private var ex = 0f
        private var ey = 0f
        private var fx = 0f
        private var fy = 0f
        private var gx = 0f
        private var gy = 0f

        /** (s, t) of the pixel last located by [locate]. */
        private var s = 0f
        private var t = 0f

        fun fill(px: FloatArray, py: FloatArray, grid: FloatArray, k00: Int, k10: Int, k01: Int, k11: Int) {
            val ax = px[k00]
            val ay = py[k00]
            ex = px[k10] - ax
            ey = py[k10] - ay
            fx = px[k01] - ax
            fy = py[k01] - ay
            gx = ax - px[k10] + px[k11] - px[k01]
            gy = ay - py[k10] + py[k11] - py[k01]
            val ox0 = ceil(min(min(ax, px[k10]), min(px[k01], px[k11]))).toInt().coerceAtLeast(0)
            val ox1 = floor(max(max(ax, px[k10]), max(px[k01], px[k11]))).toInt().coerceAtMost(outW - 1)
            val oy0 = ceil(min(min(ay, py[k10]), min(py[k01], py[k11]))).toInt().coerceAtLeast(0)
            val oy1 = floor(max(max(ay, py[k10]), max(py[k01], py[k11]))).toInt().coerceAtMost(outH - 1)
            val v00 = grid[k00]
            val v10 = grid[k10]
            val v01 = grid[k01]
            val v11 = grid[k11]
            for (oy in oy0..oy1) {
                val rowOffset = oy * outW
                for (ox in ox0..ox1) {
                    if (locate(ox - ax, oy - ay)) {
                        // Same bilinear form as the reference render.
                        val leftEdgeV = v00 + t * (v01 - v00)
                        val rightEdgeV = v10 + t * (v11 - v10)
                        val v = leftEdgeV + s * (rightEdgeV - leftEdgeV)
                        val norm = ((v.coerceIn(minV, maxV) - minV) / range * LAST_COLOR).toInt()
                        plane[rowOffset + ox] = norm.coerceIn(0, LAST_COLOR).toByte()
                    }
                }
            }
        }

        /**
         * Inverse bilinear for the offset (hx, hy) from corner 00: solves the
         * quadratic in t, then s from whichever axis is better conditioned.
         * True, with [s] and [t] set, when the pixel lies inside the cell.
         */
        private fun locate(hx: Float, hy: Float): Boolean {
            val k2 = gx * fy - gy * fx
            val k1 = ex * fy - ey * fx + hx * gy - hy * gx
            val k0 = hx * ey - hy * ex
            var found = false
            if (abs(k2) < DEGENERATE_EPS) {
                if (abs(k1) >= DEGENERATE_EPS) found = tryT(-k0 / k1, hx, hy)
            } else {
                val disc = k1 * k1 - 4f * k0 * k2
                if (disc >= 0f) {
                    val root = sqrt(disc)
                    found = tryT((-k1 - root) / (2f * k2), hx, hy) || tryT((-k1 + root) / (2f * k2), hx, hy)
                }
            }
            return found
        }

        private fun tryT(candidate: Float, hx: Float, hy: Float): Boolean {
            val dx = ex + gx * candidate
            val dy = ey + gy * candidate
            val candidateS = if (abs(dx) >= abs(dy)) {
                (hx - fx * candidate) / dx
            } else {
                (hy - fy * candidate) / dy
            }
            val inside = candidate in -EDGE_EPS..1f + EDGE_EPS && candidateS in -EDGE_EPS..1f + EDGE_EPS
            if (inside) {
                s = candidateS.coerceIn(0f, 1f)
                t = candidate.coerceIn(0f, 1f)
            }
            return inside
        }
    }

    /** Below this a cross product is treated as zero: the quad is a parallelogram along that axis. */
    private const val DEGENERATE_EPS = 1e-6f

    /** Slack on the cell's (s, t) bounds, so a pixel on a shared edge is not lost to rounding. */
    private const val EDGE_EPS = 1e-4f
}
