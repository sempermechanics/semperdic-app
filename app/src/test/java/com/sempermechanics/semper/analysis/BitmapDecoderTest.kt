package com.sempermechanics.semper.analysis

import com.sempermechanics.semper.imaging.BitmapDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class BitmapDecoderTest {

    @Test
    fun `returns 1 for image smaller than request`() {
        assertEquals(1, BitmapDecoder.calculateInSampleSize(100, 80, 500, 500))
    }

    @Test
    fun `returns power-of-two sample for large image`() {
        val sample = BitmapDecoder.calculateInSampleSize(4000, 3000, 500, 500)
        assertTrue("expected sample ≥ 4, got $sample", sample >= 4)
        assertEquals(0, sample and (sample - 1))
    }

    @Test
    fun `returns 1 for zero or negative dimensions`() {
        assertEquals(1, BitmapDecoder.calculateInSampleSize(0, 100, 500, 500))
        assertEquals(1, BitmapDecoder.calculateInSampleSize(100, -1, 500, 500))
        assertEquals(1, BitmapDecoder.calculateInSampleSize(-10, -10, 500, 500))
    }

    @Test
    fun `PREVIEW_MAX_EDGE equals 1000`() {
        assertEquals(1000, BitmapDecoder.PREVIEW_MAX_EDGE)
    }

    @Test
    fun `platform raster sniff accepts PNG JPEG WEBP and rejects TIFF RAW`() {
        val png = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val webp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
        )
        assertTrue(BitmapDecoder.looksLikePlatformRaster(png))
        assertTrue(BitmapDecoder.looksLikePlatformRaster(jpeg))
        assertTrue(BitmapDecoder.looksLikePlatformRaster(webp))
        // TIFF little-endian / big-endian
        val tiffLe = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0)
        val tiffBe = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42)
        assertFalse(BitmapDecoder.looksLikePlatformRaster(tiffLe))
        assertFalse(BitmapDecoder.looksLikePlatformRaster(tiffBe))
        assertFalse(BitmapDecoder.looksLikePlatformRaster(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun `platform raster sniff on file matches header`() {
        val dir = createTempDirectory(prefix = "raster-sniff-").toFile()
        try {
            val png = File(dir, "a.png")
            png.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            assertTrue(BitmapDecoder.looksLikePlatformRaster(png))
            val tiff = File(dir, "reference.png")
            tiff.writeBytes(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 0, 0, 0, 0))
            assertFalse(BitmapDecoder.looksLikePlatformRaster(tiff))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `decodeFileForView falls back only when RGBA dims match the blob`() {
        val dir = createTempDirectory(prefix = "rgba-view-").toFile()
        try {
            val w = 8
            val h = 4
            val blob = ByteArray(w * h * 4) { i -> (i % 256).toByte() }
            val file = File(dir, "reference.png")
            file.writeBytes(blob)
            assertFalse(BitmapDecoder.looksLikePlatformRaster(file))
            // Without dims the blob is not a platform raster — refuse.
            assertTrue(
                BitmapDecoder.decodeFileForView(file.absolutePath, 8, 4, 8) == null,
            )
            // Wrong dims must not invent a decode.
            assertTrue(
                BitmapDecoder.decodeFileForView(
                    file.absolutePath,
                    8,
                    4,
                    8,
                    rawWidth = 16,
                    rawHeight = 4,
                ) == null,
            )
            // Correct dims: RawRgba.matches is the gate; bitmap alloc is Android-only.
            assertTrue(com.sempermechanics.semper.imaging.RawRgba.matches(file.length(), w, h))
        } finally {
            dir.deleteRecursively()
        }
    }
}
