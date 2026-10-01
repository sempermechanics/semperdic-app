// Custom ROI overlay view: coordinate mapping, gesture/hit-testing and mask
// serialization. Complexity is inherent; suppress rather than baseline so new
// findings elsewhere still fail CI.

@file:Suppress(
    "TooManyFunctions",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
)
@file:SuppressLint("ClickableViewAccessibility")

package com.indicvision.semper.ui.analysis

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.toColorInt
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class StudioOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // Store the actual image dimensions for perfect coordinate scaling
    var realImageWidth: Int = 0
    var realImageHeight: Int = 0

    // --- 1. IMAGE BOUNDARY TRACKING ---
    private var imageBounds = RectF()

    /** Pinch / pan / double-tap state; [imageBounds] is derived from it. */
    private val viewport = RoiViewport()
    private val nextBounds = RectF()
    private val drawableRect = RectF()
    private val imageMatrix = Matrix()

    /** Zoom over the fit view, reported after every pinch or double-tap. */
    var onZoomChangedListener: ((Float) -> Unit)? = null
    val zoom: Float get() = viewport.zoom

    /** Reused every draw: the drag rect is rebuilt on each touch move. */
    private val activeHoleScratch = RectF()

    /** Reused every draw for the main ROI while it is dragged or implied. */
    private val mainRectScratch = RectF()
    var onRoiChangedListener: ((RectF) -> Unit)? = null
    var imageView: ImageView? = null
        set(value) {
            field = value
            if (value?.width ?: 0 > 0) updateImageBounds()
        }

    private var pendingRestoreRoi: RectF? = null

    fun updateImageBounds() {
        val iv = imageView ?: return
        val drawable = iv.drawable ?: return
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        val viewWidth = iv.width.toFloat()
        val viewHeight = iv.height.toFloat()
        // A canvas squeezed to nothing (keyboard + dock taller than the screen)
        // keeps the last bounds, so the ROI still maps back when it regrows.
        if (imageWidth == 0f || imageHeight == 0f || viewWidth <= 0f || viewHeight <= 0f) return

        viewport.layout(imageWidth, imageHeight, viewWidth, viewHeight)
        viewport.bounds(nextBounds)

        // Remap live geometry when letterboxing changes (toolbar/IME resize) or
        // the canvas is zoomed or panned: crop and holes stay on the same image px.
        val liveRoi = if (!imageBounds.isEmpty && hasValidRoi && pendingRestoreRoi == null) {
            getRelativeRoi()
        } else {
            null
        }
        val liveHoles = if (!imageBounds.isEmpty && holes.isNotEmpty()) {
            holes.map { it.mode to viewRectToImage(it.rect) }
        } else {
            emptyList()
        }

        imageBounds.set(nextBounds)
        // The ImageView draws through the same rect, so photo and overlay move together.
        drawableRect.set(0f, 0f, imageWidth, imageHeight)
        imageMatrix.setRectToRect(drawableRect, imageBounds, Matrix.ScaleToFit.FILL)
        iv.scaleType = ImageView.ScaleType.MATRIX
        iv.imageMatrix = imageMatrix

        if (liveRoi != null && liveRoi.width() > 0f && liveRoi.height() > 0f) {
            pendingRestoreRoi = liveRoi
        }
        applyPendingRestore()

        if (liveHoles.isNotEmpty()) {
            // Float remap, as for the ROI: rounding to whole pixels here lost up
            // to a pixel per edge on every keyboard open/close.
            holes.clear()
            for ((mode, img) in liveHoles) {
                holes.add(Hole(mode, imageRectToView(img)))
            }
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImageBounds()
    }

    // --- 2. LIFECYCLE RESTORE ---
    fun restoreRelativeRoi(savedRoi: RectF) {
        pendingRestoreRoi = savedRoi
        if (!imageBounds.isEmpty) {
            applyPendingRestore()
        }
    }

    /**
     * Places the main crop from image-pixel [x], [y], [width], [height].
     * Clamps to the image; returns false if the size is non-positive or bounds
     * are not ready yet.
     */
    fun applyImageRoi(x: Int, y: Int, width: Int, height: Int): Boolean {
        val mapped = mapImageRectToView(x, y, width, height) ?: return false
        roiRect.set(mapped)
        hasValidRoi = true
        invalidate()
        onRoiChangedListener?.invoke(getRelativeRoi())
        return true
    }

    /**
     * Adds an erase (hole) rect from image-pixel [x], [y], [width], [height].
     * Same clamp rules as [applyImageRoi].
     */
    fun applyImageHole(x: Int, y: Int, width: Int, height: Int): Boolean {
        val mapped = mapImageRectToView(x, y, width, height) ?: return false
        holes.add(Hole(currentMode, RectF(mapped)))
        invalidate()
        onRoiChangedListener?.invoke(getRelativeRoi())
        return true
    }

    /** Image-pixel bounds of the last erase rect, or empty if there are none. */
    fun lastHoleRelative(): RectF {
        val hole = holes.lastOrNull() ?: return RectF()
        return viewRectToImage(hole.rect)
    }

    private fun mapImageRectToView(x: Int, y: Int, width: Int, height: Int): RectF? {
        if (realImageWidth <= 0 || realImageHeight <= 0 || imageBounds.isEmpty) return null
        if (width <= 0 || height <= 0) return null

        val leftPx = x.coerceIn(0, realImageWidth - 1)
        val topPx = y.coerceIn(0, realImageHeight - 1)
        val rightPx = (leftPx + width).coerceAtMost(realImageWidth)
        val bottomPx = (topPx + height).coerceAtMost(realImageHeight)
        if (rightPx <= leftPx || bottomPx <= topPx) return null

        val scaleX = imageBounds.width() / realImageWidth.toFloat()
        val scaleY = imageBounds.height() / realImageHeight.toFloat()
        return RectF(
            imageBounds.left + leftPx * scaleX,
            imageBounds.top + topPx * scaleY,
            imageBounds.left + rightPx * scaleX,
            imageBounds.top + bottomPx * scaleY,
        )
    }

    private fun viewRectToImage(viewRect: RectF): RectF {
        if (imageBounds.isEmpty || imageBounds.width() == 0f) return RectF()
        val scale = if (realImageWidth > 0) {
            realImageWidth.toFloat() / imageBounds.width()
        } else {
            (imageView?.drawable?.intrinsicWidth?.toFloat() ?: 1f) / imageBounds.width()
        }
        return RectF(
            (viewRect.left - imageBounds.left) * scale,
            (viewRect.top - imageBounds.top) * scale,
            (viewRect.right - imageBounds.left) * scale,
            (viewRect.bottom - imageBounds.top) * scale,
        )
    }

    /** Inverse of [viewRectToImage]: image pixels (fractional) to view coordinates. */
    private fun imageRectToView(img: RectF): RectF {
        val scale = if (realImageWidth > 0) {
            realImageWidth.toFloat() / imageBounds.width()
        } else {
            (imageView?.drawable?.intrinsicWidth?.toFloat() ?: 1f) / imageBounds.width()
        }
        return RectF(
            imageBounds.left + img.left / scale,
            imageBounds.top + img.top / scale,
            imageBounds.left + img.right / scale,
            imageBounds.top + img.bottom / scale,
        )
    }

    private fun applyPendingRestore() {
        pendingRestoreRoi?.let { saved ->
            // Map the physical image coordinates back to the scaled screen view
            roiRect.set(imageRectToView(saved))
            hasValidRoi = true
            invalidate()
        }
        pendingRestoreRoi = null
    }

    // --- 3. RESET ---
    fun reset() {
        hasValidRoi = false
        isDrawing = false
        roiRect.setEmpty()
        holes.clear()
        touchState = TouchState.NONE
        activeHoleIndex = -1 // Prevent stale index crash!
        invalidate()
        onRoiChangedListener?.invoke(RectF())
    }

    /** Crop and erase are rectangles; the mask encoder relies on that (TD-74). */
    enum class RoiMode { RECTANGLE, SQUARE }
    var currentMode = RoiMode.RECTANGLE

    // Hole Tracking Variables
    var isSubtractMode = false
    data class Hole(val mode: RoiMode, val rect: RectF)
    val holes = mutableListOf<Hole>()

    private val holeFillPaint = Paint().apply {
        color = "#88FF0000".toColorInt()
        style = Paint.Style.FILL
    }
    private val holeBorderPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    var hasValidRoi = false
        private set

    private var roiRect = RectF()
    private val minSize = 50f

    // Drawing variables
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isDrawing = false

    private enum class TouchState { NONE, CENTER, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var touchState = TouchState.NONE

    private val borderPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val dimPaint = Paint().apply {
        color = "#99000000".toColorInt()
        style = Paint.Style.FILL
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL
    }
    private fun safeCoerce(value: Float, min: Float, max: Float): Float {
        val actualMax = if (max < min) min else max
        return value.coerceIn(min, actualMax)
    }

    // --- 4. ZOOM AND PAN ---
    // Two fingers pinch and pan; double-tap toggles 2x and fit. One finger
    // always edits, so drawing never fights the zoom. Handles and the minimum
    // size stay in view px: zoomed in, they are finer in image px.

    /** True from a second finger (or a double-tap) until every finger lifts. */
    private var viewportGesture = false
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinchSpan = 0f

    /** The grabbed rect as it was at ACTION_DOWN, put back if the touch turns into a pinch. */
    private val editStart = RectF()

    private val doubleTapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                cancelEdit()
                viewport.beginPan()
                viewport.toggle(e.x, e.y)
                applyViewport()
                viewportGesture = true
                trackPinch(e)
                return true
            }
        },
    ).apply { setIsLongpressEnabled(false) }

    /** Back to fit (zoom 1). */
    fun resetZoom() {
        viewport.reset()
        applyViewport()
    }

    private fun applyViewport() {
        updateImageBounds()
        invalidate()
        onZoomChangedListener?.invoke(viewport.zoom)
    }

    /** Returns true when [event] belongs to a zoom/pan gesture, not to editing. */
    private fun handleViewportGesture(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) viewportGesture = false
        doubleTapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!viewportGesture) {
                    cancelEdit()
                    viewport.beginPan()
                    viewportGesture = true
                }
                trackPinch(event)
            }
            MotionEvent.ACTION_POINTER_UP -> if (viewportGesture) settleThenTrack(event)
            MotionEvent.ACTION_MOVE -> if (viewportGesture) pinchMove(event)
            MotionEvent.ACTION_UP -> if (viewportGesture) {
                pinchMove(event, lifting = NO_POINTER, settle = true)
                viewportGesture = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (viewportGesture) {
                viewportGesture = false
                return true
            }
        }
        return viewportGesture
    }

    /**
     * A finger lifting: first follow every finger to where it really is, then
     * re-anchor on the ones still down. The last MOVE can be resampled a few px
     * past the fingers (Choreographer input resampling, worst on a slow
     * device); the lift carries the true positions, so the photo stops where
     * the fingers left it.
     */
    private fun settleThenTrack(event: MotionEvent) {
        pinchMove(event, lifting = NO_POINTER, settle = true)
        trackPinch(event)
    }

    private fun pinchMove(event: MotionEvent, lifting: Int = liftingIndex(event), settle: Boolean = false) {
        val prevX = pinchFocusX
        val prevY = pinchFocusY
        val prevSpan = pinchSpan
        trackPinch(event, lifting)
        if (settle) {
            viewport.settleBy(pinchFocusX - prevX, pinchFocusY - prevY)
        } else {
            viewport.panBy(pinchFocusX - prevX, pinchFocusY - prevY)
        }
        if (prevSpan > MIN_PINCH_SPAN && pinchSpan > MIN_PINCH_SPAN) {
            viewport.zoomBy(pinchSpan / prevSpan, pinchFocusX, pinchFocusY)
        }
        applyViewport()
    }

    private fun liftingIndex(event: MotionEvent): Int =
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else NO_POINTER

    /** Focus and mean spread of the fingers still down (a lifting one is left out). */
    private fun trackPinch(event: MotionEvent, lifting: Int = liftingIndex(event)) {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == lifting) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        if (count == 0) return
        pinchFocusX = sumX / count
        pinchFocusY = sumY / count
        var spread = 0f
        for (i in 0 until event.pointerCount) {
            if (i == lifting) continue
            spread += hypot(event.getX(i) - pinchFocusX, event.getY(i) - pinchFocusY)
        }
        pinchSpan = spread / count
    }

    /** Drops a one-finger edit in progress: a drag being drawn, or a grab put back where it started. */
    private fun cancelEdit() {
        if (touchState != TouchState.NONE) {
            val target = if (activeHoleIndex >= 0) holes[activeHoleIndex].rect else roiRect
            target.set(editStart)
        }
        touchState = TouchState.NONE
        activeHoleIndex = -1
        isDrawing = false
        invalidate()
        onRoiChangedListener?.invoke(getRelativeRoi())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handleViewportGesture(event)) return true

        val bounds = if (imageBounds.isEmpty) RectF(0f, 0f, width.toFloat(), height.toFloat()) else imageBounds
        val x = event.x.coerceIn(bounds.left, bounds.right)
        val y = event.y.coerceIn(bounds.top, bounds.bottom)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchState = getTouchState(x, y)
                if (touchState != TouchState.NONE) {
                    editStart.set(if (activeHoleIndex >= 0) holes[activeHoleIndex].rect else roiRect)
                    lastX = x
                    lastY = y
                    return true
                }

                // A new crop replaces the old one and its holes only once the
                // drag is kept (ACTION_UP): the first finger of a pinch, or a
                // stray tap, must not wipe the selection.
                isDrawing = true
                startX = x
                startY = y
                endX = x
                endY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchState != TouchState.NONE) {
                    val dx = x - lastX
                    val dy = y - lastY
                    // DYNAMIC TARGET: Modifies either the grabbed hole OR the main ROI
                    val target = if (activeHoleIndex >= 0) holes[activeHoleIndex].rect else roiRect

                    when (touchState) {
                        TouchState.CENTER -> {
                            val newLeft = safeCoerce(target.left + dx, bounds.left, bounds.right - target.width())
                            val newTop = safeCoerce(target.top + dy, bounds.top, bounds.bottom - target.height())
                            target.offsetTo(newLeft, newTop)
                        }
                        TouchState.TOP_LEFT -> {
                            target.left = safeCoerce(target.left + dx, bounds.left, target.right - minSize)
                            target.top = safeCoerce(target.top + dy, bounds.top, target.bottom - minSize)
                            if (isSquareMode()) makeSquare(target.right, target.bottom, bounds, target)
                        }
                        TouchState.TOP_RIGHT -> {
                            target.right = safeCoerce(target.right + dx, target.left + minSize, bounds.right)
                            target.top = safeCoerce(target.top + dy, bounds.top, target.bottom - minSize)
                            if (isSquareMode()) makeSquare(target.left, target.bottom, bounds, target)
                        }
                        TouchState.BOTTOM_LEFT -> {
                            target.left = safeCoerce(target.left + dx, bounds.left, target.right - minSize)
                            target.bottom = safeCoerce(target.bottom + dy, target.top + minSize, bounds.bottom)
                            if (isSquareMode()) makeSquare(target.right, target.top, bounds, target)
                        }
                        TouchState.BOTTOM_RIGHT -> {
                            target.right = safeCoerce(target.right + dx, target.left + minSize, bounds.right)
                            target.bottom = safeCoerce(target.bottom + dy, target.top + minSize, bounds.bottom)
                            if (isSquareMode()) makeSquare(target.left, target.top, bounds, target)
                        }
                        else -> {}
                    }
                    lastX = x
                    lastY = y
                } else if (isDrawing) {
                    endX = x
                    endY = y
                }
                invalidate()
                // A crop being drawn reports itself; the kept ROI is still the old one.
                val live = if (isDrawing && !isSubtractMode) {
                    viewRectToImage(RectF(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY)))
                } else {
                    getRelativeRoi()
                }
                onRoiChangedListener?.invoke(live)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    val rect = RectF(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))

                    if (isSubtractMode) {
                        // ARCHITECTURE FIX: Removed 'hasValidRoi' so you can punch holes in the Full Image
                        if (rect.width() > 50f || rect.height() > 50f) {
                            holes.add(Hole(currentMode, rect))
                        }
                    } else {
                        if (rect.width() > 50f || rect.height() > 50f) {
                            holes.clear()
                            roiRect.set(rect)
                            hasValidRoi = true
                        }
                    }
                    isDrawing = false
                }
                touchState = TouchState.NONE
                activeHoleIndex = -1
                invalidate()
                onRoiChangedListener?.invoke(getRelativeRoi())
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelEdit()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isSquareMode() = currentMode == RoiMode.SQUARE

    private var activeHoleIndex = -1 // Tracks which hole you grabbed

    private fun makeSquare(pivotX: Float, pivotY: Float, bounds: RectF, targetRect: RectF) {
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

    private fun getTouchState(x: Float, y: Float): TouchState {
        val slop = 45f // Massive hitboxes for precision resizing
        activeHoleIndex = -1

        // 1. Check Holes First (allows resizing holes drawn over the main ROI)
        if (isSubtractMode) {
            for (i in holes.indices.reversed()) {
                val hr = holes[i].rect
                if (abs(x - hr.left) < slop && abs(y - hr.top) < slop) {
                    activeHoleIndex = i
                    return TouchState.TOP_LEFT
                }
                if (abs(x - hr.right) < slop && abs(y - hr.top) < slop) {
                    activeHoleIndex = i
                    return TouchState.TOP_RIGHT
                }
                if (abs(x - hr.left) < slop && abs(y - hr.bottom) < slop) {
                    activeHoleIndex = i
                    return TouchState.BOTTOM_LEFT
                }
                if (abs(x - hr.right) < slop && abs(y - hr.bottom) < slop) {
                    activeHoleIndex = i
                    return TouchState.BOTTOM_RIGHT
                }
                if (hr.contains(x, y)) {
                    activeHoleIndex = i
                    return TouchState.CENTER
                }
            }
        }

        // 2. Check Main ROI Second
        if (hasValidRoi && !isSubtractMode) {
            if (abs(x - roiRect.left) < slop && abs(y - roiRect.top) < slop) return TouchState.TOP_LEFT
            if (abs(x - roiRect.right) < slop && abs(y - roiRect.top) < slop) return TouchState.TOP_RIGHT
            if (abs(x - roiRect.left) < slop && abs(y - roiRect.bottom) < slop) return TouchState.BOTTOM_LEFT
            if (abs(x - roiRect.right) < slop && abs(y - roiRect.bottom) < slop) return TouchState.BOTTOM_RIGHT
            if (roiRect.contains(x, y)) return TouchState.CENTER
        }

        return TouchState.NONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // BUG FIX: Don't return early if we have holes but no main ROI!
        if (!isDrawing && !hasValidRoi && holes.isEmpty()) return

        val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // --- 1. DRAW THE MAIN ROI ---
        // Holes with no explicit crop still mean "full image minus holes". Clear
        // imageBounds so the specimen stays visible instead of staying fully dimmed.
        val implicitFullImage = !hasValidRoi && (holes.isNotEmpty() || (isDrawing && isSubtractMode))
        if (hasValidRoi || (!isSubtractMode && isDrawing) || implicitFullImage) {
            val drawMainRect = when {
                !isSubtractMode && isDrawing -> mainRectScratch.apply {
                    set(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))
                }
                hasValidRoi -> roiRect
                else -> mainRectScratch.apply { set(imageBounds) }
            }

            canvas.drawRect(drawMainRect, clearPaint)
            if (hasValidRoi || (!isSubtractMode && isDrawing)) {
                canvas.drawRect(drawMainRect, borderPaint)
            }

            // Draw Main ROI Handles (Only in Add Mode)
            if (!isSubtractMode && (hasValidRoi || isDrawing)) {
                val r = 20f
                canvas.drawCircle(drawMainRect.left, drawMainRect.top, r, handlePaint)
                canvas.drawCircle(drawMainRect.right, drawMainRect.top, r, handlePaint)
                canvas.drawCircle(drawMainRect.left, drawMainRect.bottom, r, handlePaint)
                canvas.drawCircle(drawMainRect.right, drawMainRect.bottom, r, handlePaint)
            }
        }

        // --- 2. DRAW SAVED HOLES ---
        // A new crop being drawn drops them when it is kept, so hide them meanwhile.
        val shownHoles = if (isDrawing && !isSubtractMode) emptyList() else holes
        for (hole in shownHoles) {
            canvas.drawRect(hole.rect, holeFillPaint)
            canvas.drawRect(hole.rect, holeBorderPaint)
            // Draw handles for holes if we are in Erase mode to show they are editable
            if (isSubtractMode && !isDrawing) {
                val r = 15f
                canvas.drawCircle(hole.rect.left, hole.rect.top, r, handlePaint)
                canvas.drawCircle(hole.rect.right, hole.rect.top, r, handlePaint)
                canvas.drawCircle(hole.rect.left, hole.rect.bottom, r, handlePaint)
                canvas.drawCircle(hole.rect.right, hole.rect.bottom, r, handlePaint)
            }
        }

        // --- 3. DRAW ACTIVE HOLE BEING DRAGGED ---
        if (isDrawing && isSubtractMode) {
            val activeHoleRect = activeHoleScratch.apply {
                set(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))
            }
            canvas.drawRect(activeHoleRect, holeFillPaint)
            canvas.drawRect(activeHoleRect, holeBorderPaint)
        }

        canvas.restoreToCount(layerId)
    }

    fun getRelativeRoi(): RectF {
        if (imageBounds.isEmpty || imageBounds.width() == 0f) return RectF()

        // Use the physical image scale if available, otherwise use preview scale
        val scale = if (realImageWidth > 0) {
            realImageWidth.toFloat() / imageBounds.width()
        } else {
            (imageView?.drawable?.intrinsicWidth?.toFloat() ?: 1f) / imageBounds.width()
        }

        return RectF(
            (roiRect.left - imageBounds.left) * scale,
            (roiRect.top - imageBounds.top) * scale,
            (roiRect.right - imageBounds.left) * scale,
            (roiRect.bottom - imageBounds.top) * scale,
        )
    }

    // OOM FIX: Generate Raw ALPHA_8 bytes
    fun generateMaskBytes(): ByteArray = StudioOverlayMaskEncoder.encode(maskInput())

    /**
     * A copy of what the mask is drawn from, for [StudioOverlayMaskEncoder.encode]
     * off the main thread: the view's own rects keep changing under touch.
     */
    fun maskInput() = StudioOverlayMaskEncoder.Input(
        realImageWidth = realImageWidth,
        realImageHeight = realImageHeight,
        imageBounds = RectF(imageBounds),
        holes = holes.map { it.copy(rect = RectF(it.rect)) },
    )

    private companion object {
        /** Below this finger spread (view px) a pinch only pans. */
        const val MIN_PINCH_SPAN = 10f
        const val NO_POINTER = -1
    }
}
