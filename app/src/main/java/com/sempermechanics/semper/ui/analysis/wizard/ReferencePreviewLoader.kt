package com.sempermechanics.semper.ui.analysis.wizard

import android.graphics.Bitmap
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.imaging.RawRgba
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes the reference an editor draws on (the ROI studio, the bending edge
 * taps): its true size, and a preview whose long edge is at most
 * [Request.previewMaxEdge].
 * Coordinates an editor returns are in true reference pixels, the frame the
 * `.dat` x / y use.
 */
object ReferencePreviewLoader {

    /** [width] / [height] are the reference's true pixels; [bitmap] may be smaller. */
    class Loaded(val bitmap: Bitmap?, val width: Int, val height: Int)

    /**
     * [intentWidth] / [intentHeight] are what the wizard knew; a decodable
     * image replaces them with the decoder's own (OpenCV applies EXIF, the
     * wizard's BitmapFactory bounds do not).
     */
    class Request(val bytes: ByteArray, val intentWidth: Int, val intentHeight: Int, val previewMaxEdge: Int)

    suspend fun load(request: Request): Loaded {
        // A RAW/DNG reference is stored as a headerless RGBA blob, which no
        // decoder can read — both native calls would fail. Its dimensions
        // arrive with the request and are already correct.
        val bytes = request.bytes
        if (RawRgba.matches(bytes.size.toLong(), request.intentWidth, request.intentHeight)) {
            val bitmap = withContext(Dispatchers.Default) {
                RawRgba.preview(bytes, request.intentWidth, request.intentHeight, request.previewMaxEdge)
            }
            return Loaded(bitmap, request.intentWidth, request.intentHeight)
        }
        // Off the main thread: getImageDimensions decodes the whole image
        // rather than parsing a header, ~0.3 s and tens of MB on a 12 MP shot.
        val dims = withContext(SemperNativeLib.nativeDispatcher) {
            runCatching { SemperNativeLib.getImageDimensions(bytes) }.getOrNull()
        }?.takeIf { it.size >= 2 && it[0] > 0 && it[1] > 0 }
        val width = dims?.get(0) ?: request.intentWidth
        val height = dims?.get(1) ?: request.intentHeight
        val bitmap = withContext(SemperNativeLib.nativeDispatcher) {
            SemperNativeLib.getPreviewFromBytes(bytes, request.previewMaxEdge)
        }
        return Loaded(bitmap, width, height)
    }
}
