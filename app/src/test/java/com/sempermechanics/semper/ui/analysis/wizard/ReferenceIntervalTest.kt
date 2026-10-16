package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.ui.analysis.rerun.RerunInputs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reference setting's values: a video's choices are seconds of the clip,
 * none short enough to move the reference over tiny motion, converted to
 * frames at its frame spacing; picked images keep frame counts. A saved
 * interval lands on a choice, and a Re-run's settings written before the
 * setting existed read as the fixed reference they ran with.
 */
class ReferenceIntervalTest {

    @Test
    fun `a video's choices start with the fixed reference, then every 10 s or more`() {
        assertEquals(listOf(0, 10, 30, 60), ReferenceIntervals.VIDEO_SECONDS)
    }

    @Test
    fun `picked images' choices start with the fixed reference, then every frame`() {
        assertEquals(listOf(0, 1, 2, 5, 10), ReferenceIntervals.IMAGE_FRAMES)
    }

    @Test
    fun `the frame spacing is the clip span over the gaps, and none without times`() {
        assertEquals(100.0, ReferenceIntervals.frameSpacingMs(listOf(100L, 200L, 300L, 400L))!!, 1e-9)
        // Reordered frames still span the same clip.
        assertEquals(100.0, ReferenceIntervals.frameSpacingMs(listOf(400L, 100L, 300L, 200L))!!, 1e-9)
        assertNull(ReferenceIntervals.frameSpacingMs(null))
        assertNull(ReferenceIntervals.frameSpacingMs(listOf(500L)))
        assertNull(ReferenceIntervals.frameSpacingMs(listOf(500L, 500L)))
    }

    @Test
    fun `seconds of video become frames at the sampled rate`() {
        assertEquals(0, ReferenceIntervals.framesFor(0, 100.0))
        // 10 s at 10 fps, 2 fps and 30 fps.
        assertEquals(100, ReferenceIntervals.framesFor(10, 100.0))
        assertEquals(20, ReferenceIntervals.framesFor(10, 500.0))
        assertEquals(300, ReferenceIntervals.framesFor(10, 1000.0 / 30))
        // Frames sampled further apart than the interval: every frame.
        assertEquals(1, ReferenceIntervals.framesFor(10, 20_000.0))
    }

    @Test
    fun `a video's choice comes back from its own frame count`() {
        for (spacing in listOf(100.0, 100.3, 1000.0 / 30, 733.0)) {
            for (seconds in ReferenceIntervals.VIDEO_SECONDS) {
                val frames = ReferenceIntervals.framesFor(seconds, spacing)
                assertEquals("$seconds s at $spacing ms", seconds, ReferenceIntervals.snapSeconds(frames, spacing))
            }
        }
    }

    @Test
    fun `a saved frame interval on a video lands on a choice, never below 10 s`() {
        assertEquals(0, ReferenceIntervals.snapSeconds(0, 100.0))
        assertEquals(0, ReferenceIntervals.snapSeconds(-3, 100.0))
        // Every 2 frames at 10 fps, saved before the floor: the shortest choice.
        assertEquals(10, ReferenceIntervals.snapSeconds(2, 100.0))
        assertEquals(30, ReferenceIntervals.snapSeconds(450, 100.0))
        assertEquals(60, ReferenceIntervals.snapSeconds(10_000, 100.0))
    }

    @Test
    fun `a saved interval on images lands on the largest choice not above it`() {
        assertEquals(0, ReferenceIntervals.snapFrames(-3))
        assertEquals(0, ReferenceIntervals.snapFrames(0))
        assertEquals(1, ReferenceIntervals.snapFrames(1))
        assertEquals(2, ReferenceIntervals.snapFrames(4))
        assertEquals(5, ReferenceIntervals.snapFrames(5))
        assertEquals(10, ReferenceIntervals.snapFrames(40))
    }

    @Test
    fun `re-run settings from before the setting read as the fixed reference`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"subset":41,"step":5,"strainWindow":21,"use6x6":true}"""

        assertEquals(0, json.decodeFromString(RerunInputs.Params.serializer(), old).refUpdateInterval)
    }

    @Test
    fun `re-run settings keep an interval`() {
        val params = RerunInputs.Params(41, 5, 21, use6x6 = false, refUpdateInterval = 2)
        val text = Json.encodeToString(RerunInputs.Params.serializer(), params)

        assertEquals(params, Json.decodeFromString(RerunInputs.Params.serializer(), text))
    }
}
