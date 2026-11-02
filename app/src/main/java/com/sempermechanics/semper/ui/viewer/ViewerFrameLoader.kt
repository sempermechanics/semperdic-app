package com.sempermechanics.semper.ui.viewer

import android.graphics.BitmapFactory
import android.os.Trace
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.common.SerialJob
import com.sempermechanics.semper.ui.common.dialog.FaqRedirect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import timber.log.Timber
import java.io.File

/** What [ViewerFrameLoader] needs from the viewer. */
internal interface FrameLoaderHost {
    /** Holds the frame on screen ([ResultViewerViewModel.currentFrameIndex]). */
    val viewModel: ResultViewerViewModel

    /** The opened frame set's `.dat` files; empty until it is read. */
    val batchFiles: List<File>
    val scrubCache: ScrubFrameCache

    /** Puts frame [index], decoded as [data], on screen. Main thread. */
    fun applyLoadedFrame(index: Int, data: FloatArray)

    /** Frame [index]'s `.dat` could not be read: say so, and keep the frame on screen. Main thread. */
    fun onFrameUnreadable(index: Int)

    /** Frame [index] ran out of memory while it decoded: keep the frame on screen. Main thread. */
    fun onFrameOutOfMemory(index: Int)
}

/**
 * The viewer's frame data: the batch listing read when the viewer opens, each
 * frame's `.dat` decoded off the main thread, and a bounded look-ahead that
 * keeps the scrub cache warm around the frame on screen.
 *
 * [activity] is the scope the decodes run in and the snackbar's window;
 * [images] learns which photo a frame goes on as it is read, and [captions]
 * drops the opening pill when a frame cannot be drawn.
 */
internal class ViewerFrameLoader(
    private val activity: AppCompatActivity,
    private val host: FrameLoaderHost,
    private val images: ViewerImageLoader,
    private val captions: ViewerCaptions,
) {

    /** What the viewer needs from disk before the first frame can load; see [readFrameSet]. */
    class FrameSet(
        val imageSize: ImageSize,
        val defImagePaths: List<String>,
        val batchFiles: List<File>,
        val plannedFrames: List<Int>,
        val maxDatBytes: Long,
    )

    /**
     * Largest `.dat` size, from the [FrameSet] ([useFrameSet]). The prefetch heap
     * guard used to `stat()` every file on every frame load (3F syscalls per scrub
     * step); the file set never changes once read, so one scan suffices.
     */
    private var maxDatBytes: Long = 0L

    private val loadFrameJob = SerialJob()

    /** Decodes one frame's `.dat`; a test swaps it to fail a decode. Any thread. */
    @VisibleForTesting
    internal var decodeDat: (File) -> FloatArray? = DicResult::decodeDatFile

    /** The single in-flight look-ahead worker; see [prefetchAround]. */
    private val prefetchJob = SerialJob()

    /** Previous look-ahead centre, used to infer scrub direction. */
    private var lastPrefetchCenter = 0

    private val batchFiles: List<File> get() = host.batchFiles
    private val scrubCache: ScrubFrameCache get() = host.scrubCache

    /** Takes what this loader needs from the [set] the viewer has just read. Main thread. */
    fun useFrameSet(set: FrameSet) {
        maxDatBytes = set.maxDatBytes
    }

    fun loadFrameData(index: Int) {
        if (index < 0 || index >= batchFiles.size) return

        scrubCache.getData(index)?.let { cached ->
            host.applyLoadedFrame(index, cached)
            prefetchAround(index)
            return
        }

        loadFrameJob.launch(activity.lifecycleScope, Dispatchers.IO) {
            try {
                val data = readFrameDat(index)
                if (data == null) {
                    // Nothing to draw: the opening pill must not wait on it.
                    withContext(Dispatchers.Main) { unreadable(index) }
                    return@launch
                }
                scrubCache.putData(index, data)

                withContext(Dispatchers.Main) {
                    if (host.viewModel.currentFrameIndex == index) host.applyLoadedFrame(index, data)
                    // On Main: prefetchJob is a SerialJob, which is main-thread only.
                    prefetchAround(index)
                }
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: OutOfMemoryError) {
                // Error, not Exception — must be caught explicitly or the process dies.
                Timber.e(e, "OOM loading frame $index")
                // The frames only: the heatmap on screen must not be recycled under its view.
                scrubCache.clearData()
                withContext(Dispatchers.Main) {
                    captions.openingDone()
                    if (host.viewModel.currentFrameIndex == index) host.onFrameOutOfMemory(index)
                    FaqRedirect.snackbar(
                        activity,
                        R.string.viewer_frame_oom,
                        R.string.url_faq_viewer_oom,
                    )
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.e(e, "Failed to load frame $index")
                withContext(Dispatchers.Main) { unreadable(index) }
            }
        }
    }

    /** Drops the opening pill and tells the viewer, if [index] is still the frame asked for (readiness AE1-013). */
    private fun unreadable(index: Int) {
        captions.openingDone()
        if (host.viewModel.currentFrameIndex == index) host.onFrameUnreadable(index)
    }

    /**
     * Fills [scrubCache] with a bounded look-ahead window around [center], so a scrub
     * step is usually a cache hit without letting memory grow with scrub speed.
     *
     * One serialized worker, not a job per neighbour: the previous version launched up
     * to two uncancelled coroutines on *every* frame load, so a fast scrub could have a
     * dozen concurrent decodes in flight — each holding a full frame — while the cache
     * only ever kept the last two, so most of that work became garbage on arrival. Peak
     * memory then scaled with how fast the user scrubbed rather than with any bound.
     *
     * Here exactly one decode runs at a time, the window is cancelled and restarted when
     * the user moves on, and each frame is admitted only if the cache still has room
     * (count *and* bytes) and the heap guard passes — so the queue stays warm while peak
     * stays flat.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // one guard per reason to skip or stop
    private fun prefetchAround(center: Int) {
        prefetchJob.cancel()
        val direction = if (center >= lastPrefetchCenter) 1 else -1
        lastPrefetchCenter = center

        prefetchJob.launch(activity.lifecycleScope, Dispatchers.IO) {
            try {
                for (offset in lookAheadOffsets(direction)) {
                    val index = center + offset
                    if (index < 0 || index >= batchFiles.size) continue
                    if (scrubCache.getData(index) != null) continue
                    // Re-checked per frame: both the cache budget and the heap can be
                    // consumed by the foreground frame while this window is filling.
                    if (scrubCache.freeSlots(maxDatBytes) <= 0) return@launch
                    if (!heapHasRoomForPrefetch()) return@launch
                    val data = readFrameDat(index) ?: continue
                    scrubCache.putData(index, data)
                    yield() // stay promptly cancellable between frames
                }
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: OutOfMemoryError) {
                Timber.w(e, "Prefetch around frame %d OOM — clearing scrub cache", center)
                // The frames only, off Main: the heatmap on screen must not be recycled under its view.
                scrubCache.clearData()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.w(e, "Prefetch around frame %d failed", center)
            }
        }
    }

    /**
     * Frames to warm, nearest first and biased to the scrub [direction], with one frame
     * behind so reversing is still a hit. Length is capped by the cache, so this never
     * queues more than can be held.
     */
    private fun lookAheadOffsets(direction: Int): IntArray {
        val ahead = ScrubFrameCache.DEFAULT_MAX_FRAMES - 1
        val offsets = IntArray(ahead + 1)
        for (i in 0 until ahead) offsets[i] = direction * (i + 1)
        offsets[ahead] = -direction
        return offsets
    }

    private fun readFrameDat(index: Int): FloatArray? {
        Trace.beginSection("Semper.viewer.decodeDat")
        try {
            // Off the main thread, before the frame can be shown: which photo it
            // goes on decides how its map is drawn.
            images.lookUpFramePhoto(index)
            val file = batchFiles[index]
            val data = decodeDat(file)
            if (data == null) {
                Timber.e("Invalid file size for frame $index")
            }
            return data
        } finally {
            Trace.endSection()
        }
    }

    /** Rough guard: need headroom for another full-frame FloatArray (~file size). */
    private fun heapHasRoomForPrefetch(): Boolean {
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        if (maxDatBytes <= 0L) return false
        return free > maxDatBytes * PREFETCH_HEADROOM_FRAMES
    }

    /** Stops the frame decode in flight. The look-ahead ends with the lifecycle scope. */
    fun cancel() {
        loadFrameJob.cancel()
    }

    companion object {
        /** Free heap a prefetch needs, in largest frames. */
        private const val PREFETCH_HEADROOM_FRAMES = 3

        /**
         * Reads the batch listing of [args], the deformed originals, each frame's
         * size and (for an Intent without it) the reference's dimensions from
         * [refPath]. Plain data in and out, so the view model can keep it across
         * a rotation. Disk only — call it off the main thread.
         */
        fun readFrameSet(args: ViewerArgs, refPath: String?, knownSize: ImageSize): FrameSet {
            var size = knownSize
            if (!size.isKnown && refPath != null) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(refPath, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    size = ImageSize(bounds.outWidth, bounds.outHeight)
                }
            }
            val batchDirPath = args.batchDirPath
            // Prefer the raw deformed originals persisted in the session dir (survive
            // reopen/eviction); fall back to the just-analysed session's temp paths.
            val rawDeformedDir = batchDirPath?.let { File(it, SessionPaths.RAW_DEFORMED_SUBDIR) }
            val defPaths = rawDeformedDir?.takeIf { it.isDirectory }
                ?.listFiles()?.sortedBy { it.name }?.map { it.absolutePath }
                ?: args.defFilePaths
            val dir = batchDirPath?.let { File(it) }?.takeIf { it.isDirectory }
            val files = dir?.listFiles { file -> file.extension == "dat" }?.sortedBy { it.name }.orEmpty()
            return FrameSet(
                imageSize = size,
                defImagePaths = defPaths,
                batchFiles = files,
                plannedFrames = SessionPaths.plannedFrameIndices(files),
                // Once, here: the prefetch heap guard used to stat() every file per load.
                maxDatBytes = files.maxOfOrNull { it.length() } ?: 0L,
            )
        }
    }
}
