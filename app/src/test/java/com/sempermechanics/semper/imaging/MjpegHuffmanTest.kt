package com.sempermechanics.semper.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The tables in [MjpegHuffman] are the JPEG standard's, transcribed by hand, so
 * the only check worth having is a real decoder's: a frame stripped of its
 * tables must come back pixel-for-pixel identical once they are put back.
 */
class MjpegHuffmanTest {

    private companion object {
        const val MARKER = 0xFF
        const val SOS = 0xDA
        const val DHT = 0xC4
    }

    /** A gradient, so a wrong AC table shows up as a difference rather than flat grey. */
    private fun sample(width: Int = 48, height: Int = 32): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = x * 255 / (width - 1)
                val g = y * 255 / (height - 1)
                val b = (x + y) * 255 / (width + height - 2)
                image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }
        return image
    }

    private fun encode(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue("no JPEG writer", ImageIO.write(image, "jpg", out))
        return out.toByteArray()
    }

    private fun decode(jpeg: ByteArray): BufferedImage? =
        runCatching { ImageIO.read(ByteArrayInputStream(jpeg)) }.getOrNull()

    private fun pixels(image: BufferedImage): IntArray =
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    private fun byteAt(bytes: ByteArray, at: Int) = bytes[at].toInt() and 0xFF

    /** What pre-AVI2 capture software wrote: the same JPEG, minus its tables. */
    private fun stripTables(jpeg: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(jpeg, 0, 2)
        var at = 2
        while (at + 3 < jpeg.size) {
            check(byteAt(jpeg, at) == MARKER) { "not a marker at $at" }
            val marker = byteAt(jpeg, at + 1)
            if (marker == SOS) break
            val length = 2 + ((byteAt(jpeg, at + 2) shl 8) or byteAt(jpeg, at + 3))
            if (marker != DHT) out.write(jpeg, at, length)
            at += length
        }
        out.write(jpeg, at, jpeg.size - at)
        return out.toByteArray()
    }

    @Test
    fun `a frame without tables decodes identically once they are restored`() {
        val image = sample()
        val original = encode(image)
        val stripped = stripTables(original)
        assertTrue("stripping changed nothing", stripped.size < original.size)
        assertNull("a tableless frame should not decode", decode(stripped))

        val repaired = checkNotNull(MjpegHuffman.withStandardTables(stripped))
        val decoded = checkNotNull(decode(repaired)) { "repaired frame did not decode" }

        assertEquals(image.width, decoded.width)
        assertEquals(image.height, decoded.height)
        assertArrayEquals(pixels(checkNotNull(decode(original))), pixels(decoded))
    }

    @Test
    fun `a frame that already has its tables is left alone`() {
        assertNull(MjpegHuffman.withStandardTables(encode(sample())))
    }

    @Test
    fun `anything that is not a JPEG is refused`() {
        assertNull(MjpegHuffman.withStandardTables(ByteArray(0)))
        assertNull(MjpegHuffman.withStandardTables(ByteArray(64)))
        assertNull(MjpegHuffman.withStandardTables("RIFFxxxxAVI ".toByteArray(Charsets.US_ASCII)))
        // A JPEG header with no scan to insert before.
        assertNull(MjpegHuffman.withStandardTables(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    @Test
    fun `the tables go in ahead of the scan`() {
        val stripped = stripTables(encode(sample()))
        val repaired = checkNotNull(MjpegHuffman.withStandardTables(stripped))

        val dhtAt = (0 until repaired.size - 1).first {
            byteAt(repaired, it) == MARKER && byteAt(repaired, it + 1) == DHT
        }
        val sosAt = (0 until repaired.size - 1).first {
            byteAt(repaired, it) == MARKER && byteAt(repaired, it + 1) == SOS
        }
        assertTrue("DHT at $dhtAt must precede SOS at $sosAt", dhtAt < sosAt)
    }
}
