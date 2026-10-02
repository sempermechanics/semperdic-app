// The public face of the heatmap renders: each entry point takes the field,
// the grid and its optional bounds and cap, and there is one per render the
// viewer, the report, the GIF and the tests call.
@file:Suppress("LongParameterList", "TooManyFunctions")

package com.indicvision.semper.report

import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.report.HeatmapColorScale.JET_LUT
import kotlin.math.max

/**
 * Turns a full-field result array into heatmap bitmaps: grid interpolation,
 * percentile-based color scaling, and the jet colormap shared by the on-screen
 * viewer and the PDF report.
 */
object VisualizationEngine {

    /** Longest-edge cap for on-screen scrub heatmaps (export paths omit this). */
    const val DISPLAY_MAX_EDGE = 1080

    /**
     * Longest-edge cap for report/upload compositing. The PDF and cloud heatmaps
     * are downscaled to 600 px wide by [ReportBuilder.compressForPdf] anyway, so
     * this is far above the visible output — it exists purely to stop the
     * intermediate full-resolution ARGB_8888 bitmaps from OOMing on large
     * (e.g. 26 MP) references.
     */
    const val REPORT_MAX_EDGE = 1280

    /**
     * Scale factor that shrinks [imgW]×[imgH] so its longest edge is ≤ [maxEdge]
     * (1f when already within). This is the identical formula
     * [generateHeatmapIndices] uses for its own downscale, so a caller that composes
     * at `imgW*scale × imgH*scale` lines up pixel-for-pixel with the capped heatmap.
     */
    fun cappedRenderScale(imgW: Int, imgH: Int, maxEdge: Int): Float {
        val longest = max(imgW, imgH).coerceAtLeast(1)
        return if (longest > maxEdge) maxEdge.toFloat() / longest else 1f
    }

    /** [w]×[h] shrunk so its longest edge is ≤ [maxEdge]; unchanged if already within. */
    fun cappedDims(w: Int, h: Int, maxEdge: Int): ImageSize {
        val scale = cappedRenderScale(w, h, maxEdge)
        return ImageSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
    }

    /**
     * Palette slot for "no correlated data here" — transparent on screen, the
     * animation's background colour in a GIF. It costs the colour ramp its top
     * entry (values map to 0..[LAST_COLOR]), which is one 255th of the scale and
     * buys a single render path shared by the viewer, the report and the GIF.
     */
    const val TRANSPARENT_INDEX = 255
    internal const val LAST_COLOR = TRANSPARENT_INDEX - 1

    /**
     * One byte per pixel, each an index into the jet ramp or [TRANSPARENT_INDEX],
     * with the value range the colours were mapped against.
     */
    class IndexPlane(
        val indices: ByteArray,
        val width: Int,
        val height: Int,
        val min: Float,
        val max: Float,
    )

    /**
     * The jet ramp as a GIF global colour table: [TRANSPARENT_INDEX] takes
     * [background], every other slot is the colour the viewer would draw.
     */
    fun gifPalette(background: Int): IntArray =
        IntArray(JET_LUT.size) { if (it == TRANSPARENT_INDEX) background else JET_LUT[it] }

    /**
     * The colours values are mapped to, lowest value first: every [JET_LUT] slot
     * but [TRANSPARENT_INDEX]. Evenly spaced gradient stops over these draw a
     * colour bar that matches the map.
     */
    fun rampColors(): IntArray = JET_LUT.copyOf(LAST_COLOR + 1)

    /**
     * The k-th smallest value of `values[fromIndex, toIndex)`, in [Float.compareTo]
     * order: what `values.sort(fromIndex, toIndex); values[k]` would give, found
     * in expected O(n). Partially reorders that range. See [HeatmapColorScale.quickSelect].
     */
    internal fun quickSelect(values: FloatArray, k: Int, fromIndex: Int, toIndex: Int): Float =
        HeatmapColorScale.quickSelect(values, k, fromIndex, toIndex)

    /**
     * The displayed value range of several fields at once, in one pass over the
     * points — the same percentile-clamped bounds [generateHeatmap] would pick
     * for each. Null for a field with no correlated points.
     *
     * Per-frame heatmaps use this. The summary GIF widens these same ends
     * across the batch: lowest scale-min, highest scale-max.
     */
    fun valueRanges(data: FloatArray, valIndices: IntArray): Map<Int, Pair<Float, Float>?> =
        valueRanges(data, data.size, valIndices, scratch = null)

    /** See [HeatmapColorScale.valueRanges]. */
    internal fun valueRanges(
        data: FloatArray,
        floatCount: Int,
        valIndices: IntArray,
        scratch: Array<FloatArray>?,
    ): Map<Int, Pair<Float, Float>?> = HeatmapColorScale.valueRanges(data, floatCount, valIndices, scratch)

    /**
     * @param maxLongEdge when set and smaller than the image's longest edge, the
     *   bitmap is generated at display scale (viewer scrub). Pass null / omit for
     *   full-resolution PDF and share export.
     */
    fun generateHeatmap(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null, // Optional Custom Bounds
        customMax: Float? = null,
        maxLongEdge: Int? = null,
    ): BakedHeatmap {
        val plane = generateHeatmapIndices(data, imgW, imgH, valIndex, step, customMin, customMax, maxLongEdge)
        return BakedHeatmap(HeatmapRenderer.toBitmap(plane), plane.min, plane.max)
    }

    /**
     * The same render as [generateHeatmap], stopping one step earlier: one byte
     * per pixel, holding an index into the jet ramp — or [TRANSPARENT_INDEX] where
     * no correlated data covers the pixel. See [HeatmapRenderer.generateHeatmapIndices].
     */
    fun generateHeatmapIndices(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null,
        customMax: Float? = null,
        maxLongEdge: Int? = null,
    ): IndexPlane =
        HeatmapRenderer.generateHeatmapIndices(data, imgW, imgH, valIndex, step, customMin, customMax, maxLongEdge)

    /**
     * [generateHeatmap] in the deformed configuration: each point is drawn where
     * it moved to, (x + u, y + v), so the map lies on that frame's own photo the
     * way [generateHeatmap] lies on the reference. The colour range is taken
     * over the same accepted values, so both renders share one scale.
     *
     * The viewer's frames only. The summary GIF and the report keep the
     * reference render, whose bytes are pinned.
     */
    fun generateDeformedHeatmap(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null,
        customMax: Float? = null,
        maxLongEdge: Int? = null,
    ): BakedHeatmap {
        val plane = generateDeformedHeatmapIndices(data, imgW, imgH, valIndex, step, customMin, customMax, maxLongEdge)
        return BakedHeatmap(HeatmapRenderer.toBitmap(plane), plane.min, plane.max)
    }

    /** The index plane behind [generateDeformedHeatmap]. See [DeformedHeatmap.generateDeformedHeatmapIndices]. */
    fun generateDeformedHeatmapIndices(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null,
        customMax: Float? = null,
        maxLongEdge: Int? = null,
    ): IndexPlane = DeformedHeatmap.generateDeformedHeatmapIndices(
        data,
        imgW,
        imgH,
        valIndex,
        step,
        customMin,
        customMax,
        maxLongEdge,
    )
}
