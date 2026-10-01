package com.indicvision.semper.field

/**
 * [max], [min] and [mean] of one field over its accepted points, in display
 * units (millistrain for strain).
 *
 * `DicResult.fieldStats` returns these positionally as `[max, min, mean]`;
 * `AnalysisCsvWriter`, `ResultViewerActivity` and its `FieldMetrics` read them
 * back by index. [fromArray] and [toArray] are that layout.
 */
data class FieldStats(val max: Float, val min: Float, val mean: Float) {

    /** `[max, min, mean]`, `DicResult.fieldStats`'s layout. */
    fun toArray(): FloatArray = floatArrayOf(max, min, mean)

    companion object {
        private const val SIZE = 3

        /**
         * From `DicResult.fieldStats`'s `[max, min, mean]`; null for its null
         * (no accepted point) or any other length.
         */
        fun fromArray(stats: FloatArray?): FieldStats? =
            stats?.takeIf { it.size == SIZE }?.let { (max, min, mean) -> FieldStats(max, min, mean) }
    }
}
