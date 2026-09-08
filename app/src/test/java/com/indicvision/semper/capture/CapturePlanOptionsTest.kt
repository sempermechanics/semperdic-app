package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.CapturePlanOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePlanOptionsTest {

    private companion object {
        const val BIG_CAP = 5_000
        const val EPS = 1e-4f
    }

    @Test
    fun `every offered rate is one the device can hold for the whole run`() {
        // The whole promise: no offered rate can outrun the per-frame cost.
        // Swept across durations and costs so no device shape slips through.
        for (seconds in 1..120 step 7) {
            for (perFrame in longArrayOf(17, 80, 150, 290, 400, 900, 2500)) {
                for (option in CapturePlanOptions.of(seconds, perFrame, BIG_CAP)) {
                    assertTrue(
                        "$seconds s at ${perFrame}ms: ${option.fps} fps leaves ${option.intervalMs}ms",
                        option.frames == 1 || option.intervalMs >= perFrame,
                    )
                }
            }
        }
    }

    @Test
    fun `the frame count never exceeds the max frames setting`() {
        for (seconds in 1..120 step 11) {
            for (cap in intArrayOf(10, 50, 150, 500)) {
                for (option in CapturePlanOptions.of(seconds, 17L, cap)) {
                    assertTrue("$seconds s cap $cap gave ${option.frames}", option.frames <= cap)
                }
            }
        }
    }

    @Test
    fun `offers a rate strictly below what the hardware could just about manage`() {
        // 290ms per frame is 3.4 fps flat out; the offer must sit under that.
        val top = CapturePlanOptions.of(30, perFrameMs = 290, maxFramesSetting = BIG_CAP).first()
        assertTrue("top was ${top.fps} fps", top.fps < 1_000f / 290f)
        assertEquals(2f, top.fps, EPS)
    }

    @Test
    fun `offers a short descending ladder of standard rates`() {
        val rates = CapturePlanOptions.of(30, perFrameMs = 17, maxFramesSetting = BIG_CAP).map { it.fps }
        assertEquals(listOf(30f, 15f, 6f, 3f), rates)
        assertTrue(rates.size <= CapturePlanOptions.MAX_OPTIONS)
    }

    @Test
    fun `frames and interval follow from the rate`() {
        val option = CapturePlanOptions.of(30, perFrameMs = 290, maxFramesSetting = BIG_CAP).first()
        assertEquals(2f, option.fps, EPS)
        assertEquals(60, option.frames)
        assertEquals(500L, option.intervalMs)
    }

    @Test
    fun `the max frames setting can be the binding limit`() {
        // 17ms per frame would allow 30 fps, but 60 frames over 30s is 2 fps.
        val options = CapturePlanOptions.of(30, perFrameMs = 17, maxFramesSetting = 60)
        assertEquals(2f, options.first().fps, EPS)
        assertTrue(CapturePlanOptions.cappedByFrameSetting(17L, 30, 60))
    }

    @Test
    fun `a setting below one frame per second of the run offers nothing`() {
        // 20 frames over 30 s is 0.66 fps, under the floor. The old code
        // answered with a single one-frame "option" — a recording that cannot
        // be correlated afterwards, offered as though it were a plan.
        assertTrue(CapturePlanOptions.of(30, perFrameMs = 17, maxFramesSetting = 20).isEmpty())
        // And the caller can tell which limit to name.
        assertTrue(CapturePlanOptions.cappedByFrameSetting(17L, 30, 20))
        assertEquals(30, CapturePlanOptions.framesNeededAtFloor(30))
    }

    @Test
    fun `no offered rate is ever below the floor`() {
        val plans = (1..600 step 13).flatMap { seconds ->
            longArrayOf(17, 80, 150, 290, 400, 900, 2500, 9_000).flatMap { perFrame ->
                intArrayOf(10, 50, 150, 500).map { cap -> Triple(seconds, perFrame, cap) }
            }
        }
        for ((seconds, perFrame, cap) in plans) {
            val slowest = CapturePlanOptions.of(seconds, perFrame, cap).minByOrNull { it.fps } ?: continue
            assertTrue(
                "$seconds s at ${perFrame}ms cap $cap offered ${slowest.fps} fps",
                slowest.fps >= CapturePlanOptions.MIN_FPS,
            )
        }
    }

    @Test
    fun `the camera is the binding limit when the setting is generous`() {
        assertFalse(CapturePlanOptions.cappedByFrameSetting(290L, 30, 500))
    }

    @Test
    fun `raising the setting to 500 lifts a run the 150 default held down`() {
        // 60s at 17ms per frame: the camera would allow 30 fps either way.
        val at150 = CapturePlanOptions.of(60, perFrameMs = 17, maxFramesSetting = 150).first()
        val at500 = CapturePlanOptions.of(60, perFrameMs = 17, maxFramesSetting = 500).first()
        assertTrue("150 gave ${at150.fps}, 500 gave ${at500.fps}", at500.fps > at150.fps)
        assertTrue(at500.frames > at150.frames)
    }

    @Test
    fun `a device too slow for the floor offers nothing at all`() {
        // 9 s per still cannot hold 1 fps by any arrangement of frames. The
        // screen has to say so and disable Continue; there is no plan here to
        // dress a single frame up as.
        assertTrue(CapturePlanOptions.of(1, perFrameMs = 9_000, maxFramesSetting = BIG_CAP).isEmpty())
        assertFalse(CapturePlanOptions.cappedByFrameSetting(9_000L, 1, BIG_CAP))
    }

    @Test
    fun `a slower device is offered lower rates, not the same ones`() {
        val fast = CapturePlanOptions.of(30, perFrameMs = 30, maxFramesSetting = BIG_CAP).first().fps
        // 700 ms is about as slow as a device can be and still clear the
        // floor; past that it is offered nothing, which the empty-list tests
        // above cover.
        val slow = CapturePlanOptions.of(30, perFrameMs = 700, maxFramesSetting = BIG_CAP).first().fps
        assertTrue("fast=$fast slow=$slow", slow < fast)
    }

    @Test
    fun `a bigger frame is offered lower rates than a smaller one`() {
        // Standing in for what StreamConfigurationMap reports: a full-sensor
        // read costs more per frame than a binned one on the same camera.
        val binned = CapturePlanOptions.of(30, perFrameMs = 33, maxFramesSetting = BIG_CAP).first().fps
        val full = CapturePlanOptions.of(30, perFrameMs = 500, maxFramesSetting = BIG_CAP).first().fps
        assertTrue("binned=$binned full=$full", full < binned)
    }
}
