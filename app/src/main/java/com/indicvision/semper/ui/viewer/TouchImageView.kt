// Pan/zoom gesture view: literal touch thresholds and matrix math read clearest
// inline, so MagicNumber / ComplexCondition are suppressed for this whole file.
@file:Suppress("MagicNumber", "ComplexCondition")

@file:SuppressLint("ClickableViewAccessibility")

package com.indicvision.semper.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.hypot

class TouchImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    private var matrix = Matrix()
    private var mode = 0 // 0: None, 1: Drag, 2: Zoom
    private var last = PointF()
    private var start = PointF()
    private var minScale = 1f
    private var maxScale = 10f
    private var currentScale = 1f
    private var m: FloatArray = FloatArray(9)
    private var viewWidth = 0
    private var viewHeight = 0
    private var mScaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragArmed = false

    // --- CRITICAL FIX: Explicit dimensions provided by the Activity ---
    private var trueImageWidth = 0f
    private var trueImageHeight = 0f

    /** Maps display-bitmap pixels → true image space when the decode is downsampled. */
    private var contentScaleX = 1f
    private var contentScaleY = 1f

    var onMatrixChangedListener: (() -> Unit)? = null

    /** Confirmed short tap in view coordinates (not a pan or pinch). */
    var onTapListener: ((x: Float, y: Float) -> Unit)? = null

    /** Horizontal fling while fit-to-screen: −1 previous frame, +1 next. */
    var onScrubListener: ((delta: Int) -> Unit)? = null

    /** Center-third tap: hide/show chrome. */
    var onCenterTapListener: (() -> Unit)? = null

    /** Vertical swipe at 1×: true = show chrome, false = hide. */
    var onChromeSwipeListener: ((show: Boolean) -> Unit)? = null

    init {
        super.setClickable(true)
        mScaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
        scaleType = ScaleType.MATRIX

        setOnTouchListener { _, event ->
            mScaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            val curr = PointF(event.x, event.y)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    last.set(curr)
                    start.set(last)
                    mode = 1
                    dragArmed = false
                }
                MotionEvent.ACTION_MOVE -> if (mode == 1 && !mScaleDetector.isInProgress && event.pointerCount == 1) {
                    if (!dragArmed) {
                        val travelled = hypot(curr.x - start.x, curr.y - start.y)
                        if (travelled > touchSlop) dragArmed = true
                    }
                    if (dragArmed) {
                        val deltaX = curr.x - last.x
                        val deltaY = curr.y - last.y
                        matrix.postTranslate(deltaX, deltaY)
                        limitPan()
                        last.set(curr.x, curr.y)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        maybeFitSwipe(curr)
                    }
                    mode = 0
                    dragArmed = false
                }
            }
            publishMatrix()
            true
        }
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        updateContentScale(bm)
        publishMatrix()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        val w = drawable?.intrinsicWidth ?: 0
        val h = drawable?.intrinsicHeight ?: 0
        if (w > 0 && h > 0) {
            setTrueImageDimensions(w, h)
        }
    }

    private fun maybeFitSwipe(curr: PointF) {
        if (!isAtFitScale() || !dragArmed || mScaleDetector.isInProgress) return
        val dx = curr.x - start.x
        val dy = curr.y - start.y
        if (hypot(dx, dy) < SWIPE_DISTANCE) return
        if (abs(dx) > abs(dy)) {
            onScrubListener?.invoke(if (dx < 0f) 1 else -1)
        } else {
            onChromeSwipeListener?.invoke(dy > 0f)
        }
    }

    // --- NEW: Manually inject the known dimensions ---
    fun setTrueImageDimensions(width: Int, height: Int) {
        trueImageWidth = width.toFloat()
        trueImageHeight = height.toFloat()
        val bm = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        updateContentScale(bm)
        post {
            if (viewWidth > 0 && viewHeight > 0) fitToScreen()
        }
    }

    private fun updateContentScale(bm: Bitmap?) {
        if (bm != null && trueImageWidth > 0f && bm.width > 0) {
            contentScaleX = trueImageWidth / bm.width
            contentScaleY = trueImageHeight / bm.height
        } else {
            contentScaleX = 1f
            contentScaleY = 1f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        fitToScreen()
    }

    private fun fitToScreen() {
        if (trueImageWidth <= 0f || trueImageHeight <= 0f || viewWidth <= 0 || viewHeight <= 0) return

        val drawableRect = RectF(0f, 0f, trueImageWidth, trueImageHeight)
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())

        matrix.setRectToRect(drawableRect, viewRect, Matrix.ScaleToFit.CENTER)

        matrix.getValues(m)
        val baseScale = m[Matrix.MSCALE_X]

        minScale = baseScale
        currentScale = baseScale

        publishMatrix()
    }

    private fun isAtFitScale(): Boolean = currentScale <= minScale * 1.02f

    private fun toggleZoom(focusX: Float, focusY: Float) {
        if (!isAtFitScale()) {
            fitToScreen()
            return
        }
        val target = (minScale * 2f).coerceAtMost(maxScale)
        val factor = target / currentScale
        currentScale = target
        matrix.postScale(factor, factor, focusX, focusY)
        limitPan()
        publishMatrix()
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isCenterTap(e.x, e.y) && onCenterTapListener != null) {
                onCenterTapListener?.invoke()
                return true
            }
            onTapListener?.invoke(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            toggleZoom(e.x, e.y)
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            val accept = isAtFitScale() &&
                abs(velocityX) >= abs(velocityY) &&
                abs(velocityX) >= FLING_MIN_VELOCITY
            if (accept) {
                onScrubListener?.invoke(if (velocityX < 0f) 1 else -1)
            }
            return accept
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            mode = 2
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var scaleFactor = detector.scaleFactor
            val origScale = currentScale
            currentScale *= scaleFactor

            if (currentScale > maxScale) {
                currentScale = maxScale
                scaleFactor = maxScale / origScale
            } else if (currentScale < minScale) {
                currentScale = minScale
                scaleFactor = minScale / origScale
            }

            matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            limitPan()
            return true
        }
    }

    private fun limitPan() {
        matrix.getValues(m)
        val transX = m[Matrix.MTRANS_X]
        val transY = m[Matrix.MTRANS_Y]
        val scaleX = m[Matrix.MSCALE_X]
        val scaleY = m[Matrix.MSCALE_Y]

        // Use the mathematically guaranteed dimensions!
        val contentW = trueImageWidth * scaleX
        val contentH = trueImageHeight * scaleY

        var deltaX = 0f
        var deltaY = 0f

        if (contentW <= viewWidth) {
            val targetX = (viewWidth - contentW) / 2f
            deltaX = targetX - transX
        } else {
            if (transX > 0) {
                deltaX = -transX
            } else if (transX + contentW < viewWidth) {
                deltaX = viewWidth - (transX + contentW)
            }
        }

        if (contentH <= viewHeight) {
            val targetY = (viewHeight - contentH) / 2f
            deltaY = targetY - transY
        } else {
            if (transY > 0) {
                deltaY = -transY
            } else if (transY + contentH < viewHeight) {
                deltaY = viewHeight - (transY + contentH)
            }
        }

        if (deltaX != 0f || deltaY != 0f) {
            matrix.postTranslate(deltaX, deltaY)
        }
    }

    /** Logical zoom matrix in true image-pixel space (for overlays / probe). */
    fun getZoomMatrix(): Matrix = Matrix(matrix)

    private fun publishMatrix() {
        if (contentScaleX != 1f || contentScaleY != 1f) {
            val draw = Matrix(matrix)
            draw.preScale(contentScaleX, contentScaleY)
            imageMatrix = draw
        } else {
            imageMatrix = matrix
        }
        invalidate()
        onMatrixChangedListener?.invoke()
    }

    private fun isCenterTap(x: Float, y: Float): Boolean {
        if (viewWidth <= 0 || viewHeight <= 0) return false
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        return abs(x - cx) <= viewWidth * CENTER_FRACTION / 2f &&
            abs(y - cy) <= viewHeight * CENTER_FRACTION / 2f
    }

    private companion object {
        const val FLING_MIN_VELOCITY = 400f
        const val SWIPE_DISTANCE = 80f
        const val CENTER_FRACTION = 0.34f
    }
}
