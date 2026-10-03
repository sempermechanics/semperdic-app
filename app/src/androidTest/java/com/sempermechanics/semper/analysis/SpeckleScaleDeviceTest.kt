package com.sempermechanics.semper.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sempermechanics.semper.ui.analysis.recommend.DicGoodPractice
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The speckle readout the wizard shows, measured the way the wizard measures
 * it: off an encoded PNG, through `BitmapRegionDecoder`.
 *
 * `SpeckleScaleTest` proves the autocorrelation against float arrays it built
 * itself. It cannot prove the part that actually ships, because
 * [SubsetRecommender.recommend] reaches the pixels through a region decoder
 * that does not exist off-device. This closes that gap: a pattern drawn at a
 * known dot size, encoded, decoded back by the real platform, and measured
 * through the real call the activity makes.
 *
 * The assertion is the band, not the decimal. What the readout has to get
 * right is which side of 3 px and 9 px the user's pattern sits on, since that
 * is what the chip tells them to go and change.
 */
@RunWith(AndroidJUnit4::class)
class SpeckleScaleDeviceTest {

    @Test
    fun aTwoPixelSpeckleReadsAsUnderResolved() {
        val measured = measure(dotDiameter = 2)
        assertTrue("measured $measured", measured < DicGoodPractice.MIN_SPECKLE_PX)
        assertEquals(DicGoodPractice.Verdict.UNDER_RESOLVED, DicGoodPractice.verdictFor(measured))
    }

    @Test
    fun aFivePixelSpeckleReadsAsUsable() {
        val measured = measure(dotDiameter = 5)
        assertEquals(
            "measured $measured",
            DicGoodPractice.Verdict.USABLE,
            DicGoodPractice.verdictFor(measured),
        )
    }

    @Test
    fun aFourteenPixelSpeckleReadsAsOverResolved() {
        val measured = measure(dotDiameter = 14)
        assertTrue("measured $measured", measured > DicGoodPractice.MAX_SPECKLE_PX)
        assertEquals(DicGoodPractice.Verdict.OVER_RESOLVED, DicGoodPractice.verdictFor(measured))
    }

    @Test
    fun theMeasurementOrdersTheThreePatternsTheWayTheyWereDrawn() {
        // The ordering is the property the chip rests on, and it has to hold
        // through the encode/decode round trip, not only in the pure math.
        val fine = measure(2)
        val good = measure(5)
        val coarse = measure(14)
        assertTrue("$fine then $good", good > fine)
        assertTrue("$good then $coarse", coarse > good)
    }

    @Test
    fun anUnpatternedFrameYieldsNoSpeckleRatherThanANumber() {
        // A blank card is the case where inventing a diameter would be worst:
        // it would put a confident readout under the slider for a specimen
        // that cannot be correlated at all.
        val blank = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        blank.eraseColor(android.graphics.Color.rgb(128, 128, 128))
        val rec = recommend(encode(blank))
        assertNotNull(rec)
        assertEquals(null, rec!!.speckleDiameterPx)
    }

    @Test
    fun aCoarsePatternAsksForMoreSubsetThanSssigAlone() {
        // The cross-check, end to end: 14 px dots need a subset spanning three
        // of them, and SSSIG on a high-contrast pattern settles well below it.
        val rec = recommend(encode(speckleField(dotDiameter = 14, seed = 11)))!!
        val wanted = rec.subsetSpanningSpeckles
        assertNotNull(wanted)
        assertTrue("SSSIG ${rec.subsetSize} vs speckle $wanted", wanted!! > rec.subsetSize)
    }

    // ------------------------------------------------------------------

    private fun measure(dotDiameter: Int): Double {
        val rec = recommend(encode(speckleField(dotDiameter, seed = 11)))
        assertNotNull("no recommendation for $dotDiameter px dots", rec)
        val measured = rec!!.speckleDiameterPx
        assertNotNull("$dotDiameter px dots measured nothing", measured)
        return measured!!
    }

    private fun recommend(bytes: ByteArray) = SubsetRecommender.recommend(
        refBytes = bytes,
        imgW = WIDTH,
        imgH = HEIGHT,
        roi = Rect(0, 0, WIDTH, HEIGHT),
    )

    private fun encode(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        // PNG: the measurement is of a length scale, and JPEG's 8x8 blocks are
        // themselves a length scale. Lossless in, or the test measures the codec.
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Discs of one diameter at about half coverage — see `SpeckleScaleTest`. */
    private fun speckleField(dotDiameter: Int, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.rgb(30, 30, 30))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(225, 225, 225)
        }
        val random = Random(seed)
        val radius = dotDiameter / 2f
        val area = Math.PI * radius * radius
        val dots = ((WIDTH * HEIGHT * COVERAGE) / area.coerceAtLeast(1.0)).roundToInt()
        repeat(dots) {
            canvas.drawCircle(
                random.nextFloat() * WIDTH,
                random.nextFloat() * HEIGHT,
                radius,
                paint,
            )
        }
        return bmp
    }

    private companion object {
        const val WIDTH = 1200
        const val HEIGHT = 900
        const val COVERAGE = 0.5
    }
}
