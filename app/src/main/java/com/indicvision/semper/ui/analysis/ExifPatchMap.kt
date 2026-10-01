package com.indicvision.semper.ui.analysis

import androidx.exifinterface.media.ExifInterface
import com.indicvision.semper.imaging.ExifOrientedSize
import java.io.ByteArrayInputStream

/**
 * Reads square patches of an EXIF-oriented image from a decoder that ignores
 * the orientation.
 *
 * OpenCV turns an image upright by its EXIF tag before anything measures it,
 * so the reference's size, the ROI and every sample point the wizard has are
 * upright pixels ([ExifOrientedSize]). `BitmapRegionDecoder` and
 * `BitmapFactory` return the pixels as stored. Reading an upright patch
 * through them took the stored pixels at the same coordinates: on a portrait
 * photo, another part of the frame (or none of it), so the subset suggestion
 * measured speckle the user had not selected.
 *
 * The orientation is applied the way OpenCV's `ApplyExifOrientation` does it
 * (a transpose and/or flips), so a patch read here is pixel for pixel the
 * patch the engine sees.
 *
 * @param storedWidth the image's width as stored, before the orientation
 * @param storedHeight its height as stored
 */
internal class ExifPatchMap(
    private val orientation: Int,
    private val storedWidth: Int,
    private val storedHeight: Int,
) {

    /** Stored x of the upright pixel ([xo], [yo]). */
    fun storedX(xo: Int, yo: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_ROTATE_180 -> storedWidth - 1 - xo
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90 -> yo
        ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270 -> storedWidth - 1 - yo
        else -> xo
    }

    /** Stored y of the upright pixel ([xo], [yo]). */
    fun storedY(xo: Int, yo: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_VERTICAL, ExifInterface.ORIENTATION_ROTATE_180 -> storedHeight - 1 - yo
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_270 -> xo
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE -> storedHeight - 1 - xo
        else -> yo
    }

    /**
     * The upright [side] x [side] patch at ([x0], [y0]), row-major, read
     * through [readStored], which reads a patch of the stored pixels. The
     * stored patch covers the same pixels, since every orientation maps a
     * square onto a square; null when [readStored] cannot read it.
     */
    fun read(x0: Int, y0: Int, side: Int, readStored: (Int, Int, Int) -> FloatArray?): FloatArray? {
        val last = side - 1
        val sx0 = minOf(storedX(x0, y0), storedX(x0 + last, y0 + last))
        val sy0 = minOf(storedY(x0, y0), storedY(x0 + last, y0 + last))
        val stored = readStored(sx0, sy0, side) ?: return null
        val out = FloatArray(side * side)
        for (j in 0 until side) {
            for (i in 0 until side) {
                val xs = storedX(x0 + i, y0 + j) - sx0
                val ys = storedY(x0 + i, y0 + j) - sy0
                out[j * side + i] = stored[ys * side + xs]
            }
        }
        return out
    }

    companion object {
        /** True when [orientation] moves pixels at all. */
        fun isRotated(orientation: Int): Boolean =
            orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED

        /** The EXIF orientation of an encoded image, or NORMAL when it has none or cannot be read. */
        fun orientationOf(bytes: ByteArray): Int = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        /**
         * The map for an image OpenCV measured as [uprightWidth] x
         * [uprightHeight] and a decoder reads as [storedWidth] x
         * [storedHeight], or null when no mapping is needed or the sizes
         * disagree with the tag (a format OpenCV does not rotate: its
         * upright size is then the stored one).
         */
        fun forImage(
            orientation: Int,
            uprightWidth: Int,
            uprightHeight: Int,
            storedWidth: Int,
            storedHeight: Int,
        ): ExifPatchMap? {
            val swaps = ExifOrientedSize.swapsAxes(orientation)
            val expectedW = if (swaps) uprightHeight else uprightWidth
            val expectedH = if (swaps) uprightWidth else uprightHeight
            val agrees = storedWidth == expectedW && storedHeight == expectedH
            return if (isRotated(orientation) && agrees) ExifPatchMap(orientation, storedWidth, storedHeight) else null
        }
    }
}
