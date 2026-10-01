@file:Suppress("MagicNumber")

package com.indicvision.semper.analysis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.indicvision.semper.ui.analysis.recommend.SubsetRecommender
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * A phone photo is usually stored sideways with an EXIF tag that turns it
 * upright. The reference size and the ROI come from OpenCV, which applies the
 * tag; the patches were read through decoders that do not, so they sampled
 * another part of the frame (or nothing) than the ROI the user drew.
 *
 * Each fixture is a JPEG whose speckle is in one corner of the stored pixels
 * and flat grey everywhere else, so only the right patches can see texture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubsetRecommenderExifTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A [storedW] x [storedH] JPEG, speckled where [speckled] says, tagged [orientation]. */
    private fun jpeg(storedW: Int, storedH: Int, orientation: Int, speckled: (Int, Int) -> Boolean): ByteArray {
        val rng = Random(7)
        val cells = IntArray((storedW / 3 + 1) * (storedH / 3 + 1)) { if (rng.nextBoolean()) 230 else 25 }
        val bmp = Bitmap.createBitmap(storedW, storedH, Bitmap.Config.ARGB_8888)
        for (y in 0 until storedH) {
            for (x in 0 until storedW) {
                val v = if (speckled(x, y)) cells[(y / 3) * (storedW / 3 + 1) + x / 3] else 128
                bmp.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val file = temp.newFile()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file.readBytes()
    }

    @Test
    fun `a photo turned half a turn is sampled where the ROI is`() {
        // Speckle in the stored top-left: upright, it is the bottom-right.
        val bytes = jpeg(300, 200, ExifInterface.ORIENTATION_ROTATE_180) { x, y -> x < 150 && y < 100 }

        val result = SubsetRecommender.recommend(bytes, 300, 200, Rect(160, 110, 290, 190))

        assertNotNull(result)
        assertFalse("sampled the flat stored bottom-right instead", result!!.lowTexture)
        assertNotNull(result.speckleDiameterPx)
    }

    @Test
    fun `a portrait photo stored sideways is sampled where the ROI is`() {
        // Stored 300 x 200, ROTATE_90: upright 200 x 300. Stored x >= 200 is
        // the upright bottom band, y >= 200, which the stored image does not
        // even reach.
        val bytes = jpeg(300, 200, ExifInterface.ORIENTATION_ROTATE_90) { x, _ -> x >= 200 }

        val result = SubsetRecommender.recommend(bytes, 200, 300, Rect(10, 210, 190, 290))

        assertNotNull("no patch could be read", result)
        assertFalse(result!!.lowTexture)
        assertNotNull(result.speckleDiameterPx)
    }
}
