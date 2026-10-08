package com.sempermechanics.semper.data.cloud

import androidx.work.Data
import com.sempermechanics.semper.navigation.IntentKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The upload's live progress for the Home row and the notification: one
 * throttled sampler emits phase + percent, fed by the bundling frame count
 * ("prepare") and then the uploaded byte count ("upload"). Decoupling the emit
 * from the producers keeps WorkManager writes cheap however fast frames or
 * chunks complete.
 *
 * Only a change is published: most samples of a long upload repeat the last
 * value, and each [publish] is a WorkManager DB transaction plus a LiveData
 * dispatch to Home (FI-15). A change is a tenth of a percent, as the
 * notification shows it, so at most one publish per sample interval.
 *
 * While the count is bytes to Drive ([begin] with `bytes`), the Data carries
 * them too ([TransferBytes]) with the [meter]'s rate.
 */
internal class UploadProgressSampler(
    private val localId: String,
    initialPhase: String,
    initialTotal: Long,
    private val meter: TransferMeter = TransferMeter(),
) {
    val phase = AtomicReference(initialPhase)
    val done = AtomicLong(0)
    val total = AtomicLong(initialTotal)

    /** Whether [done] / [total] count bytes sent to Drive, rather than frames or bytes staged. */
    private val countsBytes = AtomicBoolean(false)

    /** Bumped by [begin], so the sampling coroutine knows to start its meter over. */
    private val generation = AtomicInteger()

    /** Start [phase] over: nothing done yet, out of [total]; [bytes] when the count is bytes to Drive. */
    fun begin(phase: TransferPhase, total: Long, bytes: Boolean = false) {
        this.phase.set(phase.wire)
        done.set(0)
        this.total.set(total)
        countsBytes.set(bytes)
        generation.incrementAndGet()
    }

    /**
     * Samples every [intervalMs] until the returned job is cancelled. Each
     * change goes to [publish] (the progress Data) and then [onReading] (what
     * the notification shows: the meter's reading, or "preparing" while the
     * count is not bytes to Drive).
     */
    fun launchIn(
        scope: CoroutineScope,
        intervalMs: Long,
        onReading: suspend (TransferNotifications.Progress) -> Unit = {},
        publish: suspend (Data) -> Unit,
    ): Job = scope.launch {
        var lastPhase: String? = null
        var lastTenths = -1L
        var lastGeneration = generation.get()
        while (isActive) {
            val gen = generation.get()
            if (gen != lastGeneration) {
                meter.reset()
                lastGeneration = gen
            }
            val reading = meter.sample(done.get(), total.get())
            val tenths = tenths()
            val current = phase.get()
            if (tenths != lastTenths || current != lastPhase) {
                publish(data(current, reading))
                onReading(TransferNotifications.Progress(reading, preparing = !countsBytes.get()))
                lastTenths = tenths
                lastPhase = current
            }
            delay(intervalMs)
        }
    }

    private fun data(phase: String, reading: TransferMeter.Reading): Data = Data.Builder()
        .putString(IntentKeys.SESSION_LOCAL_ID, localId)
        .putString(IntentKeys.UPLOAD_PHASE, phase)
        .putInt(IntentKeys.UPLOAD_PERCENT, percent())
        .apply { if (countsBytes.get() && reading.total > 0L) TransferBytes.of(reading).putInto(this) }
        .build()

    internal fun percent(): Int = (scaled(PERCENT)).toInt()

    /** Tenths of a percent done: what a change is measured in. */
    private fun tenths(): Long = scaled(PERMILLE)

    private fun scaled(whole: Long): Long {
        val t = total.get()
        return if (t > 0) (done.get() * whole / t).coerceIn(0L, whole) else 0L
    }

    private companion object {
        const val PERCENT = 100L
        const val PERMILLE = 1_000L
    }
}
