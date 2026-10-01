package com.indicvision.semper.results

import com.indicvision.semper.field.DicResult
import com.indicvision.semper.report.VisualizationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Bitmap is needed for the ARGB half of the comparison, so this runs under
// Robolectric, pinned to 34 like the other Robolectric tests here.
@RunWith(RobolectricTestRunner::class)
class VisualizationEngineTest {

    /**
     * A correlated grid: `step`-spaced points whose value rises left to right. Every
     * field carries a distinct pattern (signed, and spanning different magnitudes) so
     * the multi-field parity checks below are not all reading the same numbers.
     */
    private fun rampField(cols: Int, rows: Int, step: Int): FloatArray {
        val out = FloatArray(cols * rows * DicResult.STRIDE)
        var p = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                out[p + DicResult.IDX_X] = (c * step).toFloat()
                out[p + DicResult.IDX_Y] = (r * step).toFloat()
                out[p + DicResult.IDX_U] = (c - cols / 2) * 0.037f
                out[p + DicResult.IDX_V] = (rows / 2 - r) * 0.019f
                out[p + DicResult.IDX_EXX] = c.toFloat() / cols
                out[p + DicResult.IDX_EYY] = (r % 5 - 2) * 0.0007f
                out[p + DicResult.IDX_EXY] = (c % 3 - 1) * 0.0002f
                out[p + DicResult.IDX_ZNSSD] = 0.01f
                p += DicResult.STRIDE
            }
        }
        return out
    }

    @Test
    fun `DISPLAY_MAX_EDGE equals 1080`() {
        assertEquals(1080, VisualizationEngine.DISPLAY_MAX_EDGE)
    }

    @Test
    fun `the index plane and the bitmap are the same render`() {
        val step = 4
        val cols = 12
        val rows = 9
        val w = cols * step
        val h = rows * step
        val data = rampField(cols, rows, step)

        val plane = VisualizationEngine.generateHeatmapIndices(data, w, h, DicResult.IDX_EXX, step)
        val (bitmap, min, max) = VisualizationEngine.generateHeatmap(data, w, h, DicResult.IDX_EXX, step)

        assertEquals(plane.width, bitmap.width)
        assertEquals(plane.height, bitmap.height)
        assertEquals(plane.min, min, 0f)
        assertEquals(plane.max, max, 0f)

        val palette = VisualizationEngine.gifPalette(background = 0x000000)
        var covered = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val index = plane.indices[y * plane.width + x].toInt() and 0xFF
                val argb = bitmap.getPixel(x, y)
                if (index == VisualizationEngine.TRANSPARENT_INDEX) {
                    assertEquals("($x,$y) should be transparent", 0, argb)
                } else {
                    covered++
                    assertEquals("($x,$y)", palette[index] and 0xFFFFFF, argb and 0xFFFFFF)
                }
            }
        }
        assertTrue("the ramp should cover most of the frame, covered=$covered", covered > bitmap.width)
    }

    /** The original boxed sigma-clamp, kept as the parity oracle for the de-boxed path. */
    private fun boxedSigmaClamp(data: FloatArray, valIndex: Int): Pair<Float, Float> {
        val valid = mutableListOf<Float>()
        var i = 0
        while (i < data.size) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) valid.add(data[i + valIndex])
            i += DicResult.STRIDE
        }
        valid.sort()
        var mn = valid[(valid.size * 0.02).toInt().coerceIn(0, valid.size - 1)]
        var mx = valid[(valid.size * 0.98).toInt().coerceIn(0, valid.size - 1)]
        val minSpan = if (valIndex > 3) 0.0001f else 0.01f
        if ((mx - mn) < minSpan) {
            val mid = (mx + mn) / 2f
            mn = mid - minSpan / 2f
            mx = mid + minSpan / 2f
        }
        return mn to mx
    }

    @Test
    fun `de-boxed sigma-clamp min max is identical to the boxed oracle`() {
        val step = 4
        val data = rampField(cols = 25, rows = 17, step = step)
        for (valIndex in listOf(DicResult.IDX_EXX, DicResult.IDX_X)) {
            val plane = VisualizationEngine.generateHeatmapIndices(
                data,
                25 * step,
                17 * step,
                valIndex,
                step,
            )
            val (mn, mx) = boxedSigmaClamp(data, valIndex)
            assertEquals("min field=$valIndex", mn, plane.min, 0f)
            assertEquals("max field=$valIndex", mx, plane.max, 0f)
        }
    }

    @Test
    fun `de-boxed valueRanges is identical to the boxed oracle for every field`() {
        val data = rampField(cols = 25, rows = 17, step = 4)
        val fields = intArrayOf(
            DicResult.IDX_U,
            DicResult.IDX_V,
            DicResult.IDX_EXX,
            DicResult.IDX_EYY,
            DicResult.IDX_EXY,
        )

        val ranges = VisualizationEngine.valueRanges(data, fields)

        assertEquals(fields.size, ranges.size)
        for (valIndex in fields) {
            val actual = requireNotNull(ranges[valIndex]) { "field=$valIndex missing" }
            val (mn, mx) = boxedSigmaClamp(data, valIndex)
            assertEquals("min field=$valIndex", mn, actual.first, 0f)
            assertEquals("max field=$valIndex", mx, actual.second, 0f)
        }
    }

    @Test
    fun `valueRanges over a reused, oversized buffer and columns matches a fresh call`() {
        // globalRanges reuses one frame buffer and one set of columns across a batch
        // (TD-87): a smaller frame must read only its own floats, never the tail an
        // earlier, larger frame left, and must ignore what the columns held before.
        val data = rampField(cols = 25, rows = 17, step = 4)
        val fields = intArrayOf(
            DicResult.IDX_U,
            DicResult.IDX_V,
            DicResult.IDX_EXX,
            DicResult.IDX_EYY,
            DicResult.IDX_EXY,
        )
        val tail = 3 * DicResult.STRIDE
        val buffer = data.copyOf(data.size + tail).also { it.fill(1e9f, data.size, it.size) }
        val scratch = Array(fields.size) { FloatArray(data.size / DicResult.STRIDE + 50) { -1e9f } }

        val fresh = VisualizationEngine.valueRanges(data, fields)
        val reused = VisualizationEngine.valueRanges(buffer, data.size, fields, scratch)

        assertEquals(fresh, reused)
    }

    @Test
    fun `valueRanges is null per field when nothing correlates`() {
        val data = FloatArray(4 * DicResult.STRIDE) { -1f }
        val fields = intArrayOf(DicResult.IDX_U, DicResult.IDX_EXX)

        val ranges = VisualizationEngine.valueRanges(data, fields)

        assertEquals(fields.size, ranges.size)
        for (valIndex in fields) assertEquals("field=$valIndex", null, ranges[valIndex])
    }

    @Test
    fun `a field with no correlated points is entirely the transparent index`() {
        // Every point rejected: the native engine's invalid sentinel.
        val data = FloatArray(4 * DicResult.STRIDE) { -1f }

        val plane = VisualizationEngine.generateHeatmapIndices(data, 8, 8, DicResult.IDX_EXX, 4)

        assertTrue(
            plane.indices.all { (it.toInt() and 0xFF) == VisualizationEngine.TRANSPARENT_INDEX },
        )
        assertEquals(0f, plane.min, 0f)
        assertEquals(0f, plane.max, 0f)
    }

    /**
     * computeSigmaClampedRange is private — reached only through generateHeatmapIndices
     * / valueRanges above, which already parity-check it against the sort-based oracle
     * on ramp data. quickSelect (its new internal implementation) is notorious for
     * subtly misbehaving on shapes a smooth ramp never exercises — heavy duplicates,
     * reverse order, a single point — so these drive it through the same public API
     * with data specifically shaped to hit those cases.
     */
    private fun singleFieldData(values: FloatArray): FloatArray {
        val out = FloatArray(values.size * DicResult.STRIDE)
        for (i in values.indices) {
            val p = i * DicResult.STRIDE
            out[p + DicResult.IDX_X] = i.toFloat()
            out[p + DicResult.IDX_Y] = 0f
            out[p + DicResult.IDX_EXX] = values[i]
            out[p + DicResult.IDX_ZNSSD] = 0.01f
        }
        return out
    }

    @Test
    fun `quickSelect-backed range matches the sort oracle on heavy duplicates`() {
        // Mostly one repeated value with a few outliers — a classic quickselect
        // pathological case (Lomuto partition against a value that appears
        // hundreds of times).
        val values = FloatArray(500) { 5f }.also {
            it[0] = -100f
            it[1] = 200f
            it[250] = 5.5f
        }
        val data = singleFieldData(values)
        val plane = VisualizationEngine.generateHeatmapIndices(data, values.size, 1, DicResult.IDX_EXX, 1)
        val (mn, mx) = boxedSigmaClamp(data, DicResult.IDX_EXX)
        assertEquals(mn, plane.min, 0f)
        assertEquals(mx, plane.max, 0f)
    }

    @Test
    fun `quickSelect stays linear on a large field of one repeated value`() {
        // A partition on strict `<` settled one copy of the pivot per pass: this took
        // ~70 s on a desktop JVM. Three-way partitioning settles them all in one pass.
        // (Robolectric does not enforce @Test(timeout), so the bound is asserted.)
        val values = FloatArray(300_000) { 0f }.also { it[7] = -1f }
        val t0 = System.nanoTime()
        assertEquals(0f, VisualizationEngine.quickSelect(values.copyOf(), 294_000, 0, values.size), 0f)
        assertEquals(-1f, VisualizationEngine.quickSelect(values.copyOf(), 0, 0, values.size), 0f)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 5_000)
    }

    @Test
    fun `quickSelect stays near-linear on sorted and staircase fields`() {
        // Median-of-three at the ends and middle touched ~370n elements for the p98
        // pick of a sorted 1.2 M field, ~0.8 s each on a desktop JVM. The ninther stays
        // near 4n. A V field in grid order is the staircase.
        val n = 1_228_800
        val low = (n * 0.02).toInt()
        val high = (n * 0.98).toInt()
        var ns = 0L
        for (values in listOf(
            FloatArray(n) { it * 0.031f },
            FloatArray(n) { -it * 0.017f },
            FloatArray(n) { (it / 160) * 0.02f },
        )) {
            val sorted = values.copyOf().also { it.sort() }
            val t0 = System.nanoTime()
            val p02 = VisualizationEngine.quickSelect(values, low, 0, n)
            val p98 = VisualizationEngine.quickSelect(values, high, low, n)
            ns += System.nanoTime() - t0
            assertEquals(sorted[low], p02, 0f)
            assertEquals(sorted[high], p98, 0f)
        }
        val ms = ns / 1_000_000
        assertTrue("took $ms ms", ms < 1_500)
    }

    @Test
    fun `quickSelect matches a full sort when it falls back to sorting`() {
        // Organ-pipe fields (up, then down) defeat the ninther often enough to spend the
        // pass budget, and the rest of the range is sorted instead (n = 112, k = 2 does).
        for (n in 100..400) {
            val values = FloatArray(n) { (if (it < n / 2) it else n - it).toFloat() }
            val sorted = values.copyOf().also { it.sort() }
            for (k in intArrayOf(2, n / 50, n / 2, n * 49 / 50)) {
                assertEquals("n=$n k=$k", sorted[k], VisualizationEngine.quickSelect(values.copyOf(), k, 0, n), 0f)
            }
        }
    }

    @Test
    fun `quickSelect matches a full sort on duplicate-heavy random fields`() {
        val rng = kotlin.random.Random(3)
        repeat(200) {
            val n = rng.nextInt(1, 400)
            val alphabet = rng.nextInt(1, 6)
            val values = FloatArray(n) { rng.nextInt(alphabet) * 0.5f - 1f }
            val sorted = values.copyOf().also { it.sort() }
            // The two picks as computeSigmaClampedRange makes them, on one scratch
            // array: the second searches only from the first pick's index on.
            val low = rng.nextInt(n)
            val high = rng.nextInt(low, n)
            val scratch = values.copyOf()
            assertEquals(sorted[low], VisualizationEngine.quickSelect(scratch, low, 0, n), 0f)
            assertEquals(sorted[high], VisualizationEngine.quickSelect(scratch, high, low, n), 0f)
        }
    }

    @Test
    fun `quickSelect-backed range matches the sort oracle on a single point`() {
        val data = singleFieldData(floatArrayOf(42f))
        val plane = VisualizationEngine.generateHeatmapIndices(data, 1, 1, DicResult.IDX_EXX, 1)
        val (mn, mx) = boxedSigmaClamp(data, DicResult.IDX_EXX)
        assertEquals(mn, plane.min, 0f)
        assertEquals(mx, plane.max, 0f)
    }

    @Test
    fun `quickSelect-backed range matches the sort oracle when every value is identical`() {
        val data = singleFieldData(FloatArray(200) { 3.5f })
        val plane = VisualizationEngine.generateHeatmapIndices(data, 200, 1, DicResult.IDX_EXX, 1)
        val (mn, mx) = boxedSigmaClamp(data, DicResult.IDX_EXX)
        assertEquals(mn, plane.min, 0f)
        assertEquals(mx, plane.max, 0f)
    }

    @Test
    fun `quickSelect-backed range matches the sort oracle on reverse-sorted input`() {
        val values = FloatArray(300) { (300 - it).toFloat() }
        val data = singleFieldData(values)
        val plane = VisualizationEngine.generateHeatmapIndices(data, values.size, 1, DicResult.IDX_EXX, 1)
        val (mn, mx) = boxedSigmaClamp(data, DicResult.IDX_EXX)
        assertEquals(mn, plane.min, 0f)
        assertEquals(mx, plane.max, 0f)
    }

    @Test
    fun `quickSelect uses Float total order, not IEEE less-than, so -0f and 0f sort like Arrays sort`() {
        // IEEE `<` treats -0.0 and 0.0 as equal; Float.compareTo (what Arrays.sort
        // uses) puts -0.0 strictly before 0.0. A naive `<`-based quickSelect would
        // silently diverge from the sort oracle here — this pins the fix by testing
        // quickSelect directly, since going through the public heatmap API would let
        // clampSpan's min-span floor overwrite the exact bit pattern this checks.
        val rng = kotlin.random.Random(11)
        repeat(20) { trial ->
            val values = FloatArray(80) {
                when {
                    it == 0 -> -0f
                    it == 1 -> 0f
                    else -> (rng.nextFloat() - 0.5f) * 1000f
                }
            }
            for (k in values.indices) {
                val actual = values.copyOf()
                val expected = values.copyOf().also { it.sort() }
                val got = VisualizationEngine.quickSelect(actual, k, 0, actual.size)
                assertEquals(
                    "trial=$trial k=$k expected=${expected[k]} (bits=${expected[k].toRawBits()}) " +
                        "got=$got (bits=${got.toRawBits()})",
                    expected[k].toRawBits(),
                    got.toRawBits(),
                )
            }
        }
    }

    @Test
    fun `quickSelect-backed range matches the sort oracle on many random seeds`() {
        val rng = kotlin.random.Random(7)
        repeat(30) { trial ->
            val n = rng.nextInt(2, 400)
            val values = FloatArray(n) { (rng.nextFloat() - 0.5f) * rng.nextInt(1, 10_000) }
            val data = singleFieldData(values)
            val plane = VisualizationEngine.generateHeatmapIndices(data, n, 1, DicResult.IDX_EXX, 1)
            val (mn, mx) = boxedSigmaClamp(data, DicResult.IDX_EXX)
            assertEquals("trial=$trial n=$n min", mn, plane.min, 0f)
            assertEquals("trial=$trial n=$n max", mx, plane.max, 0f)
        }
    }

    @Test
    fun `fixed bounds put the ramp ends at the ends of the colour ramp`() {
        val step = 4
        val cols = 16
        val data = rampField(cols, 4, step)

        val plane = VisualizationEngine.generateHeatmapIndices(
            data,
            cols * step,
            4 * step,
            DicResult.IDX_EXX,
            step,
            customMin = 0f,
            customMax = 1f,
        )

        val used = plane.indices
            .map { it.toInt() and 0xFF }
            .filter { it != VisualizationEngine.TRANSPARENT_INDEX }
        assertEquals("cold end", 0, used.min())
        // Values reach (cols-1)/cols of the range, so the hot end is near but not
        // at the top of the ramp — what matters is that it never exceeds it.
        assertTrue("hot end ${used.max()} above the ramp", used.max() <= VisualizationEngine.TRANSPARENT_INDEX - 1)
        assertTrue("hot end ${used.max()} unexpectedly cold", used.max() > 200)
    }
}
