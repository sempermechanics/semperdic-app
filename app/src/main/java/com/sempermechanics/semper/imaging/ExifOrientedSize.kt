package com.sempermechanics.semper.imaging

import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * The pixel size a decoder that honours EXIF orientation will report, from the
 * size a decoder that ignores it reported.
 *
 * The two disagree, and the app uses both. `BitmapFactory`'s bounds-only decode
 * gives the size as stored in the file; OpenCV's `imdecode` rotates by the EXIF
 * orientation tag first (it has since 3.4.1 — `IMREAD_IGNORE_ORIENTATION`
 * exists to opt out), so for a portrait phone photo tagged ROTATE_90 the same
 * bytes measure 4080x3072 one way and 3072x4080 the other.
 *
 * That mismatch is not cosmetic: the engine decodes through OpenCV, so its view
 * is the rotated one, and any size the app records with `BitmapFactory` and then
 * compares against a size it recorded through OpenCV will disagree for the very
 * same file. Import measures deformed frames the fast way and the reference the
 * native way, which made picking one image as both sides of an analysis report
 * a size mismatch.
 */
object ExifOrientedSize {

    /**
     * True when [orientation] rotates by a quarter turn, so width and height
     * swap. The four flip/180 cases move pixels without changing the shape.
     */
    fun swapsAxes(orientation: Int): Boolean = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSVERSE,
        -> true

        else -> false
    }

    /**
     * [file]'s orientation tag, or NORMAL when it has none or cannot be read.
     * A header parse, not a decode — cheap enough to run per imported frame.
     */
    fun orientationOf(file: File): Int = runCatching {
        ExifInterface(file).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** [width]x[height] as stored in [file], turned upright. */
    fun applyTo(file: File, width: Int, height: Int): Pair<Int, Int> =
        if (swapsAxes(orientationOf(file))) height to width else width to height
}
