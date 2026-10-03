package com.sempermechanics.semper.results

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.FieldHistogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame ⓘ histogram: accepted points only, millistrain for strain, Scott bins.
 */
class DicResultHistogramTest {

    private fun field(
        n: Int,
        fill: (FloatArray, Int) -> Unit,
    ): FloatArray {
        val data = FloatArray(n * DicResult.STRIDE)
        for (i in 0 until n) fill(data, i * DicResult.STRIDE)
        return data
    }

    @Test
    fun `empty or all-rejected fields return null`() {
        assertNull(FieldHistogram.from(FloatArray(0), DicResult.IDX_U))
        val rejected = field(4) { data, offset ->
            data[offset + DicResult.IDX_U] = 1f
            data[offset + DicResult.IDX_ZNSSD] = -1f
        }
        assertNull(FieldHistogram.from(rejected, DicResult.IDX_U))
    }

    @Test
    fun `constant accepted values collapse to one bin`() {
        val data = field(12) { data, offset ->
            data[offset + DicResult.IDX_U] = 2.5f
            data[offset + DicResult.IDX_ZNSSD] = 0.01f
        }
        val hist = FieldHistogram.from(data, DicResult.IDX_U)
        assertNotNull(hist)
        assertEquals(1, hist!!.binCount)
        assertEquals(12, hist.counts[0])
        assertEquals(12, hist.n)
        assertEquals(2.5f, hist.min, 0f)
        assertEquals(2.5f, hist.max, 0f)
        assertEquals(2.5f, hist.binStart(0), 0f)
        assertEquals(2.5f, hist.binEnd(0), 0f)
    }

    @Test
    fun `strain values are counted in millistrain`() {
        val data = field(3) { data, offset ->
            data[offset + DicResult.IDX_EXX] = 0.002f
            data[offset + DicResult.IDX_ZNSSD] = 0.01f
        }
        val hist = FieldHistogram.from(data, DicResult.IDX_EXX)!!
        assertEquals(2.0f, hist.min, 1e-5f)
        assertEquals(2.0f, hist.max, 1e-5f)
        assertEquals(3, hist.n)
    }

    @Test
    fun `counts sum to the accepted population including colour-bar outliers`() {
        val data = field(11) { data, offset ->
            val i = offset / DicResult.STRIDE
            data[offset + DicResult.IDX_U] = if (i == 10) 400f else i.toFloat()
            data[offset + DicResult.IDX_ZNSSD] = 0.02f
        }
        val hist = FieldHistogram.from(data, DicResult.IDX_U)!!
        assertEquals(11, hist.n)
        assertEquals(hist.n, hist.counts.sum())
        assertEquals(400f, hist.max, 0f)
        assertEquals(0f, hist.min, 0f)
        assertTrue(hist.counts.last() >= 1)
        assertEquals(hist.max, hist.binEnd(hist.binCount - 1), 0f)
    }

    @Test
    fun `rejected points are not counted`() {
        val data = field(5) { data, offset ->
            val i = offset / DicResult.STRIDE
            data[offset + DicResult.IDX_U] = i.toFloat()
            data[offset + DicResult.IDX_ZNSSD] = if (i == 4) 0.2f else 0.01f
        }
        val hist = FieldHistogram.from(data, DicResult.IDX_U)!!
        assertEquals(4, hist.n)
        assertEquals(4, hist.counts.sum())
        assertEquals(3f, hist.max, 0f)
    }

    @Test
    fun `the exact maximum lands in the last bin`() {
        val data = field(9) { data, offset ->
            val i = offset / DicResult.STRIDE
            data[offset + DicResult.IDX_U] = i.toFloat()
            data[offset + DicResult.IDX_ZNSSD] = 0.01f
        }
        val hist = FieldHistogram.from(data, DicResult.IDX_U)!!
        val last = hist.binCount - 1
        val maxIdx = ((hist.max - hist.min) / (hist.max - hist.min) * hist.binCount)
            .toInt()
            .coerceIn(0, last)
        assertEquals(last, maxIdx)
        assertTrue(hist.counts[last] >= 1)
    }

    @Test
    fun `Scott's rule clamps to the readable bin range`() {
        val tight = FieldHistogram.scottBinCount(
            n = 100,
            minV = 0f,
            maxV = 1f,
            sum = 0.5,
            sumSq = 0.0026,
        )
        assertEquals(FieldHistogram.MAX_BINS, tight)

        val wide = FieldHistogram.scottBinCount(
            n = 5,
            minV = 0f,
            maxV = 4f,
            sum = 10.0,
            sumSq = 30.0,
        )
        assertEquals(FieldHistogram.MIN_BINS, wide)
    }

    @Test
    fun `one bin when there is no scatter`() {
        assertEquals(1, FieldHistogram.scottBinCount(1, 0f, 1f, 0.0, 0.0))
        assertEquals(1, FieldHistogram.scottBinCount(8, 3f, 3f, 24.0, 72.0))
    }
}
