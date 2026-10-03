package com.sempermechanics.semper.field

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

// The android.graphics forms of a [Roi], kept out of Roi.kt so the type itself
// stays plain JVM.

/** `Rect(x, y, x + w, y + h)`. */
fun Roi.toRect(): Rect = Rect(x, y, right, bottom)

/** From a [Rect]'s edges. */
fun Roi.Companion.fromRect(rect: Rect): Roi = fromLtrb(rect.left, rect.top, rect.right, rect.bottom)

/**
 * From the ROI editor's float selection in image pixels, each edge rounded
 * to the nearest pixel, then clamped: the left/top edges at 0, the
 * right/bottom edges at the image size. The result can be empty; the editor
 * rejects that itself.
 */
fun Roi.Companion.fromImageRect(rect: RectF, size: ImageSize): Roi = fromLtrb(
    rect.left.roundToInt().coerceAtLeast(0),
    rect.top.roundToInt().coerceAtLeast(0),
    rect.right.roundToInt().coerceAtMost(size.width),
    rect.bottom.roundToInt().coerceAtMost(size.height),
)
