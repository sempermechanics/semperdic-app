package com.sempermechanics.semper.ui.analysis.recommend

import android.content.res.Resources
import com.sempermechanics.semper.R
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * How much a run solves and roughly how long it takes on this phone.
 *
 * The time comes only from runs this phone has finished: a running mean of
 * grid points solved per second ([nextRate]). With no history the estimate
 * gives the point count alone, never a made-up time.
 */
object RunEstimate {

    /** Weight of the newest run in the running mean of points per second. */
    private const val SMOOTHING = 0.3
    private const val MS_PER_SECOND = 1000.0
    private const val SECONDS_PER_MINUTE = 60.0

    /** Up to this many seconds the estimate counts seconds; above it, minutes. */
    private const val SECONDS_LABEL_MAX = 90L

    /** Points the engine solves over a [roiW] × [roiH] region at [step]: its grid is (w / step) × (h / step). */
    fun gridPoints(roiW: Int, roiH: Int, step: Int): Int =
        if (step <= 0 || roiW <= 0 || roiH <= 0) 0 else (roiW / step) * (roiH / step)

    /** Seconds to solve [points] in each of [frames] at [pointsPerSecond], rounded up; null without a rate. */
    fun seconds(points: Int, frames: Int, pointsPerSecond: Int): Long? {
        if (pointsPerSecond <= 0 || points <= 0 || frames <= 0) return null
        return ceil(points.toDouble() * frames / pointsPerSecond).toLong().coerceAtLeast(1L)
    }

    /**
     * The running mean of points per second once a run solved [points] in each
     * of [frames] in [elapsedMs]; [previous] is 0 before the first run. A run
     * that solved nothing, or took no time, leaves the mean as it was.
     */
    fun nextRate(previous: Int, points: Int, frames: Int, elapsedMs: Long): Int {
        if (points <= 0 || frames <= 0 || elapsedMs <= 0) return previous
        val rate = points.toDouble() * frames / (elapsedMs / MS_PER_SECOND)
        val next = if (previous <= 0) rate else previous + SMOOTHING * (rate - previous)
        return next.roundToInt().coerceAtLeast(1)
    }

    /** "8,800 points in the region": the caption under the step slider. */
    fun regionLabel(res: Resources, points: Int): String =
        res.getQuantityString(R.plurals.run_estimate_region_fmt, points, grouped(points))

    /**
     * The line above Compute: "8,800 points × 40 frames · about 1 min", the
     * frames left out for one frame, and the time left out without [seconds].
     */
    fun runLabel(res: Resources, points: Int, frames: Int, seconds: Long?): String {
        val work = if (frames > 1) {
            res.getQuantityString(R.plurals.run_estimate_frames_fmt, frames, grouped(points), frames)
        } else {
            res.getQuantityString(R.plurals.run_estimate_points_fmt, points, grouped(points))
        }
        val time = seconds?.let { duration(res, it) } ?: return work
        return res.getString(R.string.run_estimate_joined_fmt, work, time)
    }

    /** "about 33 s" up to a minute and a half, then "about 2 min". */
    fun duration(res: Resources, seconds: Long): String =
        if (seconds <= SECONDS_LABEL_MAX) {
            res.getString(R.string.run_estimate_seconds_fmt, seconds)
        } else {
            res.getString(R.string.run_estimate_minutes_fmt, (seconds / SECONDS_PER_MINUTE).roundToLong())
        }

    private fun grouped(value: Int): String = String.format(Locale.getDefault(), "%,d", value)
}
