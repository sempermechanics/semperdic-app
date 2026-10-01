package com.indicvision.semper.data.prefs

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.util.AtomicFiles
import com.indicvision.semper.util.writeVia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The analysis wizard's inputs on disk, so a process death between picking
 * and Compute does not lose them (ADR-005).
 *
 * The view model's small values ride in its `SavedStateHandle`; what is too
 * big for a saved-state Bundle lives here: the reference bytes, the ROI mask
 * and the frame list. The staged frames themselves stay in
 * `cacheDir/temp_deformed`; [CacheJanitor] leaves that directory alone while
 * this draft is live, and drops the draft once it is a day old.
 *
 * Every write refreshes [MARKER]'s timestamp, which is what "live" is
 * measured from. Blocking I/O throughout: call it on [io], the one lane all
 * draft I/O shares, except [discard], which returns at once.
 */
class WizardDraft(private val dir: File) {

    constructor(context: Context) : this(dirIn(context.filesDir))

    @WorkerThread
    fun writeReference(bytes: ByteArray?) = write(REFERENCE, bytes)

    @WorkerThread
    fun writeMask(bytes: ByteArray?) = write(MASK, bytes)

    @WorkerThread
    fun writeFrames(json: String) = write(FRAMES, json.toByteArray())

    @WorkerThread
    fun readReference(): ByteArray? = read(REFERENCE)

    @WorkerThread
    fun readMask(): ByteArray? = read(MASK)

    @WorkerThread
    fun readFrames(): String? = read(FRAMES)?.toString(Charsets.UTF_8)

    /** Set by [discard]; a write still queued behind it must not bring the draft back. */
    @Volatile
    private var discarded = false

    /** Empties the draft for a wizard that starts from nothing. */
    @WorkerThread
    @Synchronized
    fun clear() {
        if (dir.exists() && !dir.deleteRecursively()) Timber.w("Could not delete the wizard draft")
    }

    /**
     * Drops the draft for good: the wizard was left, so nothing will restore it.
     *
     * Called from `onDestroy`, so it does not wait: it marks the draft, which
     * stops any write still queued, and deletes the files on [io], behind a
     * write in flight. Taking the lock here made the main thread wait out a
     * reference write of tens of megabytes.
     */
    @AnyThread
    fun discard() {
        discarded = true
        queue(this) { it.clear() }
    }

    /** Writes atomically; null deletes the part. Failures are logged, not thrown. */
    @Synchronized
    private fun write(name: String, bytes: ByteArray?) {
        if (discarded) return
        try {
            dir.mkdirs()
            val target = File(dir, name)
            if (bytes == null) {
                target.delete()
            } else {
                AtomicFiles.writeVia(target, tmp = File(dir, "$name.tmp")) { it.writeBytes(bytes) }
            }
            File(dir, MARKER).writeText(System.currentTimeMillis().toString())
        } catch (e: IOException) {
            Timber.e(e, "Could not write wizard draft part %s", name)
        }
    }

    private fun read(name: String): ByteArray? = try {
        File(dir, name).takeIf(File::isFile)?.readBytes()
    } catch (e: IOException) {
        Timber.e(e, "Could not read wizard draft part %s", name)
        null
    }

    companion object {
        /**
         * The one lane all draft I/O goes through, one task at a time, in the
         * order it was queued. Shared by every view model, so a restore reads
         * what the stop queued before it.
         *
         * Order is only as good as the queueing: a wizard that finishes queues
         * its delete from `onDestroy`, which can run after the next wizard has
         * queued its first writes, and then deletes them. A process death
         * after that restores as LOST (the Bundle names parts the draft no
         * longer holds), never as the wrong inputs.
         */
        val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

        /** Where [queue] runs: the process's, so no view model can cancel a write. */
        private val lane = CoroutineScope(SupervisorJob() + io)

        /**
         * Runs [write] on [target] on [io], behind everything queued before
         * it, and returns at once. The work belongs to the process, not to the
         * caller: a view model cleared under "Don't keep activities" must not
         * cancel the frame list its Activity's stop just queued.
         */
        @AnyThread
        fun queue(target: WizardDraft, write: (WizardDraft) -> Unit) {
            lane.launch { write(target) }
        }

        const val DIR_NAME = "wizard_draft"
        private const val REFERENCE = "reference.bin"
        private const val MASK = "mask.bin"
        private const val FRAMES = "frames.json"
        private const val MARKER = "live"

        /** A draft untouched this long is abandoned, not paused. */
        private const val MAX_AGE_HOURS = 24L
        val MAX_AGE_MS = TimeUnit.HOURS.toMillis(MAX_AGE_HOURS)

        fun dirIn(filesDir: File): File = File(filesDir, DIR_NAME)

        /** True when a wizard may still restore from the draft under [filesDir]. */
        fun isLive(filesDir: File, now: Long = System.currentTimeMillis()): Boolean {
            val marker = File(dirIn(filesDir), MARKER)
            return marker.isFile && now - marker.lastModified() < MAX_AGE_MS
        }

        /**
         * Deletes the draft under [filesDir] unless it is live. Returns whether
         * a live draft remains, i.e. whether its staged frames must be kept.
         */
        @WorkerThread
        fun reclaimIfStale(filesDir: File, now: Long = System.currentTimeMillis()): Boolean {
            if (isLive(filesDir, now)) return true
            val dir = dirIn(filesDir)
            if (dir.exists()) {
                Timber.i("Reclaiming an abandoned wizard draft")
                dir.deleteRecursively()
            }
            return false
        }

        /** Bytes the draft occupies, for the storage budget. */
        @WorkerThread
        fun sizeIn(filesDir: File): Long = CacheJanitor.sizeOf(dirIn(filesDir))
    }
}
