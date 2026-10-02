package com.indicvision.semper.ui.analysis.sweep

import android.graphics.RectF
import android.view.MotionEvent
import com.indicvision.semper.ui.common.ViewportMath
import kotlin.math.abs
import kotlin.math.max

/** A data-space rectangle: the x and y ranges in the series' own units. */
internal class PlotBounds(val xMin: Float, val xMax: Float, val yMin: Float, val yMax: Float)

/**
 * The full extent of [series]' points, with [yMarginFraction] of head-room
 * above and below so markers are not clipped; null when there are no points.
 * One primitive pass over every point, no intermediate lists.
 */
internal fun plotBoundsOf(series: List<VsgPlotView.Series>, yMarginFraction: Float): PlotBounds? {
    var xMin = Float.POSITIVE_INFINITY
    var xMax = Float.NEGATIVE_INFINITY
    var yMin = Float.POSITIVE_INFINITY
    var yMax = Float.NEGATIVE_INFINITY
    var any = false
    for (s in series) {
        for (p in s.points) {
            any = true
            if (p.first < xMin) xMin = p.first
            if (p.first > xMax) xMax = p.first
            if (p.second < yMin) yMin = p.second
            if (p.second > yMax) yMax = p.second
        }
    }
    if (!any) return null
    val span = max(yMax - yMin, abs(yMax) * yMarginFraction).takeIf { it > 0f } ?: 1f
    yMin -= span * yMarginFraction
    yMax += span * yMarginFraction
    return PlotBounds(xMin, if (xMax > xMin) xMax else xMin + 1f, yMin, yMax)
}

/**
 * The plot's zoom and pan, held as a data-space window rather than a Canvas
 * matrix (which would scale strokes and tick labels). No window means the
 * full extent. Every change is clamped by [ViewportMath.clampWindow]: never
 * narrower than [minSpanFraction] of the extent, never outside it.
 */
internal class VsgPlotViewport(private val minSpanFraction: Float) {

    /** Null = show the full extent; otherwise the zoomed window. */
    private var window: PlotBounds? = null

    /** What is on screen out of [full]. */
    fun visible(full: PlotBounds): PlotBounds = window ?: full

    /** Back to the full extent. */
    fun reset() {
        window = null
    }

    /**
     * A pinch step: scales the window by [factor] about the view point
     * ([focusXPx], [focusYPx]) in [frame], keeping the data point under it fixed.
     */
    fun zoomAbout(full: PlotBounds, focusXPx: Float, focusYPx: Float, factor: Float, frame: RectF) {
        val vp = visible(full)
        val focusX = pxToDataX(focusXPx, frame, vp)
        val focusY = pxToDataY(focusYPx, frame, vp)
        val xSpan = ((vp.xMax - vp.xMin) / factor).coerceAtLeast((full.xMax - full.xMin) * minSpanFraction)
        val ySpan = ((vp.yMax - vp.yMin) / factor).coerceAtLeast((full.yMax - full.yMin) * minSpanFraction)
        // Keep the focus point fixed in data space.
        val leftFrac = (focusX - vp.xMin) / (vp.xMax - vp.xMin).coerceAtLeast(MIN_DIVISOR)
        val bottomFrac = (focusY - vp.yMin) / (vp.yMax - vp.yMin).coerceAtLeast(MIN_DIVISOR)
        val xMin = focusX - leftFrac * xSpan
        val yMin = focusY - bottomFrac * ySpan
        setClamped(full, PlotBounds(xMin, xMin + xSpan, yMin, yMin + ySpan))
    }

    /** A two-finger pan of ([dxPx], [dyPx]) view px over [frame]: the data follows the fingers. */
    fun panByPx(full: PlotBounds, dxPx: Float, dyPx: Float, frame: RectF) {
        val vp = visible(full)
        val dxData = -dxPx / (frame.right - frame.left) * (vp.xMax - vp.xMin)
        val dyData = dyPx / (frame.bottom - frame.top) * (vp.yMax - vp.yMin)
        setClamped(full, PlotBounds(vp.xMin + dxData, vp.xMax + dxData, vp.yMin + dyData, vp.yMax + dyData))
    }

    private fun setClamped(full: PlotBounds, next: PlotBounds) {
        val x = ViewportMath.clampWindow(next.xMin, next.xMax, full.xMin, full.xMax, minSpanFraction)
        val y = ViewportMath.clampWindow(next.yMin, next.yMax, full.yMin, full.yMax, minSpanFraction)
        window = PlotBounds(x.min, x.max, y.min, y.max)
    }

    private companion object {
        /** Guards the focus fraction against a window with no width. */
        const val MIN_DIVISOR = 1e-6f
    }
}

/** Data x at view x [xPx] in [frame] showing [b]; clamped to the frame's edges. */
internal fun pxToDataX(xPx: Float, frame: RectF, b: PlotBounds): Float {
    val ratio = ((xPx - frame.left) / (frame.right - frame.left)).coerceIn(0f, 1f)
    return b.xMin + ratio * (b.xMax - b.xMin)
}

/** Data y at view y [yPx] in [frame] showing [b]; clamped to the frame's edges. */
internal fun pxToDataY(yPx: Float, frame: RectF, b: PlotBounds): Float {
    val ratio = ((frame.bottom - yPx) / (frame.bottom - frame.top)).coerceIn(0f, 1f)
    return b.yMin + ratio * (b.yMax - b.yMin)
}

/**
 * Linear y of [points] (ordered along x) at [x]: an end's value past that end,
 * the left point's over a zero-width step; null with no points.
 */
@Suppress("ReturnCount") // empty, both clamps, the degenerate span and the interpolated hit
internal fun interpolateY(points: List<Pair<Float, Float>>, x: Float): Float? {
    if (points.isEmpty()) return null
    if (x <= points.first().first) return points.first().second
    if (x >= points.last().first) return points.last().second
    for (i in 0 until points.lastIndex) {
        val (x0, y0) = points[i]
        val (x1, y1) = points[i + 1]
        if (x in x0..x1) {
            val span = (x1 - x0).takeIf { it != 0f } ?: return y0
            val t = (x - x0) / span
            return y0 + t * (y1 - y0)
        }
    }
    return null
}

/** Mean x of the pointers down: where a two-finger pan is anchored. */
internal fun MotionEvent.focusX(): Float {
    var sum = 0f
    for (i in 0 until pointerCount) sum += getX(i)
    return sum / pointerCount
}

/** Mean y of the pointers down: where a two-finger pan is anchored. */
internal fun MotionEvent.focusY(): Float {
    var sum = 0f
    for (i in 0 until pointerCount) sum += getY(i)
    return sum / pointerCount
}
