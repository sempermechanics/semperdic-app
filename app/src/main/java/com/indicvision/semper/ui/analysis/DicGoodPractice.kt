package com.indicvision.semper.ui.analysis

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The speckle-size numbers from the iDICs *Good Practices Guide for Digital
 * Image Correlation*, and what they imply for the resolution a run should be
 * recorded at.
 *
 * These are the only literal thresholds in this work, and they are properties
 * of the method rather than of any phone: a subset correlates on the gradients
 * inside it, and a dot rendered across too few pixels has none worth solving
 * on. Everything device-shaped — what this specimen's speckle actually
 * measures, which resolutions the camera offers, what each costs per frame —
 * is measured at the moment of use and passed in.
 *
 * ### Why there is a ceiling as well as a floor
 *
 * The floor is the familiar half of the guidance: below [MIN_SPECKLE_PX] the
 * pattern aliases, AKAZE finds nothing to seed on and the solve returns no
 * points at all. The ceiling is the half that usually goes unsaid, and on a
 * phone it is the more useful one. Past [MAX_SPECKLE_PX] the pattern is
 * oversampled — the extra pixels carry no extra correlation, but they cost
 * read-out and encode time on every frame, which is paid directly in capture
 * rate. So an over-resolved plan has a *lower* resolution to recommend, and
 * recommending it buys frames per second for nothing. That is what turns
 * "high resolution or high frame rate" from a trade-off into a solvable
 * question with one answer.
 */
object DicGoodPractice {

    /**
     * Below this a speckle is not resolved: too few pixels across a dot to
     * carry the intensity gradient a subset correlates on.
     */
    const val MIN_SPECKLE_PX = 3.0

    /** What to aim for. Comfortably clear of aliasing, nowhere near wasteful. */
    const val RECOMMENDED_SPECKLE_PX = 5.0

    /**
     * Above this the pattern is oversampled: no better correlated, and paid
     * for in frame cost and therefore in capture rate.
     */
    const val MAX_SPECKLE_PX = 9.0

    /**
     * Speckles a subset must span. Fewer than this and the subset's pattern is
     * close to a single feature, which correlates well against the wrong place
     * as readily as the right one.
     */
    const val MIN_SPECKLES_PER_SUBSET = 3

    /** Where a measured speckle sits against the band. */
    enum class Verdict {
        /** Under [MIN_SPECKLE_PX]: the run will not correlate. */
        UNDER_RESOLVED,

        /** Inside the band. Nothing to say. */
        USABLE,

        /** Over [MAX_SPECKLE_PX]: correct, but paying frame rate for nothing. */
        OVER_RESOLVED,
    }

    /**
     * The plan long edges that keep a speckle measuring [diameterPx] at
     * [atLongEdge] inside the band, and the one that puts it at
     * [RECOMMENDED_SPECKLE_PX].
     *
     * Speckle size scales with the frame, so this is a proportion throughout:
     * a dot spanning `d` pixels on an `L0` px edge spans `d * L / L0` on an
     * `L` px one. Null when the measurement cannot support the arithmetic.
     */
    fun usefulLongEdges(diameterPx: Double, atLongEdge: Int): LongEdgeRange? {
        if (!diameterPx.isFinite() || diameterPx <= 0.0 || atLongEdge <= 0) return null
        val perPixel = atLongEdge / diameterPx
        return LongEdgeRange(
            minimum = ceil(MIN_SPECKLE_PX * perPixel).toInt(),
            recommended = (RECOMMENDED_SPECKLE_PX * perPixel).roundToInt(),
            maximum = (MAX_SPECKLE_PX * perPixel).toInt(),
        )
    }

    /** The band expressed as frame long edges, for one measured speckle. */
    data class LongEdgeRange(val minimum: Int, val recommended: Int, val maximum: Int)

    /** Where [diameterPx] — already scaled to the plan's own frame — sits. */
    fun verdictFor(diameterPx: Double): Verdict = when {
        diameterPx < MIN_SPECKLE_PX -> Verdict.UNDER_RESOLVED
        diameterPx > MAX_SPECKLE_PX -> Verdict.OVER_RESOLVED
        else -> Verdict.USABLE
    }

    /**
     * A subset spanning [MIN_SPECKLES_PER_SUBSET] speckles, snapped odd and
     * into the range the engine will accept.
     *
     * Odd because the engine centres a subset on a pixel and an even size has
     * no centre; clamped because [SubsetRecommender] owns what the solver can
     * be asked for, and a size outside that is not a subset it would run.
     */
    fun subsetForSpeckle(diameterPx: Double): Int? {
        if (!diameterPx.isFinite() || diameterPx <= 0.0) return null
        val spanning = ceil(diameterPx * MIN_SPECKLES_PER_SUBSET).toInt()
        val odd = if (spanning % 2 == 0) spanning + 1 else spanning
        return odd.coerceIn(SubsetRecommender.MIN_SUBSET, SubsetRecommender.MAX_SUBSET)
    }

    /**
     * Speckle sizes in millimetres for the band, given a scale.
     *
     * Reported so the user can fix the *pattern* rather than only the setting:
     * when no offered resolution can put this speckle in the band, the answer
     * is a coarser or finer pattern, and that is a number in millimetres at the
     * bench, not in pixels.
     */
    fun bandInMillimetres(mmPerPx: Double): Triple<Double, Double, Double> =
        Triple(MIN_SPECKLE_PX * mmPerPx, RECOMMENDED_SPECKLE_PX * mmPerPx, MAX_SPECKLE_PX * mmPerPx)
}
