package com.indicvision.semper.ui.analysis.recommend

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Measures how big the speckles are, in pixels of the frame being analysed.
 *
 * Nothing else in the app knows this number. [SubsetRecommender] measures
 * SSSIG — how much gradient a patch carries — and answers with a subset size,
 * which is a different question. SSSIG is a sum over the subset, so a pattern
 * that is too fine to resolve can still total enough gradient to clear the
 * threshold once the subset is grown, and the recommendation comes back
 * looking healthy while every dot in it spans two pixels. Speckle size is the
 * property the iDICs guidance is written against ([DicGoodPractice]), and it
 * is the one a user can act on at the bench — by moving the camera or
 * respraying the pattern — rather than by moving a slider.
 *
 * ### How the size is measured
 *
 * From the image's own autocorrelation. `rho(k)` is the Pearson correlation
 * between the window and a copy of itself shifted `k` pixels, averaged over the
 * two axes. At zero lag it is 1 by construction; it falls as the shift carries
 * a speckle off itself, and the lag where it passes half is a length scale of
 * the pattern rather than of the camera. That is the same normalised-covariance
 * loop [NoiseFloorPixels.noiseCorrelationOf] already runs at lag 1, generalised
 * to arbitrary lag and to a single frame.
 *
 * The half-height *width* is not the dot diameter, and the two must not be
 * confused. For an ideal disc of diameter `d` the autocorrelation is
 * `rho(r) = (2/pi)(acos t - t*sqrt(1 - t^2))` with `t = r/d`, which reaches 0.5
 * at `t = 0.4037` — so the width across the half-height point is `0.807 d`, and
 * the measured half-width is scaled by [AUTOCORR_TO_DIAMETER] to recover the
 * diameter. Real speckle is not a field of ideal discs, so that constant is
 * checked against a seeded synthetic pattern in `SpeckleScaleTest` rather than
 * trusted from the derivation alone.
 */
object SpeckleScale {

    /**
     * Half-height width of a disc's autocorrelation, as a fraction of its
     * diameter. See the class KDoc for where 0.807 comes from.
     */
    const val AUTOCORR_TO_DIAMETER = 0.807

    /** The correlation level whose crossing defines the speckle's width. */
    private const val HALF_HEIGHT = 0.5

    /**
     * Lags to walk. A pattern whose correlation has not halved by here is
     * either a very coarse speckle or no speckle at all, and both answers are
     * "not measurable from this window" rather than a number.
     */
    private const val MAX_LAG = 64

    /** Below this a window has too few pixels for a correlation worth having. */
    private const val MIN_WINDOW_EDGE = 16

    /**
     * Speckle diameter in pixels of [window], or null when the window cannot
     * answer.
     *
     * @param window square grey levels, row-major — the patch
     *   [SubsetRecommender] already reads at each of its ROI sample points, or
     *   anything [NoiseFloorPixels.grayWindow] returns. It must be at full
     *   resolution and undownsampled: averaging neighbours changes the very
     *   length scale being measured.
     */
    @Suppress("ReturnCount") // a window that is too small, not square, or flat
    fun diameterPx(window: FloatArray?): Double? {
        if (window == null) return null
        val edge = sqrt(window.size.toDouble()).toInt()
        if (edge < MIN_WINDOW_EDGE || edge * edge != window.size) return null
        val lastLag = minOf(MAX_LAG, edge / 2)
        if (lastLag < 1) return null

        var previous = 1.0
        for (lag in 1..lastLag) {
            val rho = autocorrelationAt(window, edge, lag) ?: return null
            if (rho < HALF_HEIGHT) {
                // Linear interpolation between the last lag above the half
                // height and this one below it, so a 4 px speckle and a 5 px
                // speckle are not both reported as the same integer.
                val span = previous - rho
                val fraction = if (span <= 0.0) 0.0 else (previous - HALF_HEIGHT) / span
                val halfWidth = (lag - 1) + fraction
                return halfWidth * 2.0 / AUTOCORR_TO_DIAMETER
            }
            previous = rho
        }
        // Still correlated with itself at half the window: the speckle is
        // larger than this window can measure, which is not a number.
        return null
    }

    /**
     * Pearson correlation between the window and itself shifted [lag] pixels,
     * averaged over the horizontal and vertical directions.
     *
     * Both directions because a pattern can be smeared by motion or by a
     * rolling shutter along one axis alone, and averaging keeps one bad axis
     * from being read as a fine speckle. Rows and columns nearer the edge than
     * [lag] are skipped rather than wrapped: a wrapped pair correlates the two
     * sides of the image, which are not neighbours.
     */
    private fun autocorrelationAt(window: FloatArray, edge: Int, lag: Int): Double? {
        val horizontal = correlate(window, edge, lag, byRow = true)
        val vertical = correlate(window, edge, lag, byRow = false)
        if (horizontal == null || vertical == null) return null
        return (horizontal + vertical) / 2.0
    }

    private fun correlate(window: FloatArray, edge: Int, lag: Int, byRow: Boolean): Double? {
        var n = 0
        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0
        val outer = if (byRow) edge else edge - lag
        val inner = if (byRow) edge - lag else edge
        for (y in 0 until outer) {
            for (x in 0 until inner) {
                val a = window[y * edge + x].toDouble()
                val b = if (byRow) window[y * edge + x + lag] else window[(y + lag) * edge + x]
                sumA += a
                sumB += b
                sumAA += a * a
                sumBB += b.toDouble() * b
                sumAB += a * b
                n++
            }
        }
        if (n == 0) return null
        val covariance = sumAB / n - (sumA / n) * (sumB / n)
        val varA = max(0.0, sumAA / n - (sumA / n) * (sumA / n))
        val varB = max(0.0, sumBB / n - (sumB / n) * (sumB / n))
        val denominator = sqrt(varA * varB)
        // A flat window has no variance to correlate. That is not a speckle of
        // infinite size; it is the absence of a pattern to measure.
        return if (denominator <= 0.0) null else covariance / denominator
    }
}
