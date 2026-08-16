@file:Suppress(
    "TooManyFunctions",
    "MagicNumber",
    "ComplexCondition",
    "LongMethod",
    "CyclomaticComplexMethod",
    "ReturnCount",
)

package com.indicvision.semper.ui.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import com.indicvision.semper.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Minimal XY line plot for the virtual strain gauge study — the two charts
 * §5.4.5 of the Good Practices Guide reads its conclusions off:
 *
 *  - peak strain and strain noise against VSG size (the convergence view), and
 *  - strain along the line cut, one polyline per VSG (the line-scan view).
 *
 * Linear axes; optional pinch-zoom / pan via a persistent data-space viewport
 * (not a Canvas Matrix — that would scale strokes and break tick labels). The
 * one-finger gesture is a horizontal scrub that reports values through [onScrub].
 */
class VsgPlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * @param points x/y pairs in data units, ordered along x
     * @param markers true to draw a dot at each point (sparse convergence
     *   curves) rather than a bare polyline (dense line scans)
     * @param muted true to draw the line translucent, for background context
     */
    data class Series(
        val label: String,
        val color: Int,
        val points: List<Pair<Float, Float>>,
        val markers: Boolean = true,
        val muted: Boolean = false,
    )

    /**
     * One curve's value under the scrub line, carrying the colour it is drawn
     * in — the readout is only readable against several curves if each entry
     * matches the line it came from.
     */
    data class Sample(val label: String, val value: Float, val color: Int)

    companion object {
        /** Colour for the n-th series of a multi-line plot; safe for any index
         *  (a skipped lattice node has frameIndex -1). */
        fun paletteColor(index: Int): Int = PALETTE[index.mod(PALETTE.size)]

        const val AXIS_LABEL_SP = 11f
        const val LINE_WIDTH_DP = 2f
        const val MARKER_RADIUS_DP = 3.5f
        const val PAD_LEFT_DP = 46f
        const val PAD_RIGHT_DP = 12f
        const val PAD_TOP_DP = 10f
        const val PAD_BOTTOM_DP = 30f
        const val GRID_LINES = 4
        const val TICK_GAP_DP = 4f

        /** Nudge that centres a tick label on its gridline, as a share of text size. */
        const val TICK_BASELINE = 0.33f

        /** Head-room above/below the data so markers are not clipped. */
        const val Y_MARGIN_FRACTION = 0.08f

        /** Smallest viewport span as a fraction of the full data extent. */
        const val MIN_SPAN_FRACTION = 0.05f

        /** Series colours, reused cyclically for line-scan plots. */
        val PALETTE = intArrayOf(
            0xFF0288D1.toInt(),
            0xFFE53935.toInt(),
            0xFF43A047.toInt(),
            0xFFF5A623.toInt(),
            0xFF8E24AA.toInt(),
            0xFF00897B.toInt(),
            0xFF5D4037.toInt(),
            0xFF3949AB.toInt(),
        )
    }

    private val density = resources.displayMetrics.density

    private fun dp(value: Float) = value * density

    /** Axis labels in px, scaled for the user's font-size setting. */
    private val axisLabelPx =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, AXIS_LABEL_SP, resources.displayMetrics)

    /** Reused every draw — onDraw runs on each scrub frame. */
    private val frame = Frame()
    private val clipRect = RectF()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(LINE_WIDTH_DP)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Dot marking the scrub point on the selected curve. */
    private val valueDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** The y value drawn on the plot beside the scrub point. */
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = axisLabelPx
        color = ContextCompat.getColor(context, R.color.text_primary)
        isFakeBoldText = true
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.surface_outline)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = axisLabelPx
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }
    private val path = Path()

    private var series: List<Series> = emptyList()
    private var xLabel: String = ""
    private var yLabel: String = ""

    /** Data-unit x of a vertical guide line, e.g. the recommended VSG. */
    private var highlightX: Float? = null
    private var scrubX: Float? = null
    private var dataBounds: Bounds? = null

    /** Null = show the full [dataBounds]; otherwise a zoomed viewport. */
    private var viewXMin: Float? = null
    private var viewXMax: Float? = null
    private var viewYMin: Float? = null
    private var viewYMax: Float? = null

    /**
     * When true, pinch-zoom and two-finger pan are active (lattice strain plot).
     * The settings-sheet line-cut reuses this view with zoom off.
     */
    var zoomEnabled: Boolean = false

    /** Called while scrubbing: x position and y values per visible series. */
    var onScrub: ((x: Float, samples: List<Sample>) -> Unit)? = null

    /**
     * Called with the scrub x as a 0..1 fraction of the current viewport, so an
     * external slider can follow the finger (and vice-versa via [scrubToFraction]).
     */
    var onScrubMove: ((fraction: Float) -> Unit)? = null

    private var multiTouchActive = false
    private var lastPanFocusX = 0f
    private var lastPanFocusY = 0f
    private var hasPanFocus = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                multiTouchActive = true
                clearScrub()
                return zoomEnabled
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!zoomEnabled || !hasFrame()) return false
                val full = dataBounds() ?: return false
                ensureViewport(full)
                val vp = viewport(full)
                val focusX = pxToDataX(detector.focusX, vp)
                val focusY = pxToDataY(detector.focusY, vp)
                val factor = detector.scaleFactor
                val newXSpan = (vp.xMax - vp.xMin) / factor
                val newYSpan = (vp.yMax - vp.yMin) / factor
                val minXSpan = (full.xMax - full.xMin) * MIN_SPAN_FRACTION
                val minYSpan = (full.yMax - full.yMin) * MIN_SPAN_FRACTION
                val xSpan = newXSpan.coerceAtLeast(minXSpan)
                val ySpan = newYSpan.coerceAtLeast(minYSpan)
                // Keep the focus point fixed in data space.
                val leftFrac = (focusX - vp.xMin) / (vp.xMax - vp.xMin).coerceAtLeast(1e-6f)
                val bottomFrac = (focusY - vp.yMin) / (vp.yMax - vp.yMin).coerceAtLeast(1e-6f)
                viewXMin = focusX - leftFrac * xSpan
                viewXMax = viewXMin!! + xSpan
                viewYMin = focusY - bottomFrac * ySpan
                viewYMax = viewYMin!! + ySpan
                clampViewport(full)
                invalidate()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!zoomEnabled) return false
                resetViewport()
                invalidate()
                return true
            }
        },
    )

    /**
     * @param preserveViewport keep the current pinch-zoom / pan viewport instead
     *   of resetting to fit — used when the data changes but its scale does not
     *   (switching solved node or Highlight/Isolate), so the zoom survives.
     */
    fun setData(
        series: List<Series>,
        xLabel: String,
        yLabel: String,
        highlightX: Float? = null,
        preserveViewport: Boolean = false,
    ) {
        this.series = series
        this.xLabel = xLabel
        this.yLabel = yLabel
        this.highlightX = highlightX
        scrubX = null
        dataBounds = null // series changed → recompute extent lazily on next access
        if (!preserveViewport) resetViewport()
        invalidate()
    }

    /**
     * Renders the current plot at [widthPx]×[heightPx] (full resolution, not a
     * screenshot of the on-screen size). Restores the view's prior layout after.
     */
    fun renderToBitmap(widthPx: Int, heightPx: Int): Bitmap {
        val prevW = width
        val prevH = height
        val wSpec = MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY)
        val hSpec = MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY)
        measure(wSpec, hSpec)
        layout(0, 0, widthPx, heightPx)
        val bitmap = createBitmap(widthPx, heightPx)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        draw(canvas)
        if (prevW > 0 && prevH > 0) {
            measure(
                MeasureSpec.makeMeasureSpec(prevW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(prevH, MeasureSpec.EXACTLY),
            )
            layout(left, top, left + prevW, top + prevH)
        } else {
            requestLayout()
        }
        return bitmap
    }

    private class Bounds(val xMin: Float, val xMax: Float, val yMin: Float, val yMax: Float)

    /** The plot area in view pixels, inside the axis gutters. */
    /** Mutable so one instance can serve every draw. */
    private class Frame(
        var left: Float = 0f,
        var right: Float = 0f,
        var top: Float = 0f,
        var bottom: Float = 0f,
    ) {
        fun set(l: Float, r: Float, t: Float, b: Float) {
            left = l
            right = r
            top = t
            bottom = b
        }
    }

    /**
     * Full data extent, cached in [dataBounds]. onDraw and every touch/scale/pan
     * handler asks for this; recomputing it (flattening the series into three boxed
     * lists) on each call allocated at touch frequency. Bounds depend only on the
     * series, so they are computed once per [setData] and reused until it changes.
     */
    private fun dataBounds(): Bounds? {
        dataBounds?.let { return it }
        return computeDataBounds()?.also { dataBounds = it }
    }

    /** Single primitive pass over every series' points — no intermediate lists. */
    private fun computeDataBounds(): Bounds? {
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
        val span = max(yMax - yMin, abs(yMax) * Y_MARGIN_FRACTION).takeIf { it > 0f } ?: 1f
        yMin -= span * Y_MARGIN_FRACTION
        yMax += span * Y_MARGIN_FRACTION
        return Bounds(xMin, if (xMax > xMin) xMax else xMin + 1f, yMin, yMax)
    }

    private fun viewport(full: Bounds): Bounds {
        val xmin = viewXMin
        val xmax = viewXMax
        val ymin = viewYMin
        val ymax = viewYMax
        return if (xmin != null && xmax != null && ymin != null && ymax != null) {
            Bounds(xmin, xmax, ymin, ymax)
        } else {
            full
        }
    }

    private fun ensureViewport(full: Bounds) {
        if (viewXMin == null) {
            viewXMin = full.xMin
            viewXMax = full.xMax
            viewYMin = full.yMin
            viewYMax = full.yMax
        }
    }

    private fun resetViewport() {
        viewXMin = null
        viewXMax = null
        viewYMin = null
        viewYMax = null
    }

    private fun clampViewport(full: Bounds) {
        var xmin = viewXMin ?: return
        var xmax = viewXMax ?: return
        var ymin = viewYMin ?: return
        var ymax = viewYMax ?: return
        val minXSpan = (full.xMax - full.xMin) * MIN_SPAN_FRACTION
        val minYSpan = (full.yMax - full.yMin) * MIN_SPAN_FRACTION
        if (xmax - xmin < minXSpan) {
            val mid = (xmin + xmax) / 2f
            xmin = mid - minXSpan / 2f
            xmax = mid + minXSpan / 2f
        }
        if (ymax - ymin < minYSpan) {
            val mid = (ymin + ymax) / 2f
            ymin = mid - minYSpan / 2f
            ymax = mid + minYSpan / 2f
        }
        val xSpan = xmax - xmin
        val ySpan = ymax - ymin
        if (xSpan >= full.xMax - full.xMin) {
            xmin = full.xMin
            xmax = full.xMax
        } else {
            if (xmin < full.xMin) {
                xmin = full.xMin
                xmax = xmin + xSpan
            }
            if (xmax > full.xMax) {
                xmax = full.xMax
                xmin = xmax - xSpan
            }
        }
        if (ySpan >= full.yMax - full.yMin) {
            ymin = full.yMin
            ymax = full.yMax
        } else {
            if (ymin < full.yMin) {
                ymin = full.yMin
                ymax = ymin + ySpan
            }
            if (ymax > full.yMax) {
                ymax = full.yMax
                ymin = ymax - ySpan
            }
        }
        viewXMin = xmin
        viewXMax = xmax
        viewYMin = ymin
        viewYMax = ymax
    }

    private fun pxToDataX(xPx: Float, b: Bounds): Float {
        val ratio = ((xPx - frame.left) / (frame.right - frame.left)).coerceIn(0f, 1f)
        return b.xMin + ratio * (b.xMax - b.xMin)
    }

    private fun pxToDataY(yPx: Float, b: Bounds): Float {
        val ratio = ((frame.bottom - yPx) / (frame.bottom - frame.top)).coerceIn(0f, 1f)
        return b.yMin + ratio * (b.yMax - b.yMin)
    }

    @Suppress("CyclomaticComplexMethod") // one branch per optional layer: crosshair, muted series, markers
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val full = dataBounds() ?: return
        val b = viewport(full)

        val left = dp(PAD_LEFT_DP)
        val right = width - dp(PAD_RIGHT_DP)
        val top = dp(PAD_TOP_DP)
        val bottom = height - dp(PAD_BOTTOM_DP)
        if (right <= left || bottom <= top) return

        fun sx(x: Float) = left + (x - b.xMin) / (b.xMax - b.xMin) * (right - left)
        fun sy(y: Float) = bottom - (y - b.yMin) / (b.yMax - b.yMin) * (bottom - top)

        frame.set(left, right, top, bottom)
        clipRect.set(left, top, right, bottom)
        canvas.withClip(clipRect) {
            for (i in 0..GRID_LINES) {
                val y = bottom - (bottom - top) * i / GRID_LINES
                drawLine(left, y, right, y, gridPaint)
            }
            (scrubX ?: highlightX)?.let {
                gridPaint.color = ContextCompat.getColor(context, R.color.sky_primary)
                drawLine(sx(it), top, sx(it), bottom, gridPaint)
                gridPaint.color = ContextCompat.getColor(context, R.color.surface_outline)
            }

            for (s in series) {
                if (s.points.isEmpty()) continue
                linePaint.color = s.color
                linePaint.alpha = if (s.muted) ALPHA_MUTED else ALPHA_SOLID
                path.reset()
                s.points.forEachIndexed { i, (x, y) ->
                    if (i == 0) path.moveTo(sx(x), sy(y)) else path.lineTo(sx(x), sy(y))
                }
                drawPath(path, linePaint)
                if (s.markers) {
                    markerPaint.color = s.color
                    s.points.forEach { (x, y) -> drawCircle(sx(x), sy(y), dp(MARKER_RADIUS_DP), markerPaint) }
                }
            }
        }

        // The selected curve's y at the scrub line: a dot plus a value label on the plot.
        scrubX?.let { scrub ->
            val target = series.firstOrNull { !it.muted && it.points.isNotEmpty() }
            val yVal = target?.let { interpolateY(it.points, scrub) }
            if (target != null && yVal != null) {
                val px = sx(scrub).coerceIn(left, right)
                val py = sy(yVal).coerceIn(top, bottom)
                valueDotPaint.color = target.color
                canvas.drawCircle(px, py, dp(MARKER_RADIUS_DP), valueDotPaint)
                val label = format(yVal)
                valuePaint.textAlign = Paint.Align.LEFT
                canvas.drawText(
                    label,
                    (px + dp(TICK_GAP_DP)).coerceAtMost(right - valuePaint.measureText(label)),
                    (py - dp(TICK_GAP_DP)).coerceAtLeast(top + valuePaint.textSize),
                    valuePaint,
                )
            }
        }

        drawGridTicks(canvas, b, frame)
        drawAxisLabels(canvas, left, right, bottom)
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // gesture phases: scale, pan, scrub, double-tap
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dataBounds == null && dataBounds() == null) return super.onTouchEvent(event)
        if (!hasFrame() && event.actionMasked != MotionEvent.ACTION_DOWN) {
            // Frame is filled on first draw; still accept DOWN to claim the gesture.
        }

        if (zoomEnabled) {
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                multiTouchActive = false
                hasPanFocus = false
                if (!zoomEnabled || event.pointerCount == 1) {
                    val full = dataBounds() ?: return false
                    updateScrub(event.x, viewport(full))
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (zoomEnabled && event.pointerCount >= 2) {
                    multiTouchActive = true
                    clearScrub()
                    lastPanFocusX = event.focusX()
                    lastPanFocusY = event.focusY()
                    hasPanFocus = true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (zoomEnabled && event.pointerCount >= 2) {
                    multiTouchActive = true
                    // Two-finger focus translation pans when not mid-pinch scale.
                    if (!scaleDetector.isInProgress && hasPanFocus) {
                        panByFocusDelta(event.focusX() - lastPanFocusX, event.focusY() - lastPanFocusY)
                    }
                    lastPanFocusX = event.focusX()
                    lastPanFocusY = event.focusY()
                    hasPanFocus = true
                    return true
                }
                if (!multiTouchActive && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val full = dataBounds() ?: return true
                    updateScrub(event.x, viewport(full))
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    hasPanFocus = false
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                multiTouchActive = false
                hasPanFocus = false
                clearScrub()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun MotionEvent.focusX(): Float {
        var sum = 0f
        for (i in 0 until pointerCount) sum += getX(i)
        return sum / pointerCount
    }

    private fun MotionEvent.focusY(): Float {
        var sum = 0f
        for (i in 0 until pointerCount) sum += getY(i)
        return sum / pointerCount
    }

    private fun panByFocusDelta(dxPx: Float, dyPx: Float) {
        val full = dataBounds() ?: return
        if (!hasFrame()) return
        ensureViewport(full)
        val vp = viewport(full)
        val xSpan = vp.xMax - vp.xMin
        val ySpan = vp.yMax - vp.yMin
        val dxData = -dxPx / (frame.right - frame.left) * xSpan
        val dyData = dyPx / (frame.bottom - frame.top) * ySpan
        viewXMin = vp.xMin + dxData
        viewXMax = vp.xMax + dxData
        viewYMin = vp.yMin + dyData
        viewYMax = vp.yMax + dyData
        clampViewport(full)
        invalidate()
    }

    /** A scrub ends as a click so accessibility services can drive the view. */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hasFrame(): Boolean = frame.right > frame.left && frame.bottom > frame.top

    private fun contains(x: Float, y: Float): Boolean =
        x in frame.left..frame.right && y in frame.top..frame.bottom

    private fun updateScrub(xPx: Float, b: Bounds) {
        val clamped = xPx.coerceIn(frame.left, frame.right)
        val ratio = (clamped - frame.left) / (frame.right - frame.left)
        emitScrub(b.xMin + ratio * (b.xMax - b.xMin), b)
    }

    /** Moves the scrub to [fraction] (0..1) of the current viewport — for a slider. */
    fun scrubToFraction(fraction: Float) {
        val full = dataBounds() ?: return
        if (!hasFrame()) return
        val vp = viewport(full)
        emitScrub(vp.xMin + fraction.coerceIn(0f, 1f) * (vp.xMax - vp.xMin), vp)
    }

    /** Sets the scrub at data-x [xData] and reports it to [onScrub] / [onScrubMove]. */
    private fun emitScrub(xData: Float, vp: Bounds) {
        scrubX = xData
        val values = series
            .filterNot { it.muted }
            .mapNotNull { entry ->
                interpolateY(entry.points, xData)?.let { y -> Sample(entry.label, y, entry.color) }
            }
        onScrub?.invoke(xData, values)
        val span = vp.xMax - vp.xMin
        onScrubMove?.invoke(if (span > 0f) ((xData - vp.xMin) / span).coerceIn(0f, 1f) else 0f)
        invalidate()
    }

    private fun clearScrub() {
        if (scrubX == null) return
        scrubX = null
        onScrub?.invoke(Float.NaN, emptyList())
        invalidate()
    }

    @Suppress("ReturnCount") // empty, both clamps, the degenerate span and the interpolated hit
    private fun interpolateY(points: List<Pair<Float, Float>>, x: Float): Float? {
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

    private fun drawGridTicks(canvas: Canvas, b: Bounds, f: Frame) {
        textPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        for (i in 0..GRID_LINES) {
            val y = f.bottom - (f.bottom - f.top) * i / GRID_LINES
            val value = b.yMin + (b.yMax - b.yMin) * i / GRID_LINES
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(format(value), f.left - dp(TICK_GAP_DP), y + textPaint.textSize * TICK_BASELINE, textPaint)
        }
        val baseline = f.bottom + textPaint.textSize + dp(TICK_GAP_DP)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(format(b.xMin), f.left, baseline, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(format(b.xMax), f.right, baseline, textPaint)
    }

    private fun drawAxisLabels(canvas: Canvas, left: Float, right: Float, bottom: Float) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
        canvas.drawText(
            xLabel,
            (left + right) / 2f,
            bottom + textPaint.textSize * 2f + dp(TICK_GAP_DP),
            textPaint,
        )
        canvas.withRotation(
            -QUARTER_TURN,
            dp(TICK_GAP_DP) + textPaint.textSize,
            bottom / 2f,
        ) {
            drawText(yLabel, dp(TICK_GAP_DP) + textPaint.textSize, bottom / 2f, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    /** Compact tick label: enough digits to separate neighbouring gridlines. */
    private fun format(value: Float): String = when {
        abs(value) >= LARGE_VALUE -> String.format(Locale.US, "%.0f", value)
        abs(value) >= SMALL_VALUE -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.2f", value)
    }
}

private const val ALPHA_SOLID = 255
private const val ALPHA_MUTED = 140
private const val QUARTER_TURN = 90f
private const val LARGE_VALUE = 100f
private const val SMALL_VALUE = 1f
