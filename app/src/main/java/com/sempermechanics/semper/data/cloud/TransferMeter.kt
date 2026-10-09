package com.sempermechanics.semper.data.cloud

import androidx.work.Data
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.common.EtaEstimator
import kotlin.math.roundToLong

/**
 * One transfer's progress as its notification and its progress Data tell it:
 * the percent with its fraction, a smoothed byte rate and the time left.
 *
 * The rate is a moving average of the bytes moved between samples at least
 * [MIN_STEP_MS] apart, like [EtaEstimator]'s, so chunks landing in a burst do
 * not make it jump; it is null until one such step exists. A count that goes
 * back (a retry starting the file over) adds nothing to the rate.
 *
 * One instance per transfer, sampled from one coroutine at a time; [reset]
 * when a new count starts (an upload moving from preparing to bytes).
 *
 * @param clock monotonic milliseconds; a test's to set.
 */
class TransferMeter(private val clock: () -> Long = MONOTONIC_MS) {

    /** What a transfer has done: [done] of [total] (0 = not known yet), as [percent] 0–100. */
    data class Reading(
        val done: Long,
        val total: Long,
        val percent: Double,
        /** Smoothed bytes per second; null until known. */
        val bytesPerSecond: Double?,
        val eta: EtaEstimator.Eta,
    )

    private val eta = EtaEstimator()
    private var lastMs = -1L
    private var lastDone = 0L
    private var rate = Double.NaN

    /** Forget every sample, for a new count. */
    fun reset() {
        eta.reset()
        lastMs = -1L
        lastDone = 0L
        rate = Double.NaN
    }

    /** Records [done] of [total] now and returns the reading. */
    fun sample(done: Long, total: Long): Reading {
        val now = clock()
        val percent = percentOf(done, total)
        sampleRate(done, now)
        val left = if (total > 0L) eta.sample(percent / WHOLE, now) else EtaEstimator.Eta.Unknown
        return Reading(done, total, percent, rate.takeUnless { it.isNaN() }, left)
    }

    private fun sampleRate(done: Long, now: Long) {
        if (lastMs < 0L) {
            lastMs = now
            lastDone = done
            return
        }
        val stepMs = now - lastMs
        if (stepMs < MIN_STEP_MS) return
        val instant = (done - lastDone).coerceAtLeast(0L) * MS_PER_SECOND / stepMs
        rate = if (rate.isNaN()) instant else rate + SMOOTHING * (instant - rate)
        lastMs = now
        lastDone = done
    }

    companion object {
        const val MIN_STEP_MS = EtaEstimator.MIN_STEP_MS
        const val SMOOTHING = EtaEstimator.SMOOTHING
        private const val MS_PER_SECOND = 1_000.0
        private const val WHOLE = 100.0
        private const val NANOS_PER_MS = 1_000_000L
        private val origin = System.nanoTime()

        /**
         * Milliseconds since this class loaded: monotonic and never negative
         * (as [EtaEstimator] needs), and plain JVM, so a worker's meter runs
         * in a unit test without Android's clock.
         */
        val MONOTONIC_MS: () -> Long = { (System.nanoTime() - origin) / NANOS_PER_MS }

        /** [done] of [total] as a percent with its fraction; 0 while the total is unknown. */
        fun percentOf(done: Long, total: Long): Double =
            if (total > 0L) done.coerceIn(0L, total) * WHOLE / total else 0.0
    }
}

/**
 * A byte-counting phase's progress as its WorkInfo progress Data carries it:
 * [done] of [total] bytes and, once known, [perSecond]. Home reads the whole
 * percent beside it ([IntentKeys.UPLOAD_PERCENT]); Settings' banner reads these.
 */
data class TransferBytes(val done: Long, val total: Long, val perSecond: Double? = null) {

    /** [done] of [total] as a percent with its fraction. */
    val percent: Double get() = TransferMeter.percentOf(done, total)

    /** Adds these to [builder]; the rate only once it is known. */
    fun putInto(builder: Data.Builder): Data.Builder = builder
        .putLong(IntentKeys.TRANSFER_BYTES_DONE, done)
        .putLong(IntentKeys.TRANSFER_BYTES_TOTAL, total)
        .apply { perSecond?.let { putLong(IntentKeys.TRANSFER_BYTES_PER_SECOND, it.roundToLong()) } }

    companion object {
        fun of(reading: TransferMeter.Reading): TransferBytes =
            TransferBytes(reading.done, reading.total, reading.bytesPerSecond)

        /** The bytes in [data], or null when it carries none: a phase counting frames, or no total yet. */
        fun read(data: Data): TransferBytes? {
            val values = data.keyValueMap
            val done = values[IntentKeys.TRANSFER_BYTES_DONE] as? Long
            val total = values[IntentKeys.TRANSFER_BYTES_TOTAL] as? Long
            if (done == null || total == null || total <= 0L) return null
            val rate = (values[IntentKeys.TRANSFER_BYTES_PER_SECOND] as? Long)?.toDouble()
            return TransferBytes(done, total, rate)
        }
    }
}
