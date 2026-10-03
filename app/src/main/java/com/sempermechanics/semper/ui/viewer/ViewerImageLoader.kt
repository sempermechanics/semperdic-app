package com.sempermechanics.semper.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.imaging.BitmapDecode
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.common.SerialJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * The image under the viewer's heatmap: the reference, decoded once at display
 * size, or the frame's own photo when it is on disk. Owns both bitmaps and the
 * rest-fit box the image is framed to.
 *
 * Constructed before onCreate; reads [ResultViewerActivity.binding] lazily.
 */
internal class ViewerImageLoader(private val host: ResultViewerActivity) {

    /** The reference at display size. Exports and the report read it; it is never a frame's photo. */
    var cachedBaseImage: Bitmap? = null
        private set

    /**
     * Each frame's own photo by position, looked up off the main thread when a
     * frame's data is read ([lookUpFramePhoto]); "" when it is not on disk.
     * See [onFramePhoto].
     */
    private val framePhotos = ConcurrentHashMap<Int, String>()

    /** The frame photo under the map now, and its path; null while the reference is shown. */
    private var framePhotoBitmap: Bitmap? = null
    private var framePhotoPath: String? = null
    private val framePhotoJob = SerialJob()

    /** True once [cachedBaseImage] is the image under the map. */
    private var referenceShown = false

    private val refDecodeJob = SerialJob()

    /** Puts the reference on screen at its true dimensions (decoded off-main, display size). */
    fun showReference(refPath: String?) {
        // True sensor dims stay on the intent for math / probe / export; the
        // on-screen bitmap is decoded off-main at ImageView scale.
        host.binding.imgBaseResult.setTrueImageDimensions(host.imageSize.width, host.imageSize.height)
        updateHeatmapFitBounds(data = null)
        if (refPath != null) {
            decodeReferenceForDisplay(refPath)
        }
    }

    /**
     * Decode the reference off the main thread with [BitmapFactory.Options.inSampleSize]
     * sized to the ImageView (capped by [VisualizationEngine.DISPLAY_MAX_EDGE]).
     */
    private fun decodeReferenceForDisplay(refPath: String) {
        val image = host.binding.imgBaseResult
        image.post {
            val viewW = image.width.coerceAtLeast(1)
            val viewH = image.height.coerceAtLeast(1)
            val reqW = viewW.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            val reqH = viewH.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            refDecodeJob.launch(host.lifecycleScope, Dispatchers.IO) {
                val bmp = BitmapDecode.decodeFileForView(
                    refPath,
                    reqW,
                    reqH,
                    rawWidth = host.imageSize.width,
                    rawHeight = host.imageSize.height,
                )
                withContext(Dispatchers.Main) {
                    if (host.isDestroyed || host.isFinishing) {
                        bmp?.recycle()
                        return@withContext
                    }
                    cachedBaseImage = bmp
                    // A frame already on its own photo keeps it.
                    if (!onFramePhoto) showReferenceBase()
                }
            }
        }
    }

    /**
     * Rest-fit the coloured region: custom ROI if set, else accepted-point
     * bounds for this frame, else the full specimen. On the frame's own photo
     * the box also takes in where the points moved to, so the displaced map
     * stays in view.
     */
    fun updateHeatmapFitBounds(data: FloatArray?) {
        val imageSize = host.imageSize
        if (!imageSize.isKnown) return
        val box = HeatmapFit.resolve(imageSize, host.roi, accepted = data?.let { DicResult.acceptedPointsBounds(it) })
        val moved = data?.takeIf { onFramePhoto }?.let { DicResult.acceptedPointsBounds(it, displaced = true) }
        if (moved != null) {
            box[HeatmapFit.LEFT] = minOf(box[HeatmapFit.LEFT], moved[HeatmapFit.LEFT])
            box[HeatmapFit.TOP] = minOf(box[HeatmapFit.TOP], moved[HeatmapFit.TOP])
            box[HeatmapFit.RIGHT] = maxOf(box[HeatmapFit.RIGHT], moved[HeatmapFit.RIGHT])
            box[HeatmapFit.BOTTOM] = maxOf(box[HeatmapFit.BOTTOM], moved[HeatmapFit.BOTTOM])
        }
        host.binding.imgBaseResult.setFitBounds(
            box[HeatmapFit.LEFT],
            box[HeatmapFit.TOP],
            box[HeatmapFit.RIGHT],
            box[HeatmapFit.BOTTOM],
        )
    }

    /**
     * Looks up, once, whether the frame at [index] has its own photo on disk.
     * Disk only: call it off the main thread, before the frame can be shown —
     * which photo it goes on decides how its map is drawn.
     */
    fun lookUpFramePhoto(index: Int) {
        framePhotos.getOrPut(index) { host.deformedImagePathAt(index).orEmpty() }
    }

    /** The photo of the frame at [position] when it is on disk, else null (the reference is shown). */
    private fun framePhotoPathFor(position: Int): String? = framePhotos[position]?.ifEmpty { null }

    /**
     * True when the frame on screen is drawn over its own photo, with its map
     * at the displaced positions. False shows the reference under the
     * reference-position map: a frame whose photo is gone (a restore without
     * it, storage reclaim) still lines up.
     */
    val onFramePhoto: Boolean get() = framePhotoPathFor(host.currentFrameIndex) != null

    /**
     * Puts the photo of the frame at [index] under its map — or the reference,
     * when that frame has none. Decoded off the main thread at display size,
     * like the reference; a photo that fails to decode drops the frame back to
     * the reference and its map with it.
     */
    fun showFrameBase(index: Int) {
        framePhotoJob.cancel()
        val path = framePhotoPathFor(index)
        if (path == null) {
            showReferenceBase()
            return
        }
        if (path == framePhotoPath) return
        val image = host.binding.imgBaseResult
        val reqW = image.width.takeIf { it > 0 }?.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            ?: VisualizationEngine.DISPLAY_MAX_EDGE
        val reqH = image.height.takeIf { it > 0 }?.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            ?: VisualizationEngine.DISPLAY_MAX_EDGE
        framePhotoJob.launch(host.lifecycleScope) {
            val bmp = withContext(Dispatchers.IO) {
                BitmapDecode.decodeFileForView(
                    path,
                    reqW,
                    reqH,
                    rawWidth = host.imageSize.width,
                    rawHeight = host.imageSize.height,
                )
            }
            if (host.isDestroyed || host.isFinishing) {
                bmp?.recycle()
                return@launch
            }
            if (bmp == null) {
                Timber.w("Frame %d photo did not decode; showing it on the reference", index)
                framePhotos[index] = ""
                val data = host.rawData
                if (host.currentFrameIndex == index && data != null) host.applyLoadedFrame(index, data)
                return@launch
            }
            if (framePhotoPathFor(host.currentFrameIndex) != path) {
                bmp.recycle()
                return@launch
            }
            val previous = framePhotoBitmap
            framePhotoBitmap = bmp
            framePhotoPath = path
            image.setImageBitmap(bmp)
            referenceShown = false
            previous?.recycle()
        }
    }

    /** Puts the reference back under the map, once it is decoded, and frees any frame photo. */
    private fun showReferenceBase() {
        val reference = cachedBaseImage ?: return
        val previous = framePhotoBitmap
        if (previous == null && framePhotoPath == null && referenceShown) return
        framePhotoBitmap = null
        framePhotoPath = null
        host.binding.imgBaseResult.setImageBitmap(reference)
        referenceShown = true
        previous?.recycle()
    }

    /** Stops the decodes in flight. */
    fun cancel() {
        refDecodeJob.cancel()
        framePhotoJob.cancel()
    }
}
