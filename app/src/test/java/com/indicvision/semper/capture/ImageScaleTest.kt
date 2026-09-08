package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.ImageScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mm-per-pixel derivation, across several synthetic device profiles.
 *
 * None of these is a real phone. They exist to pin the *arithmetic* over the
 * range of sensors, focal lengths and working distances this app will meet —
 * a small sensor behind a long lens, a large sensor behind a short one, an
 * ultra-wide — because the app must behave the same on all of them and no
 * device's numbers may be baked in.
 */
class ImageScaleTest {

    @Test
    fun `a long lens over a small sensor resolves finer than a wide one`() {
        // Same sensor, same frame, same standoff: the longer focal length
        // magnifies more, so each pixel covers less of the specimen.
        val long = known(sensorMm = 7.0f, focalMm = 6.8f, distanceM = 0.3f, longEdgePx = 4000)
        val wide = known(sensorMm = 7.0f, focalMm = 2.2f, distanceM = 0.3f, longEdgePx = 4000)
        assertTrue("$long vs $wide", long < wide)
    }

    @Test
    fun `a small sensor behind a long lens gives the expected scale`() {
        // (7.0 / 4000) * (300 - 6.8) / 6.8
        assertEquals(0.075456, known(7.0f, 6.8f, 0.3f, 4000), 1e-5)
    }

    @Test
    fun `a large sensor behind a short lens gives the expected scale`() {
        // (17.3 / 5000) * (800 - 4.5) / 4.5
        assertEquals(0.611652, known(17.3f, 4.5f, 0.8f, 5000), 1e-5)
    }

    @Test
    fun `an ultra wide at close range gives the expected scale`() {
        // (5.7 / 3000) * (120 - 1.6) / 1.6
        assertEquals(0.140600, known(5.7f, 1.6f, 0.12f, 3000), 1e-5)
    }

    @Test
    fun `halving the frame doubles what each pixel covers`() {
        val full = known(7.0f, 6.8f, 0.3f, 4000)
        val half = known(7.0f, 6.8f, 0.3f, 2000)
        assertEquals(full * 2.0, half, 1e-9)
    }

    @Test
    fun `standing twice as far back roughly doubles the scale`() {
        // Exactly (s - f) scaling, so slightly more than double once the focal
        // length is subtracted from both.
        val near = known(7.0f, 6.8f, 0.4f, 4000)
        val far = known(7.0f, 6.8f, 0.8f, 4000)
        assertTrue("$near then $far", far > near * 2.0)
    }

    @Test
    fun `a device that will not report its sensor size has no scale`() {
        assertUnavailable(ImageScale.of(null, 6.8f, 0.3f, 4000))
    }

    @Test
    fun `a shot with no focal length has no scale`() {
        assertUnavailable(ImageScale.of(7.0f, null, 0.3f, 4000))
    }

    @Test
    fun `the common case of no subject distance is unavailable, not an error`() {
        // Most phones report focus distance as UNCALIBRATED and write no EXIF
        // tag at all. This is the expected path on a great many devices.
        assertUnavailable(ImageScale.of(7.0f, 6.8f, null, 4000))
    }

    @Test
    fun `an unknown frame size has no scale`() {
        assertUnavailable(ImageScale.of(7.0f, 6.8f, 0.3f, 0))
    }

    @Test
    fun `a distance outside arm's reach of a specimen is refused`() {
        // 2 cm is inside any phone's close focus; 40 m is not a specimen.
        assertUnavailable(ImageScale.of(7.0f, 6.8f, 0.02f, 4000))
        assertUnavailable(ImageScale.of(7.0f, 6.8f, 40f, 4000))
    }

    @Test
    fun `a focal length or sensor size no phone has is refused`() {
        assertUnavailable(ImageScale.of(7.0f, 0.4f, 0.3f, 4000))
        assertUnavailable(ImageScale.of(7.0f, 400f, 0.3f, 4000))
        assertUnavailable(ImageScale.of(0.2f, 6.8f, 0.3f, 4000))
        assertUnavailable(ImageScale.of(90f, 6.8f, 0.3f, 4000))
    }

    @Test
    fun `a non-finite input is refused rather than propagated`() {
        assertUnavailable(ImageScale.of(Float.NaN, 6.8f, 0.3f, 4000))
        assertUnavailable(ImageScale.of(7.0f, Float.NaN, 0.3f, 4000))
        assertUnavailable(ImageScale.of(7.0f, 6.8f, Float.POSITIVE_INFINITY, 4000))
    }

    @Test
    fun `a derived scale outside the plausible range is refused`() {
        // Inputs each individually in range, product absurd: a 50 mm sensor
        // 5 m from the specimen behind a 1 mm lens is 4.9 mm per pixel on a
        // 50 px frame — arithmetic, not a photograph.
        assertUnavailable(ImageScale.of(50f, 1f, 5f, 50))
    }

    @Test
    fun `the scale follows the frame it is quoted for`() {
        assertEquals(0.2, ImageScale.scaledTo(0.1, fromLongEdge = 4000, toLongEdge = 2000)!!, 1e-9)
        assertEquals(0.05, ImageScale.scaledTo(0.1, fromLongEdge = 2000, toLongEdge = 4000)!!, 1e-9)
    }

    @Test
    fun `an unknown frame leaves the scale unscaled rather than guessing`() {
        assertNull(ImageScale.scaledTo(0.1, fromLongEdge = 0, toLongEdge = 2000))
        assertNull(ImageScale.scaledTo(0.1, fromLongEdge = 2000, toLongEdge = 0))
        assertNull(ImageScale.scaledTo(Double.NaN, fromLongEdge = 2000, toLongEdge = 2000))
    }

    private fun known(sensorMm: Float, focalMm: Float, distanceM: Float, longEdgePx: Int): Double {
        val result = ImageScale.of(sensorMm, focalMm, distanceM, longEdgePx)
        assertTrue("expected a scale, got $result", result is ImageScale.Result.Known)
        return (result as ImageScale.Result.Known).mmPerPx
    }

    private fun assertUnavailable(result: ImageScale.Result) {
        assertEquals(ImageScale.Result.Unavailable, result)
    }
}
