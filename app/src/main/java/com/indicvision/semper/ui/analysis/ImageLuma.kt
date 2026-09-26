package com.indicvision.semper.ui.analysis

import android.media.Image
import android.media.MediaFormat
import com.indicvision.semper.imaging.GrayPngEncoder
import com.indicvision.semper.imaging.LumaRange

/**
 * The Y plane of a decoded [Image], copied out as a [GrayPngEncoder.Luma].
 *
 * Plane 0 of a `COLOR_FormatYUV420Flexible` output already is the luminance the
 * DIC engine correlates on, so taking it directly skips every YUV→RGB→gray
 * conversion and the blur that comes with them.
 */
internal object ImageLuma {

    /**
     * Copies the cropped Y plane of [image]; the caller still owns the image. A
     * [limitedRange] plane (see [isLimitedRange]) is stretched to 0–255, so video
     * frames reach the engine at the same contrast as photos and MJPEG (TD-134).
     */
    fun of(image: Image, rotationDegrees: Int, limitedRange: Boolean): GrayPngEncoder.Luma {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val crop = image.cropRect
        val width = if (crop.width() > 0) crop.width() else image.width
        val height = if (crop.height() > 0) crop.height() else image.height

        val buffer = plane.buffer.duplicate()
        val start = (crop.top * rowStride + crop.left * pixelStride).coerceIn(0, buffer.capacity())
        buffer.position(start)
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (limitedRange) LumaRange.expandInPlace(bytes)

        return GrayPngEncoder.Luma(
            bytes = bytes,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            rotationDegrees = rotationDegrees,
        )
    }

    /**
     * Whether a decoder's Y plane holds limited-range luma (TD-134). The first of
     * [formats] that names a colour range decides; with none, video's default
     * applies, which is limited. Only a stream that says full range is kept as stored.
     */
    fun isLimitedRange(vararg formats: MediaFormat?): Boolean {
        val range = formats.firstOrNull { it?.containsKey(MediaFormat.KEY_COLOR_RANGE) == true }
            ?.getInteger(MediaFormat.KEY_COLOR_RANGE)
        return range != MediaFormat.COLOR_RANGE_FULL
    }
}
