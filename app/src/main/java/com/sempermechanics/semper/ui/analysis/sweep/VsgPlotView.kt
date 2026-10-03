package com.sempermechanics.semper.ui.analysis.sweep

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.dp

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
@Suppress("TooManyFunctions") // one small method per gesture phase and draw layer
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
        fun paletteColor(context: Context, index: Int): Int = VsgPlotPalette.series(context, index)

        /** Colour for the n-th of the line-cut's 3 concurrent strain components. */
        fun lineCutColor(context: Context, slot: Int): Int = VsgPlotPalette.lineCut(context, slot)

        const val AXIS_LABEL_SP = PlotStyle.AXIS_LABEL_SP
        const val LINE_WIDTH_DP = 2f
        const val MARKER_RADIUS_DP = 3.5f

        /** Full axis gutters: a separate descriptive title line/rotated title
         *  beside the tick labels -- used for the detached PNG export, which has
         *  the 1600x1000px room to spare and no other on-screen context to lean on. */
        const val PAD_LEFT_FULL_DP = 46f
        const val PAD_BOTTOM_FULL_DP = 30f

        /** Compact axis gutters ([compactAxes]): no separate title, just the tick
         *  labels with the unit folded onto the outermost tick -- for the
         *  space-constrained on-screen lattice and peek-sheet plots, whose
         *  section headers/params chip already say what each axis is. */
        const val PAD_LEFT_COMPACT_DP = 34f
        const val PAD_BOTTOM_COMPACT_DP = 18f

        const val PAD_RIGHT_DP = 12f
        const val PAD_TOP_DP = 10f
        const val GRID_LINES = 4
        const val TICK_GAP_DP = PlotStyle.TICK_GAP_DP

        /** Nudge that centres a tick label on its gridline, as a share of text size. */
        const val TICK_BASELINE = PlotStyle.TICK_BASELINE

        /** Head-room above/below the data so markers are not clipped. */
        const val Y_MARGIN_FRACTION = 0.08f

        /** Smallest viewport span as a fraction of the full data extent. */
        const val MIN_SPAN_FRACTION = 0.05f
    }

    /** The plot area in view pixels, inside the axis gutters; reused every draw, which runs on each scrub frame. */
    private val frame = RectF()

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
    private val valuePaint = PlotStyle.valueTextPaint(context)
    private val gridPaint = PlotStyle.gridPaint(context)
    private val textPaint = PlotStyle.axisTextPaint(context)
    private val path = Path()

    private val axes = VsgPlotAxes(this, textPaint)

    private var series: List<Series> = emptyList()
    private var xLabel: String = ""
    private var yLabel: String = ""
    private var xUnit: String = ""
    private var yUnit: String = ""

    /**
     * When true, the plot drops its separate axis-title lines and instead folds
     * the unit onto the outermost tick, freeing [PAD_LEFT_COMPACT_DP] /
     * [PAD_BOTTOM_COMPACT_DP] of gutter -- for the on-screen lattice and
     * peek-sheet plots. The detached PNG export leaves this false (default) for
     * the full descriptive titles, since it has the room and no surrounding
     * screen context to lean on.
     */
    var compactAxes: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Data-unit x of a vertical guide line, e.g. the recommended VSG. */
    private var highlightX: Float? = null
    private var scrubX: Float? = null

    /**
     * Full data extent, cached: onDraw and every touch/scale/pan handler asks
     * for it, and recomputing it on each call allocated at touch frequency.
     * Bounds depend only on the series, so they are computed once per [setData].
     */
    private var dataBounds: PlotBounds? = null

    /** The zoomed window into [dataBounds], or none to show all of it. */
    private val viewport = VsgPlotViewport(MIN_SPAN_FRACTION)

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
     * Called with NaN when the scrub clears (the finger lifts, or a pinch or pan
     * starts), so the slider goes back to rest instead of marking a line that is gone.
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
                val full = dataBounds()?.takeIf { zoomEnabled && hasFrame() } ?: return false
                viewport.zoomAbout(full, detector.focusX, detector.focusY, detector.scaleFactor, frame)
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
                viewport.reset()
                invalidate()
                return true
            }
        },
    )

    /**
     * @param preserveViewport keep the current pinch-zoom / pan viewport instead
     *   of resetting to fit — used when the data changes but its scale does not
     *   (switching solved node or Highlight/Isolate), so the zoom survives.
     * @param xUnit @param yUnit only used when [compactAxes] is true, appended to
     *   the outermost tick in place of the (then unused) [xLabel] / [yLabel] title.
     */
    @Suppress("LongParameterList") // a plot's full config: series + 4 optional, named, defaulted display params
    fun setData(
        series: List<Series>,
        xLabel: String,
        yLabel: String,
        highlightX: Float? = null,
        preserveViewport: Boolean = false,
        xUnit: String = "",
        yUnit: String = "",
    ) {
        this.series = series
        this.xLabel = xLabel
        this.yLabel = yLabel
        this.xUnit = xUnit
        this.yUnit = yUnit
        this.highlightX = highlightX
        scrubX = null
        dataBounds = null // series changed → recompute extent lazily on next access
        if (!preserveViewport) viewport.reset()
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

    private fun dataBounds(): PlotBounds? {
        dataBounds?.let { return it }
        return plotBoundsOf(series, Y_MARGIN_FRACTION)?.also { dataBounds = it }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val full = dataBounds() ?: return
        val b = viewport.visible(full)

        val left = axes.leftPad(b, compactAxes)
        val right = width - dp(PAD_RIGHT_DP)
        val top = dp(PAD_TOP_DP)
        val bottom = height - dp(if (compactAxes) PAD_BOTTOM_COMPACT_DP else PAD_BOTTOM_FULL_DP)
        if (right <= left || bottom <= top) return

        frame.set(left, top, right, bottom)
        canvas.withClip(frame) {
            drawGrid(this, b)
            drawSeries(this, b)
        }
        drawScrubValue(canvas, b)
        axes.drawTicks(canvas, b, frame, compactAxes, xUnit, yUnit)
        // Compact mode folds the unit onto the outermost tick instead of a
        // separate title line -- drawTitles draws that line, so skip it.
        if (!compactAxes) axes.drawTitles(canvas, frame, xLabel, yLabel)
    }

    private fun sx(x: Float, b: PlotBounds) = frame.left + (x - b.xMin) / (b.xMax - b.xMin) * frame.width()

    private fun sy(y: Float, b: PlotBounds) = frame.bottom - (y - b.yMin) / (b.yMax - b.yMin) * frame.height()

    /** The horizontal gridlines, then the scrub (or highlight) line in the solved-node colour. */
    private fun drawGrid(canvas: Canvas, b: PlotBounds) {
        for (i in 0..GRID_LINES) {
            val y = frame.bottom - frame.height() * i / GRID_LINES
            canvas.drawLine(frame.left, y, frame.right, y, gridPaint)
        }
        (scrubX ?: highlightX)?.let {
            gridPaint.color = ContextCompat.getColor(context, R.color.viewer_plot_node_solved)
            canvas.drawLine(sx(it, b), frame.top, sx(it, b), frame.bottom, gridPaint)
            gridPaint.color = PlotStyle.grid(context)
        }
    }

    /**
     * Each series as a polyline, with its markers. Emphasis (dataviz skill):
     * the focused series keeps its real hue; every muted one shares a single
     * neutral instead of its own dimmed hue, so at most one categorical colour
     * is ever on screen at once -- see [VsgPlotPalette]'s per-slot (not
     * pairwise) validation.
     */
    private fun drawSeries(canvas: Canvas, b: PlotBounds) {
        val mutedColor = ContextCompat.getColor(context, R.color.viewer_plot_muted)
        for (s in series) {
            if (s.points.isEmpty()) continue
            linePaint.color = if (s.muted) mutedColor else s.color
            linePaint.alpha = if (s.muted) ALPHA_MUTED else ALPHA_SOLID
            path.reset()
            s.points.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(sx(x, b), sy(y, b)) else path.lineTo(sx(x, b), sy(y, b))
            }
            canvas.drawPath(path, linePaint)
            if (s.markers) {
                markerPaint.color = if (s.muted) mutedColor else s.color
                s.points.forEach { (x, y) -> canvas.drawCircle(sx(x, b), sy(y, b), dp(MARKER_RADIUS_DP), markerPaint) }
            }
        }
    }

    /** The selected curve's y at the scrub line: a dot plus a value label on the plot. */
    private fun drawScrubValue(canvas: Canvas, b: PlotBounds) {
        val scrub = scrubX
        val target = series.firstOrNull { !it.muted && it.points.isNotEmpty() }
        val yVal = if (scrub != null && target != null) interpolateY(target.points, scrub) else null
        if (scrub == null || target == null || yVal == null) return
        val px = sx(scrub, b).coerceIn(frame.left, frame.right)
        val py = sy(yVal, b).coerceIn(frame.top, frame.bottom)
        valueDotPaint.color = target.color
        canvas.drawCircle(px, py, dp(MARKER_RADIUS_DP), valueDotPaint)
        val label = VsgPlotAxes.tickLabel(yVal)
        valuePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            label,
            VsgPlotAxes.scrubLabelX(
                px,
                valuePaint.measureText(label),
                dp(MARKER_RADIUS_DP) + dp(TICK_GAP_DP),
                frame.left,
                frame.right,
            ),
            (py - dp(TICK_GAP_DP)).coerceAtLeast(frame.top + valuePaint.textSize),
            valuePaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val full = dataBounds() ?: return super.onTouchEvent(event)
        if (zoomEnabled) {
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onFirstFinger(event, full)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (zoomEnabled && event.pointerCount >= 2) startPan(event)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                onMove(event, full)
                true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) hasPanFocus = false
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onLift()
                // A lift is a click, so accessibility services can drive the scrub.
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    /** A press inside the plot area claims the gesture and starts a scrub; one in a gutter is left alone. */
    private fun onFirstFinger(event: MotionEvent, full: PlotBounds): Boolean {
        if (!contains(event.x, event.y)) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        multiTouchActive = false
        hasPanFocus = false
        if (!zoomEnabled || event.pointerCount == 1) updateScrub(event.x, viewport.visible(full))
        return true
    }

    /** A second finger ends the scrub; the fingers' focus is where a pan starts from. */
    private fun startPan(event: MotionEvent) {
        multiTouchActive = true
        clearScrub()
        lastPanFocusX = event.focusX()
        lastPanFocusY = event.focusY()
        hasPanFocus = true
    }

    private fun onMove(event: MotionEvent, full: PlotBounds) {
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
        } else if (!multiTouchActive && !scaleDetector.isInProgress && event.pointerCount == 1) {
            updateScrub(event.x, viewport.visible(full))
        }
    }

    /** The last finger lifting (or the gesture cancelled) clears the scrub. */
    private fun onLift() {
        parent?.requestDisallowInterceptTouchEvent(false)
        multiTouchActive = false
        hasPanFocus = false
        clearScrub()
    }

    private fun panByFocusDelta(dxPx: Float, dyPx: Float) {
        val full = dataBounds() ?: return
        if (!hasFrame()) return
        viewport.panByPx(full, dxPx, dyPx, frame)
        invalidate()
    }

    /** A scrub ends as a click so accessibility services can drive the view. */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hasFrame(): Boolean = frame.right > frame.left && frame.bottom > frame.top

    /** Inside the plot area, edges included. */
    private fun contains(x: Float, y: Float): Boolean =
        x in frame.left..frame.right && y in frame.top..frame.bottom

    private fun updateScrub(xPx: Float, b: PlotBounds) {
        val clamped = xPx.coerceIn(frame.left, frame.right)
        val ratio = (clamped - frame.left) / (frame.right - frame.left)
        emitScrub(b.xMin + ratio * (b.xMax - b.xMin), b)
    }

    /** Moves the scrub to [fraction] (0..1) of the current viewport — for a slider. */
    fun scrubToFraction(fraction: Float) {
        val full = dataBounds() ?: return
        if (!hasFrame()) return
        val vp = viewport.visible(full)
        emitScrub(vp.xMin + fraction.coerceIn(0f, 1f) * (vp.xMax - vp.xMin), vp)
    }

    /** Sets the scrub at data-x [xData] and reports it to [onScrub] / [onScrubMove]. */
    private fun emitScrub(xData: Float, vp: PlotBounds) {
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
        onScrubMove?.invoke(Float.NaN)
        invalidate()
    }
}

private const val ALPHA_SOLID = 255
private const val ALPHA_MUTED = 140
