package com.sempermechanics.semper.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.SemperNativeLib
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The two decoders the app measures frames with must agree on a frame's size.
 *
 * `BitmapFactory` reports the raster as stored; OpenCV's `imdecode` rotates by
 * the EXIF orientation tag first. Import measures deformed frames the fast way
 * and the reference the native way, so while the fast way ignored the tag, one
 * portrait phone photo picked as *both* sides of an analysis reported a size
 * mismatch against itself.
 *
 * Instrumented rather than a unit test because only the real thing proves it:
 * the real androidx EXIF parser reading a real JPEG, against the real OpenCV
 * build in `libsemper`. [ExifOrientedSizeTest] covers the axis-swap table; this
 * covers that the tag is read at all and that the two answers now match.
 */
@RunWith(AndroidJUnit4::class)
class ExifOrientedSizeInstrumentedTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    }

    /** A [width]x[height] JPEG carrying [orientation] in its EXIF header. */
    private fun jpeg(name: String, width: Int, height: Int, orientation: Int): File {
        val file = File(dir, name)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally {
            bmp.recycle()
        }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }

    /** [file]'s size as stored, straight from a bounds-only decode. */
    private fun storedSize(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth to opts.outHeight
    }

    /** [file]'s size as the engine will see it. */
    private fun engineSize(file: File): Pair<Int, Int> {
        val dims = SemperNativeLib.getImageDimensions(file.readBytes())
        return dims[0] to dims[1]
    }

    @Test
    fun a_quarter_turn_tag_is_read_and_transposes_the_reported_size() {
        val file = jpeg("exif_rot90.jpg", 640, 480, ExifInterface.ORIENTATION_ROTATE_90)

        // The raster on disk is untouched — only the tag says to turn it.
        assertEquals(640 to 480, storedSize(file))
        assertEquals(480 to 640, ExifOrientedSize.applyTo(file, 640, 480))
    }

    @Test
    fun the_corrected_size_matches_what_the_engine_decodes() {
        // The regression itself: these two numbers disagreed, and the import
        // check compared one against the other.
        val file = jpeg("exif_rot90_engine.jpg", 640, 480, ExifInterface.ORIENTATION_ROTATE_90)
        val (w, h) = storedSize(file)

        val engine = engineSize(file)
        assertTrue("engine decode failed outright: $engine", engine.first > 0)
        assertEquals(engine, ExifOrientedSize.applyTo(file, w, h))
    }

    @Test
    fun an_upright_frame_is_left_exactly_as_it_was() {
        // Capture normalises its own JPEGs to ORIENTATION_NORMAL, so this is
        // the path every recorded run takes: the correction must be a no-op.
        val file = jpeg("exif_normal.jpg", 640, 480, ExifInterface.ORIENTATION_NORMAL)
        val (w, h) = storedSize(file)

        assertEquals(640 to 480, w to h)
        assertEquals(640 to 480, ExifOrientedSize.applyTo(file, w, h))
        assertEquals(engineSize(file), ExifOrientedSize.applyTo(file, w, h))
    }
}
