package com.sempermechanics.semper.ui.analysis.sweep

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.Roi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Parameter sweep for a virtual strain gauge study — §5.4.5 of *A Good
 * Practices Guide for Digital Image Correlation* (iDICs, 2018).
 *
 * Strain is a derived quantity, so its spatial resolution is set by the
 * *virtual strain gauge*: the footprint the strain calculation averages over.
 * Too large a VSG over-smooths and biases the peak strain low; too small a VSG
 * lets displacement noise through. The guide's remedy is to re-analyse the same
 * image pair over a ladder of VSG sizes and compare the strain along a line cut
 * through the specimen — as the VSG shrinks the peak strain rises, and once it
 * stops rising the real amplitude has been resolved.
 *
 * ## Sweep space
 *
 * The three dominant variables are subset size, step size and strain window
 * (guide, Tip 5.4). The user gives a subset range and a strain-window range
 * in data points; this object derives the rest:
 *
 *  - subset sizes are the odd values across the requested range;
 *  - step size is an integer between 1/6 and 1/3 of each subset, the usual
 *    overlap band — below 1/6 neighbouring subsets are so redundant that the
 *    extra runtime buys nothing, above 1/3 the field is under-sampled.
 *    The sweep asks for that fraction as `subset ÷ N`; single analysis asks
 *    for a pixel step plus the linked overlap `1 − step/subset`;
 *  - each window's VSG in pixels then follows from its step,
 *    `(window - 1) * step + 1` ([vsgFor]), so one window gives a larger VSG
 *    at a larger subset.
 *
 * Every surviving combination is solved in its own right and lands in the
 * result viewer as its own frame, so the comparison is made on the real fields
 * rather than on a summary statistic.
 */
@Suppress("TooManyFunctions") // planning and line-cut extraction belong together
object VsgStudy {

    /**
     * VSG of a strain window of [points] data points at [step], in pixels:
     * `(points - 1) * step + 1`, the iDICs guide's expression for a window
     * counted in data points. The user sets the window in points; this is the
     * diameter the engine is handed, stored and reported.
     *
     * The engine reads its `strain_window` as a circle's **diameter in
     * pixels**: `radius = strain_window / 2`, each neighbour tested by its
     * distance from the centre. At this diameter the radius is
     * `(points - 1) / 2` steps plus half a pixel, so the circle takes in every
     * grid point within that many steps and no more, whatever the step.
     *
     * Two device runs confirmed that the footprint is the diameter itself
     * rather than a count of points. Solving `L = sqrt(2) * sigma_u / sigma_e`
     * from each run's own displacement jitter and strain scatter, on a static
     * specimen over 60 frames, both at a 15 px diameter:
     *
     * | device | step | measured `L` | diameter |
     * |--------|------|--------------|----------|
     * | Samsung SM-G996U1 | 3 | 13.4 px | 15 px |
     * | Pixel 6 | 5 | 10.6 px | 15 px |
     *
     * Both land just under the diameter, which is what the engine's 90%
     * support rule predicts: the outer ring is not always filled.
     */
    fun vsgFor(points: Int, step: Int): Int = (points - 1) * step + 1

    /**
     * The strain window, in data points, that a VSG of [vsg] px spans at
     * [step]; null when it is not a whole odd count of at least
     * [MIN_WINDOW_POINTS]. Sessions from before the window was counted in
     * points stored whatever diameter the slider gave, so most of theirs have
     * no such count and are shown by their VSG alone.
     */
    fun windowPointsFor(vsg: Int, step: Int): Int? {
        if (step < 1 || vsg < 1 || (vsg - 1) % step != 0) return null
        val points = (vsg - 1) / step + 1
        return points.takeIf { it >= MIN_WINDOW_POINTS && it % 2 == 1 }
    }

    /**
     * The odd window, in points and inside the slider's range, whose VSG at
     * [step] is nearest [vsg]. Paste uses it: the clipboard holds a VSG in px.
     */
    fun nearestWindowPoints(vsg: Int, step: Int): Int {
        val raw = ((vsg - 1).toDouble() / step.coerceAtLeast(1)).roundToInt() + 1
        return oddWindowPoints(raw)
    }

    /** [raw] snapped odd and into `[MIN_WINDOW_POINTS, MAX_WINDOW_POINTS]`. */
    fun oddWindowPoints(raw: Int): Int {
        val odd = if (raw % 2 == 0) raw + 1 else raw
        return odd.coerceIn(MIN_WINDOW_POINTS, MAX_WINDOW_POINTS)
    }

    /** The wizard's strain window on first open and on Reset: a 21 px VSG at step 5. */
    const val DEFAULT_WINDOW_POINTS = 5

    /**
     * The strain window's range, in data points, matching the settings
     * slider. Odd, so the circle is centred on its point: 3 is the smallest
     * whose circle holds enough points for a plane fit.
     */
    const val MIN_WINDOW_POINTS = 3
    const val MAX_WINDOW_POINTS = 31

    /** Where a sweep's strain-window range starts, in points. */
    const val DEFAULT_SWEEP_WINDOW_MIN = 3
    const val DEFAULT_SWEEP_WINDOW_MAX = 11

    /** Step size range of the settings slider. */
    const val MIN_STEP = 1
    const val MAX_STEP = 30

    /** Subset sizes are odd, so consecutive sweep values differ by this much. */
    const val SUBSET_INCREMENT = 2

    /**
     * Defensive ceiling on how wide a subset range the planner will enumerate.
     * The per-axis sample counts bound the real cost of a sweep, so this only
     * guards against a pathological range; the UI never offers subsets outside
     * 15..101 anyway.
     */
    const val MAX_SUBSET_SPAN = 200

    /**
     * Step fraction for a sweep. One denominator for every subset:
     * `step = round(subset / N)`. The user picks N — `2` (half the subset,
     * the coarsest) through `9` (the finest).
     */
    const val STEP_DENOM_MIN = 2
    const val STEP_DENOM_MAX = 9
    const val DEFAULT_STEP_DENOM = 3

    /**
     * Overlap after a step stride: `1 − step/subset`. Single analysis links
     * pixel step to this ratio. The iDICs guide keeps this at least half
     * (step ≤ subset/2) and strictly below 1.0 (step ≥ 1 px). Typical
     * practice is about 0.50–0.75.
     */
    const val MIN_OVERLAP = 0.5

    /** Inclusive slider ceiling; overlap never reaches 1.0 because step ≥ 1. */
    const val MAX_OVERLAP = 0.99
    const val DEFAULT_OVERLAP = 0.8

    /** Samples the user may take along each axis (subset, strain window). */
    const val MIN_SAMPLES = 1
    const val MAX_SAMPLES = 8
    const val DEFAULT_SUBSET_SAMPLES = 3
    const val DEFAULT_VSG_SAMPLES = 3

    /** Strain components a sweep reports, as indices into a `.dat` point. */
    val STRAIN_COMPONENTS = listOf(DicResult.IDX_EXX, DicResult.IDX_EYY, DicResult.IDX_EXY)

    // ------------------------------------------------------------------
    // Sweep planning
    //
    // The sweep is a grid: the user picks how many subset sizes (x) and VSG
    // sizes (y) to sample, plus one step fraction shared by the whole sweep.
    // The number of analyses is x · y — one full solve per grid cell — set
    // explicitly rather than through a single opaque "runs" budget.
    // ------------------------------------------------------------------

    /** One analysis of the sweep: a full set of engine parameters. */
    data class Point(
        val subset: Int,
        val step: Int,
        /** Strain window in data points. */
        val window: Int,
    ) {
        /** Footprint the strain calculation averages over, in pixels: what the engine is handed. */
        val vsg: Int get() = vsgFor(window, step)
    }

    /** The single step size for [subset] at the chosen [denominator]: `subset/N`, in pixels. */
    fun stepSizeFor(subset: Int, denominator: Int): Int {
        val d = denominator.coerceIn(STEP_DENOM_MIN, STEP_DENOM_MAX)
        return (subset.toDouble() / d).roundToInt().coerceIn(MIN_STEP, MAX_STEP)
    }

    /** Largest step that still keeps overlap ≥ [MIN_OVERLAP] and within [MAX_STEP]. */
    fun maxStepFor(subset: Int): Int {
        val half = (subset * (1.0 - MIN_OVERLAP)).toInt().coerceAtLeast(MIN_STEP)
        return minOf(MAX_STEP, half)
    }

    fun clampOverlap(overlap: Double): Double = overlap.coerceIn(MIN_OVERLAP, MAX_OVERLAP)

    /** Overlap implied by [step] on [subset], clamped into the good-practice band. */
    fun overlapFor(subset: Int, step: Int): Double {
        if (subset <= 0) return DEFAULT_OVERLAP
        val s = step.coerceIn(MIN_STEP, maxStepFor(subset))
        return clampOverlap(1.0 - s.toDouble() / subset)
    }

    /** Overlap implied by sweep denominator N: `1 − 1/N`. */
    fun overlapForDenominator(denominator: Int): Double {
        val d = denominator.coerceIn(STEP_DENOM_MIN, STEP_DENOM_MAX)
        return clampOverlap(1.0 - 1.0 / d)
    }

    /** Sweep denominator nearest [overlap]: `round(1 / (1 − overlap))`. */
    fun denominatorForOverlap(overlap: Double): Int {
        val o = clampOverlap(overlap)
        val denom = (1.0 / (1.0 - o)).roundToInt()
        return denom.coerceIn(STEP_DENOM_MIN, STEP_DENOM_MAX)
    }

    /** Pixel step for [subset] at the chosen [overlap]. */
    fun stepSizeFor(subset: Int, overlap: Double): Int {
        val o = clampOverlap(overlap)
        return (subset * (1.0 - o)).roundToInt().coerceIn(MIN_STEP, maxStepFor(subset))
    }

    /**
     * The odd subset sizes between [subsetMin] and [subsetMax] inclusive. Both
     * ends are snapped odd — the engine only accepts odd subsets — and the span
     * is bounded by [MAX_SUBSET_SPAN] as a safety net.
     */
    fun subsetSizes(subsetMin: Int, subsetMax: Int): List<Int> {
        val low = subsetMin.coerceAtLeast(1) or 1
        val high = (maxOf(subsetMax, low) or 1).coerceAtMost(low + MAX_SUBSET_SPAN)
        return (low..high step SUBSET_INCREMENT).toList()
    }

    /** [count] subset sizes sampled evenly across `[subsetMin, subsetMax]`. */
    fun sampledSubsets(subsetMin: Int, subsetMax: Int, count: Int): List<Int> =
        sampleEvenly(subsetSizes(subsetMin, subsetMax), count)

    /**
     * The odd strain windows, in data points, between [winMin] and [winMax]
     * inclusive, clamped to the slider's range. The subset's twin: both ends
     * snapped odd, both ends the user's, so the axis says what it sweeps.
     */
    fun strainWindows(winMin: Int, winMax: Int): List<Int> {
        val low = oddWindowPoints(winMin)
        val high = oddWindowPoints(maxOf(winMax, low))
        return (low..high step SUBSET_INCREMENT).toList()
    }

    /** [count] strain windows sampled evenly across `[winMin, winMax]`. */
    fun sampledWindows(winMin: Int, winMax: Int, count: Int): List<Int> =
        sampleEvenly(strainWindows(winMin, winMax), count)

    /**
     * The sweep grid: for each sampled subset (x) and each of [strainWinSamples]
     * strain windows (y) sampled across `[strainWinMin, strainWinMax]`, one
     * analysis at the fixed step size `subset/[stepDenominator]`. Deduplicated,
     * ordered by subset then VSG — the order the frames are solved and scrubbed
     * through in.
     */
    @Suppress("LongParameterList") // the sweep's independent axes
    fun plan(
        subsetMin: Int,
        subsetMax: Int,
        subsetSamples: Int,
        strainWinMin: Int,
        strainWinMax: Int,
        strainWinSamples: Int,
        stepDenominator: Int,
    ): List<Point> {
        val windows = sampledWindows(strainWinMin, strainWinMax, strainWinSamples)
        val out = LinkedHashSet<Point>()
        for (subset in sampledSubsets(subsetMin, subsetMax, subsetSamples)) {
            val step = stepSizeFor(subset, stepDenominator)
            for (window in windows) {
                out.add(Point(subset, step, window))
            }
        }
        return out.sortedWith(compareBy({ it.subset }, { it.vsg }, { it.step }))
    }

    /** Same grid as [plan], with step taken from [overlap] via [denominatorForOverlap]. */
    @Suppress("LongParameterList") // overlap overload of the sweep planner
    fun plan(
        subsetMin: Int,
        subsetMax: Int,
        subsetSamples: Int,
        strainWinMin: Int,
        strainWinMax: Int,
        strainWinSamples: Int,
        overlap: Double,
    ): List<Point> = plan(
        subsetMin,
        subsetMax,
        subsetSamples,
        strainWinMin,
        strainWinMax,
        strainWinSamples,
        denominatorForOverlap(overlap),
    )

    /** [count] items of [items], evenly spaced, both ends included. */
    private fun <T> sampleEvenly(items: List<T>, count: Int): List<T> = when {
        items.isEmpty() || count <= 0 -> emptyList()
        items.size <= count -> items
        count == 1 -> listOf(items[items.size / 2])
        else -> (0 until count).map { items[it * (items.size - 1) / (count - 1)] }.distinct()
    }

    // ------------------------------------------------------------------
    // Line cut
    // ------------------------------------------------------------------

    /**
     * The line strain is read along: a cut through the centre of the region of
     * interest, running along the axis the user picked.
     *
     * A centre cut is deliberately fixed rather than hunted for. It is the same
     * physical line for every combination in the sweep, so the strain profiles
     * of different VSG sizes lie on top of each other and can be compared
     * directly — which is the whole point of guide step 4.
     *
     * @param horizontal true for a cut along x at constant y, false for a cut
     *   along y at constant x
     * @param position the constant coordinate: y when [horizontal], else x
     */
    data class StudyLine(
        val horizontal: Boolean,
        val position: Float,
    )

    /** The centre cut of the ROI `[roiX, roiY, roiW, roiH]` along the given axis. */
    fun centreLine(roiX: Int, roiY: Int, roiW: Int, roiH: Int, horizontal: Boolean): StudyLine =
        StudyLine(
            horizontal = horizontal,
            position = if (horizontal) roiY + roiH / 2f else roiX + roiW / 2f,
        )

    /** The centre cut of [roi] along the given axis. */
    fun centreLine(roi: Roi, horizontal: Boolean): StudyLine = centreLine(roi.x, roi.y, roi.w, roi.h, horizontal)

    private fun isAccepted(data: FloatArray, i: Int): Boolean =
        DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])

    /** Largest |value| of [component] over accepted points, in millistrain. */
    fun fieldPeak(data: FloatArray, component: Int): Float {
        var peak = 0f
        var i = 0
        while (i < data.size) {
            if (isAccepted(data, i)) {
                val v = abs(data[i + component]) * DicResult.STRAIN_TO_MILLISTRAIN
                if (v > peak) peak = v
            }
            i += DicResult.STRIDE
        }
        return peak
    }

    /**
     * Strain profile along [line], as (distance-along-line, millistrain) pairs
     * sorted by distance. [tolerance] admits the grid row/column nearest the
     * line — half a step keeps exactly one row of points for any step size.
     */
    fun profileAlong(
        data: FloatArray,
        component: Int,
        line: StudyLine,
        tolerance: Float,
    ): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        var i = 0
        while (i < data.size) {
            pointOnLine(data, i, component, line, tolerance)?.let(out::add)
            i += DicResult.STRIDE
        }
        return out.sortedBy { it.first }
    }

    /**
     * Profiles several strain [components] along [line] in a single pass, instead of
     * one full array walk per component. The accept test and the along/across geometry
     * are computed once per point and shared, so this is N× less work for N components.
     * Each returned list is identical (same points, same ascending-distance order) to
     * the single-component [profileAlong] for that component.
     */
    fun profileAlong(
        data: FloatArray,
        components: IntArray,
        line: StudyLine,
        tolerance: Float,
    ): Map<Int, List<Pair<Float, Float>>> {
        val out = LinkedHashMap<Int, ArrayList<Pair<Float, Float>>>(components.size)
        for (c in components) out[c] = ArrayList()
        var i = 0
        while (i < data.size) {
            val along = alongIfOnLine(data, i, line, tolerance)
            if (along != null) {
                for (c in components) {
                    out.getValue(c).add(along to data[i + c] * DicResult.STRAIN_TO_MILLISTRAIN)
                }
            }
            i += DicResult.STRIDE
        }
        return out.mapValues { (_, points) -> points.sortedBy { it.first } }
    }

    /** Distance along [line] for accepted point [i], or null when it's off the line. */
    @Suppress("ReturnCount")
    private fun alongIfOnLine(data: FloatArray, i: Int, line: StudyLine, tolerance: Float): Float? {
        if (!isAccepted(data, i)) return null
        val x = data[i + DicResult.IDX_X]
        val y = data[i + DicResult.IDX_Y]
        val across = if (line.horizontal) y else x
        if (abs(across - line.position) > tolerance) return null
        return if (line.horizontal) x else y
    }

    /** The (position, millistrain) pair at point [i], or null when off the line. */
    @Suppress("ReturnCount")
    private fun pointOnLine(
        data: FloatArray,
        i: Int,
        component: Int,
        line: StudyLine,
        tolerance: Float,
    ): Pair<Float, Float>? {
        if (!isAccepted(data, i)) return null
        val x = data[i + DicResult.IDX_X]
        val y = data[i + DicResult.IDX_Y]
        val across = if (line.horizontal) y else x
        if (abs(across - line.position) > tolerance) return null
        val along = if (line.horizontal) x else y
        return along to data[i + component] * DicResult.STRAIN_TO_MILLISTRAIN
    }

    /** Max |strain| along [line]; falls back to the field peak on an empty cut. */
    fun linePeak(data: FloatArray, component: Int, line: StudyLine, tolerance: Float): Float {
        val profile = profileAlong(data, component, line, tolerance)
        if (profile.isEmpty()) return fieldPeak(data, component)
        return profile.maxOf { abs(it.second) }
    }
}
