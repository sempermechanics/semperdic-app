package com.sempermechanics.semper.ui.analysis.roi

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
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.toColorInt
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import kotlin.math.max
import kotlin.math.min

/**
 * The ROI editor's canvas overlay: the crop and erase rects over the reference,
 * edited with one finger, zoomed and panned with two (see [StudioOverlayViewport]).
 */
@Suppress("TooManyFunctions") // the overlay's public API for RoiDrawActivity plus its touch and draw steps
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
        val drawable = iv.drawable
        val imageWidth = drawable?.intrinsicWidth?.toFloat() ?: 0f
        val imageHeight = drawable?.intrinsicHeight?.toFloat() ?: 0f
        // A canvas squeezed to nothing (keyboard + dock taller than the screen)
        // keeps the last bounds, so the ROI still maps back when it regrows.
        if (imageWidth == 0f || imageHeight == 0f || min(iv.width, iv.height) <= 0) return
        fitImage(iv, imageWidth, imageHeight)
    }

    /**
     * Lays the [imageWidth] x [imageHeight] drawable out in [iv] through the
     * viewport, and carries the crop and holes over to the new bounds.
     */
    private fun fitImage(iv: ImageView, imageWidth: Float, imageHeight: Float) {
        viewport.layout(imageWidth, imageHeight, iv.width.toFloat(), iv.height.toFloat())
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
        if (imageBounds.isEmpty) return null
        return imageRectInView(Roi(x, y, width, height), ImageSize(realImageWidth, realImageHeight), imageBounds)
    }

    /** Image px per view px: of the photo when its size is known, else of the preview drawable. */
    private fun imageScale(): Float {
        val imageWidth = if (realImageWidth > 0) {
            realImageWidth.toFloat()
        } else {
            imageView?.drawable?.intrinsicWidth?.toFloat() ?: 1f
        }
        return imageWidth / imageBounds.width()
    }

    private fun viewRectToImage(viewRect: RectF): RectF {
        if (imageBounds.isEmpty || imageBounds.width() == 0f) return RectF()
        return viewToImage(viewRect, imageBounds, imageScale())
    }

    /** Inverse of [viewRectToImage]: image pixels (fractional) to view coordinates. */
    private fun imageRectToView(img: RectF): RectF = imageToView(img, imageBounds, imageScale())

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
        strokeWidth = STROKE_WIDTH
    }
    var hasValidRoi = false
        private set

    private var roiRect = RectF()

    // The rect being drawn runs from (startX, startY) to (endX, endY); a grab
    // moves by the finger's travel since (lastX, lastY).
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** What the one editing finger is doing: drawing a new rect, or which part of one it holds. */
    internal enum class TouchState { NONE, DRAWING, CENTER, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var touchState = TouchState.NONE

    private val isDrawing: Boolean get() = touchState == TouchState.DRAWING

    /** True while a finger holds a rect: its body or one of its corners. */
    private val isGrabbing: Boolean get() = touchState != TouchState.NONE && !isDrawing

    private var activeHoleIndex = -1 // Tracks which hole you grabbed

    /** The rect the grab moves: the grabbed hole, or the main ROI. */
    private val grabTarget: RectF get() = if (activeHoleIndex >= 0) holes[activeHoleIndex].rect else roiRect

    private val borderPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
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

    // --- 4. ZOOM AND PAN ---
    // Two fingers pinch and pan; double-tap toggles 2x and fit. One finger
    // always edits, so drawing never fights the zoom. Handles and the minimum
    // size stay in view px: zoomed in, they are finer in image px.

    /** The grabbed rect as it was at ACTION_DOWN, put back if the touch turns into a pinch. */
    private val editStart = RectF()

    private val viewportGestures = StudioOverlayViewport(
        context = context,
        viewport = viewport,
        cancelEdit = ::cancelEdit,
        onMoved = ::applyViewport,
    )

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

    /** Drops a one-finger edit in progress: a drag being drawn, or a grab put back where it started. */
    private fun cancelEdit() {
        if (isGrabbing) grabTarget.set(editStart)
        touchState = TouchState.NONE
        activeHoleIndex = -1
        invalidate()
        onRoiChangedListener?.invoke(getRelativeRoi())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = viewportGestures.onTouchEvent(event) || onEditTouch(event)

    /** One-finger editing: draw a new rect, or move or resize the one grabbed. */
    private fun onEditTouch(event: MotionEvent): Boolean {
        val bounds = if (imageBounds.isEmpty) RectF(0f, 0f, width.toFloat(), height.toFloat()) else imageBounds
        val x = event.x.coerceIn(bounds.left, bounds.right)
        val y = event.y.coerceIn(bounds.top, bounds.bottom)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> onDown(x, y)
            MotionEvent.ACTION_MOVE -> onMove(x, y, bounds)
            MotionEvent.ACTION_UP -> onUp()
            MotionEvent.ACTION_CANCEL -> cancelEdit()
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    private fun onDown(x: Float, y: Float) {
        touchState = grabAt(x, y)
        if (isGrabbing) {
            editStart.set(grabTarget)
            lastX = x
            lastY = y
            return
        }

        // A new crop replaces the old one and its holes only once the
        // drag is kept (ACTION_UP): the first finger of a pinch, or a
        // stray tap, must not wipe the selection.
        touchState = TouchState.DRAWING
        startX = x
        startY = y
        endX = x
        endY = y
        invalidate()
    }

    private fun onMove(x: Float, y: Float, bounds: RectF) {
        when (touchState) {
            TouchState.NONE -> Unit
            TouchState.DRAWING -> {
                endX = x
                endY = y
            }
            else -> {
                // The grab moves whichever it holds: a hole, or the main ROI.
                dragRect(grabTarget, touchState, x - lastX, y - lastY, bounds, MIN_SIZE, isSquareMode())
                lastX = x
                lastY = y
            }
        }
        invalidate()
        // A crop being drawn reports itself; the kept ROI is still the old one.
        val live = if (isDrawing && !isSubtractMode) viewRectToImage(drawnRect(RectF())) else getRelativeRoi()
        onRoiChangedListener?.invoke(live)
    }

    private fun onUp() {
        if (isDrawing) keepDrawnRect(drawnRect(RectF()))
        touchState = TouchState.NONE
        activeHoleIndex = -1
        invalidate()
        onRoiChangedListener?.invoke(getRelativeRoi())
    }

    /** Keeps a drawn [rect] longer than [MIN_KEPT] either way: a new hole, or a new crop that drops the holes. */
    private fun keepDrawnRect(rect: RectF) {
        if (rect.width() <= MIN_KEPT && rect.height() <= MIN_KEPT) return
        if (isSubtractMode) {
            // No crop needed: a hole can be punched in the full image.
            holes.add(Hole(currentMode, rect))
        } else {
            holes.clear()
            roiRect.set(rect)
            hasValidRoi = true
        }
    }

    /** The rect being drawn, normalised so left ≤ right and top ≤ bottom, into [out]. */
    private fun drawnRect(out: RectF): RectF =
        out.apply { set(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY)) }

    private fun isSquareMode() = currentMode == RoiMode.SQUARE

    /**
     * What a finger landing at ([x], [y]) takes hold of, noting a grabbed hole
     * in [activeHoleIndex]. Erase mode grabs holes, topmost first, so a hole
     * drawn over the crop stays editable; crop mode grabs the crop.
     */
    private fun grabAt(x: Float, y: Float): TouchState {
        activeHoleIndex = -1
        if (!isSubtractMode) {
            return roiRect.takeIf { hasValidRoi }?.let { hitState(it, x, y, HANDLE_SLOP) } ?: TouchState.NONE
        }
        val grabbed = holes.indices.reversed().asSequence()
            .mapNotNull { i -> hitState(holes[i].rect, x, y, HANDLE_SLOP)?.let { hit -> i to hit } }
            .firstOrNull()
        activeHoleIndex = grabbed?.first ?: -1
        return grabbed?.second ?: TouchState.NONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Holes with no crop still draw: they cut into the full image.
        if (!isDrawing && !hasValidRoi && holes.isEmpty()) return

        val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        drawMainRect(canvas)
        drawHoles(canvas)
        canvas.restoreToCount(layerId)
    }

    /**
     * The crop: cleared out of the dim, outlined, and with handles in crop mode.
     * Holes with no explicit crop still mean "full image minus holes", so the
     * whole image is cleared then, instead of staying fully dimmed.
     */
    private fun drawMainRect(canvas: Canvas) {
        val rect = shownMainRect() ?: return
        canvas.drawRect(rect, clearPaint)
        val outlined = hasValidRoi || (isDrawing && !isSubtractMode)
        if (outlined) canvas.drawRect(rect, borderPaint)
        // Main ROI handles show only in crop mode.
        if (outlined && !isSubtractMode) drawHandles(canvas, rect, CROP_HANDLE_RADIUS, handlePaint)
    }

    /** The crop to clear: the one being drawn, the kept one, or the whole image under holes alone; else null. */
    private fun shownMainRect(): RectF? {
        val drawingHole = isDrawing && isSubtractMode
        return when {
            isDrawing && !isSubtractMode -> drawnRect(mainRectScratch)
            hasValidRoi -> roiRect
            holes.isNotEmpty() || drawingHole -> mainRectScratch.apply { set(imageBounds) }
            else -> null
        }
    }

    /** The kept holes (hidden while a new crop is drawn, which drops them), then the hole being drawn. */
    private fun drawHoles(canvas: Canvas) {
        val shownHoles = if (isDrawing && !isSubtractMode) emptyList() else holes
        for (hole in shownHoles) {
            canvas.drawRect(hole.rect, holeFillPaint)
            canvas.drawRect(hole.rect, holeBorderPaint)
            // In erase mode the handles show that the holes are editable.
            if (isSubtractMode && !isDrawing) drawHandles(canvas, hole.rect, HOLE_HANDLE_RADIUS, handlePaint)
        }
        if (isDrawing && isSubtractMode) {
            val activeHoleRect = drawnRect(activeHoleScratch)
            canvas.drawRect(activeHoleRect, holeFillPaint)
            canvas.drawRect(activeHoleRect, holeBorderPaint)
        }
    }

    fun getRelativeRoi(): RectF = viewRectToImage(roiRect)

    /** The mask as raw ALPHA_8 bytes: one byte per reference pixel, a quarter of an ARGB mask's memory. */
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
        /** Smallest side a resize leaves a rect, view px. */
        const val MIN_SIZE = 50f

        /** A drawn rect is kept once it is longer than this either way, view px. */
        const val MIN_KEPT = 50f

        /** Massive hitboxes for precision resizing: a corner grabs within this, view px. */
        const val HANDLE_SLOP = 45f
        const val CROP_HANDLE_RADIUS = 20f
        const val HOLE_HANDLE_RADIUS = 15f

        /** Crop and hole outlines, view px. */
        const val STROKE_WIDTH = 5f
    }
}
