package com.sempermechanics.semper.ui.analysis.recommend

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.sempermechanics.semper.field.ImageSize
import timber.log.Timber
import kotlin.math.max

/**
 * Reads grey levels straight out of a burst frame, without going near the
 * correlator.
 *
 * Split from [NoiseFloorProbe] because it answers a different question with
 * different failure modes. The probe asks what the *engine* made of a pair of
 * frames; this asks what is actually in the pixels — the image noise variance
 * `D(η)` the subset recommendation should be solving against, and the mean
 * level that exposes flicker. Neither needs a solve, and both must survive a
 * frame the platform decoder will not open.
 *
 * Every entry point returns null or NaN rather than throwing. A window that
 * would not decode costs the caller one input to the median, not a crashed test
 * shot.
 */
internal object NoiseFloorPixels {

    /**
     * `D(η) = var(I₁ − I₂) / 2` — the image noise variance the subset
     * recommendation should be solving against instead of a lab camera's.
     *
     * The two frames are of a static scene, so their difference is twice the
     * per-frame noise; halving the variance of that difference recovers one
     * frame's worth. NaN when either window could not be read, which the caller
     * treats as "no measurement" and falls back to the paper's constant.
     */
    @Suppress("ReturnCount") // a window that would not decode, then two that do not match
    fun noiseVarianceOf(ref: FloatArray?, def: FloatArray?): Double {
        if (ref == null || def == null) return Double.NaN
        if (ref.size != def.size || ref.isEmpty()) return Double.NaN
        var sum = 0.0
        var sumSq = 0.0
        for (i in ref.indices) {
            val d = (ref[i] - def[i]).toDouble()
            sum += d
            sumSq += d * d
        }
        val mean = sum / ref.size
        return max(0.0, sumSq / ref.size - mean * mean) / 2.0
    }

    /**
     * How alike neighbouring pixels of the difference image are, as a Pearson
     * correlation between each pixel and the one to its right.
     *
     * This is the one measurement that tells an honestly quiet camera apart
     * from a camera that is quietly smoothing. Sensor noise is independent
     * pixel to pixel, so the difference of two static frames is white and this
     * comes back near zero — a little above it, because demosaic and the YUV
     * downscale both mix neighbours slightly, on every phone and by a similar
     * amount. A spatial denoiser running underneath the pipeline lockdown
     * replaces each pixel with a weighted average of the ones around it, which
     * drives this number up hard.
     *
     * It matters because the noise variance the subset recommendation solves
     * against assumes white noise. Correlated noise makes `D(η)` come back far
     * smaller than the error the correlator will actually see — the frames look
     * clean and correlate badly — and no Camera2 key reports it: the HAL
     * answers with the noise-reduction mode it was asked for and runs its own
     * graph anyway.
     *
     * NaN when the windows could not be read or are not square, which the
     * caller treats as "not measured" rather than as "not denoising".
     */
    @Suppress("ReturnCount") // unreadable windows, mismatched windows, a non-square window
    fun noiseCorrelationOf(ref: FloatArray?, def: FloatArray?): Double {
        if (ref == null || def == null) return Double.NaN
        if (ref.size != def.size || ref.isEmpty()) return Double.NaN
        val edge = kotlin.math.sqrt(ref.size.toDouble()).toInt()
        if (edge < MIN_WINDOW_EDGE || edge * edge != ref.size) return Double.NaN

        var n = 0
        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0
        for (i in ref.indices) {
            // The last column of each row has no right-hand neighbour; pairing
            // it with the next row's first pixel would correlate across a wrap.
            if (i % edge == edge - 1) continue
            val a = (ref[i] - def[i]).toDouble()
            val b = (ref[i + 1] - def[i + 1]).toDouble()
            sumA += a
            sumB += b
            sumAA += a * a
            sumBB += b * b
            sumAB += a * b
            n++
        }
        if (n == 0) return Double.NaN
        val covariance = sumAB / n - (sumA / n) * (sumB / n)
        val varA = max(0.0, sumAA / n - (sumA / n) * (sumA / n))
        val varB = max(0.0, sumBB / n - (sumB / n) * (sumB / n))
        val denominator = kotlin.math.sqrt(varA * varB)
        // A perfectly flat difference has no variance to correlate. That is not
        // evidence of smoothing, it is the absence of evidence either way.
        return if (denominator <= 0.0) Double.NaN else covariance / denominator
    }

    /**
     * A full-resolution square window at the centre of [region], as grey levels.
     *
     * Full resolution matters: downsampling averages neighbouring pixels and
     * would shrink the measured variance by the sampling factor squared, which
     * is exactly the quantity being measured. A bounded window keeps that
     * honest without decoding a 50 MP frame — [WINDOW_MAX_EDGE] pixels is far
     * more than a variance estimate needs.
     */
    @Suppress("ReturnCount") // a window too small to sample, then a failed decode
    fun grayWindow(bytes: ByteArray, region: Rect): FloatArray? {
        val edge = minOf(WINDOW_MAX_EDGE, region.width(), region.height())
        if (edge < MIN_WINDOW_EDGE) return null
        val left = region.centerX() - edge / 2
        val top = region.centerY() - edge / 2
        val window = Rect(left, top, left + edge, top + edge)
        val bmp = decodeRegion(bytes, window) ?: return null
        return try {
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            // The burst frames are grey PNGs, so any channel is the grey level.
            FloatArray(pixels.size) { (pixels[it] and CHANNEL_MASK).toFloat() }
        } finally {
            bmp.recycle()
        }
    }

    private fun decodeRegion(bytes: ByteArray, window: Rect): Bitmap? = runCatching {
        // Nullable on purpose: the API 31+ overload is annotated non-null, but
        // the older one can hand back null and both land here.
        @Suppress("DEPRECATION")
        val decoder: BitmapRegionDecoder? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }
        // BitmapRegionDecoder is not Closeable below API 31, so it is recycled
        // by hand rather than with use {}.
        try {
            decoder?.decodeRegion(window, BitmapFactory.Options())
        } finally {
            decoder?.recycle()
        }
    }.onFailure { Timber.d(it, "noise probe: region decode unavailable") }.getOrNull()

    /** Decoded dimensions of an encoded frame, without decoding the pixels. */
    fun boundsOf(bytes: ByteArray): ImageSize? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return ImageSize(opts.outWidth, opts.outHeight).takeIf { it.isKnown }
    }

    /** Below this a window has too few pixels for a variance worth having. */
    private const val MIN_WINDOW_EDGE = 4
    private const val WINDOW_MAX_EDGE = 512
    private const val CHANNEL_MASK = 0xFF
}
