package com.indicvision.semper.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.ui.capture.CaptureCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The per-frame cost model, exercised against several synthetic device
 * profiles rather than a replay of one phone.
 *
 * The cases that matter are the ones where the old single-quotient model went
 * wrong: a small frame measured on a device whose cost is nearly all fixed
 * overhead, and that measurement then asked about a frame many times larger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureCalibrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearStoredCalibration() {
        context.getSharedPreferences("capture_calibration", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `assumes a slow device before any measurement`() {
        // 8 MP at the default slope, plus headroom.
        val expected = CaptureCalibration.DEFAULT_MS_PER_MEGAPIXEL * 8f * CaptureCalibration.SAFETY_FACTOR
        assertEquals(
            expected.toDouble(),
            CaptureCalibration.estimateFrameMs(context, W_8MP, H_8MP).toDouble(),
            expected * 0.02,
        )
    }

    @Test
    fun `a measurement on one device does not constrain another resolution linearly`() {
        // The name this test has always had, now with the assertion to match.
        // Most of a still is overhead that does not scale, so twice the pixels
        // is well short of twice the cost — the whole reason the old quotient
        // predicted seconds per frame at full resolution.
        CaptureCalibration.record(context, W_8MP, H_8MP, 800L)

        val full = CaptureCalibration.estimateFrameMs(context, W_8MP, H_8MP)
        val double = CaptureCalibration.estimateFrameMs(context, W_8MP * 2, H_8MP)

        assertTrue("a bigger frame is never cheaper", double >= full)
        assertTrue("but not twice the cost either: $double vs $full", double < full * 2.0)
    }

    @Test
    fun `a small slow sample never characterises a much larger frame`() {
        // The recorded failure: a 0.3 MP frame that took 369 ms implies 1201
        // ms per megapixel, which scaled up predicted ~14 s per frame at full
        // resolution and made the setup screen offer a tenth of a frame per
        // second. Past the extrapolation limit the fit is discarded.
        CaptureCalibration.record(context, 640, 480, 369L)

        val large = CaptureCalibration.estimateFrameMs(context, 2560, 1920)

        assertTrue("would have been ~14 s; was $large ms", large < 2_000L)
    }

    @Test
    fun `a larger frame is never predicted cheaper than a measured smaller one`() {
        // Falling back to the default model must not undercut a real timing:
        // a device that needs 369 ms for 0.3 MP will not do 4.9 MP in 250 ms.
        CaptureCalibration.record(context, 640, 480, 369L)

        assertTrue(CaptureCalibration.estimateFrameMs(context, 2560, 1920) >= 369L)
    }

    @Test
    fun `two samples whose cost falls with pixel count fit a flat slope`() {
        // Both real: 0.307 MP took 369 ms, 0.480 MP took 320 ms. A line through
        // them slopes downward, which would predict free frames at high
        // resolution. The slope flattens instead and the worse sample holds.
        CaptureCalibration.record(context, 640, 480, 369L)
        CaptureCalibration.record(context, 1600, 1200, 320L)

        val small = CaptureCalibration.estimateFrameMs(context, 640, 480)
        val large = CaptureCalibration.estimateFrameMs(context, 1600, 1200)

        assertTrue("cost may not fall with pixels: $small then $large", large >= small)
    }

    @Test
    fun `two well separated samples fit the line through them`() {
        // A profile where pixels genuinely dominate: 1 MP costs 200 ms, 8 MP
        // costs 900 ms, so the slope is 100 ms/MP and the intercept 100 ms.
        CaptureCalibration.record(context, 1000, 1000, 200L)
        CaptureCalibration.record(context, W_8MP, H_8MP, 900L)

        // 4 MP should land near 100 + 4 * 100 = 500 ms, plus headroom.
        val mid = CaptureCalibration.estimateFrameMs(context, 2000, 2000)

        assertEquals(500.0 * CaptureCalibration.SAFETY_FACTOR, mid.toDouble(), 60.0)
    }

    @Test
    fun `predictions carry safety headroom over the raw measurement`() {
        CaptureCalibration.record(context, W_8MP, H_8MP, 800L)

        // Thermal throttling and background load make later frames slower, so
        // the offer must sit above the calibration frame, never below it.
        assertTrue(CaptureCalibration.estimateFrameMs(context, W_8MP, H_8MP) > 800L)
    }

    @Test
    fun `never predicts below the floor when scaling far down`() {
        // A tiny ROI-sized frame still pays sensor and file overhead that does
        // not shrink with pixel count.
        CaptureCalibration.record(context, W_8MP, H_8MP, 800L)

        assertTrue(
            CaptureCalibration.estimateFrameMs(context, 64, 64) >= CaptureCalibration.MIN_FRAME_MS,
        )
    }

    @Test
    fun `a re-measurement at the same size is blended, not ignored`() {
        CaptureCalibration.record(context, W_8MP, H_8MP, 800L)
        val first = CaptureCalibration.estimateFrameMs(context, W_8MP, H_8MP)

        CaptureCalibration.record(context, W_8MP, H_8MP, 1600L)
        val blended = CaptureCalibration.estimateFrameMs(context, W_8MP, H_8MP)

        assertTrue("must move toward the slower sample", blended > first)
        assertTrue("but not jump straight to it", blended < 1600L * 1.2)
    }

    @Test
    fun `the estimate tracks the measured cost`() {
        CaptureCalibration.record(context, W_8MP, H_8MP, 800L)

        val perFrame = CaptureCalibration.estimateFrameMs(context, W_8MP, H_8MP)

        assertTrue("perFrame was $perFrame", perFrame >= 800L)
    }

    @Test
    fun `ignores a degenerate measurement`() {
        CaptureCalibration.record(context, 0, 0, 500L)
        CaptureCalibration.record(context, W_8MP, H_8MP, 0L)

        val expected = CaptureCalibration.DEFAULT_MS_PER_MEGAPIXEL * 8f * CaptureCalibration.SAFETY_FACTOR
        assertEquals(
            expected.toDouble(),
            CaptureCalibration.estimateFrameMs(context, W_8MP, H_8MP).toDouble(),
            expected * 0.02,
        )
    }

    @Test
    fun `the poisoned version one figure is discarded on first read`() {
        // An install that already stored the quotient must not keep it: the
        // whole point of the fix is that it reaches devices already carrying
        // the bad number.
        val store = context.getSharedPreferences("capture_calibration", Context.MODE_PRIVATE)
        store.edit().putFloat("ms_per_megapixel", 1201f).commit()

        val predicted = CaptureCalibration.estimateFrameMs(context, 2560, 1920)

        assertFalse(store.contains("ms_per_megapixel"))
        assertTrue("1201 ms/MP would have predicted ~5.9 s; was $predicted", predicted < 1_000L)
    }

    private companion object {
        const val W_8MP = 4000
        const val H_8MP = 2000
    }
}
