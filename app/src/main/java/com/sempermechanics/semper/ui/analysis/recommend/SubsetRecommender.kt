package com.sempermechanics.semper.ui.analysis.recommend

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.imaging.RawRgba
import timber.log.Timber

/**
 * Theoretical starting subset size, after Pan, Xie, Wang, Qian and Wang,
 * "Study on subset size selection in digital image correlation for speckle
 * patterns", Opt. Express 16(10), 7037–7048 (2008).
 *
 * The paper models the standard-deviation error of a DIC displacement as
 *
 *     sigma(u) = sqrt( D(eta) / SSSIG_x )          (Eqs. 18 and 19)
 *
 * where `D(eta)` is the variance of the image noise and SSSIG — the Sum of
 * Square of Subset Intensity Gradients — is `sum(f_x^2)` over the subset
 * (`sum(f_y^2)` for the v-displacement). Inverting it turns a target accuracy
 * into the SSSIG a subset has to reach:
 *
 *     SSSIG_min = D(eta) / sigma^2
 *
 * The paper's own validation (§5.1, §5.4) uses a noise variance of 4 and
 * reports a theoretical SD error of 0.007 px; those are the values used here,
 * giving a threshold of 4 / 0.007^2 ≈ 8.16e4 (the paper rounds it to 1e5).
 *
 * The selection loop is Fig. 3 of the paper: start small, grow the subset by
 * 2 px, stop as soon as the SSSIG clears the threshold in *both* directions.
 * Because the answer depends on the local speckle contrast, the loop is run at
 * a grid of sample points across the ROI and the median is recommended.
 */
object SubsetRecommender {

    /** D(eta): variance of the image noise, in squared gray levels (paper §5.1). */
    const val NOISE_VARIANCE = 4.0

    /** Target standard-deviation error of the displacement, in pixels (paper §5.4). */
    const val TARGET_SD_ERROR_PX = 0.007

    /** SSSIG a subset must reach for [TARGET_SD_ERROR_PX] under [NOISE_VARIANCE]. */
    const val SSSIG_THRESHOLD = NOISE_VARIANCE / (TARGET_SD_ERROR_PX * TARGET_SD_ERROR_PX)

    /**
     * The SSSIG a subset must reach for [TARGET_SD_ERROR_PX] under a noise
     * variance measured on this phone, under this light, instead of the paper's
     * lab camera.
     *
     * **A measurement can only ever raise the threshold, never lower it.** The
     * model behind it assumes the image noise is independent pixel to pixel,
     * and a phone that is quietly smoothing its frames breaks that assumption
     * in the one direction that matters: `D(η)` comes back far below the error
     * the correlator will actually see, which would recommend a subset smaller
     * than the paper's own default on evidence that is not real. Clamping at
     * [NOISE_VARIANCE] makes the measurement able to fix the failure it was
     * brought in for — a phone whose noise is *worse* than the lab camera's,
     * where the recommended subset is too small — while being unable to cause
     * the opposite one.
     *
     * **Nothing calls this with a real measurement today.** The in-app camera
     * that produced one was removed, so every caller takes the default and the
     * threshold is the paper's constant. The parameter and the clamp stay
     * because the reasoning above is what any future measurement — from an
     * import-side probe, say — would have to satisfy; see
     * [NoiseFloorPixels.noiseCorrelationOf], which measures the smoothing but
     * currently has no threshold and no caller either.
     *
     * A non-finite or non-positive value means no measurement, and falls back.
     */
    fun thresholdFor(noiseVariance: Double): Double {
        val usable = if (noiseVariance.isFinite() && noiseVariance > 0.0) noiseVariance else NOISE_VARIANCE
        return maxOf(usable, NOISE_VARIANCE) / (TARGET_SD_ERROR_PX * TARGET_SD_ERROR_PX)
    }

    /** Matches the subset slider's range/step in activity_static_analysis.xml. */
    const val MIN_SUBSET = 15
    const val MAX_SUBSET = 121

    /** Sample points are laid out on a GRID x GRID lattice inside the ROI. */
    private const val GRID = 4

    /**
     * Share of the strongest patch's gradient energy a patch needs for its
     * speckle reading to count. On the steel set the patches on the bar sit
     * at 0.83–1.0 of the strongest and those straddling its edge at ≤ 0.55.
     */
    private const val TEXTURED_FRACTION = 2.0 / 3.0

    /** Rec.601 luma weights — the same conversion the native engine uses. */
    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    private const val BYTES_PER_RGBA_PIXEL = RawRgba.BYTES_PER_PIXEL

    /** ~32 MP, i.e. 128 MB as ARGB_8888 — the ceiling for a whole-image decode. */
    private const val MAX_FULL_DECODE_PIXELS = 32L * 1024 * 1024
    private const val CHANNEL_MASK = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val OFFSET_R = 0
    private const val OFFSET_G = 1
    private const val OFFSET_B = 2

    /** True when a [side]-wide patch anchored at (x0, y0) lies inside w x h. */
    private fun patchFits(x0: Int, y0: Int, side: Int, w: Int, h: Int): Boolean =
        x0 >= 0 && y0 >= 0 && x0 + side <= w && y0 + side <= h

    /**
     * What a recommendation is solved for, as opposed to what it is solved on.
     *
     * The two travel together because they answer the same question — how
     * accurate the result has to be, and at what sizes that is allowed to be
     * bought — and a caller that sets one without thinking about the other
     * usually meant to set both.
     *
     * @param sizes the allowed subset sizes, i.e. the slider's own range.
     * @param noiseVariance `D(η)` measured on this device's own frames, when
     *   the run captured them. See [thresholdFor] for why a measurement can
     *   only ever make the subset larger. The default is the paper's constant,
     *   which is what an imported analysis gets — it has no burst behind it.
     */
    data class Tuning(
        val sizes: IntRange = MIN_SUBSET..MAX_SUBSET,
        val noiseVariance: Double = NOISE_VARIANCE,
    )

    /**
     * @param subsetSize the recommended (odd) subset size in pixels
     * @param samples how many ROI points were evaluated
     * @param cappedSamples how many of them never reached the SSSIG threshold
     *   and were capped at the largest allowed subset
     * @param focusNormX normalized x of the strongest-contrast sample (0..1)
     * @param focusNormY normalized y of the strongest-contrast sample (0..1)
     * @param speckleDiameterPx median speckle diameter across the same sample
     *   points, in pixels of this image, or null when no point could be
     *   measured. See [SpeckleScale]; this is a different question from the
     *   subset size and is reported separately rather than folded into it.
     */
    data class Recommendation(
        val subsetSize: Int,
        val samples: Int,
        val cappedSamples: Int,
        val focusNormX: Float = 0.5f,
        val focusNormY: Float = 0.5f,
        val speckleDiameterPx: Double? = null,
    ) {
        /** True when the speckle is too weak for the target accuracy at any allowed size. */
        val lowTexture: Boolean get() = samples > 0 && cappedSamples * 2 >= samples

        /** Where the measured speckle sits against the iDICs band, if it was measured. */
        val speckleVerdict: DicGoodPractice.Verdict?
            get() = speckleDiameterPx?.let { DicGoodPractice.verdictFor(it) }

        /**
         * The subset size the measured speckle asks for — enough to span
         * [DicGoodPractice.MIN_SPECKLES_PER_SUBSET] dots — or null when the
         * speckle was not measurable.
         *
         * Deliberately *not* compared against [subsetSize] here. This is a
         * requirement, and whether it is met depends on the size the user has
         * since dialled in, which the recommendation cannot know.
         */
        val subsetSpanningSpeckles: Int?
            get() = speckleDiameterPx?.let { DicGoodPractice.subsetForSpeckle(it) }
    }

    /**
     * Smallest subset size in `[minSize, maxSize]` whose SSSIG clears
     * [threshold] in x and y, evaluated on a square grayscale [patch] of side
     * `maxSize + 2` (one pixel of margin for the central differences).
     * Returns [maxSize] when the threshold is never reached.
     */
    fun subsetSizeForPatch(
        patch: FloatArray,
        patchSide: Int,
        minSize: Int,
        maxSize: Int,
        threshold: Double = SSSIG_THRESHOLD,
    ): Int {
        require(patchSide == maxSize + 2) { "patch must carry a 1 px gradient margin" }
        val maxHalf = maxSize / 2
        val center = patchSide / 2

        // gx, gy by central difference (paper §3), indexed by offset from the
        // patch center so the growth loop can walk out ring by ring.
        fun gradSquares(dx: Int, dy: Int): Pair<Double, Double> {
            val x = center + dx
            val y = center + dy
            val gx = (patch[y * patchSide + x + 1] - patch[y * patchSide + x - 1]) / 2.0
            val gy = (patch[(y + 1) * patchSide + x] - patch[(y - 1) * patchSide + x]) / 2.0
            return gx * gx to gy * gy
        }

        var sssigX = 0.0
        var sssigY = 0.0
        val accumulate = { dx: Int, dy: Int ->
            val (sx, sy) = gradSquares(dx, dy)
            sssigX += sx
            sssigY += sy
        }
        accumulate(0, 0)

        for (half in 1..maxHalf) {
            // Grow by one ring (Chebyshev distance `half`): top and bottom rows
            // in full, then the two side columns without their corners.
            for (dx in -half..half) {
                accumulate(dx, -half)
                accumulate(dx, half)
            }
            for (dy in -half + 1..half - 1) {
                accumulate(-half, dy)
                accumulate(half, dy)
            }
            val size = 2 * half + 1
            if (size >= minSize && sssigX >= threshold && sssigY >= threshold) return size
        }
        return maxSize
    }

    /**
     * Recommends a subset size for [refBytes] (the encoded reference image, or
     * a raw RGBA buffer of `imgW * imgH * 4` bytes) sampled inside [roi], with
     * [sizes] giving the allowed subset sizes (the slider's range).
     * Returns null when the image cannot be sampled or the ROI is too small.
     *
     * @param tuning what the recommendation is solved for — the sizes on offer
     *   and the noise it has to beat.
     */
    @Suppress("ReturnCount", "NestedBlockDepth")
    fun recommend(
        refBytes: ByteArray,
        imgW: Int,
        imgH: Int,
        roi: Rect,
        tuning: Tuning = Tuning(),
    ): Recommendation? {
        val minSize = tuning.sizes.first
        val maxSize = tuning.sizes.last
        if (imgW <= 0 || imgH <= 0) return null

        val region = Rect(roi)
        if (!region.intersect(Rect(0, 0, imgW, imgH))) return null

        // The largest subset we can actually measure inside the region, keeping
        // the 1 px central-difference margin. Kept odd.
        val fits = minOf(region.width(), region.height()) - 2
        val cappedMax = minOf(maxSize, if (fits % 2 == 0) fits - 1 else fits)
        if (cappedMax < minSize) return null

        val threshold = thresholdFor(tuning.noiseVariance)
        val source = patchSourceFor(refBytes, imgW, imgH) ?: return null
        try {
            val side = cappedMax + 2
            val halfPatch = side / 2
            val perPoint = ArrayList<Int>(GRID * GRID)
            // Smallest recommended subset = strongest local contrast → focus there.
            var bestSize = Int.MAX_VALUE
            var bestCx = region.centerX()
            var bestCy = region.centerY()

            // Speckle diameters from the same patches, so the measurement
            // costs no extra decoding: the patch is already square, already
            // full-resolution and already inside the ROI, which is exactly
            // what SpeckleScale needs.
            val speckles = ArrayList<Pair<Double, Double?>>(GRID * GRID)

            for (row in 0 until GRID) {
                for (col in 0 until GRID) {
                    val cx = region.left + ((2 * col + 1) * region.width()) / (2 * GRID)
                    val cy = region.top + ((2 * row + 1) * region.height()) / (2 * GRID)
                    // Clamped to the ROI, not the image: on a thin ROI (a beam's
                    // side face) a patch centred on a sample point would
                    // otherwise reach into the background, and its edge would
                    // read as a speckle tens of pixels across. side <= the
                    // ROI's short edge (cappedMax above), so the range is valid.
                    val x0 = (cx - halfPatch).coerceIn(region.left, region.right - side)
                    val y0 = (cy - halfPatch).coerceIn(region.top, region.bottom - side)
                    val patch = source.readGray(x0, y0, side) ?: continue
                    val size = subsetSizeForPatch(patch, side, minSize, cappedMax, threshold)
                    perPoint.add(size)
                    speckles.add(gradientEnergy(patch, side) to SpeckleScale.diameterPx(patch))
                    if (size < bestSize) {
                        bestSize = size
                        bestCx = cx
                        bestCy = cy
                    }
                }
            }

            if (perPoint.isEmpty()) return null
            perPoint.sort()
            return Recommendation(
                subsetSize = perPoint[perPoint.size / 2],
                samples = perPoint.size,
                cappedSamples = perPoint.count { it >= cappedMax },
                focusNormX = bestCx.toFloat() / imgW.coerceAtLeast(1),
                focusNormY = bestCy.toFloat() / imgH.coerceAtLeast(1),
                speckleDiameterPx = texturedSpeckleMedian(speckles),
            )
        } finally {
            source.close()
        }
    }

    /**
     * The median speckle diameter over the patches that carry the pattern,
     * from (gradient energy, diameter) per patch.
     *
     * Only patches with at least [TEXTURED_FRACTION] of the strongest patch's
     * gradient energy count. Before an ROI is drawn the whole frame is
     * sampled, and a patch that is mostly dark surround with the specimen's
     * edge across it measures that edge — ~100 px on the steel set, against
     * 4.8 px on the bar. Such a patch holds a fraction of the pattern's
     * gradient, so it falls below the cut. Inside an ROI every patch is on the
     * pattern and all of them count.
     *
     * Median, not mean: a patch on a bare corner of the specimen measures a
     * speckle the size of the whole window, and one such outlier would drag
     * an average clean out of the band.
     */
    internal fun texturedSpeckleMedian(samples: List<Pair<Double, Double?>>): Double? {
        val strongest = samples.maxOfOrNull { it.first } ?: return null
        val diameters = samples
            .filter { it.first >= strongest * TEXTURED_FRACTION }
            .mapNotNull { it.second }
            .sorted()
        return diameters.getOrNull(diameters.size / 2)
    }

    /** Mean squared central-difference gradient over the patch's interior. */
    private fun gradientEnergy(patch: FloatArray, side: Int): Double {
        var sum = 0.0
        for (y in 1 until side - 1) {
            for (x in 1 until side - 1) {
                val gx = (patch[y * side + x + 1] - patch[y * side + x - 1]) / 2.0
                val gy = (patch[(y + 1) * side + x] - patch[(y - 1) * side + x]) / 2.0
                sum += gx * gx + gy * gy
            }
        }
        val interior = (side - 2) * (side - 2)
        return if (interior > 0) sum / interior else 0.0
    }

    // ------------------------------------------------------------------
    // Grayscale patch sources
    // ------------------------------------------------------------------

    private interface PatchSource {
        /** Grayscale (0..255) square patch, or null when it cannot be read. */
        fun readGray(x0: Int, y0: Int, side: Int): FloatArray?
        fun close()
    }

    @Suppress("ReturnCount")
    private fun patchSourceFor(bytes: ByteArray, w: Int, h: Int): PatchSource? {
        // RAW/DNG frames arrive already decoded as an RGBA buffer.
        if (RawRgba.matches(bytes.size.toLong(), w, h)) {
            return RgbaSource(bytes, w, h)
        }
        // Region decoding keeps full-resolution gradients without ever holding
        // the whole (possibly 50 MP) bitmap in memory.
        runCatching {
            // Nullable on purpose: the API 31+ overload is annotated non-null,
            // but the older one can hand back null and both land here.
            @Suppress("DEPRECATION")
            val decoder: BitmapRegionDecoder? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
            } else {
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            }
            if (decoder != null) return upright(RegionSource(decoder), bytes, w, h, decoder.width, decoder.height)
        }.onFailure {
            // Some formats (like TIFF) are supported by OpenCV but not by
            // BitmapRegionDecoder. We'll try to decode the whole bitmap.
            Timber.d("Region decoding unavailable (format not supported?); falling back to full decode")
        }

        // Whole-bitmap fallbacks hold w*h*4 bytes at once. Past the budget the
        // suggestion is not worth an OOM — the slider keeps its default.
        if (w.toLong() * h.toLong() > MAX_FULL_DECODE_PIXELS) {
            Timber.d("Reference too large (%d x %d) to decode whole; no subset suggestion", w, h)
            return null
        }

        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()?.let { stored ->
            return upright(BitmapSource(stored), bytes, w, h, stored.width, stored.height)
        }
        // Last resort: OpenCV decodes what the platform cannot (TIFF above
        // all). Full width, so the gradients stay at native resolution, and
        // already upright, as the engine sees it.
        // JNI — the caller must already be on the native dispatcher.
        return runCatching { SemperNativeLib.getPreviewFromBytes(bytes, w) }.getOrNull()?.let { BitmapSource(it) }
    }

    /**
     * [stored], a decoder that ignores EXIF orientation, read in the upright
     * [w] x [h] pixels the ROI and sample points are in ([ExifPatchMap]).
     */
    @Suppress("LongParameterList") // the image as measured both ways
    private fun upright(
        stored: PatchSource,
        bytes: ByteArray,
        w: Int,
        h: Int,
        storedW: Int,
        storedH: Int,
    ): PatchSource {
        val map = ExifPatchMap.forImage(ExifPatchMap.orientationOf(bytes), w, h, storedW, storedH) ?: return stored
        return OrientedSource(stored, map)
    }

    private fun grayFromPixels(pixels: IntArray, count: Int): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) {
            val p = pixels[i]
            out[i] = LUMA_R * ((p shr RED_SHIFT) and CHANNEL_MASK) +
                LUMA_G * ((p shr GREEN_SHIFT) and CHANNEL_MASK) +
                LUMA_B * (p and CHANNEL_MASK)
        }
        return out
    }

    private class RgbaSource(
        private val bytes: ByteArray,
        private val w: Int,
        private val h: Int,
    ) : PatchSource {
        override fun readGray(x0: Int, y0: Int, side: Int): FloatArray? {
            if (!patchFits(x0, y0, side, w, h)) return null
            val out = FloatArray(side * side)
            for (y in 0 until side) {
                var src = ((y0 + y) * w + x0) * BYTES_PER_RGBA_PIXEL
                var dst = y * side
                for (x in 0 until side) {
                    out[dst] = LUMA_R * (bytes[src + OFFSET_R].toInt() and CHANNEL_MASK) +
                        LUMA_G * (bytes[src + OFFSET_G].toInt() and CHANNEL_MASK) +
                        LUMA_B * (bytes[src + OFFSET_B].toInt() and CHANNEL_MASK)
                    src += BYTES_PER_RGBA_PIXEL
                    dst++
                }
            }
            return out
        }

        override fun close() = Unit
    }

    private class RegionSource(private val decoder: BitmapRegionDecoder) : PatchSource {
        private val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        @Suppress("ReturnCount")
        override fun readGray(x0: Int, y0: Int, side: Int): FloatArray? {
            val bmp = runCatching {
                decoder.decodeRegion(Rect(x0, y0, x0 + side, y0 + side), opts)
            }.getOrNull() ?: return null
            if (bmp.width < side || bmp.height < side) {
                bmp.recycle()
                return null
            }
            val pixels = IntArray(side * side)
            bmp.getPixels(pixels, 0, side, 0, 0, side, side)
            bmp.recycle()
            return grayFromPixels(pixels, side * side)
        }

        override fun close() {
            runCatching { decoder.recycle() }
        }
    }

    private class OrientedSource(private val stored: PatchSource, private val map: ExifPatchMap) : PatchSource {
        override fun readGray(x0: Int, y0: Int, side: Int): FloatArray? = map.read(x0, y0, side, stored::readGray)

        override fun close() = stored.close()
    }

    private class BitmapSource(private val bmp: Bitmap) : PatchSource {
        override fun readGray(x0: Int, y0: Int, side: Int): FloatArray? {
            if (!patchFits(x0, y0, side, bmp.width, bmp.height)) return null
            val pixels = IntArray(side * side)
            bmp.getPixels(pixels, 0, side, x0, y0, side, side)
            return grayFromPixels(pixels, side * side)
        }

        override fun close() {
            bmp.recycle()
        }
    }
}
