package com.indicvision.semper.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Limited-range video luma stretched to full range (TD-134): a frame decoded from
 * an MP4 must reach the engine at the contrast an MJPEG of the same scene has.
 */
class LumaRangeTest {

    @Test
    fun `video black and white map to 0 and 255`() {
        assertEquals(0, LumaRange.expand(LumaRange.BLACK))
        assertEquals(255, LumaRange.expand(LumaRange.WHITE))
    }

    @Test
    fun `values outside 16 to 235 clip`() {
        assertEquals(0, LumaRange.expand(0))
        assertEquals(0, LumaRange.expand(15))
        assertEquals(255, LumaRange.expand(236))
        assertEquals(255, LumaRange.expand(255))
    }

    @Test
    fun `a full-range frame stored as video comes back within one level`() {
        // What an encoder does to a full-range source: 0-255 squeezed into 16-235.
        for (full in 0..255) {
            val stored = (LumaRange.BLACK + full * (LumaRange.WHITE - LumaRange.BLACK) / 255.0).roundToInt()
            val back = LumaRange.expand(stored)
            assertTrue("$full -> $stored -> $back", abs(back - full) <= 1)
        }
    }

    @Test
    fun `the mapping never reorders levels`() {
        (1..255).forEach { y -> assertTrue("at $y", LumaRange.expand(y) >= LumaRange.expand(y - 1)) }
    }

    @Test
    fun `a plane is expanded in place, padding included`() {
        val bytes = byteArrayOf(16, 126, 235.toByte(), 0, 255.toByte())
        LumaRange.expandInPlace(bytes)
        assertArrayEquals(byteArrayOf(0, 128.toByte(), 255.toByte(), 0, 255.toByte()), bytes)
    }
}
