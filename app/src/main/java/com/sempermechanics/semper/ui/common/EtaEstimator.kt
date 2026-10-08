package com.sempermechanics.semper.ui.common

import android.content.res.Resources
import com.sempermechanics.semper.R
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Time left for a job that reports how far along it is: a run, an export, a
 * transfer.
 *
 * The rate is a moving average of the progress made between samples at least
 * [MIN_STEP_MS] apart, so a burst of callbacks inside one frame does not make
 * the estimate jump. Nothing is shown until [warmUpMs] of samples exist: the
 * first seconds of a job (reference caching, a slow first frame) say little
 * about the rest.
 *
 * One instance per job; [reset] before reusing it. Not thread-safe: sample
 * from one thread (the overlay samples on Main).
 */
class EtaEstimator(
    private val warmUpMs: Long = WARM_UP_MS,
    private val smoothing: Double = SMOOTHING,
) {

    /** What to tell the user about the time left. */
    sealed interface Eta {
        /** Too early to say, or no progress yet. */
        data object Unknown : Eta

        /** Under a second left, or the job is at 100% and wrapping up. */
        data object Finishing : Eta

        /** About [seconds] left, rounded up. */
        data class Remaining(val seconds: Long) : Eta
    }

    private var startMs = -1L
    private var lastMs = 0L
    private var lastFraction = 0.0
    private var ratePerMs = Double.NaN

    /** Forget every sample, for a new job. */
    fun reset() {
        startMs = -1L
        lastMs = 0L
        lastFraction = 0.0
        ratePerMs = Double.NaN
    }

    /**
     * Records that the job is [fraction] (0–1) done at [nowMs] (a monotonic
     * clock) and returns the time left.
     */
    fun sample(fraction: Double, nowMs: Long): Eta {
        val done = fraction.coerceIn(0.0, 1.0)
        if (startMs < 0) {
            startMs = nowMs
            lastMs = nowMs
            lastFraction = done
            return Eta.Unknown
        }
        val stepMs = nowMs - lastMs
        if (stepMs >= MIN_STEP_MS && done > lastFraction) {
            val instant = (done - lastFraction) / stepMs
            ratePerMs = if (ratePerMs.isNaN()) instant else ratePerMs + smoothing * (instant - ratePerMs)
            lastMs = nowMs
            lastFraction = done
        }
        return when {
            done >= 1.0 -> Eta.Finishing
            nowMs - startMs < warmUpMs || ratePerMs.isNaN() || ratePerMs <= 0.0 -> Eta.Unknown
            else -> {
                val leftMs = (1.0 - done) / ratePerMs
                if (leftMs < MS_PER_SECOND) {
                    Eta.Finishing
                } else {
                    Eta.Remaining(ceil(leftMs / MS_PER_SECOND - ROUNDING_SLACK).toLong())
                }
            }
        }
    }

    companion object {
        const val WARM_UP_MS = 3_000L
        const val SMOOTHING = 0.3
        const val MIN_STEP_MS = 500L
        private const val MS_PER_SECOND = 1_000.0
        private const val SECONDS_PER_MINUTE = 60.0

        /** Keeps float noise from rounding an exact 40 s up to 41. */
        private const val ROUNDING_SLACK = 1e-6

        /** Up to this many seconds the label counts seconds; above it, minutes. */
        const val SECONDS_LABEL_MAX = 90L

        /** "About 35 s left", "About 4 min left", "Finishing"; null while [Eta.Unknown]. */
        fun label(res: Resources, eta: Eta): String? = when (eta) {
            Eta.Unknown -> null
            Eta.Finishing -> res.getString(R.string.eta_finishing)
            is Eta.Remaining -> if (eta.seconds <= SECONDS_LABEL_MAX) {
                res.getString(R.string.eta_seconds_left, eta.seconds)
            } else {
                res.getString(R.string.eta_minutes_left, (eta.seconds / SECONDS_PER_MINUTE).roundToLong())
            }
        }
    }
}
