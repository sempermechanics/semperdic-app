package com.indicvision.semper.ui.capture

import com.indicvision.semper.ui.analysis.DicGoodPractice
import com.indicvision.semper.ui.analysis.SpeckleScale
import kotlin.math.abs

/**
 * Whether the resolution about to be recorded at can actually measure this
 * specimen's speckle — asked before the run, not discovered after it.
 *
 * The speckle check runs on the vendor camera app's test shot; the run records
 * at the size chosen on the setup screen, and on the device this was found on
 * the two were a factor of five apart on the long edge. Nothing re-checked
 * anything in between, so a pattern that resolved perfectly well when it was
 * measured was recorded at a size where it did not resolve at all, and the
 * first sign of it was a burst that correlated with nothing.
 *
 * ### Both ends of the band are actionable
 *
 * Under [DicGoodPractice.MIN_SPECKLE_PX] the run will not correlate, and the
 * answer is a larger frame or a coarser pattern. Over
 * [DicGoodPractice.MAX_SPECKLE_PX] the run will correlate perfectly well and is
 * paying for it: the extra pixels carry no extra correlation but cost read-out
 * and encode time on every frame, so a *smaller* frame gives the same
 * measurement at a higher rate. Both are worth saying, and the second one is
 * the reason "high resolution or high frame rate" has an answer here rather
 * than being left to the user as a trade-off.
 *
 * Everything specimen- and device-shaped is measured: the speckle from the test
 * shot, the frame sizes from the camera, the scale from EXIF. The only fixed
 * numbers are the published band in [DicGoodPractice].
 */
internal object CaptureSuitability {

    /**
     * What the plan resolution does to this specimen's speckle, when that is
     * something worth telling the user about.
     */
    data class Verdict(
        /** Speckle diameter as the run would record it, in plan pixels. */
        val speckleOnPlanPx: Double,
        /** Which end of the band it breaks. Never [DicGoodPractice.Verdict.USABLE]. */
        val band: DicGoodPractice.Verdict,
        /** Long edge that would put the speckle at the recommended size. */
        val recommendedLongEdge: Int,
        /** The offered size closest to that, or null when none could be found. */
        val recommended: CameraCapabilities.Resolution?,
        /** Speckle size in millimetres, or null when no scale could be derived. */
        val speckleMm: Double?,
        /** The 3 / 5 / 9 px band in millimetres, or null alongside [speckleMm]. */
        val bandMm: Triple<Double, Double, Double>?,
    )

    /**
     * The verdict for a plan, or null when there is nothing to say — the
     * speckle lands inside the band, or it could not be measured at all.
     *
     * Null for "unmeasurable" rather than a warning, because a check that fires
     * whenever it cannot see is a check the user learns to dismiss. The
     * uncorrelated-burst dialog still catches the case where the pattern really
     * was unresolvable; this one only speaks when it knows something.
     *
     * @param speckleOnTestShotPx from [SpeckleScale.diameterPx], in the test
     *   shot's own pixels.
     * @param scale from [ImageScale.of] for the *test shot*; rescaled here onto
     *   the plan frame, so the millimetre figures describe the same speckle.
     */
    fun of(
        speckleOnTestShotPx: Double?,
        testShotLongEdge: Int,
        plan: CameraCapabilities.Resolution,
        offered: List<CameraCapabilities.Resolution>,
        scale: ImageScale.Result,
    ): Verdict? {
        val planLongEdge = maxOf(plan.width, plan.height)
        val onPlan = speckleOnTestShotPx
            ?.let { SpeckleScale.scaledTo(it, testShotLongEdge, planLongEdge) }
        val range = onPlan?.let { DicGoodPractice.usefulLongEdges(it, planLongEdge) }
        // The three ways there is nothing to say: no speckle was measured, the
        // measurement cannot support the arithmetic, or the plan is already
        // fine. All silence, in one decision.
        if (onPlan == null || range == null || DicGoodPractice.verdictFor(onPlan) == DicGoodPractice.Verdict.USABLE) {
            return null
        }
        val band = DicGoodPractice.verdictFor(onPlan)
        // Millimetres per pixel is quoted for the test shot, so it has to be
        // carried onto the plan frame before the speckle is converted: the same
        // dot covers the same millimetres either way, and it is the pixels that
        // changed size.
        val mmPerPx = (scale as? ImageScale.Result.Known)
            ?.let { ImageScale.scaledTo(it.mmPerPx, testShotLongEdge, planLongEdge) }
        return Verdict(
            speckleOnPlanPx = onPlan,
            band = band,
            recommendedLongEdge = range.recommended,
            recommended = nearestOffered(offered, range.recommended),
            speckleMm = mmPerPx?.let { onPlan * it },
            bandMm = mmPerPx?.let { DicGoodPractice.bandInMillimetres(it) },
        )
    }

    /**
     * The catalogued size closest to [longEdgePx].
     *
     * Nearest on the long edge, matching [CaptureResolutionPicker.select], so
     * the size named in the dialog is the size the spinner lands on when the
     * user takes the recommendation. Naming one and selecting another would be
     * its own small betrayal.
     */
    private fun nearestOffered(
        offered: List<CameraCapabilities.Resolution>,
        longEdgePx: Int,
    ): CameraCapabilities.Resolution? =
        offered.minByOrNull { abs(maxOf(it.width, it.height) - longEdgePx) }
}
