package com.indicvision.semper.ui.capture

import android.content.Context
import androidx.core.content.edit
import timber.log.Timber
import kotlin.math.abs

/**
 * Learns how long one locked still actually costs on *this* device, so the
 * setup screen offers frame counts the hardware can really deliver.
 *
 * This is the software half of the per-frame cost. The sensor's own read-out
 * floor is the other half and comes from Camera2 instead
 * ([CameraCapabilities.Info.minFrameMs]); [CaptureFrameCost] combines them.
 *
 * Nothing here is tuned to a particular phone. Camera2 reports a JPEG stall
 * duration, but the still path encodes grayscale PNG in software
 * ([GrayPngEncoder]) and never touches the hardware JPEG encoder, so that
 * figure describes a pipeline this app does not use — and it varies wildly
 * between vendors regardless. The only trustworthy number is one measured on
 * the device in front of us.
 *
 * ### Why the cost is not one number
 *
 * This used to store a single ms-per-megapixel quotient, attributing the whole
 * frame to pixel count. Two measurements taken minutes apart on one device
 * refute that outright: 0.307 MP took 369 ms and 0.480 MP took 320 ms — **the
 * larger frame was faster**. Almost all of a still is fixed overhead (request,
 * sensor round trip, file open and write) that does not shrink with pixels, so
 * a quotient measured on a small frame is enormous and, scaled back up, predicts
 * seconds per frame at full resolution. The setup screen then offered a tenth of
 * a frame per second, and the figure persisted across restarts.
 *
 * So the cost is modelled as `fixedMs + slopeMsPerMp * MP`, fitted from the two
 * most widely separated samples this device has produced, and never trusted far
 * outside the range those samples cover. Both parameters are learned at runtime:
 * there is no per-device table here, and nothing below reads a model name.
 */
object CaptureCalibration {

    /**
     * Assumed slope before any measurement, and the model used again whenever
     * the fitted one is being asked about a frame far larger than anything it
     * has seen. Set well above every device measured so far rather than at a
     * typical value: the first run of the app on unknown hardware must not
     * over-promise.
     */
    const val DEFAULT_MS_PER_MEGAPIXEL = 90f

    /**
     * Assumed fixed overhead when only one frame size has been measured, so
     * that sample is not forced to explain the whole cost through its pixel
     * count alone. Capped at the sample itself: overhead cannot exceed a total.
     */
    const val DEFAULT_FIXED_MS = 200f

    /**
     * No device completes the full sensor→buffer→encode→write round trip
     * faster than this, whatever the arithmetic says. Guards the prediction
     * when scaling a measurement down to a much smaller resolution, where the
     * per-frame overhead that does not shrink with pixel count would
     * otherwise be scaled away.
     */
    const val MIN_FRAME_MS = 80L

    /**
     * Headroom on predictions. Thermal throttling, a busy CPU and background
     * work all make a later frame slower than the calibration frame, and the
     * asymmetry matters: a slightly pessimistic offer loses a frame or two, an
     * optimistic one stalls the capture.
     */
    const val SAFETY_FACTOR = 1.15f

    /**
     * How far apart two samples must be in megapixels before the line through
     * them is worth believing. Two nearby points fit their slope from what is
     * mostly scheduling noise, and that slope is then extrapolated.
     */
    const val MIN_ANCHOR_MP_RATIO = 2.0f

    /**
     * How far past the largest measured frame the fit may be used. Beyond it
     * the fit is discarded for the conservative default model: a 0.3 MP sample
     * must never be allowed to characterise a 12 MP frame in either direction.
     */
    const val MAX_EXTRAPOLATION = 4.0f

    private const val PREFS = "capture_calibration"

    /** Bumped whenever the stored shape changes; older data is discarded. */
    private const val SCHEMA_VERSION = 2
    private const val KEY_SCHEMA = "schema_version"
    private const val KEY_LO_MP = "anchor_lo_mp"
    private const val KEY_LO_MS = "anchor_lo_ms"
    private const val KEY_HI_MP = "anchor_hi_mp"
    private const val KEY_HI_MS = "anchor_hi_ms"

    /** The single quotient version 1 stored. Deleted on upgrade, never read. */
    private const val KEY_LEGACY_MS_PER_MP = "ms_per_megapixel"

    private const val PIXELS_PER_MEGAPIXEL = 1_000_000f

    /** Smooths one-off scheduling noise without ignoring a real change. */
    private const val SMOOTHING = 0.5f

    /** Two samples this close in megapixels are the same anchor, re-measured. */
    private const val ANCHOR_SAME_TOLERANCE = 0.1f

    /** A still slower than this is a stall or a paused process, not a cost. */
    private const val MAX_SAMPLE_MS = 60_000f

    /** One measured still: how many megapixels it was, and what it cost. */
    private data class Anchor(val megapixels: Float, val ms: Float)

    /** The fitted line, plus the samples that constrain where it may be used. */
    internal data class Fit(
        val fixedMs: Float,
        val slopeMsPerMegapixel: Float,
        val maxAnchorMp: Float,
        /** Every sample the fit was built from, as megapixels to milliseconds. */
        val anchors: List<Pair<Float, Float>>,
    ) {
        fun predict(mp: Float): Float = fixedMs + slopeMsPerMegapixel * mp
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Discards anything stored under an older schema, including the version 1
     * quotient. Without this an existing install keeps the poisoned figure and
     * the fix never reaches the device it was written for.
     */
    private fun ensureSchema(context: Context) {
        val store = prefs(context)
        if (store.getInt(KEY_SCHEMA, 0) == SCHEMA_VERSION) return
        val hadLegacy = store.contains(KEY_LEGACY_MS_PER_MP)
        store.edit {
            clear()
            putInt(KEY_SCHEMA, SCHEMA_VERSION)
        }
        if (hadLegacy) Timber.i("capture calibration: discarded the version 1 ms-per-megapixel figure")
    }

    /**
     * Folds one measured still into the stored figures. [measuredMs] must be
     * the whole capture — sensor, encode and write — for [width] × [height].
     *
     * The two anchors kept are the smallest and largest frames this device has
     * been measured at, because they are what constrain the line. A sample at a
     * size already anchored is blended into that anchor; one strictly between
     * the two cannot widen the fit and is logged rather than folded into an
     * endpoint it was not measured at.
     */
    fun record(context: Context, width: Int, height: Int, measuredMs: Long) {
        if (width <= 0 || height <= 0 || measuredMs <= 0L) return
        ensureSchema(context)
        val sample = Anchor(
            megapixels = megapixels(width, height),
            ms = measuredMs.toFloat().coerceAtMost(MAX_SAMPLE_MS),
        )
        val lo = anchorAt(context, KEY_LO_MP, KEY_LO_MS)
        val hi = anchorAt(context, KEY_HI_MP, KEY_HI_MS)
        if (lo == null || hi == null) {
            writeAnchor(context, KEY_LO_MP, KEY_LO_MS, sample)
            writeAnchor(context, KEY_HI_MP, KEY_HI_MS, sample)
            return
        }
        when {
            sameSize(sample, lo) -> writeAnchor(context, KEY_LO_MP, KEY_LO_MS, blend(lo, sample))
            sameSize(sample, hi) -> writeAnchor(context, KEY_HI_MP, KEY_HI_MS, blend(hi, sample))
            sample.megapixels < lo.megapixels -> writeAnchor(context, KEY_LO_MP, KEY_LO_MS, sample)
            sample.megapixels > hi.megapixels -> writeAnchor(context, KEY_HI_MP, KEY_HI_MS, sample)
            else -> Timber.i(
                "capture calibration: %.2f MP sits between the anchors; keeping the wider pair",
                sample.megapixels,
            )
        }
    }

    /** Predicted cost of one still at [width] × [height], in milliseconds. */
    fun estimateFrameMs(context: Context, width: Int, height: Int): Long {
        val mp = megapixels(width, height)
        val fit = fitOf(context)
        val modelled = when {
            fit == null -> defaultModel(mp)
            mp > fit.maxAnchorMp * MAX_EXTRAPOLATION -> {
                Timber.i(
                    "capture calibration: %.2f MP is past %.1fx the largest sample (%.2f MP); using the default",
                    mp,
                    MAX_EXTRAPOLATION,
                    fit.maxAnchorMp,
                )
                defaultModel(mp)
            }

            else -> fit.predict(mp)
        }
        // A larger frame is never cheaper than a smaller one this device has
        // actually been timed at. Without this the default model can undercut a
        // real measurement and promise a rate the hardware has already refused.
        val measuredAtOrBelow = fit?.anchors.orEmpty()
            .filter { it.first <= mp }
            .maxOfOrNull { it.second } ?: 0f
        val predicted = maxOf(modelled, measuredAtOrBelow) * SAFETY_FACTOR
        return predicted.toLong().coerceAtLeast(MIN_FRAME_MS)
    }

    /**
     * The line through this device's samples, or null before any still has been
     * timed.
     *
     * With two anchors far enough apart the slope and intercept come from them
     * directly. A slope at or below zero means cost fell as pixels rose — real,
     * and the honest reading is that pixel count is not what this frame costs —
     * so the slope is flattened to zero and the intercept holds the worse of
     * the two, rather than a negative slope predicting free frames.
     */
    internal fun fitOf(context: Context): Fit? {
        ensureSchema(context)
        val lo = anchorAt(context, KEY_LO_MP, KEY_LO_MS) ?: return null
        val hi = anchorAt(context, KEY_HI_MP, KEY_HI_MS) ?: return null
        val anchors = listOf(lo.megapixels to lo.ms, hi.megapixels to hi.ms)
        val widest = maxOf(lo.megapixels, hi.megapixels)
        if (hi.megapixels < lo.megapixels * MIN_ANCHOR_MP_RATIO) {
            // One usable size only: hold the assumed overhead and let the
            // sample explain whatever is left, never less than nothing.
            val single = if (hi.ms >= lo.ms) hi else lo
            val fixed = minOf(DEFAULT_FIXED_MS, single.ms)
            val slope = ((single.ms - fixed) / single.megapixels).coerceAtLeast(0f)
            return Fit(fixed, slope, widest, anchors)
        }
        val rawSlope = (hi.ms - lo.ms) / (hi.megapixels - lo.megapixels)
        return if (rawSlope <= 0f) {
            Fit(maxOf(lo.ms, hi.ms), 0f, widest, anchors)
        } else {
            Fit((lo.ms - rawSlope * lo.megapixels).coerceAtLeast(0f), rawSlope, widest, anchors)
        }
    }

    private fun defaultModel(mp: Float): Float = DEFAULT_MS_PER_MEGAPIXEL * mp

    private fun sameSize(a: Anchor, b: Anchor): Boolean {
        val reference = maxOf(a.megapixels, b.megapixels)
        if (reference <= 0f) return true
        return abs(a.megapixels - b.megapixels) / reference <= ANCHOR_SAME_TOLERANCE
    }

    private fun blend(stored: Anchor, sample: Anchor) = Anchor(
        megapixels = sample.megapixels,
        ms = stored.ms * (1f - SMOOTHING) + sample.ms * SMOOTHING,
    )

    private fun anchorAt(context: Context, mpKey: String, msKey: String): Anchor? {
        val store = prefs(context)
        if (!store.contains(mpKey) || !store.contains(msKey)) return null
        val mp = store.getFloat(mpKey, 0f)
        val ms = store.getFloat(msKey, 0f)
        return if (mp > 0f && ms > 0f) Anchor(mp, ms) else null
    }

    private fun writeAnchor(context: Context, mpKey: String, msKey: String, anchor: Anchor) {
        prefs(context).edit {
            putInt(KEY_SCHEMA, SCHEMA_VERSION)
            putFloat(mpKey, anchor.megapixels)
            putFloat(msKey, anchor.ms)
        }
    }

    private fun megapixels(width: Int, height: Int): Float =
        width.coerceAtLeast(1).toFloat() * height.coerceAtLeast(1) / PIXELS_PER_MEGAPIXEL
}
