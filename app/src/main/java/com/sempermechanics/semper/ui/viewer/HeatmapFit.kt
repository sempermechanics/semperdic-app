@file:Suppress("LongParameterList", "ReturnCount")

package com.sempermechanics.semper.ui.viewer

import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.report.VisualizationEngine
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Shared rest-fit / GIF crop helpers: the coloured region (custom ROI or accepted
 * points) fills a box using the smaller of the two axis scales — contained, not
 * cropped or stretched.
 */
object HeatmapFit {

    const val LEFT = 0
    const val TOP = 1
    const val RIGHT = 2
    const val BOTTOM = 3
    const val BOX_LEN = 4

    /**
     * `[left, top, right, bottom]` in image pixels. Custom ROI wins when it is a
     * true sub-rectangle of the frame; otherwise [accepted] if present; else the
     * full image.
     */
    fun resolve(
        imgW: Int,
        imgH: Int,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        accepted: FloatArray? = null,
    ): FloatArray = resolve(ImageSize(imgW, imgH), Roi(roiX, roiY, roiW, roiH), accepted)

    /** [resolve] for an image of [size] and its [roi]. */
    fun resolve(size: ImageSize, roi: Roi, accepted: FloatArray? = null): FloatArray = when {
        roi.isCustomFor(size) -> roi.toLtrb()
        accepted != null && accepted.size >= BOX_LEN -> accepted.copyOf(BOX_LEN)
        else -> floatArrayOf(0f, 0f, size.width.toFloat(), size.height.toFloat())
    }

    /** [Roi.isCustomFor], for callers that hold the ROI as four ints. */
    fun isCustomRoi(imgW: Int, imgH: Int, roiX: Int, roiY: Int, roiW: Int, roiH: Int): Boolean =
        Roi(roiX, roiY, roiW, roiH).isCustomFor(ImageSize(imgW, imgH))

    /**
     * Long-edge cap to pass into [VisualizationEngine.generateHeatmapIndices] so
     * that after cropping to [fit], the region's long edge is about [maxEdge].
     */
    fun renderLongEdgeCap(imgW: Int, imgH: Int, fit: FloatArray, maxEdge: Int): Int {
        val fitW = (fit[RIGHT] - fit[LEFT]).coerceAtLeast(1f)
        val fitH = (fit[BOTTOM] - fit[TOP]).coerceAtLeast(1f)
        val longestImg = max(imgW, imgH).coerceAtLeast(1)
        val longestFit = max(fitW, fitH)
        return ceil(maxEdge * longestImg / longestFit).toInt().coerceAtLeast(maxEdge)
    }

    /**
     * Crops [plane] (full-image render) to [fit] and scales with nearest-neighbour
     * so the long edge is [maxEdge] — same contain idea as the viewer rest pose.
     */
    fun cropAndScale(
        plane: VisualizationEngine.IndexPlane,
        imgW: Int,
        imgH: Int,
        fit: FloatArray,
        maxEdge: Int,
    ): VisualizationEngine.IndexPlane {
        if (imgW <= 0 || imgH <= 0) return plane
        if (plane.width <= 0 || plane.height <= 0) return plane
        val scaleX = plane.width.toFloat() / imgW
        val scaleY = plane.height.toFloat() / imgH
        val x0 = (fit[LEFT] * scaleX).toInt().coerceIn(0, plane.width - 1)
        val y0 = (fit[TOP] * scaleY).toInt().coerceIn(0, plane.height - 1)
        val x1 = (fit[RIGHT] * scaleX).toInt().coerceIn(x0 + 1, plane.width)
        val y1 = (fit[BOTTOM] * scaleY).toInt().coerceIn(y0 + 1, plane.height)
        val cropW = x1 - x0
        val cropH = y1 - y0
        val outScale = maxEdge.toFloat() / max(cropW, cropH).toFloat()
        val outW = max(1, (cropW * outScale).toInt())
        val outH = max(1, (cropH * outScale).toInt())
        val unchanged = outW == cropW &&
            outH == cropH &&
            x0 == 0 &&
            y0 == 0 &&
            x1 == plane.width &&
            y1 == plane.height
        if (unchanged) return plane
        val out = ByteArray(outW * outH)
        for (y in 0 until outH) {
            val sy = y0 + min(cropH - 1, (y * cropH) / outH)
            val srcRow = sy * plane.width
            val dstRow = y * outW
            for (x in 0 until outW) {
                val sx = x0 + min(cropW - 1, (x * cropW) / outW)
                out[dstRow + x] = plane.indices[srcRow + sx]
            }
        }
        return VisualizationEngine.IndexPlane(out, outW, outH, plane.min, plane.max)
    }
}
