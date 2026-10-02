package com.indicvision.semper.ui.analysis.roi

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.Roi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The part of [rect] under ([x], [y]): a corner within [slop] of it (checked
 * top-left, top-right, bottom-left, bottom-right), else its body, else null.
 */
internal fun hitState(rect: RectF, x: Float, y: Float, slop: Float): StudioOverlayView.TouchState? {
    fun near(a: Float, b: Float) = abs(a - b) < slop
    return when {
        near(x, rect.left) && near(y, rect.top) -> StudioOverlayView.TouchState.TOP_LEFT
        near(x, rect.right) && near(y, rect.top) -> StudioOverlayView.TouchState.TOP_RIGHT
        near(x, rect.left) && near(y, rect.bottom) -> StudioOverlayView.TouchState.BOTTOM_LEFT
        near(x, rect.right) && near(y, rect.bottom) -> StudioOverlayView.TouchState.BOTTOM_RIGHT
        rect.contains(x, y) -> StudioOverlayView.TouchState.CENTER
        else -> null
    }
}

/**
 * Moves [target] by ([dx], [dy]) inside [bounds] as [handle] drags it: the
 * body slides without leaving [bounds]; a corner resizes, no smaller than
 * [minSize] a side, and when [square] stays square about the opposite corner.
 */
@Suppress("LongParameterList") // the rect, the drag, and the limits it is held to
internal fun dragRect(
    target: RectF,
    handle: StudioOverlayView.TouchState,
    dx: Float,
    dy: Float,
    bounds: RectF,
    minSize: Float,
    square: Boolean,
) {
    when (handle) {
        StudioOverlayView.TouchState.CENTER -> {
            val newLeft = safeCoerce(target.left + dx, bounds.left, bounds.right - target.width())
            val newTop = safeCoerce(target.top + dy, bounds.top, bounds.bottom - target.height())
            target.offsetTo(newLeft, newTop)
        }
        StudioOverlayView.TouchState.TOP_LEFT -> {
            target.left = safeCoerce(target.left + dx, bounds.left, target.right - minSize)
            target.top = safeCoerce(target.top + dy, bounds.top, target.bottom - minSize)
            if (square) makeSquare(target.right, target.bottom, bounds, target, minSize)
        }
        StudioOverlayView.TouchState.TOP_RIGHT -> {
            target.right = safeCoerce(target.right + dx, target.left + minSize, bounds.right)
            target.top = safeCoerce(target.top + dy, bounds.top, target.bottom - minSize)
            if (square) makeSquare(target.left, target.bottom, bounds, target, minSize)
        }
        StudioOverlayView.TouchState.BOTTOM_LEFT -> {
            target.left = safeCoerce(target.left + dx, bounds.left, target.right - minSize)
            target.bottom = safeCoerce(target.bottom + dy, target.top + minSize, bounds.bottom)
            if (square) makeSquare(target.right, target.top, bounds, target, minSize)
        }
        StudioOverlayView.TouchState.BOTTOM_RIGHT -> {
            target.right = safeCoerce(target.right + dx, target.left + minSize, bounds.right)
            target.bottom = safeCoerce(target.bottom + dy, target.top + minSize, bounds.bottom)
            if (square) makeSquare(target.left, target.top, bounds, target, minSize)
        }
        StudioOverlayView.TouchState.NONE, StudioOverlayView.TouchState.DRAWING -> Unit
    }
}

/** [value] within [min]..[max]; when the range has closed up ([max] < [min]) it pins to [min]. */
internal fun safeCoerce(value: Float, min: Float, max: Float): Float {
    val actualMax = if (max < min) min else max
    return value.coerceIn(min, actualMax)
}

/**
 * Squares [targetRect] about the fixed corner ([pivotX], [pivotY]): its longer
 * side, cut to what fits in [bounds] on the growing sides, never under [minSize].
 */
internal fun makeSquare(pivotX: Float, pivotY: Float, bounds: RectF, targetRect: RectF, minSize: Float) {
    val currentW = abs(targetRect.right - targetRect.left)
    val currentH = abs(targetRect.bottom - targetRect.top)
    val desiredSide = max(currentW, currentH)

    val growLeft = targetRect.left != pivotX && targetRect.left < pivotX
    val growRight = targetRect.right != pivotX && targetRect.right > pivotX
    val growTop = targetRect.top != pivotY && targetRect.top < pivotY
    val growBottom = targetRect.bottom != pivotY && targetRect.bottom > pivotY

    var maxSide = desiredSide
    if (growLeft) maxSide = min(maxSide, pivotX - bounds.left)
    if (growRight) maxSide = min(maxSide, bounds.right - pivotX)
    if (growTop) maxSide = min(maxSide, pivotY - bounds.top)
    if (growBottom) maxSide = min(maxSide, bounds.bottom - pivotY)

    val finalSide = max(maxSide, minSize)
    val newLeft = if (growLeft) pivotX - finalSide else pivotX
    val newRight = if (growRight) pivotX + finalSide else pivotX
    val newTop = if (growTop) pivotY - finalSide else pivotY
    val newBottom = if (growBottom) pivotY + finalSide else pivotY

    targetRect.set(
        newLeft.coerceIn(bounds.left, bounds.right),
        newTop.coerceIn(bounds.top, bounds.bottom),
        newRight.coerceIn(bounds.left, bounds.right),
        newBottom.coerceIn(bounds.top, bounds.bottom),
    )
}

/**
 * A typed [rect] in image px, in view px on an [image] drawn into [bounds];
 * null when the image size is unknown or the rect is empty. The origin is
 * pulled onto the image and the far edges cut at it, so a rect starting off
 * the image keeps its size where it can: not [Roi.clampTo]'s clip.
 */
internal fun imageRectInView(rect: Roi, image: ImageSize, bounds: RectF): RectF? {
    if (!image.isKnown || rect.w <= 0 || rect.h <= 0) return null

    val leftPx = rect.x.coerceIn(0, image.width - 1)
    val topPx = rect.y.coerceIn(0, image.height - 1)
    // Int edges, as typed: a huge width overflows here and is refused below.
    val rightPx = (leftPx + rect.w).coerceAtMost(image.width)
    val bottomPx = (topPx + rect.h).coerceAtMost(image.height)

    val scaleX = bounds.width() / image.width.toFloat()
    val scaleY = bounds.height() / image.height.toFloat()
    return if (rightPx <= leftPx || bottomPx <= topPx) {
        null
    } else {
        RectF(
            bounds.left + leftPx * scaleX,
            bounds.top + topPx * scaleY,
            bounds.left + rightPx * scaleX,
            bounds.top + bottomPx * scaleY,
        )
    }
}

/** [view] (view px) in image px, for an image drawn into [bounds] at [scale] image px per view px. */
internal fun viewToImage(view: RectF, bounds: RectF, scale: Float): RectF = RectF(
    (view.left - bounds.left) * scale,
    (view.top - bounds.top) * scale,
    (view.right - bounds.left) * scale,
    (view.bottom - bounds.top) * scale,
)

/** Inverse of [viewToImage]: [image] (image px, fractional) in view px. */
internal fun imageToView(image: RectF, bounds: RectF, scale: Float): RectF = RectF(
    bounds.left + image.left / scale,
    bounds.top + image.top / scale,
    bounds.left + image.right / scale,
    bounds.top + image.bottom / scale,
)

/** A dot of [radius] on each corner of [rect], in [paint]: the handles a finger grabs. */
internal fun drawHandles(canvas: Canvas, rect: RectF, radius: Float, paint: Paint) {
    canvas.drawCircle(rect.left, rect.top, radius, paint)
    canvas.drawCircle(rect.right, rect.top, radius, paint)
    canvas.drawCircle(rect.left, rect.bottom, radius, paint)
    canvas.drawCircle(rect.right, rect.bottom, radius, paint)
}
