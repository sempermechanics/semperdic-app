package com.indicvision.semper.ui.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.data.CaptureNoiseFloor
import com.indicvision.semper.ui.analysis.NoiseFloorProbe
import com.indicvision.semper.ui.analysis.NoiseFloorStats
import com.indicvision.semper.ui.analysis.SubsetRecommender
import com.indicvision.semper.ui.analysis.VsgStudy
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Takes the static burst that decides whether this setup can measure the strain
 * about to be applied, and reduces it to a verdict.
 *
 * It runs at the end of the test shot rather than as a mode of its own, because
 * the number has to be known **before any load is applied** and a check the user
 * has to remember to run is a check that does not happen. By this point the
 * speckled specimen is mounted, framed and focused, which is what makes the
 * measurement meaningful: it is the run, minus the load.
 *
 * **The burst is captured on the run's own settings**, through
 * [LockedCameraSession] — same lens, same output size, the same frozen pipeline
 * and exposure, the same YUV → grey PNG encode. A floor measured any other way
 * describes a different camera, and a gate built on it would refuse good runs
 * and pass bad ones. That defect already exists once in this flow (the speckle
 * check runs on the vendor camera app's JPEG), and repeating it here would have
 * much worse consequences, because this measurement can stop a run.
 *
 * The first frame doubles as the pipeline warm-up the capture screen used to
 * throw away, so its cost is what sizes the rest of the burst.
 */
object NoiseFloorGate {

    /** What the burst concluded, plus what it cost and what it was judged at. */
    data class Result(
        val verdict: NoiseFloorStats.Verdict,
        /** Gauge length the floor is quoted at; a floor without one is meaningless. */
        val vsgPx: Double,
        /** Frames actually captured, which can be below the ceiling on a slow phone. */
        val framesCaptured: Int,
        /** Cost of the first still, for [CaptureCalibration]. */
        val firstFrameMs: Long,
        /**
         * The scatter drawn over the burst's own reference frame, or null when
         * the burst could not produce one. Rendered here rather than carried as
         * a field because the burst frames are deleted the moment [measure]
         * returns, and a map has to be drawn while its image still exists.
         */
        val sigmaMap: NoiseFloorSigmaMap? = null,
    )

    /**
     * Capture the burst and measure it.
     *
     * @param roiNorm the contrast ROI as fractions of the test shot, so it
     *   survives the resolution and orientation change into capture frames.
     * @param subset the speckle check's recommended subset, in capture pixels.
     * @param sourceLandscape whether the test shot the ROI was drawn on was
     *   wider than it was tall. See [uprightRoi] for what it is compared with.
     * @return null when the burst could not be captured at all — which is the
     *   existing "test shot failed" path, not a new kind of failure.
     */
    suspend fun measure(
        context: Context,
        session: LockedCameraSession,
        roiNorm: RectF,
        subset: Int,
        sourceLandscape: Boolean,
    ): Result? {
        val dir = SystemCamera.captureDir(context)
        val first = File(dir, CaptureWorkspace.burstName(0))
        first.delete()
        val startNs = System.nanoTime()
        if (!session.captureStill(first)) {
            first.delete()
            return null
        }
        val firstMs = ((System.nanoTime() - startNs) / NANOS_PER_MILLI).coerceAtLeast(1L)

        // The ceiling is six frames; a slow phone gets fewer, and the tests in
        // [NoiseFloorStats] degrade in a documented order rather than linearly.
        val wanted = NoiseFloorStats.frameCountFor(firstMs)
        val files = mutableListOf(first)
        for (index in 1 until wanted) {
            val file = File(dir, CaptureWorkspace.burstName(index))
            file.delete()
            if (!session.captureStill(file)) {
                // A frame that failed shortens the burst; it does not fail the
                // check. Fewer frames means a weaker verdict, which is honest.
                Timber.w("noise burst: frame %d failed, stopping at %d", index, files.size)
                file.delete()
                break
            }
            files.add(file)
        }
        Timber.i("noise burst: %d frames, first still %d ms", files.size, firstMs)

        return try {
            evaluate(files, roiNorm, subset, firstMs, sourceLandscape, session.frameRotationDegrees)
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Suppress("ReturnCount", "LongParameterList") // too few frames, then a first frame that would not decode
    private suspend fun evaluate(
        files: List<File>,
        roiNorm: RectF,
        subset: Int,
        firstFrameMs: Long,
        sourceLandscape: Boolean,
        frameRotationDegrees: Int,
    ): Result? {
        if (files.size < NoiseFloorStats.MIN_FRAMES) return null
        val bounds = boundsOf(files.first()) ?: return null
        val upright = uprightRoi(roiNorm, sourceLandscape, bounds.first, bounds.second, frameRotationDegrees)
        val roi = denormalize(upright, bounds.first, bounds.second)
        Timber.i(
            "noise burst frame: %dx%d roi=%s (norm %s)",
            bounds.first,
            bounds.second,
            roi.toShortString(),
            upright.toShortString(),
        )

        // The gauge the floor is quoted at: the settings a single analysis would
        // use for this subset by default. Quoting a floor without the gauge that
        // produced it is the one thing this measurement must never do.
        //
        // The gauge is the strain window itself, in pixels — it does not scale
        // with the step. See VsgStudy.vsgFor for the two device runs that
        // settled that, and for why the floor used to read several times better
        // than the settings could deliver.
        val step = VsgStudy.stepSizeFor(subset, VsgStudy.DEFAULT_STEP_DENOM)
        val vsgPx = VsgStudy.vsgFor(VsgStudy.DEFAULT_STRAIN_WINDOW).toDouble()

        val measurement = withContext(SemperNativeLib.nativeDispatcher) {
            NoiseFloorProbe.measure(
                refFile = files.first(),
                frameFiles = files.drop(1),
                roi = roi,
                subset = subset,
            )
        }
        val samples = measurement.samples
        logSeparation(samples)
        val verdict = NoiseFloorStats.evaluate(samples, vsgPx)
        Timber.i(
            "noise floor: %s %.0f ue sigma=%.4f px D=%.2f drift=%.3f px bright=%.3f frames=%d",
            verdict.outcome,
            verdict.floorMicrostrain,
            verdict.sigmaPx,
            verdict.noiseVariance,
            verdict.driftPx,
            verdict.brightnessScatter,
            verdict.frameCount,
        )
        Timber.i("noise floor gauge: subset=%d step=%d vsg=%.0f px", subset, step, vsgPx)
        // The pair that says whether D can be believed. A D far below the
        // other phone's with a high correlation beside it is a filtered
        // pipeline, not a quiet one; see NoiseFloorPixels.noiseCorrelationOf.
        Timber.i(
            "noise floor pixels: D=%.2f neighbour correlation=%.3f",
            verdict.noiseVariance,
            verdict.noiseCorrelation,
        )
        return Result(
            verdict = verdict,
            vsgPx = vsgPx,
            framesCaptured = files.size,
            firstFrameMs = firstFrameMs,
            sigmaMap = NoiseFloorMap.of(measurement.sigmaField, files.first()),
        )
    }

    /**
     * The burst's numbers laid out against frame separation, which is the one
     * thing that tells a genuinely quiet camera apart from a camera that is
     * quietly averaging.
     *
     * Every sample here is frame 0 against frame *k*, so the list is already
     * ordered by separation: one frame apart, then two, then three, then four.
     * On a static scene with independent frames the noise between two frames
     * does not care how far apart they were, so `D` and `sigma` are flat across
     * the row. An ISP running temporal noise reduction merges each frame with
     * the ones before it, which makes near neighbours far more alike than
     * distant ones — so the row climbs, and both numbers are understated at
     * every lag.
     *
     * That matters because the understatement is invisible in the verdict: the
     * floor simply comes back better than the camera can really do, and a
     * measured `D` fed to the subset recommendation would then under-size every
     * subset. No vendor key reports it and the read-back cannot catch it — the
     * HAL reports the noise-reduction mode it was asked for and runs its own
     * pipeline anyway — so the shape of this row is the only evidence there is.
     */
    private fun logSeparation(samples: List<NoiseFloorStats.PairSample>) {
        if (samples.isEmpty()) return
        Timber.i(
            "noise floor by separation: D=%s sigma=%s",
            samples.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it.noiseVariance) },
            samples.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it.sigmaPx) },
        )
    }

    /**
     * Put the ROI into the burst frame's own frame of reference.
     *
     * The ROI is carried as fractions on the assumption that the test shot and
     * the burst are both stored the way the user framed them. A vendor camera
     * app that hands back a sensor-native landscape frame while the phone is
     * held portrait breaks that, and the fractions then land a quarter turn
     * away — on *real pixels*, so nothing fails and nothing is logged as
     * wrong. The floor simply describes a part of the scene the user never
     * chose, which is the worst way for a measurement to be wrong.
     *
     * The two are compared by which way round they are, because that is the
     * only evidence available: both are decoded images with no shared
     * metadata. When they disagree, the turn to apply is not a guess — it is
     * [frameRotationDegrees], the same turn the locked session applied to make
     * its buffers upright, so a ROI still in sensor coordinates needs exactly
     * it to catch up.
     *
     * This is a live correction, not a dormant guard: on the Pixel 6 the
     * branch below fires on every burst, because its vendor camera app hands
     * back a landscape test shot while the locked session's frames are
     * portrait. The Samsung agrees without it. Both were verified against the
     * logged norms.
     */
    internal fun uprightRoi(
        roiNorm: RectF,
        sourceLandscape: Boolean,
        frameWidth: Int,
        frameHeight: Int,
        frameRotationDegrees: Int,
    ): RectF {
        if (sourceLandscape == (frameWidth > frameHeight)) return roiNorm
        Timber.w(
            "noise burst roi: test shot was %s and the frame is %s; turning the roi %d deg",
            if (sourceLandscape) "landscape" else "portrait",
            if (frameWidth > frameHeight) "landscape" else "portrait",
            frameRotationDegrees,
        )
        return rotateNorm(roiNorm, frameRotationDegrees)
    }

    /**
     * Turn a normalised rect clockwise inside the unit square.
     *
     * A quarter turn swaps the axes, so this is the corner mapping rather than
     * a matrix: both corners go through [CaptureOrientation.rotatePoint] and
     * are re-normalised into a rect, because a turned rect's corners are no
     * longer in min/max order. The point mapping is shared with the metering
     * regions rather than written twice — the two disagreeing about which way
     * a quarter turn goes is exactly the bug neither would report.
     *
     * Anything that is not a quarter turn is returned untouched, since there is
     * no sensible partial answer and a tilted rect is not a rect.
     */
    internal fun rotateNorm(roiNorm: RectF, degrees: Int): RectF {
        val (x0, y0) = CaptureOrientation.rotatePoint(roiNorm.left, roiNorm.top, degrees)
        val (x1, y1) = CaptureOrientation.rotatePoint(roiNorm.right, roiNorm.bottom, degrees)
        return RectF(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    }

    /**
     * Map a ROI expressed as fractions of the test shot onto a capture frame.
     *
     * Fractions rather than pixels because the two images are different sizes:
     * the test shot is whatever the vendor camera app chose, the burst is the
     * run's own resolution. Both are stored upright, so the mapping is a scale.
     */
    internal fun denormalize(roiNorm: RectF, width: Int, height: Int): Rect {
        val left = (roiNorm.left * width).toInt().coerceIn(0, width - 1)
        val top = (roiNorm.top * height).toInt().coerceIn(0, height - 1)
        val right = (roiNorm.right * width).toInt().coerceIn(left + 1, width)
        val bottom = (roiNorm.bottom * height).toInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    /** Fractions of [width] x [height] covered by [roi]; the inverse of [denormalize]. */
    internal fun normalize(roi: Rect, width: Int, height: Int): RectF {
        if (width <= 0 || height <= 0) return RectF(0f, 0f, 1f, 1f)
        return RectF(
            roi.left.toFloat() / width,
            roi.top.toFloat() / height,
            roi.right.toFloat() / width,
            roi.bottom.toFloat() / height,
        )
    }

    /**
     * Rescale a subset measured on the test shot into capture pixels.
     *
     * The speckle check measures the subset on the vendor camera app's JPEG,
     * which is usually a different resolution from the run. A subset is a
     * length in pixels, so carrying the number across unchanged would apply a
     * 12 MP subset to an 8 MP frame and get a different answer than intended.
     *
     * **Long edge against long edge, never width against width.** The vendor
     * camera app frequently returns a landscape frame while the run records
     * portrait, and comparing the two widths then puts a full sensor edge
     * against a short edge — a ratio that describes nothing. Both images hold
     * the same scene the same way up by the time the burst runs (see
     * [uprightRoi]), so their long edges are the corresponding lengths whatever
     * order the dimensions arrive in. The Pixel run this was found on measured
     * 3072 of 3072x4080 against 800 of 800x600 and scaled a 17 px subset down
     * by 3.8x when the true linear ratio was 5.1x, which
     * [SubsetRecommender.MIN_SUBSET] then hid by clamping.
     */
    internal fun rescaleSubset(
        subset: Int,
        fromWidth: Int,
        fromHeight: Int,
        toWidth: Int,
        toHeight: Int,
    ): Int {
        val from = maxOf(fromWidth, fromHeight)
        val to = maxOf(toWidth, toHeight)
        if (from <= 0 || to <= 0) return subset
        val scaled = (subset.toLong() * to / from).toInt()
        val odd = if (scaled % 2 == 0) scaled + 1 else scaled
        return odd.coerceIn(SubsetRecommender.MIN_SUBSET, SubsetRecommender.MAX_SUBSET)
    }

    private fun boundsOf(file: File): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}

/** Maps a burst measurement into the persisted session metadata shape. */
internal fun NoiseFloorGate.Result.toCaptureNoiseFloor(overridden: Boolean): CaptureNoiseFloor =
    CaptureNoiseFloor(
        microstrain = verdict.floorMicrostrain,
        vsgPx = vsgPx,
        sigmaPx = verdict.sigmaPx,
        frames = verdict.frameCount,
        exceeded = verdict.floorExceeded,
        overridden = overridden,
        noiseVariance = verdict.noiseVariance,
        noiseCorrelation = verdict.noiseCorrelation,
    )
