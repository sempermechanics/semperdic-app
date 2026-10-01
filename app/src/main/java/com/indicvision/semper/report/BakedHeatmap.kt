package com.indicvision.semper.report

import android.graphics.Bitmap
import com.indicvision.semper.field.ValueRange

/**
 * A rendered heatmap and the colour range it was rendered with.
 *
 * `VisualizationEngine.generateHeatmap` and `generateDeformedHeatmap` return
 * this as `Triple<Bitmap, Float, Float>` (bitmap, min, max), and
 * `ScrubFrameCache.HeatEntry` holds the same three. The property order matches
 * the `Triple`, so `val (bitmap, min, max) = ...` reads the same after adoption.
 */
data class BakedHeatmap(val bitmap: Bitmap, val min: Float, val max: Float) {

    /** The colour range the bitmap was rendered with. */
    val range: ValueRange get() = ValueRange(min, max)

    /** `Triple(bitmap, min, max)`, the engine's form today. */
    fun toTriple(): Triple<Bitmap, Float, Float> = Triple(bitmap, min, max)

    companion object {
        /** From the engine's `Triple(bitmap, min, max)`. */
        fun of(triple: Triple<Bitmap, Float, Float>): BakedHeatmap =
            BakedHeatmap(triple.first, triple.second, triple.third)
    }
}
