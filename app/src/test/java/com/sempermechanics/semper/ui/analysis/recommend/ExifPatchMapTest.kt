package com.sempermechanics.semper.ui.analysis.recommend

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ExifPatchMap] against OpenCV's own recipe for each EXIF orientation
 * (`ApplyExifOrientation` in imgcodecs/src/loadsave.cpp): a patch read
 * through the map must be the patch of the upright image OpenCV hands the
 * engine.
 */
class ExifPatchMapTest {

    private class Grid(val w: Int, val h: Int, val px: FloatArray) {
        operator fun get(x: Int, y: Int) = px[y * w + x]

        fun flipH() = Grid(w, h, FloatArray(w * h) { this[w - 1 - it % w, it / w] })
        fun flipV() = Grid(w, h, FloatArray(w * h) { this[it % w, h - 1 - it / w] })
        fun transpose() = Grid(h, w, FloatArray(w * h) { this[it / h, it % h] })

        fun patch(x0: Int, y0: Int, side: Int): FloatArray? {
            if (x0 !in 0..w - side || y0 !in 0..h - side) return null
            return FloatArray(side * side) { this[x0 + it % side, y0 + it / side] }
        }
    }

    /** What OpenCV does to the stored pixels for each tag. */
    private fun opencvUpright(stored: Grid, orientation: Int): Grid = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> stored.flipH()
        ExifInterface.ORIENTATION_ROTATE_180 -> stored.flipH().flipV()
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> stored.flipV()
        ExifInterface.ORIENTATION_TRANSPOSE -> stored.transpose()
        ExifInterface.ORIENTATION_ROTATE_90 -> stored.transpose().flipH()
        ExifInterface.ORIENTATION_TRANSVERSE -> stored.transpose().flipH().flipV()
        ExifInterface.ORIENTATION_ROTATE_270 -> stored.transpose().flipV()
        else -> stored
    }

    private val stored = Grid(W, H, FloatArray(W * H) { it.toFloat() })

    @Test
    fun `every orientation reads the patch OpenCV's upright image has`() {
        for (orientation in 2..8) {
            val upright = opencvUpright(stored, orientation)
            val map = checkNotNull(ExifPatchMap.forImage(orientation, upright.w, upright.h, W, H)) {
                "orientation $orientation"
            }
            for ((x0, y0) in listOf(0 to 0, 1 to 2, upright.w - SIDE to upright.h - SIDE)) {
                assertArrayEquals(
                    "orientation $orientation at ($x0, $y0)",
                    upright.patch(x0, y0, SIDE),
                    map.read(x0, y0, SIDE) { x, y, side -> stored.patch(x, y, side) },
                    0f,
                )
            }
        }
    }

    @Test
    fun `an upright image needs no map`() {
        assertNull(ExifPatchMap.forImage(ExifInterface.ORIENTATION_NORMAL, W, H, W, H))
        assertNull(ExifPatchMap.forImage(ExifInterface.ORIENTATION_UNDEFINED, W, H, W, H))
    }

    @Test
    fun `a quarter turn the decoder did not apply is left alone`() {
        // Tagged ROTATE_90, but the upright size is the stored one: OpenCV did
        // not rotate this format, so neither may the patches.
        assertNull(ExifPatchMap.forImage(ExifInterface.ORIENTATION_ROTATE_90, W, H, W, H))
        assertNotNull(ExifPatchMap.forImage(ExifInterface.ORIENTATION_ROTATE_90, H, W, W, H))
    }

    private companion object {
        const val W = 9
        const val H = 6
        const val SIDE = 4
    }
}
