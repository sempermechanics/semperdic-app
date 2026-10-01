package com.indicvision.semper.field

/**
 * A colour-scale range in a field's stored units: the auto p02/p98 span or
 * the user's custom bounds.
 *
 * Today this travels as `Pair<Float, Float>` (`VisualizationEngine.valueRanges`,
 * `computeSigmaClampedRange`, `clampSpan`, `FieldRangesStore`, the viewer's
 * `customBounds`, `SummaryAnimation.globalRanges`, `ShareCenter.summaryBounds`)
 * and as `customMin: Float?, customMax: Float?` parameter pairs. [min] is
 * `first`, [max] is `second`. No ordering is enforced: a stored range is
 * read back as it was written.
 */
data class ValueRange(val min: Float, val max: Float) {

    /** `min to max`, the `Pair` form the code passes today. */
    fun toPair(): Pair<Float, Float> = min to max

    companion object {
        /** From a `Pair`'s `first` (min) and `second` (max). */
        fun of(pair: Pair<Float, Float>): ValueRange = ValueRange(pair.first, pair.second)

        /**
         * The user's custom range, or null unless both bounds are set: the
         * `customMin != null && customMax != null` test `VisualizationEngine` makes.
         */
        fun custom(min: Float?, max: Float?): ValueRange? =
            if (min != null && max != null) ValueRange(min, max) else null
    }
}
