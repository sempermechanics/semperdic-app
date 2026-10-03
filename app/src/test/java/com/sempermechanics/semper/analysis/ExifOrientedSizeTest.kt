package com.sempermechanics.semper.analysis

import com.sempermechanics.semper.imaging.ExifOrientedSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Orientation values are the EXIF tag's own numbering (TIFF 6.0 §Orientation),
 * written as literals so the test states the spec rather than restating the
 * constants it is checking.
 */
class ExifOrientedSizeTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `quarter turns swap the axes`() {
        assertTrue("ROTATE_90", ExifOrientedSize.swapsAxes(6))
        assertTrue("ROTATE_270", ExifOrientedSize.swapsAxes(8))
        assertTrue("TRANSPOSE", ExifOrientedSize.swapsAxes(5))
        assertTrue("TRANSVERSE", ExifOrientedSize.swapsAxes(7))
    }

    @Test
    fun `flips and half turns keep the shape`() {
        assertFalse("NORMAL", ExifOrientedSize.swapsAxes(1))
        assertFalse("FLIP_HORIZONTAL", ExifOrientedSize.swapsAxes(2))
        assertFalse("ROTATE_180", ExifOrientedSize.swapsAxes(3))
        assertFalse("FLIP_VERTICAL", ExifOrientedSize.swapsAxes(4))
    }

    @Test
    fun `an undefined or out-of-range tag is left alone`() {
        assertFalse(ExifOrientedSize.swapsAxes(0))
        assertFalse(ExifOrientedSize.swapsAxes(9))
        assertFalse(ExifOrientedSize.swapsAxes(-1))
    }

    @Test
    fun `a file with no readable EXIF keeps the size it was given`() {
        // The regression this guards: anything that cannot be parsed must not
        // silently transpose a frame's dimensions, or the reference-match
        // check would start failing on files it used to accept.
        val notAnImage = folder.newFile("frame.bin").apply { writeText("not an image") }

        assertEquals(4080 to 3072, ExifOrientedSize.applyTo(notAnImage, 4080, 3072))
    }
}
