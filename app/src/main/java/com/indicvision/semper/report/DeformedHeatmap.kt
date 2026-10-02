// Displaced-grid interpolation: the render stays one function, whole, so its
// structural rules are suppressed for this file, as are its literal bounds.
@file:Suppress(
    "MagicNumber",
    "ComplexCondition",
    "LongMethod",
    "CyclomaticComplexMethod",
    "LongParameterList",
)

package com.indicvision.semper.report

import com.indicvision.semper.field.DicResult
import com.indicvision.semper.report.HeatmapColorScale.computeSigmaClampedRange
import com.indicvision.semper.report.VisualizationEngine.IndexPlane
import com.indicvision.semper.report.VisualizationEngine.LAST_COLOR
import com.indicvision.semper.report.VisualizationEngine.TRANSPARENT_INDEX
import com.indicvision.semper.report.VisualizationEngine.cappedRenderScale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The deformed-configuration heatmap: each point drawn where it moved to.
 * [VisualizationEngine.generateDeformedHeatmap] and
 * [VisualizationEngine.generateDeformedHeatmapIndices] are its public face.
 */
internal object DeformedHeatmap {

    /**
     * The index plane behind [VisualizationEngine.generateDeformedHeatmap]. The point grid is the one
     * [HeatmapRenderer.generateHeatmapIndices] builds. Each cell with four solved corners becomes
     * the quad through their displaced positions, and every pixel inside it takes
     * the bilinear value at its inverse-bilinear (s, t). With no displacement
     * that is the reference render's own interpolation.
     */
    fun generateDeformedHeatmapIndices(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null,
        customMax: Float? = null,
        maxLongEdge: Int? = null,
    ): IndexPlane {
        val scale = cappedRenderScale(imgW, imgH, maxLongEdge ?: Int.MAX_VALUE)
        val outW = (imgW * scale).toInt().coerceAtLeast(1)
        val outH = (imgH * scale).toInt().coerceAtLeast(1)

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        val validValues = FloatArray(data.size / DicResult.STRIDE)
        var validCount = 0
        for (i in data.indices step DicResult.STRIDE) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                val x = data[i].toInt()
                val y = data[i + 1].toInt()
                validValues[validCount++] = data[i + valIndex]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (validCount == 0) {
            return IndexPlane(ByteArray(outW * outH) { TRANSPARENT_INDEX.toByte() }, outW, outH, 0f, 0f)
        }

        val (minV, maxV) = if (customMin != null && customMax != null) {
            customMin to customMax
        } else {
            computeSigmaClampedRange(validValues, validCount, valIndex)
        }
        val range = if (maxV - minV == 0f) 0.0001f else maxV - minV

        val plane = ByteArray(outW * outH) { TRANSPARENT_INDEX.toByte() }
        val cols = ((maxX - minX) / step) + 1
        val rows = ((maxY - minY) / step) + 1
        val grid = FloatArray(cols * rows) { Float.NaN }
        // Where each grid point sits in the deformed frame, in output pixels.
        val px = FloatArray(cols * rows)
        val py = FloatArray(cols * rows)
        for (i in data.indices step DicResult.STRIDE) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                val x = data[i].toInt()
                val y = data[i + 1].toInt()
                val c = (x - minX) / step
                val r = (y - minY) / step
                if (c in 0 until cols && r in 0 until rows) {
                    val k = r * cols + c
                    grid[k] = data[i + valIndex]
                    px[k] = (data[i] + data[i + DicResult.IDX_U]) * scale
                    py[k] = (data[i + 1] + data[i + DicResult.IDX_V]) * scale
                }
            }
        }

        val cell = DeformedCell(plane, outW, outH, minV, maxV, range)
        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                val k00 = r * cols + c
                val k10 = k00 + 1
                val k01 = k00 + cols
                val k11 = k01 + 1
                if (!grid[k00].isNaN() && !grid[k10].isNaN() && !grid[k01].isNaN() && !grid[k11].isNaN()) {
                    cell.fill(px, py, grid, k00, k10, k01, k11)
                }
            }
        }
        return IndexPlane(plane, outW, outH, minV, maxV)
    }

    /**
     * Fills one displaced grid cell of a [generateDeformedHeatmapIndices] plane.
     * The quad is `a + e·s + f·t + g·s·t` with a = corner 00, e along 00→10 and
     * f along 00→01; a pixel inside it is found by inverting that map.
     */
    private class DeformedCell(
        private val plane: ByteArray,
        private val outW: Int,
        private val outH: Int,
        private val minV: Float,
        private val maxV: Float,
        private val range: Float,
    ) {
        private var ex = 0f
        private var ey = 0f
        private var fx = 0f
        private var fy = 0f
        private var gx = 0f
        private var gy = 0f

        /** (s, t) of the pixel last located by [locate]. */
        private var s = 0f
        private var t = 0f

        fun fill(px: FloatArray, py: FloatArray, grid: FloatArray, k00: Int, k10: Int, k01: Int, k11: Int) {
            val ax = px[k00]
            val ay = py[k00]
            ex = px[k10] - ax
            ey = py[k10] - ay
            fx = px[k01] - ax
            fy = py[k01] - ay
            gx = ax - px[k10] + px[k11] - px[k01]
            gy = ay - py[k10] + py[k11] - py[k01]
            val ox0 = ceil(min(min(ax, px[k10]), min(px[k01], px[k11]))).toInt().coerceAtLeast(0)
            val ox1 = floor(max(max(ax, px[k10]), max(px[k01], px[k11]))).toInt().coerceAtMost(outW - 1)
            val oy0 = ceil(min(min(ay, py[k10]), min(py[k01], py[k11]))).toInt().coerceAtLeast(0)
            val oy1 = floor(max(max(ay, py[k10]), max(py[k01], py[k11]))).toInt().coerceAtMost(outH - 1)
            val v00 = grid[k00]
            val v10 = grid[k10]
            val v01 = grid[k01]
            val v11 = grid[k11]
            for (oy in oy0..oy1) {
                val rowOffset = oy * outW
                for (ox in ox0..ox1) {
                    if (locate(ox - ax, oy - ay)) {
                        // Same bilinear form as the reference render.
                        val leftEdgeV = v00 + t * (v01 - v00)
                        val rightEdgeV = v10 + t * (v11 - v10)
                        val v = leftEdgeV + s * (rightEdgeV - leftEdgeV)
                        val norm = ((v.coerceIn(minV, maxV) - minV) / range * LAST_COLOR).toInt()
                        plane[rowOffset + ox] = norm.coerceIn(0, LAST_COLOR).toByte()
                    }
                }
            }
        }

        /**
         * Inverse bilinear for the offset (hx, hy) from corner 00: solves the
         * quadratic in t, then s from whichever axis is better conditioned.
         * True, with [s] and [t] set, when the pixel lies inside the cell.
         */
        private fun locate(hx: Float, hy: Float): Boolean {
            val k2 = gx * fy - gy * fx
            val k1 = ex * fy - ey * fx + hx * gy - hy * gx
            val k0 = hx * ey - hy * ex
            var found = false
            if (abs(k2) < DEGENERATE_EPS) {
                if (abs(k1) >= DEGENERATE_EPS) found = tryT(-k0 / k1, hx, hy)
            } else {
                val disc = k1 * k1 - 4f * k0 * k2
                if (disc >= 0f) {
                    val root = sqrt(disc)
                    found = tryT((-k1 - root) / (2f * k2), hx, hy) || tryT((-k1 + root) / (2f * k2), hx, hy)
                }
            }
            return found
        }

        private fun tryT(candidate: Float, hx: Float, hy: Float): Boolean {
            val dx = ex + gx * candidate
            val dy = ey + gy * candidate
            val candidateS = if (abs(dx) >= abs(dy)) {
                (hx - fx * candidate) / dx
            } else {
                (hy - fy * candidate) / dy
            }
            val inside = candidate in -EDGE_EPS..1f + EDGE_EPS && candidateS in -EDGE_EPS..1f + EDGE_EPS
            if (inside) {
                s = candidateS.coerceIn(0f, 1f)
                t = candidate.coerceIn(0f, 1f)
            }
            return inside
        }
    }

    /** Below this a cross product is treated as zero: the quad is a parallelogram along that axis. */
    private const val DEGENERATE_EPS = 1e-6f

    /** Slack on the cell's (s, t) bounds, so a pixel on a shared edge is not lost to rounding. */
    private const val EDGE_EPS = 1e-4f
}
