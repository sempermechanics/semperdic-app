package com.sempermechanics.semper.data.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import com.sempermechanics.semper.util.suspendRunCatching
import timber.log.Timber

/**
 * A download worker's progress (the restore and the Save-to-Files download):
 * the [TransferMeter] reading its progress Data carries, and the running
 * notification, posted at most once every [INTERVAL_MS] — the downloads report
 * every chunk, far more often than a notification should change.
 *
 * One per worker run; sampled from the worker's coroutine only.
 */
internal class TransferProgressUpdates(
    private val context: Context,
    private val kind: TransferNotifications.Kind,
    private val clock: () -> Long = TransferMeter.MONOTONIC_MS,
) {
    private val meter = TransferMeter(clock)
    private var lastPostMs = -1L

    /** The reading for [done] of [total] bytes now. */
    fun sample(done: Long, total: Long): TransferMeter.Reading = meter.sample(done, total)

    /**
     * The running notification for [reading], or null when the last one went
     * out under [INTERVAL_MS] ago. The first, and the one at 100%, always go.
     */
    fun foregroundIfDue(reading: TransferMeter.Reading): ForegroundInfo? {
        val now = clock()
        val finished = reading.total > 0L && reading.done >= reading.total
        if (lastPostMs >= 0L && now - lastPostMs < INTERVAL_MS && !finished) return null
        lastPostMs = now
        return TransferNotifications.foreground(context, kind, TransferNotifications.Progress(reading))
    }

    companion object {
        const val INTERVAL_MS = 1_000L

        /**
         * Shows [info] as [worker]'s foreground notification. An update the
         * system refuses (a foreground start from the background on Android 12+)
         * costs only the notification, never the transfer.
         */
        suspend fun post(worker: CoroutineWorker, info: ForegroundInfo) {
            suspendRunCatching { worker.setForeground(info) }
                .onFailure { Timber.w("Transfer notification not updated (%s)", it.javaClass.simpleName) }
        }
    }
}
