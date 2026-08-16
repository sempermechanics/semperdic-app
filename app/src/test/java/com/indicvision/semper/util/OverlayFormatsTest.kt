package com.indicvision.semper.util

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayFormatsTest {

    @Test
    fun `counts below one thousand stay raw`() {
        assertEquals("0", OverlayFormats.compactCount(0))
        assertEquals("999", OverlayFormats.compactCount(999))
    }

    @Test
    fun `thousands and millions compact`() {
        assertEquals("1K", OverlayFormats.compactCount(1_000))
        assertEquals("1.5K", OverlayFormats.compactCount(1_500))
        assertEquals("1M", OverlayFormats.compactCount(1_000_000))
        assertEquals("2.3M", OverlayFormats.compactCount(2_300_000))
    }

    @Test
    fun `elapsed stays mm ss until an hour`() {
        assertEquals("0:00", OverlayFormats.elapsed(0))
        assertEquals("0:05", OverlayFormats.elapsed(5_000))
        assertEquals("1:05", OverlayFormats.elapsed(65_000))
        assertEquals("59:59", OverlayFormats.elapsed(3_599_000))
    }

    @Test
    fun `elapsed uses h mm ss once hours appear`() {
        assertEquals("1:00:00", OverlayFormats.elapsed(3_600_000))
        assertEquals("1:02:03", OverlayFormats.elapsed(3_723_000))
    }
}
