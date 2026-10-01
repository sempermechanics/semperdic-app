@file:Suppress("MagicNumber")

package com.indicvision.semper.results

import com.indicvision.semper.field.DatDecoder
import com.indicvision.semper.field.DicResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DicResultDecodeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun bytesForPoints(n: Int, fill: (FloatArray, Int) -> Unit = { _, _ -> }): ByteArray {
        val floats = FloatArray(n * DicResult.STRIDE)
        for (i in 0 until n) {
            fill(floats, i * DicResult.STRIDE)
        }
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(floats)
        return buf.array()
    }

    @Test
    fun `valid bytes decode successfully with length bytes size over 4`() {
        val bytes = bytesForPoints(3) { data, offset ->
            data[offset + DicResult.IDX_X] = 1f
            data[offset + DicResult.IDX_ZNSSD] = 0.01f
        }
        assertEquals(0, bytes.size % 32)
        val decoded = DicResult.decodeDatBytes(bytes)
        assertNotNull(decoded)
        assertEquals(bytes.size / 4, decoded!!.size)
    }

    @Test
    fun `invalid bytes not multiple of 32 return null`() {
        assertNull(DicResult.decodeDatBytes(ByteArray(31)))
        assertNull(DicResult.decodeDatBytes(ByteArray(33)))
    }

    @Test
    fun `empty bytes return null`() {
        assertNull(DicResult.decodeDatBytes(ByteArray(0)))
    }

    @Test
    fun `decodeDatFile matches decodeDatBytes`() {
        val bytes = bytesForPoints(4) { data, offset ->
            data[offset + DicResult.IDX_X] = offset.toFloat()
            data[offset + DicResult.IDX_ZNSSD] = 0.02f
        }
        val file = temp.newFile("frame.dat").apply { writeBytes(bytes) }
        val fromFile = DicResult.decodeDatFile(file)
        val fromBytes = DicResult.decodeDatBytes(bytes)
        assertNotNull(fromFile)
        assertArrayEquals(fromBytes, fromFile, 0f)
    }

    @Test
    fun `decodeDatFile matches decodeDatBytes for a large multi-chunk file`() {
        // 5000 points = 160 KB, well over DECODE_CHUNK_BYTES (32 KB), so this exercises
        // the memory-mapped fast path on a file that the old chunked loop would have
        // read in several passes. Bytes must decode identically.
        val bytes = bytesForPoints(5000) { data, offset ->
            data[offset + DicResult.IDX_X] = offset.toFloat()
            data[offset + DicResult.IDX_U] = (offset % 97) * 0.013f
            data[offset + DicResult.IDX_EXX] = (offset % 13 - 6) * 0.0004f
            data[offset + DicResult.IDX_ZNSSD] = 0.02f
        }
        val file = temp.newFile("big.dat").apply { writeBytes(bytes) }
        val fromFile = DicResult.decodeDatFile(file)
        val fromBytes = DicResult.decodeDatBytes(bytes)
        assertNotNull(fromFile)
        assertArrayEquals(fromBytes, fromFile, 0f)
    }

    @Test
    fun `decodeInto fills a big enough array and reports the frame's float count`() {
        val big = temp.newFile("big.dat").apply {
            writeBytes(bytesForPoints(6) { data, offset -> data[offset + DicResult.IDX_X] = offset + 1f })
        }
        val small = temp.newFile("small.dat").apply {
            writeBytes(bytesForPoints(4) { data, offset -> data[offset + DicResult.IDX_U] = -offset - 1f })
        }
        val first = requireNotNull(DatDecoder.decodeInto(big, reuse = null))
        assertEquals(6 * DicResult.STRIDE, first.floatCount)
        assertEquals(first.floatCount, first.data.size)

        val second = requireNotNull(DatDecoder.decodeInto(small, reuse = first.data))

        assertSame(first.data, second.data)
        assertEquals(4 * DicResult.STRIDE, second.floatCount)
        assertArrayEquals(DicResult.decodeDatFile(small), second.data.copyOf(second.floatCount), 0f)
    }

    @Test
    fun `decodeInto allocates when the array is too small`() {
        val file = temp.newFile("frame.dat").apply {
            writeBytes(bytesForPoints(5) { data, offset -> data[offset + DicResult.IDX_V] = offset * 0.5f })
        }
        val tooSmall = FloatArray(DicResult.STRIDE)

        val decoded = requireNotNull(DatDecoder.decodeInto(file, reuse = tooSmall))

        assertNotSame(tooSmall, decoded.data)
        assertArrayEquals(DicResult.decodeDatFile(file), decoded.data, 0f)
    }

    @Test
    fun `decodeDatFile rejects bad length`() {
        val file = temp.newFile("bad.dat").apply { writeBytes(ByteArray(31)) }
        assertNull(DicResult.decodeDatFile(file))
    }

    @Test
    fun `fieldStats on a synthetic field returns correct max min mean`() {
        val n = 5
        val data = FloatArray(n * DicResult.STRIDE)
        for (i in 0 until n) {
            val offset = i * DicResult.STRIDE
            data[offset + DicResult.IDX_U] = i * 0.1f
            data[offset + DicResult.IDX_ZNSSD] = 0.01f
        }
        val stats = DicResult.fieldStats(data, DicResult.IDX_U)
        assertNotNull(stats)
        assertEquals(0.4f, stats!![0], 1e-5f)
        assertEquals(0.0f, stats[1], 1e-5f)
        assertEquals(0.2f, stats[2], 1e-5f)
    }
}
