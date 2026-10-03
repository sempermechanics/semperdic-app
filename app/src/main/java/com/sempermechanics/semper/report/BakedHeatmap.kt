package com.sempermechanics.semper.report

import android.graphics.Bitmap
import com.sempermechanics.semper.field.ValueRange

/**
 * A rendered heatmap and the colour range it was rendered with: what
 * `VisualizationEngine.generateHeatmap` and `generateDeformedHeatmap` return,
 * and what the viewer's scrub cache holds. The property order is the
 * `Triple` the engine used to return, so `val (bitmap, min, max) = ...` reads
 * the same.
 */
data class BakedHeatmap(val bitmap: Bitmap, val min: Float, val max: Float) {

    /** The colour range the bitmap was rendered with. */
    val range: ValueRange get() = ValueRange(min, max)

    /** `Triple(bitmap, min, max)`, the engine's old form. */
    fun toTriple(): Triple<Bitmap, Float, Float> = Triple(bitmap, min, max)

    companion object {
        /** From the engine's old `Triple(bitmap, min, max)`. */
        fun of(triple: Triple<Bitmap, Float, Float>): BakedHeatmap =
            BakedHeatmap(triple.first, triple.second, triple.third)
    }
}
