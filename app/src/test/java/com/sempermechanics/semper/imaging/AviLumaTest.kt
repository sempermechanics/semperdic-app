@file:Suppress("LongParameterList")

package com.sempermechanics.semper.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every uncompressed layout a lab camera writes, and what its luma must be. */
class AviLumaTest {

    private fun video(
        fourcc: String,
        width: Int,
        height: Int,
        bitCount: Int = 8,
        topDown: Boolean = true,
        codecPrivate: ByteArray? = null,
    ) = AviReader.Video(
        width = width,
        height = height,
        fourcc = fourcc,
        bitCount = bitCount,
        topDown = topDown,
        fps = 25.0,
        codecPrivate = codecPrivate,
        hasIndex = false,
        frames = listOf(AviReader.Frame(0L, 1, true)),
    )

    /** The pixels a [GrayPngEncoder.Luma] stands for, row by row. */
    private fun pixels(luma: GrayPngEncoder.Luma): List<Int> =
        (0 until luma.height).flatMap { y ->
            (0 until luma.width).map { x ->
                luma.bytes[y * luma.rowStride + x * luma.pixelStride].toInt() and 0xFF
            }
        }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `8-bit gray is the luma already`() {
        val payload = bytes(10, 20, 30, 40, 50, 60)
        val luma = checkNotNull(AviLuma.toLuma(payload, video("Y800", 3, 2)))

        assertEquals(listOf(10, 20, 30, 40, 50, 60), pixels(luma))
    }

    @Test
    fun `a planar YUV frame is read from its Y plane alone`() {
        // 2x2 Y, then the quarter-size U and V planes that follow it.
        val payload = bytes(1, 2, 3, 4, 128, 128)
        val luma = checkNotNull(AviLuma.toLuma(payload, video("I420", 2, 2)))

        assertEquals(listOf(1, 2, 3, 4), pixels(luma))
    }

    @Test
    fun `packed 4-2-2 keeps every other byte`() {
        // Y0 U Y1 V, twice: the chroma bytes must not reach the luma.
        val payload = bytes(11, 128, 22, 128, 33, 128, 44, 128)
        val luma = checkNotNull(AviLuma.toLuma(payload, video("YUY2", 2, 2)))

        assertEquals(listOf(11, 22, 33, 44), pixels(luma))
    }

    @Test
    fun `UYVY starts one byte later`() {
        val payload = bytes(128, 11, 128, 22, 128, 33, 128, 44)
        val luma = checkNotNull(AviLuma.toLuma(payload, video("UYVY", 2, 2)))

        assertEquals(listOf(11, 22, 33, 44), pixels(luma))
    }

    @Test
    fun `16-bit gray keeps the high byte`() {
        val payload = bytes(0x00, 0x7F, 0xFF, 0x80, 0x11, 0x01, 0x22, 0x02)
        val luma = checkNotNull(AviLuma.toLuma(payload, video("Y16 ", 2, 2)))

        assertEquals(listOf(0x7F, 0x80, 0x01, 0x02), pixels(luma))
    }

    @Test
    fun `a bottom-up DIB comes back the right way up`() {
        // Rows are DWORD-aligned: 3 pixels pad to 4 bytes.
        val payload = bytes(1, 2, 3, 0, 4, 5, 6, 0)
        val luma = checkNotNull(AviLuma.toLuma(payload, video("DIB ", 3, 2, bitCount = 8, topDown = false)))

        assertEquals(listOf(4, 5, 6, 1, 2, 3), pixels(luma))
    }

    @Test
    fun `a top-down DIB is passed through untouched`() {
        val payload = bytes(1, 2, 3, 0, 4, 5, 6, 0)
        val luma = checkNotNull(AviLuma.toLuma(payload, video("DIB ", 3, 2, bitCount = 8, topDown = true)))

        assertEquals(listOf(1, 2, 3, 4, 5, 6), pixels(luma))
        assertTrue("passthrough should not copy", luma.bytes === payload)
    }

    @Test
    fun `a gray pixel stored as BGR survives the conversion exactly`() {
        // 2x1 of 24-bit BGR: a neutral 200 and a neutral 37.
        val payload = bytes(200, 200, 200, 37, 37, 37, 0, 0)
        val luma = checkNotNull(AviLuma.toLuma(payload, video("DIB ", 2, 1, bitCount = 24, topDown = true)))

        assertEquals(listOf(200, 37), pixels(luma))
    }

    @Test
    fun `a colour BGR pixel weighs the channels`() {
        val payload = bytes(0, 0, 255, 255, 0, 0, 0, 0)
        val luma = checkNotNull(AviLuma.toLuma(payload, video("DIB ", 2, 1, bitCount = 24, topDown = true)))

        // Full-range Rec.601: red weighs 77/256, blue 29/256.
        assertEquals(listOf(77, 29), pixels(luma))
    }

    @Test
    fun `32-bit rows carry an ignored alpha byte`() {
        val payload = bytes(200, 200, 200, 255, 37, 37, 37, 255)
        val luma = checkNotNull(AviLuma.toLuma(payload, video("DIB ", 2, 1, bitCount = 32, topDown = true)))

        assertEquals(listOf(200, 37), pixels(luma))
    }

    @Test
    fun `an indexed frame is read through its own palette`() {
        // Two BGRA palette entries: index 0 is white, index 1 is black.
        val palette = bytes(255, 255, 255, 0, 0, 0, 0, 0)
        val payload = bytes(0, 1, 1, 0)
        val luma = checkNotNull(
            AviLuma.toLuma(payload, video("DIB ", 4, 1, bitCount = 8, topDown = true, codecPrivate = palette)),
        )

        assertEquals(listOf(255, 0, 0, 255), pixels(luma))
    }

    @Test
    fun `a truncated payload is refused rather than half read`() {
        assertNull(AviLuma.toLuma(bytes(1, 2, 3), video("Y800", 3, 2)))
        assertNull(AviLuma.toLuma(bytes(1, 2, 3, 0), video("DIB ", 3, 2, bitCount = 8)))
    }

    @Test
    fun `a compressed stream is not this class's business`() {
        assertFalse(AviLuma.isSupported(video("XVID", 8, 8, bitCount = 24)))
        assertFalse(AviLuma.isSupported(video("MJPG", 8, 8, bitCount = 24)))
        assertFalse(AviLuma.isSupported(video("DIB ", 8, 8, bitCount = 16)))
        assertNull(AviLuma.toLuma(ByteArray(64), video("XVID", 8, 8, bitCount = 24)))
    }

    @Test
    fun `every uncompressed layout reports itself supported`() {
        listOf("Y800", "GREY", "Y16 ", "I420", "NV12", "YUY2", "UYVY").forEach {
            assertTrue(it, AviLuma.isSupported(video(it, 8, 8)))
        }
        listOf(8, 24, 32).forEach {
            assertTrue("DIB $it", AviLuma.isSupported(video("DIB ", 8, 8, bitCount = it)))
        }
    }
}
