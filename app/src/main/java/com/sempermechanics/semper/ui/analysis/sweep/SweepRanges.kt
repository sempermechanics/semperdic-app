package com.sempermechanics.semper.ui.analysis.sweep

/**
 * The axes of a parameter sweep as the wizard edits them: the subset range
 * (px) and how many sizes to sample across it, the strain-window range (in
 * data points, not px) and how many windows to sample, and the step-depth
 * denominator (`step = round(subset / N)`).
 *
 * Today these are seven `var`s on `AnalysisViewModel` (`subsetMin` … `stepDenominator`),
 * the seven-int `"sweepRanges"` array of the wizard's saved state, and the
 * seven parameters of [VsgStudy.plan]. The ranges are 0 until a recommendation
 * or default seeds them ([UNSEEDED]).
 */
data class SweepRanges(
    val subsetMin: Int,
    val subsetMax: Int,
    val strainWinMin: Int,
    val strainWinMax: Int,
    val subsetSamples: Int = VsgStudy.DEFAULT_SUBSET_SAMPLES,
    val strainWinSamples: Int = VsgStudy.DEFAULT_VSG_SAMPLES,
    val stepDenominator: Int = VsgStudy.DEFAULT_STEP_DENOM,
) {

    /**
     * The combinations this sweep solves when the ROI holds subsets up to
     * [subsetCeiling] (`RoiResolveHelper.maxSubsetForRoi`): none when even
     * [subsetMin] is over it, else [VsgStudy.plan] with [subsetMax] capped at
     * it. The rule `SweepSetupController.currentPlan` applies.
     */
    fun plan(subsetCeiling: Int): List<VsgStudy.Point> = if (subsetMin > subsetCeiling) {
        emptyList()
    } else {
        VsgStudy.plan(
            subsetMin = subsetMin,
            subsetMax = subsetMax.coerceAtMost(subsetCeiling),
            subsetSamples = subsetSamples,
            strainWinMin = strainWinMin,
            strainWinMax = strainWinMax,
            strainWinSamples = strainWinSamples,
            stepDenominator = stepDenominator,
        )
    }

    /**
     * The saved-state array, in `WizardState.save`'s order: subset min, max,
     * window min, max, subset samples, window samples, step denominator.
     */
    fun toIntArray(): IntArray = intArrayOf(
        subsetMin,
        subsetMax,
        strainWinMin,
        strainWinMax,
        subsetSamples,
        strainWinSamples,
        stepDenominator,
    )

    companion object {
        private const val PACKED_SIZE = 7

        /** The view model's state before anything seeds the ranges. */
        val UNSEEDED = SweepRanges(subsetMin = 0, subsetMax = 0, strainWinMin = 0, strainWinMax = 0)

        /**
         * From [toIntArray]'s layout, or null unless it holds exactly seven ints:
         * `WizardState.restoreScalars` ignores any other array the same way.
         */
        fun fromIntArray(packed: IntArray?): SweepRanges? = packed?.takeIf { it.size == PACKED_SIZE }?.let {
            val next = it.iterator()
            SweepRanges(
                subsetMin = next.nextInt(),
                subsetMax = next.nextInt(),
                strainWinMin = next.nextInt(),
                strainWinMax = next.nextInt(),
                subsetSamples = next.nextInt(),
                strainWinSamples = next.nextInt(),
                stepDenominator = next.nextInt(),
            )
        }
    }
}
