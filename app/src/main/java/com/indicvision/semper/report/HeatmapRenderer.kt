// Grid interpolation: the pixel loop stays one function, whole, so its
// structural rules are suppressed for this file, as are its literal bounds.
@file:Suppress(
    "MagicNumber",
    "ComplexCondition",
    "LongMethod",
    "CyclomaticComplexMethod",
    "LongParameterList",
    "NestedBlockDepth",
)

package com.indicvision.semper.report

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.report.HeatmapColorScale.JET_LUT
import com.indicvision.semper.report.HeatmapColorScale.computeSigmaClampedRange
import com.indicvision.semper.report.VisualizationEngine.IndexPlane
import com.indicvision.semper.report.VisualizationEngine.LAST_COLOR
import com.indicvision.semper.report.VisualizationEngine.TRANSPARENT_INDEX
import kotlin.math.max

/**
 * The reference-configuration heatmap: the point grid interpolated over the
 * reference image into an [IndexPlane], and any plane expanded into jet
 * colours. [VisualizationEngine.generateHeatmap] and
 * [VisualizationEngine.generateHeatmapIndices] are its public face.
 */
internal object HeatmapRenderer {

    /** [plane] in [JET_LUT] colours, [TRANSPARENT_INDEX] left fully transparent. */
    fun toBitmap(plane: IndexPlane): Bitmap {
        val bitmap = createBitmap(plane.width, plane.height, Bitmap.Config.ARGB_8888)
        // Expand one row at a time into a reused buffer instead of materialising a
        // full-image IntArray next to the bitmap: peak goes from 8 bytes/px to
        // 4 bytes/px + one row. Same pixels, written in the same order.
        val row = IntArray(plane.width)
        for (y in 0 until plane.height) {
            val rowStart = y * plane.width
            for (x in 0 until plane.width) {
                val index = plane.indices[rowStart + x].toInt() and 0xFF
                row[x] = if (index == TRANSPARENT_INDEX) 0 else JET_LUT[index]
            }
            bitmap.setPixels(row, 0, plane.width, 0, y, plane.width, 1)
        }
        return bitmap
    }

    /**
     * The same render as [VisualizationEngine.generateHeatmap], stopping one step earlier: one byte
     * per pixel, holding an index into [JET_LUT] — or [TRANSPARENT_INDEX] where
     * no correlated data covers the pixel.
     *
     * The GIF summary animation consumes this directly, so its colours are the
     * viewer's colours by construction rather than by quantisation. Both callers
     * share this one implementation of the interpolation.
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
    ): IndexPlane {
        val longest = max(imgW, imgH).coerceAtLeast(1)
        val scale = if (maxLongEdge != null && longest > maxLongEdge) {
            maxLongEdge.toFloat() / longest
        } else {
            1f
        }
        val outW = (imgW * scale).toInt().coerceAtLeast(1)
        val outH = (imgH * scale).toInt().coerceAtLeast(1)

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        // Primitive collector (same values, same order as the previous List<Float>) so
        // the sort + percentile pick in computeSigmaClampedRange is bit-identical.
        val validValues = FloatArray(data.size / DicResult.STRIDE)
        var validCount = 0

        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr)) {
                val x = data[i].toInt()
                val y = data[i + 1].toInt()
                val v = data[i + valIndex]

                validValues[validCount++] = v
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        if (validCount == 0) {
            return IndexPlane(ByteArray(outW * outH) { TRANSPARENT_INDEX.toByte() }, outW, outH, 0f, 0f)
        }

        // THE SCALING LOGIC: Use Custom Bounds if provided, else use Mean ± 3σ Statistical Clamping
        val minV: Float
        val maxV: Float
        if (customMin != null && customMax != null) {
            minV = customMin
            maxV = customMax
        } else {
            val bounds = computeSigmaClampedRange(validValues, validCount, valIndex)
            minV = bounds.first
            maxV = bounds.second
        }

        val range = if (maxV - minV == 0f) 0.0001f else maxV - minV

        val plane = ByteArray(outW * outH) { TRANSPARENT_INDEX.toByte() }
        val cols = ((maxX - minX) / step) + 1
        val rows = ((maxY - minY) / step) + 1
        val grid = FloatArray(cols * rows) { Float.NaN }

        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr)) {
                val x = data[i].toInt()
                val y = data[i + 1].toInt()
                val c = (x - minX) / step
                val r = (y - minY) / step
                if (c in 0 until cols && r in 0 until rows) {
                    grid[r * cols + c] = data[i + valIndex]
                }
            }
        }

        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                val v00 = grid[r * cols + c]
                val v10 = grid[r * cols + (c + 1)]
                val v01 = grid[(r + 1) * cols + c]
                val v11 = grid[(r + 1) * cols + (c + 1)]

                if (!v00.isNaN() && !v10.isNaN() && !v01.isNaN() && !v11.isNaN()) {
                    val x0 = minX + c * step
                    val y0 = minY + r * step
                    val x1 = x0 + step
                    val y1 = y0 + step

                    val ox0 = (x0 * scale).toInt().coerceIn(0, outW)
                    val oy0 = (y0 * scale).toInt().coerceIn(0, outH)
                    val ox1 = (x1 * scale).toInt().coerceIn(0, outW)
                    val oy1 = (y1 * scale).toInt().coerceIn(0, outH)
                    val dw = (ox1 - ox0).coerceAtLeast(1)
                    val dh = (oy1 - oy0).coerceAtLeast(1)

                    for (oy in oy0 until oy1) {
                        val wy = (oy - oy0).toFloat() / dh
                        val rowOffset = oy * outW
                        val leftEdgeV = v00 + wy * (v01 - v00)
                        val rightEdgeV = v10 + wy * (v11 - v10)

                        for (ox in ox0 until ox1) {
                            val wx = (ox - ox0).toFloat() / dw
                            val v = leftEdgeV + wx * (rightEdgeV - leftEdgeV)
                            val norm = ((v.coerceIn(minV, maxV) - minV) / range * LAST_COLOR).toInt()
                            plane[rowOffset + ox] = norm.coerceIn(0, LAST_COLOR).toByte()
                        }
                    }
                }
            }
        }

        return IndexPlane(plane, outW, outH, minV, maxV)
    }
}
