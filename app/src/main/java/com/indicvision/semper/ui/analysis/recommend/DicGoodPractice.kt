package com.indicvision.semper.ui.analysis.recommend

import kotlin.math.ceil

/**
 * The speckle-size numbers from the iDICs *Good Practices Guide for Digital
 * Image Correlation*, and what they say about the frames a user has imported.
 *
 * These are the only literal thresholds in this work, and they are properties
 * of the method rather than of any camera: a subset correlates on the gradients
 * inside it, and a dot rendered across too few pixels has none worth solving
 * on. Everything specimen-shaped — what this pattern actually measures on this
 * frame — is measured at the moment of use by [SpeckleScale] and passed in.
 *
 * ### Why there is a ceiling as well as a floor
 *
 * The floor is the familiar half of the guidance: below [MIN_SPECKLE_PX] the
 * pattern aliases, AKAZE finds nothing to seed on and the solve returns few
 * points or none. The ceiling is the half that usually goes unsaid. Past
 * [MAX_SPECKLE_PX] the pattern is oversampled — the extra pixels carry no
 * extra correlation, but a subset must now be large to span
 * [MIN_SPECKLES_PER_SUBSET] dots, and a large subset is paid for directly in
 * spatial resolution: fewer independent measurement points across the same
 * ROI, and real strain gradients smeared across the subset. So both edges of
 * the band cost the user something, and which edge they are near decides what
 * they should change.
 *
 * Both answers are things the user fixes at the bench — camera distance, lens,
 * or the pattern itself — which is why they are worth surfacing before a run
 * rather than after it.
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
     * for in the subset size needed to span it, and therefore in how many
     * independent points the ROI yields.
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
        /** Under [MIN_SPECKLE_PX]: the pattern aliases and may not correlate. */
        UNDER_RESOLVED,

        /** Inside the band. Nothing the user needs to change. */
        USABLE,

        /** Over [MAX_SPECKLE_PX]: correct, but paying spatial resolution for nothing. */
        OVER_RESOLVED,
    }

    /** Where [diameterPx], measured on the frame being analysed, sits. */
    fun verdictFor(diameterPx: Double): Verdict = when {
        diameterPx < MIN_SPECKLE_PX -> Verdict.UNDER_RESOLVED
        diameterPx > MAX_SPECKLE_PX -> Verdict.OVER_RESOLVED
        else -> Verdict.USABLE
    }

    /**
     * A subset spanning [MIN_SPECKLES_PER_SUBSET] speckles, snapped odd, or
     * null when no subset the engine accepts can span that many.
     *
     * Odd because the engine centres a subset on a pixel and an even size has
     * no centre. Below [SubsetRecommender.MIN_SUBSET] the answer is raised to
     * the floor — a larger subset than asked for still spans the speckles. A
     * requirement above [SubsetRecommender.MAX_SUBSET] returns null instead of
     * the clamp: the clamped value does *not* span three speckles, and
     * reporting it would tell the user a size is sufficient when it is not.
     * Null means "this pattern is too coarse for any subset", which is the
     * honest answer and is what the [Verdict.OVER_RESOLVED] chip already says.
     *
     * This is a *cross-check* on the SSSIG recommendation, not a replacement
     * for it. SSSIG asks whether a subset carries enough gradient for the
     * target accuracy; this asks whether it carries enough distinct features
     * to be unambiguous. A subset can pass the first and fail the second on a
     * coarse pattern, and that is exactly the case worth telling the user
     * about.
     */
    fun subsetForSpeckle(diameterPx: Double): Int? {
        if (!diameterPx.isFinite() || diameterPx <= 0.0) return null
        val spanning = ceil(diameterPx * MIN_SPECKLES_PER_SUBSET).toInt()
        val odd = if (spanning % 2 == 0) spanning + 1 else spanning
        return odd.coerceAtLeast(SubsetRecommender.MIN_SUBSET)
            .takeIf { it <= SubsetRecommender.MAX_SUBSET }
    }
}
