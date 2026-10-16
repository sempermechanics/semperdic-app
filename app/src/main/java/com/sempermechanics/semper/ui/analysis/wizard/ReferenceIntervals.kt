// The choices are the numbers themselves, measured, not tuning to name.
@file:Suppress("MagicNumber")

package com.sempermechanics.semper.ui.analysis.wizard

import kotlin.math.roundToInt

/**
 * The reference dropdown's choices, and how a choice becomes the frame count
 * the engine takes ([com.sempermechanics.semper.ui.analysis.run.RunSpec.refUpdateInterval]).
 *
 * What decides an incremental run's accuracy is how far the specimen moves
 * between reference updates: each update adds a little error, and over tiny
 * motion that error is all there is. On a slow 528 s tensile clip analysed at
 * 10 fps, moving the reference every 1 s or 3 s drifted 3.6 px and 1.4 px from
 * the fixed reference; every 10 s or 30 s stayed within 0.02 px and kept every
 * point to the end, where the fixed reference lost whole frames. A frame count
 * alone cannot say which of those a user picked: it depends on the frame rate
 * they sampled at. So a video's choices are seconds of the clip, converted at
 * its frame spacing, and none is short enough to update over tiny motion.
 * Faster loading is covered by the engine's early update, which moves the
 * reference as soon as the points reported drop.
 *
 * Picked images carry no times; theirs stay frame counts, as each photo of a
 * sequence is usually its own load step.
 */
object ReferenceIntervals {
    /** A video's choices: fixed (0), then moved every this many seconds of the clip. */
    val VIDEO_SECONDS = listOf(0, 10, 30, 60)

    /** Picked images' choices: fixed (0), then moved every this many frames. */
    val IMAGE_FRAMES = listOf(0, 1, 2, 5, 10)

    private const val MS_PER_SECOND = 1000.0

    /**
     * The mean time between [timesMs]' frames in ms, the clip span over the
     * gaps; null for picked images (no times), a single frame or a zero span.
     */
    fun frameSpacingMs(timesMs: List<Long>?): Double? {
        if (timesMs == null || timesMs.size < 2) return null
        val span = timesMs.max() - timesMs.min()
        return if (span > 0) span.toDouble() / (timesMs.size - 1) else null
    }

    /** [seconds] of video as frames [spacingMs] apart; 0 stays fixed, anything else is at least 1. */
    fun framesFor(seconds: Int, spacingMs: Double): Int =
        if (seconds <= 0) 0 else (seconds * MS_PER_SECOND / spacingMs).roundToInt().coerceAtLeast(1)

    /**
     * A saved frame [interval] as one of [VIDEO_SECONDS] at [spacingMs]: the
     * largest not above the time it spans, but never fixed for one that moved,
     * so an interval saved before the floor lands on the shortest choice.
     */
    fun snapSeconds(interval: Int, spacingMs: Double): Int {
        if (interval <= 0) return 0
        // Rounded, so a choice's own frame count (rounded from it) comes back to it.
        val seconds = (interval * spacingMs / MS_PER_SECOND).roundToInt()
        return VIDEO_SECONDS.lastOrNull { it in 1..seconds } ?: VIDEO_SECONDS[1]
    }

    /** A saved frame [interval] as one of [IMAGE_FRAMES]: the largest not above it, fixed for 0 or less. */
    fun snapFrames(interval: Int): Int = IMAGE_FRAMES.lastOrNull { it <= interval } ?: 0
}
