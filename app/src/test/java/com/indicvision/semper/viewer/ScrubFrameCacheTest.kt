package com.indicvision.semper.viewer

import com.indicvision.semper.ui.viewer.ScrubFrameCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The look-ahead window is what keeps scrubbing warm without letting memory grow with
 * scrub speed, so both of its ceilings — frame count *and* bytes — are pinned here.
 */
class ScrubFrameCacheTest {

    /** 1024 floats = 4 KB per frame, so byte budgets below are easy to reason about. */
    private fun frame(): FloatArray = FloatArray(1024)

    private val frameBytes = 1024L * Float.SIZE_BYTES

    @Test
    fun `evicts least recently used once the frame count is reached`() {
        val cache = ScrubFrameCache(maxFrames = 3, maxDataBytes = Long.MAX_VALUE)
        repeat(3) { cache.putData(it, frame()) }

        // Touch frame 0 so frame 1 becomes the least recently used, then overflow.
        assertNotNull(cache.getData(0))
        cache.putData(3, frame())

        assertNotNull("0 was just used", cache.getData(0))
        assertNull("1 was the LRU and should be gone", cache.getData(1))
        assertNotNull(cache.getData(3))
    }

    @Test
    fun `byte ceiling bounds the window even when the frame count allows more`() {
        // Room for 10 frames by count, but only 2 by bytes.
        val cache = ScrubFrameCache(maxFrames = 10, maxDataBytes = frameBytes * 2)

        repeat(6) { cache.putData(it, frame()) }

        val held = (0 until 6).count { cache.getData(it) != null }
        assertEquals("byte ceiling should cap the window at 2 frames", 2, held)
    }

    @Test
    fun `freeSlots reports remaining room under whichever ceiling binds first`() {
        val cache = ScrubFrameCache(maxFrames = 4, maxDataBytes = frameBytes * 2)
        assertEquals(2, cache.freeSlots(frameBytes))

        cache.putData(0, frame())
        assertEquals(1, cache.freeSlots(frameBytes))

        cache.putData(1, frame())
        assertEquals("full by bytes", 0, cache.freeSlots(frameBytes))
    }

    @Test
    fun `a frame larger than the whole budget does not wedge the cache`() {
        val cache = ScrubFrameCache(maxFrames = 4, maxDataBytes = frameBytes)
        cache.putData(0, FloatArray(1024 * 8)) // 8x the budget

        // It is evicted immediately rather than being retained forever or wedging the
        // budget: the cache ends up empty and open for a normally-sized frame again.
        assertNull(cache.getData(0))
        assertEquals(1, cache.freeSlots(frameBytes))
    }

    @Test
    fun `clear resets the byte accounting`() {
        val cache = ScrubFrameCache(maxFrames = 4, maxDataBytes = frameBytes * 2)
        cache.putData(0, frame())
        cache.clear()

        assertEquals(2, cache.freeSlots(frameBytes))
        assertNull(cache.getData(0))
    }
}
