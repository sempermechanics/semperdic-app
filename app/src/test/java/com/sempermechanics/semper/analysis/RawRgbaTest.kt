package com.sempermechanics.semper.analysis

import com.sempermechanics.semper.imaging.RawRgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A RAW/DNG reference is stored as a headerless RGBA blob, so its only identity
 * is its length. [RawRgba] is what every consumer uses to recognise one, and a
 * wrong answer here is either a reference that cannot be opened at all or an
 * out-of-bounds read over someone else's bytes.
 */
class RawRgbaTest {

    private fun blob(w: Int, h: Int): ByteArray = ByteArray(w * h * RawRgba.BYTES_PER_PIXEL) { i ->
        // Distinct per channel and per pixel, so sampling picks up misalignment.
        (i % BYTE_RANGE).toByte()
    }

    @Test
    fun `exact size matches`() {
        assertTrue(RawRgba.matches(blob(8, 5).size.toLong(), 8, 5))
    }

    @Test
    fun `oversized blob does not match`() {
        // A JPEG that happens to be longer must not be read as raw pixels.
        assertFalse(RawRgba.matches((8L * 5 * RawRgba.BYTES_PER_PIXEL) + 1, 8, 5))
    }

    @Test
    fun `short blob does not match`() {
        assertFalse(RawRgba.matches((8L * 5 * RawRgba.BYTES_PER_PIXEL) - 1, 8, 5))
    }

    @Test
    fun `non-positive dimensions never match`() {
        assertFalse(RawRgba.matches(0L, 0, 0))
        assertFalse(RawRgba.matches(0L, 8, 0))
        assertFalse(RawRgba.matches(-4L, -1, 1))
    }

    @Test
    fun `short blob returns null rather than reading past the end`() {
        val short = ByteArray(8 * 5 * RawRgba.BYTES_PER_PIXEL - 1)
        assertNull(RawRgba.sampleArgb(short, 8, 5, 1))
    }

    @Test
    fun `step keeps the long edge within the budget`() {
        // Ceiling division: 4032 / 1080 is 3.73, so the step must be 4, not 3.
        val step = RawRgba.sampleStep(3024, 4032, 1080)
        assertEquals(4, step)
        assertTrue(RawRgba.sampledExtent(4032, step) <= 1080)
    }

    @Test
    fun `small blobs are not sampled at all`() {
        assertEquals(1, RawRgba.sampleStep(400, 300, 1080))
    }

    @Test
    fun `sampling reads the R G B channels in order and forces opaque alpha`() {
        val w = 2
        val h = 1
        // Two pixels, R,G,B,A. Alpha is deliberately 0 in the blob: the preview
        // must force it opaque or the whole image draws as nothing.
        val bytes = byteArrayOf(10, 20, 30, 0, 40, 50, 60, 0)
        val argb = RawRgba.sampleArgb(bytes, w, h, 1)!!
        assertEquals(2, argb.size)
        assertEquals(0xFF0A141E.toInt(), argb[0])
        assertEquals(0xFF28323C.toInt(), argb[1])
    }

    @Test
    fun `sampling picks the expected source pixels`() {
        val w = 4
        val h = 4
        val bytes = ByteArray(w * h * RawRgba.BYTES_PER_PIXEL)
        for (i in 0 until w * h) {
            bytes[i * RawRgba.BYTES_PER_PIXEL] = i.toByte() // R carries the pixel index
        }
        val argb = RawRgba.sampleArgb(bytes, w, h, 2)!!
        assertEquals(4, argb.size)
        // Step 2 over a 4x4 grid takes indices 0, 2, 8, 10.
        val reds = argb.map { (it shr RED_SHIFT) and BYTE_MASK }
        assertEquals(listOf(0, 2, 8, 10), reds)
    }

    @Test
    fun `sampled extent rounds up so no edge pixels are dropped`() {
        // 5 columns at step 2 needs 3 output columns, not 2.
        assertEquals(3, RawRgba.sampledExtent(5, 2))
        val argb = RawRgba.sampleArgb(blob(5, 3), 5, 3, 2)!!
        assertEquals(3 * 2, argb.size)
    }

    @Test
    fun `a degenerate step is refused rather than dividing by zero`() {
        assertNull(RawRgba.sampleArgb(blob(4, 4), 4, 4, 0))
    }

    private companion object {
        const val BYTE_RANGE = 251
        const val RED_SHIFT = 16
        const val BYTE_MASK = 0xFF
    }
}
