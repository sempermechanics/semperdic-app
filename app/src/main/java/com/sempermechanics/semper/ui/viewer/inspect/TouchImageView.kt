// Pan/zoom gesture view: the gesture conditions and the gesture API surface
// read clearest whole, so ComplexCondition / TooManyFunctions are suppressed
// for this whole file.
@file:Suppress("ComplexCondition", "TooManyFunctions")

@file:SuppressLint("ClickableViewAccessibility")

package com.sempermechanics.semper.ui.viewer.inspect

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import com.sempermechanics.semper.ui.common.ViewportMath
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class TouchImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    private var matrix = Matrix()
    private var mode = 0 // 0: None, 1: Drag, 2: Zoom
    private var last = PointF()
    private var start = PointF()

    /** Pinch floor: full specimen contained in the chrome-safe box. */
    private var minScale = 1f

    /** Rest pose: heatmap / ROI contained in the chrome-safe box. */
    private var restScale = 1f
    private var maxScale = MAX_ZOOM
    private var currentScale = 1f
    private var m: FloatArray = FloatArray(MATRIX_VALUES)
    private var viewWidth = 0
    private var viewHeight = 0

    /** Chrome-reserved space the image must fit/pan within, not the raw view bounds. */
    private var contentInsetTop = 0
    private var contentInsetBottom = 0
    private var contentInsetLeft = 0
    private var contentInsetRight = 0
    private var mScaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragArmed = false

    /** One scrub per gesture: a fast swipe is both a fling and a long swipe. */
    private var scrubbedThisGesture = false

    // --- CRITICAL FIX: Explicit dimensions provided by the Activity ---
    private var trueImageWidth = 0f
    private var trueImageHeight = 0f

    /**
     * Image-pixel rectangle that rest-fit centres in the safe box (ROI or accepted
     * points). Empty / unset means the full specimen.
     */
    private var fitLeft = 0f
    private var fitTop = 0f
    private var fitRight = 0f
    private var fitBottom = 0f

    /** Maps display-bitmap pixels → true image space when the decode is downsampled. */
    private var contentScaleX = 1f
    private var contentScaleY = 1f

    var onMatrixChangedListener: (() -> Unit)? = null

    /** Confirmed short tap in view coordinates (not a pan or pinch). */
    var onTapListener: ((x: Float, y: Float) -> Unit)? = null

    /** Horizontal fling while fit-to-screen: −1 previous frame, +1 next. */
    var onScrubListener: ((delta: Int) -> Unit)? = null

    /**
     * Centre double-tap while chrome is hidden. Return true if the host showed
     * chrome (and zoom should not run); false to keep the usual zoom toggle.
     */
    var onCenterDoubleTapShowChrome: (() -> Boolean)? = null

    /** Vertical swipe at 1×: true = show chrome. Hide is timer-only. */
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
                    scrubbedThisGesture = false
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
        // GIF / unset views learn size from the drawable. Heatmaps already have
        // specimen pixels from setTrueImageDimensions — do not overwrite those.
        if (trueImageWidth <= 0f && w > 0 && h > 0) {
            setTrueImageDimensions(w, h)
            return
        }
        updateContentScale((drawable as? BitmapDrawable)?.bitmap)
        publishMatrix()
    }

    private fun maybeFitSwipe(curr: PointF) {
        if (!isAtRestScale() || !dragArmed || mScaleDetector.isInProgress) return
        val dx = curr.x - start.x
        val dy = curr.y - start.y
        if (hypot(dx, dy) < SWIPE_DISTANCE) return
        if (abs(dx) > abs(dy)) {
            // The GestureDetector sees ACTION_UP first, so a fling has already scrubbed.
            if (!scrubbedThisGesture) onScrubListener?.invoke(if (dx < 0f) 1 else -1)
        } else if (dy > 0f) {
            // Swipe down may show chrome; hide is timer-only.
            onChromeSwipeListener?.invoke(true)
        }
    }

    // --- NEW: Manually inject the known dimensions ---
    fun setTrueImageDimensions(width: Int, height: Int) {
        trueImageWidth = width.toFloat()
        trueImageHeight = height.toFloat()
        if (fitRight <= fitLeft || fitBottom <= fitTop) {
            fitLeft = 0f
            fitTop = 0f
            fitRight = trueImageWidth
            fitBottom = trueImageHeight
        }
        val bm = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        updateContentScale(bm)
        post {
            if (viewWidth > 0 && viewHeight > 0) fitToScreen()
        }
    }

    /**
     * Image-pixel rectangle to contain at rest (ROI or accepted-point bounds).
     * Logical coordinates stay the full specimen; this only changes rest zoom.
     */
    fun setFitBounds(left: Float, top: Float, right: Float, bottom: Float) {
        val l = left.coerceIn(0f, trueImageWidth.coerceAtLeast(0f))
        val t = top.coerceIn(0f, trueImageHeight.coerceAtLeast(0f))
        val r = right.coerceIn(l, trueImageWidth.coerceAtLeast(l))
        val b = bottom.coerceIn(t, trueImageHeight.coerceAtLeast(t))
        if (l == fitLeft && t == fitTop && r == fitRight && b == fitBottom) return
        val wasAtRest = isAtRestScale()
        fitLeft = l
        fitTop = t
        fitRight = r
        fitBottom = b
        if (wasAtRest) {
            fitToScreen()
        } else {
            recomputeScaleLimits()
            limitPan()
            publishMatrix()
        }
    }

    /**
     * Chrome-reserved space (in view pixels) the fit/pan math should treat as
     * off-limits — e.g. the top bar and bottom scrubber — so the image sits
     * framed by those bars. Optional left/right insets stay available; the
     * colour scale is a fixed overlay, not an inset. Re-fits only if currently
     * at rest scale; otherwise refreshes the pan clamp.
     */
    fun setContentInsets(top: Int, bottom: Int, left: Int = 0, right: Int = 0) {
        if (top == contentInsetTop &&
            bottom == contentInsetBottom &&
            left == contentInsetLeft &&
            right == contentInsetRight
        ) {
            return
        }
        val wasAtRest = isAtRestScale()
        contentInsetTop = top
        contentInsetBottom = bottom
        contentInsetLeft = left
        contentInsetRight = right
        if (wasAtRest) {
            fitToScreen()
        } else {
            limitPan()
            publishMatrix()
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
        if (oldw <= 0 || oldh <= 0 || isAtRestScale()) {
            fitToScreen()
            return
        }
        // Zoomed in: a sibling changing height (e.g. a longer instruction) must not
        // throw the zoom away mid-task. Keep the scale and the centre point.
        recomputeScaleLimits()
        matrix.postTranslate((w - oldw) / 2f, (h - oldh) / 2f)
        limitPan()
        publishMatrix()
    }

    private fun safeViewRect(): RectF? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val safeLeft = contentInsetLeft.toFloat()
        val safeTop = contentInsetTop.toFloat()
        val safeRight = (viewWidth - contentInsetRight).toFloat()
        val safeBottom = (viewHeight - contentInsetBottom).toFloat()
        return if (safeRight > safeLeft && safeBottom > safeTop) {
            RectF(safeLeft, safeTop, safeRight, safeBottom)
        } else {
            null
        }
    }

    private fun containScale(src: RectF, dst: RectF): Float {
        val sw = src.width()
        val sh = src.height()
        if (sw <= 0f || sh <= 0f) return 1f
        return min(dst.width() / sw, dst.height() / sh)
    }

    private fun recomputeScaleLimits() {
        val viewRect = safeViewRect() ?: return
        if (trueImageWidth <= 0f || trueImageHeight <= 0f) return
        val full = RectF(0f, 0f, trueImageWidth, trueImageHeight)
        val fit = fitRect()
        minScale = containScale(full, viewRect)
        restScale = containScale(fit, viewRect).coerceAtLeast(minScale)
    }

    private fun fitRect(): RectF {
        if (fitRight > fitLeft && fitBottom > fitTop) {
            return RectF(fitLeft, fitTop, fitRight, fitBottom)
        }
        return RectF(0f, 0f, trueImageWidth, trueImageHeight)
    }

    /** Rest pose: contain the heatmap/ROI rect in the chrome-safe box. */
    private fun fitToScreen() {
        if (trueImageWidth <= 0f || trueImageHeight <= 0f) return
        val viewRect = safeViewRect() ?: return
        recomputeScaleLimits()

        val fit = fitRect()
        matrix.setRectToRect(fit, viewRect, Matrix.ScaleToFit.CENTER)
        matrix.getValues(m)
        currentScale = m[Matrix.MSCALE_X]
        // Keep currentScale aligned with restScale after setRectToRect rounding.
        restScale = currentScale.coerceAtLeast(minScale)

        publishMatrix()
    }

    private fun isAtRestScale(): Boolean =
        currentScale <= restScale * (1f + REST_TOLERANCE) && currentScale >= restScale * (1f - REST_TOLERANCE)

    private fun toggleZoom(focusX: Float, focusY: Float) {
        if (!isAtRestScale()) {
            fitToScreen()
            return
        }
        val target = (restScale * 2f).coerceAtMost(maxScale)
        val factor = target / currentScale
        currentScale = target
        matrix.postScale(factor, factor, focusX, focusY)
        limitPan()
        publishMatrix()
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTapListener?.invoke(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isCenterTap(e.x, e.y) && onCenterDoubleTapShowChrome?.invoke() == true) {
                return true
            }
            toggleZoom(e.x, e.y)
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            val accept = isAtRestScale() &&
                abs(velocityX) >= abs(velocityY) &&
                abs(velocityX) >= FLING_MIN_VELOCITY
            if (accept && !scrubbedThisGesture) {
                scrubbedThisGesture = true
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
            val step = ViewportMath.clampScale(currentScale, detector.scaleFactor, minScale, maxScale)
            currentScale = step.scale
            matrix.postScale(step.factor, step.factor, detector.focusX, detector.focusY)
            limitPan()
            return true
        }
    }

    /** Keeps the image covering the chrome-safe box, or centred in it when it fits. */
    private fun limitPan() {
        matrix.getValues(m)
        // The true image size, not the (possibly downsampled) drawable's.
        val deltaX = ViewportMath.panCorrection(
            trans = m[Matrix.MTRANS_X],
            content = trueImageWidth * m[Matrix.MSCALE_X],
            safeStart = contentInsetLeft.toFloat(),
            safeEnd = (viewWidth - contentInsetRight).toFloat(),
        )
        val deltaY = ViewportMath.panCorrection(
            trans = m[Matrix.MTRANS_Y],
            content = trueImageHeight * m[Matrix.MSCALE_Y],
            safeStart = contentInsetTop.toFloat(),
            safeEnd = (viewHeight - contentInsetBottom).toFloat(),
        )
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
        const val MAX_ZOOM = 10f

        /** A [Matrix]'s values, as [Matrix.getValues] fills them. */
        const val MATRIX_VALUES = 9

        /** How near the rest scale still counts as at rest, as a fraction of it. */
        const val REST_TOLERANCE = 0.02f
        const val FLING_MIN_VELOCITY = 400f
        const val SWIPE_DISTANCE = 80f
        const val CENTER_FRACTION = 0.34f
    }
}
