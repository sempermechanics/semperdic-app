package com.indicvision.semper.ui.common

/**
 * The zoom and pan clamps the zoomable views each wrote for themselves, one
 * axis at a time and ported operation for operation, so a view that adopts
 * them keeps its float results bit for bit (`ViewportMathTest` checks each
 * against the view it came from).
 *
 * Three ways of holding a viewport are in use, and each keeps its own clamp:
 * - **Translation** (`TouchImageView`): content of a given size sits at a
 *   translation in view px — [panCorrection], [clampScale].
 * - **Centre fraction** (`RoiViewport`, behind `StudioOverlayView`): the
 *   image point at the view centre, 0..1 of the side — [centerFraction].
 * - **Data window** (`VsgPlotView`): the visible data range, inside the full
 *   extent — [clampWindow].
 *
 * The rule is the same for all three: content larger than the window always
 * covers it, and content no larger than the window is centred (or, for a data
 * window, shows the full extent).
 */
object ViewportMath {

    /**
     * The shift that keeps content of [content] px, drawn from [trans],
     * covering the safe span [safeStart]..[safeEnd]; or centres it there when
     * it fits. Zero when nothing needs to move. `TouchImageView.limitPan`, per axis.
     */
    fun panCorrection(trans: Float, content: Float, safeStart: Float, safeEnd: Float): Float {
        val safe = safeEnd - safeStart
        return if (content <= safe) {
            val target = safeStart + (safe - content) / 2f
            target - trans
        } else if (trans > safeStart) {
            safeStart - trans
        } else if (trans + content < safeEnd) {
            safeEnd - (trans + content)
        } else {
            0f
        }
    }

    /** A pinch step's result: the new scale and the factor that reaches it from the old one. */
    data class ScaleStep(val scale: Float, val factor: Float)

    /**
     * Scales [current] by [factor] within [min]..[max]. At a limit the factor
     * is recomputed so applying it lands exactly on that limit.
     * `TouchImageView`'s pinch.
     */
    fun clampScale(current: Float, factor: Float, min: Float, max: Float): ScaleStep {
        val scaled = current * factor
        return when {
            scaled > max -> ScaleStep(max, max / current)
            scaled < min -> ScaleStep(min, min / current)
            else -> ScaleStep(scaled, factor)
        }
    }

    /**
     * The centre fraction (0..1 of the content) that keeps content of [size]
     * px covering a [view] px window; 0.5 when it fits or has no size.
     * `RoiViewport.clampAxis`.
     */
    fun centerFraction(center: Float, view: Float, size: Float): Float {
        if (size <= view || size <= 0f) return HALF
        val half = view / 2f / size
        return center.coerceIn(half, 1f - half)
    }

    /** A visible data range on one axis. */
    data class Window(val min: Float, val max: Float)

    /**
     * Keeps [min]..[max] inside [fullMin]..[fullMax] and at least
     * [minSpanFraction] of the full extent wide (widened about its middle).
     * A window as wide as the extent becomes the extent; a narrower one is
     * slid back inside it, keeping its width. `VsgPlotView.clampViewport`, per axis.
     */
    fun clampWindow(min: Float, max: Float, fullMin: Float, fullMax: Float, minSpanFraction: Float): Window {
        var lo = min
        var hi = max
        val minSpan = (fullMax - fullMin) * minSpanFraction
        if (hi - lo < minSpan) {
            val mid = (lo + hi) / 2f
            lo = mid - minSpan / 2f
            hi = mid + minSpan / 2f
        }
        val span = hi - lo
        if (span >= fullMax - fullMin) {
            lo = fullMin
            hi = fullMax
        } else {
            if (lo < fullMin) {
                lo = fullMin
                hi = lo + span
            }
            if (hi > fullMax) {
                hi = fullMax
                lo = hi - span
            }
        }
        return Window(lo, hi)
    }

    private const val HALF = 0.5f
}
