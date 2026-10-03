package com.sempermechanics.semper.data.cloud

import androidx.work.Data
import androidx.work.workDataOf
import com.sempermechanics.semper.navigation.DicKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The upload's live progress for the Home row: one throttled sampler emits
 * phase + percent, fed by the bundling frame count ("prepare") and then the
 * uploaded byte count ("upload"). Decoupling the emit from the producers keeps
 * WorkManager writes cheap however fast frames or chunks complete.
 *
 * Only a change is published: most samples of a long upload repeat the last
 * value, and each [publish] is a WorkManager DB transaction plus a LiveData
 * dispatch to Home (FI-15).
 */
internal class UploadProgressSampler(
    private val localId: String,
    initialPhase: String,
    initialTotal: Long,
) {
    val phase = AtomicReference(initialPhase)
    val done = AtomicLong(0)
    val total = AtomicLong(initialTotal)

    /** Start [phase] over: nothing done yet, out of [total]. */
    fun begin(phase: TransferPhase, total: Long) {
        this.phase.set(phase.wire)
        done.set(0)
        this.total.set(total)
    }

    /** Samples every [intervalMs] until the returned job is cancelled. */
    fun launchIn(
        scope: CoroutineScope,
        intervalMs: Long,
        publish: suspend (Data) -> Unit,
    ): Job = scope.launch {
        var lastPhase: String? = null
        var lastPct = -1
        while (isActive) {
            val pct = percent()
            val current = phase.get()
            if (pct != lastPct || current != lastPhase) {
                publish(
                    workDataOf(
                        DicKeys.SESSION_LOCAL_ID to localId,
                        DicKeys.UPLOAD_PHASE to current,
                        DicKeys.UPLOAD_PERCENT to pct,
                    ),
                )
                lastPct = pct
                lastPhase = current
            }
            delay(intervalMs)
        }
    }

    internal fun percent(): Int {
        val t = total.get()
        return if (t > 0) (done.get() * PERCENT / t).toInt().coerceIn(0, PERCENT) else 0
    }

    private companion object {
        const val PERCENT = 100
    }
}
