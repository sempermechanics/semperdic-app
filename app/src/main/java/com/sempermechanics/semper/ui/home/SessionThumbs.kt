package com.sempermechanics.semper.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.databinding.ItemSessionBinding
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.imaging.BitmapDecoder
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.common.media.ThumbnailLoader
import com.sempermechanics.semper.util.AtomicFiles
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * A Home row's thumbnail: the last frame's U-displacement heatmap, over the
 * reference image it falls back to.
 *
 * Two loaders, two stacked views. The reference ([referenceView]) shows at
 * once; the result ([resultView]) covers it when its render lands. A session
 * whose frames are not on this phone shows its reference only, as before.
 * The render is read-only: it decodes a `.dat` and writes only
 * [SessionPaths.resultThumb], which a later bind reads instead of rendering.
 *
 * @param edgePx the cached render's edge, about twice the row's thumb view in px.
 */
internal class SessionThumbs(
    private val edgePx: Int,
    referenceExecutor: Executor = referenceThread,
    resultExecutor: Executor = resultThread,
) {
    private val references = ThumbnailLoader(CACHE_MAX, referenceExecutor, ::decodeReference)
    private val results = ThumbnailLoader(CACHE_MAX, resultExecutor, { key: ResultKey -> decodeResult(key, edgePx) })

    /** A row's reference image, with the raw dimensions a TIFF/RAW sniff needs. */
    data class ReferenceKey(val path: String, val rawWidth: Int, val rawHeight: Int)

    /**
     * A session's frames as of [updatedAt]: a re-run saves a new [updatedAt],
     * so the in-memory cache never serves the previous run's render.
     */
    data class ResultKey(val sessionDir: String, val updatedAt: Long, val step: Int, val sweepSteps: List<Int>)

    /** Shows [r]'s thumbnail in [row]; [framesOnPhone] false keeps the reference only. */
    fun bind(row: ItemSessionBinding, r: SessionRecord, framesOnPhone: Boolean) {
        val resultKey = if (framesOnPhone) ResultKey(r.sessionDir, r.updatedAt, r.step, r.sweepSteps) else null
        results.bind(row.sessionResultThumb, resultKey)
        // A cached result covers the whole thumb: no reference decode behind it.
        val covered = resultKey != null && results.cached(resultKey) != null
        val referenceKey = r.refPath.takeIf { it.isNotBlank() }?.let { ReferenceKey(it, r.imgW, r.imgH) }
        references.bind(row.sessionThumb, if (covered) null else referenceKey)
    }

    /** Recycles every cached bitmap; only once no row can draw them again. */
    fun clear() {
        references.clear()
        results.clear()
    }

    companion object {
        private const val CACHE_MAX = 24
        private const val REFERENCE_EDGE = 256
        private val referenceThread: Executor = Executors.newSingleThreadExecutor()

        /** Its own thread: a render must not hold up the reference decodes queued behind it. */
        private val resultThread: Executor = Executors.newSingleThreadExecutor()

        /**
         * Runs on the decode thread. The existence check is file I/O, so it
         * runs here rather than on every bind. A missing reference is not a
         * miss: a restore can still bring it back. Sniff-first via
         * [BitmapDecoder] — never hand TIFF/RAW to BitmapFactory (Skia
         * "invalid input" spam on Home rebind).
         */
        @WorkerThread
        fun decodeReference(key: ReferenceKey): ThumbnailLoader.Decoded {
            if (!File(key.path).exists()) return ThumbnailLoader.Decoded.Missing
            val bitmap = BitmapDecoder.decodeFileForView(
                key.path,
                REFERENCE_EDGE,
                REFERENCE_EDGE,
                REFERENCE_EDGE,
                rawWidth = key.rawWidth,
                rawHeight = key.rawHeight,
            )
            return if (bitmap != null) ThumbnailLoader.Decoded.Loaded(bitmap) else ThumbnailLoader.Decoded.Undecodable
        }

        /**
         * The cached render when it is newer than every frame, else a fresh one,
         * cached for next time. No frames: [ThumbnailLoader.Decoded.Missing]
         * (a restore may bring them). A frame with no accepted point renders
         * nothing: [ThumbnailLoader.Decoded.Undecodable], so the reference stays.
         */
        @WorkerThread
        fun decodeResult(key: ResultKey, edgePx: Int): ThumbnailLoader.Decoded {
            val dir = File(key.sessionDir)
            val frames = dir.listFiles { f -> f.extension == "dat" }?.sortedBy { it.name }.orEmpty()
            val last = frames.lastOrNull() ?: return ThumbnailLoader.Decoded.Missing
            val cache = SessionPaths.resultThumb(dir)
            val cached = if (ResultThumbnail.isFresh(cache, frames)) BitmapFactory.decodeFile(cache.path) else null
            // Sweep steps are aligned with the solved frames, as the viewer reads them.
            val step = key.sweepSteps.getOrElse(frames.lastIndex) { key.step }
            val bitmap = cached ?: ResultThumbnail.render(last, step, edgePx)?.also { ResultThumbnail.write(it, cache) }
            return if (bitmap != null) ThumbnailLoader.Decoded.Loaded(bitmap) else ThumbnailLoader.Decoded.Undecodable
        }
    }
}

/** The U-displacement heatmap of one frame as a square thumbnail, and its on-disk cache. */
internal object ResultThumbnail {

    /** Where no point was accepted inside the field: a quiet grey rather than the row behind it. */
    private const val HOLE_COLOR = 0xFFE3E8EC.toInt()
    private const val PNG_QUALITY = 100

    /** The heatmap is rendered at up to this many [edgePx][render] on its long edge before the crop. */
    private const val RENDER_OVERSAMPLE = 2

    /** True when [cache] exists and is no older than any of [frames] (a re-run or restore rewrites them). */
    fun isFresh(cache: File, frames: List<File>): Boolean {
        val newestFrame = frames.maxOfOrNull { it.lastModified() }
        return newestFrame != null && cache.isFile && cache.lastModified() >= newestFrame
    }

    /**
     * [dat]'s U field over the box its accepted points span, centre-cropped to
     * an [edgePx] square; null when the file does not decode or no point was
     * accepted. Reads [dat] only.
     */
    @WorkerThread
    fun render(dat: File, step: Int, edgePx: Int): Bitmap? =
        DicResult.decodeDatFile(dat)?.let { renderField(it, step, edgePx) }

    /** [render] of decoded [data], which it shifts in place. */
    private fun renderField(data: FloatArray, step: Int, edgePx: Int): Bitmap? {
        val box = acceptedBox(data) ?: return null
        // The decoded array is this call's own copy: shift it into the box's frame
        // so the render covers the field rather than the whole photo.
        for (i in data.indices step DicResult.STRIDE) {
            data[i + DicResult.IDX_X] -= box.left
            data[i + DicResult.IDX_Y] -= box.top
        }
        val heatmap = VisualizationEngine.generateHeatmap(
            data,
            box.width,
            box.height,
            DicResult.IDX_U,
            step.coerceAtLeast(1),
            maxLongEdge = edgePx * RENDER_OVERSAMPLE,
        ).bitmap
        return squareCrop(heatmap, edgePx).also { heatmap.recycle() }
    }

    /** Writes [bitmap] to [dest] through a part file; a failure only skips the cache. */
    @WorkerThread
    fun write(bitmap: Bitmap, dest: File) {
        val part = AtomicFiles.partOf(dest)
        try {
            FileOutputStream(part).use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            AtomicFiles.promote(part, dest)
        } catch (e: IOException) {
            Timber.w(e, "Could not cache a Home result thumbnail")
            part.delete()
        }
    }

    /** The pixel box of [data]'s accepted points, or null when there is none. */
    internal fun acceptedBox(data: FloatArray): Box? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (i in 0 until data.size - DicResult.STRIDE + 1 step DicResult.STRIDE) {
            if (!DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) continue
            val x = data[i + DicResult.IDX_X].toInt()
            val y = data[i + DicResult.IDX_Y].toInt()
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
        return if (minX > maxX) null else Box(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun squareCrop(source: Bitmap, edge: Int): Bitmap {
        val out = createBitmap(edge, edge)
        val canvas = Canvas(out)
        canvas.drawColor(HOLE_COLOR)
        val scale = maxOf(edge.toFloat() / source.width, edge.toFloat() / source.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((edge - source.width * scale) / 2f, (edge - source.height * scale) / 2f)
        }
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** A pixel box: [left], [top] and its size. */
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)
}
