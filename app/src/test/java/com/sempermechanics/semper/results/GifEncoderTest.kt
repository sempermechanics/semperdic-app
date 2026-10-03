package com.sempermechanics.semper.results

import com.sempermechanics.semper.report.GifEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * The encoder is hand-written against the GIF89a spec, so these tests read the
 * bytes back with the JDK's own GIF reader rather than trusting our own decoder.
 * A round-trip that a third-party decoder agrees with is the only claim worth
 * making about a compressor.
 */
class GifEncoderTest {

    private val palette = IntArray(256) { it * 0x010101 } // grey ramp, index == grey level

    private fun encode(
        width: Int,
        height: Int,
        frames: List<ByteArray>,
        delayCentis: Int = 30,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        GifEncoder(out, width, height, palette).use { gif ->
            frames.forEach { gif.addFrame(it, delayCentis) }
        }
        return out.toByteArray()
    }

    private fun readFrames(bytes: ByteArray): List<BufferedImage> {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        return (0 until reader.getNumImages(true)).map { reader.read(it) }
    }

    /** Frame delay lives in the GIF's own metadata tree, in hundredths of a second. */
    private fun readDelays(bytes: ByteArray): List<Int> {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        return (0 until reader.getNumImages(true)).map { index ->
            val root = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
            val gce = (root as IIOMetadataNode)
                .getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
            gce.getAttribute("delayTime").toInt()
        }
    }

    @Test
    fun `starts with the GIF89a signature and ends with the trailer`() {
        val bytes = encode(2, 2, listOf(ByteArray(4)))

        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B.toByte(), bytes.last())
    }

    @Test
    fun `every frame survives the round trip pixel for pixel`() {
        val w = 7
        val h = 5
        // Two frames that share no runs, so the LZW table is genuinely exercised.
        val first = ByteArray(w * h) { (it * 3 % 256).toByte() }
        val second = ByteArray(w * h) { (255 - it * 5 % 256).toByte() }

        val frames = readFrames(encode(w, h, listOf(first, second)))

        assertEquals(2, frames.size)
        frames.forEachIndexed { f, image ->
            val expected = if (f == 0) first else second
            assertEquals(w, image.width)
            assertEquals(h, image.height)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val index = expected[y * w + x].toInt() and 0xFF
                    assertEquals(
                        "frame $f pixel ($x,$y)",
                        palette[index] and 0xFFFFFF,
                        image.getRGB(x, y) and 0xFFFFFF,
                    )
                }
            }
        }
    }

    @Test
    fun `a long run compresses far below one byte per pixel`() {
        val w = 200
        val h = 200
        val flat = ByteArray(w * h) { 42 }

        val bytes = encode(w, h, listOf(flat))

        // 40k identical pixels: LZW should crush this. The palette alone is 768
        // bytes, so compare against the pixel count rather than the whole file.
        assertTrue("unexpectedly large: ${bytes.size} bytes", bytes.size < w * h / 10)
        assertEquals(42, readFrames(bytes).single().getRGB(0, 0) and 0xFF)
    }

    @Test
    fun `pixel data spanning many codes still decodes`() {
        // Enough distinct sequences to push the code width past 9 and 10 bits.
        val w = 128
        val h = 128
        val noisy = ByteArray(w * h) { ((it * 31 + it / 7) % 256).toByte() }

        val decoded = readFrames(encode(w, h, listOf(noisy))).single()

        for (i in noisy.indices) {
            assertEquals(
                "pixel $i",
                noisy[i].toInt() and 0xFF,
                decoded.getRGB(i % w, i / w) and 0xFF,
            )
        }
    }

    @Test
    fun `data large enough to fill the code table survives the reset`() {
        // 90k high-entropy pixels overruns the 4096-code table, so this is the
        // only test that exercises the mid-stream clear and width reset.
        val w = 300
        val h = 300
        var seed = 12345
        val random = ByteArray(w * h) {
            seed = seed * 1103515245 + 12345
            ((seed ushr 16) and 0xFF).toByte()
        }

        val decoded = readFrames(encode(w, h, listOf(random))).single()

        for (i in random.indices) {
            assertEquals(
                "pixel $i",
                random[i].toInt() and 0xFF,
                decoded.getRGB(i % w, i / w) and 0xFF,
            )
        }
    }

    @Test
    fun `encoding is deterministic byte for byte`() {
        // Guards the reused LZW table: a stale or half-cleared table would leak into
        // the code stream and diverge between two runs of the same input.
        val w = 40
        val h = 30
        val frames = listOf(
            ByteArray(w * h) { ((it * 31 + it / 5) % 256).toByte() },
            ByteArray(w * h) { ((255 - it * 17) % 256).toByte() },
            ByteArray(w * h) { 7 },
        )
        assertArrayEquals(encode(w, h, frames), encode(w, h, frames))
    }

    @Test
    fun `a frame's bytes do not depend on the frame before it`() {
        // The shared LZW table is reset per frame, so encoding [A, B] then reading B
        // back must match encoding B alone — proving no state leaks across frames.
        val w = 24
        val h = 24
        val a = ByteArray(w * h) { ((it * 13) % 256).toByte() }
        val b = ByteArray(w * h) { ((200 - it * 7) % 256).toByte() }

        val second = readFrames(encode(w, h, listOf(a, b)))[1]
        val alone = readFrames(encode(w, h, listOf(b)))[0]

        for (y in 0 until h) {
            for (x in 0 until w) {
                assertEquals("($x,$y)", alone.getRGB(x, y), second.getRGB(x, y))
            }
        }
    }

    @Test
    fun `the requested delay is what a decoder reads back`() {
        val bytes = encode(2, 2, listOf(ByteArray(4), ByteArray(4)), delayCentis = 7)

        assertEquals(listOf(7, 7), readDelays(bytes))
    }

    @Test
    fun `frames must match the declared size`() {
        val out = ByteArrayOutputStream()
        val error = runCatching {
            GifEncoder(out, 4, 4, palette).use { it.addFrame(ByteArray(9), 30) }
        }.exceptionOrNull()

        assertTrue("expected a size complaint, got $error", error is IllegalArgumentException)
    }
}
