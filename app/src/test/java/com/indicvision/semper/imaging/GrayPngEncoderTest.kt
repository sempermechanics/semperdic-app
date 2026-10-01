@file:Suppress("LongParameterList")

package com.indicvision.semper.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

class GrayPngEncoderTest {

    private fun encode(
        bytes: ByteArray,
        w: Int,
        h: Int,
        rowStride: Int = w,
        pixelStride: Int = 1,
        rotationDegrees: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        GrayPngEncoder.encode(
            out,
            GrayPngEncoder.Luma(
                bytes = bytes,
                width = w,
                height = h,
                rowStride = rowStride,
                pixelStride = pixelStride,
                rotationDegrees = rotationDegrees,
            ),
        )
        return out.toByteArray()
    }

    private fun turned(
        bytes: ByteArray,
        w: Int,
        h: Int,
        degrees: Int,
        rowStride: Int = w,
        pixelStride: Int = 1,
    ): ByteArray = encode(
        bytes = bytes,
        w = w,
        h = h,
        rowStride = rowStride,
        pixelStride = pixelStride,
        rotationDegrees = degrees,
    )

    private fun int32(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)

    private fun chunk(png: ByteArray, type: String): ByteArray {
        var off = SIGNATURE_LEN
        while (off < png.size) {
            val len = int32(png, off)
            val name = String(png, off + 4, 4, Charsets.US_ASCII)
            if (name == type) return png.copyOfRange(off + 8, off + 8 + len)
            off += CHUNK_OVERHEAD + len
        }
        error("chunk $type not found")
    }

    private fun pixels(png: ByteArray, w: Int, h: Int): ByteArray {
        val raw = ByteArray((w + 1) * h)
        Inflater().run {
            setInput(chunk(png, "IDAT"))
            inflate(raw)
            end()
        }
        val out = ByteArray(w * h)
        for (row in 0 until h) {
            assertEquals("scanline $row must use filter None", 0, raw[row * (w + 1)].toInt())
            System.arraycopy(raw, row * (w + 1) + 1, out, row * w, w)
        }
        return out
    }

    @Test
    fun `writes a PNG signature and a grayscale 8-bit IHDR`() {
        val png = encode(ByteArray(4), 2, 2)

        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            png.copyOfRange(0, SIGNATURE_LEN),
        )
        val ihdr = chunk(png, "IHDR")
        assertEquals(2, int32(ihdr, 0))
        assertEquals(2, int32(ihdr, 4))
        assertEquals("bit depth", 8, ihdr[8].toInt())
        assertEquals("colour type 0 = grayscale", 0, ihdr[9].toInt())
        assertEquals("compression", 0, ihdr[10].toInt())
        assertEquals("filter", 0, ihdr[11].toInt())
        assertEquals("interlace", 0, ihdr[12].toInt())
    }

    @Test
    fun `round-trips luma samples unchanged`() {
        val y = byteArrayOf(0, 64, 128.toByte(), 255.toByte(), 1, 2)
        assertArrayEquals(y, pixels(encode(y, 3, 2), 3, 2))
    }

    @Test
    fun `drops row padding when the plane stride exceeds the width`() {
        val y = byteArrayOf(
            10,
            20,
            99.toByte(),
            99.toByte(),
            30,
            40,
            99.toByte(),
            99.toByte(),
        )
        val out = pixels(encode(y, 2, 2, rowStride = 4), 2, 2)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), out)
    }

    @Test
    fun `honours a pixel stride greater than one`() {
        val y = byteArrayOf(10, 0, 20, 0, 30, 0, 40, 0)
        val out = pixels(encode(y, 2, 2, rowStride = 4, pixelStride = 2), 2, 2)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), out)
    }

    @Test
    fun `survives a plane that ends short of stride times height`() {
        val y = byteArrayOf(10, 20, 99.toByte(), 99.toByte(), 30, 40)
        val out = pixels(encode(y, 2, 2, rowStride = 4), 2, 2)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), out)
    }

    @Test
    fun `pads a truncated row with black rather than throwing`() {
        val y = byteArrayOf(10, 20, 30)
        val out = pixels(encode(y, 2, 2), 2, 2)
        assertArrayEquals(byteArrayOf(10, 20, 30, 0), out)
    }

    @Test
    fun `every chunk carries a valid CRC`() {
        val png = encode(ByteArray(64) { it.toByte() }, 8, 8)
        var off = SIGNATURE_LEN
        var seen = 0
        while (off < png.size) {
            val len = int32(png, off)
            val crc = CRC32().apply { update(png, off + 4, 4 + len) }
            assertEquals(
                "CRC for chunk at $off",
                crc.value.toInt(),
                int32(png, off + 8 + len),
            )
            off += CHUNK_OVERHEAD + len
            seen++
        }
        assertEquals("IHDR, IDAT, IEND", 3, seen)
        assertEquals("consumed exactly", png.size, off)
    }

    @Test
    fun `ends with an empty IEND chunk`() {
        val png = encode(ByteArray(4), 2, 2)
        assertEquals(0, chunk(png, "IEND").size)
        assertTrue(png.size > SIGNATURE_LEN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive size`() {
        encode(ByteArray(0), 0, 4)
    }

    private val threeByTwo = byteArrayOf(1, 2, 3, 4, 5, 6)

    @Test
    fun `a quarter turn clockwise moves the top-left sample to the top-right`() {
        val png = turned(threeByTwo, w = 3, h = 2, degrees = 90)
        val ihdr = chunk(png, "IHDR")
        assertEquals("width and height swap", 2, int32(ihdr, 0))
        assertEquals(3, int32(ihdr, 4))
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), pixels(png, 2, 3))
    }

    @Test
    fun `a half turn reverses both axes and keeps the shape`() {
        val png = turned(threeByTwo, w = 3, h = 2, degrees = 180)
        val ihdr = chunk(png, "IHDR")
        assertEquals(3, int32(ihdr, 0))
        assertEquals(2, int32(ihdr, 4))
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), pixels(png, 3, 2))
    }

    @Test
    fun `three quarter turns are the inverse of one`() {
        val png = turned(threeByTwo, w = 3, h = 2, degrees = 270)
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), pixels(png, 2, 3))
    }

    @Test
    fun `rotation reads past row padding rather than through it`() {
        val padded = byteArrayOf(1, 2, 3, 0, 0, 4, 5, 6, 0, 0)
        val png = turned(padded, w = 3, h = 2, degrees = 90, rowStride = 5)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), pixels(png, 2, 3))
    }

    @Test
    fun `rotation honours an interleaved pixel stride`() {
        val interleaved = byteArrayOf(1, 9, 2, 9, 3, 9, 4, 9, 5, 9, 6, 9)
        val png = encode(
            interleaved,
            3,
            2,
            rowStride = 6,
            pixelStride = 2,
            rotationDegrees = 90,
        )
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), pixels(png, 2, 3))
    }

    @Test
    fun `four quarter turns return the original`() {
        var current = threeByTwo
        var w = 3
        var h = 2
        repeat(4) {
            val png = turned(current, w, h, degrees = 90)
            val next = if (w == 3) 2 to 3 else 3 to 2
            current = pixels(png, next.first, next.second)
            w = next.first
            h = next.second
        }
        assertArrayEquals(threeByTwo, current)
    }

    @Test
    fun `an unrotated plane still takes the packed path`() {
        val png = turned(threeByTwo, w = 3, h = 2, degrees = 0)
        assertArrayEquals(threeByTwo, pixels(png, 3, 2))
    }

    @Test
    fun `a rotation that is not a quarter turn is rounded rather than trusted`() {
        val png = turned(threeByTwo, w = 3, h = 2, degrees = 89)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), pixels(png, 2, 3))
    }

    @Test
    fun `a short plane leaves the tail black instead of throwing`() {
        val short = byteArrayOf(1, 2, 3, 4, 5)
        val png = turned(short, w = 3, h = 2, degrees = 90)
        assertEquals(6, pixels(png, 2, 3).size)
    }

    private companion object {
        const val SIGNATURE_LEN = 8
        const val CHUNK_OVERHEAD = 12
    }
}
