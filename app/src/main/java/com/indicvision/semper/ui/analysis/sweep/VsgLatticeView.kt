@file:SuppressLint("ClickableViewAccessibility")

package com.indicvision.semper.ui.analysis.sweep

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.withSave
import com.indicvision.semper.R
import com.indicvision.semper.ui.common.dp
import kotlin.math.hypot

/**
 * The parameter space of a virtual strain gauge study, drawn as a 2-D lattice:
 * one node per analysis, subset size across the x-axis and virtual strain gauge
 * size up the y-axis. Because every subset carries its own VSG ladder, the
 * nodes fall into vertical columns — the lattice makes the shape of the sweep,
 * and which corners of it the engine could not solve, legible at a glance.
 *
 * Interactive: tap to focus a solved node, double-tap/long-press to open it.
 *
 * Both plot axes: Y (VSG) is floored at 1; X (subset) uses evenly spaced
 * columns for the present subset sizes.
 */
class VsgLatticeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    /**
     * Off by default so the planned lattice preview keeps its old passive
     * behaviour; the result lattice explicitly enables interactions.
     */
    var interactionEnabled: Boolean = false

    /**
     * When true, drops the separate axis-title lines and shrinks the gutters to
     * just the tick labels (subset size's unit folds onto the rightmost tick
     * instead). Set on both consumers of this view -- the result lattice and
     * the wizard's sweep preview -- which now share the same 136dp height;
     * neither has room for the full gutters at that size. Defaults false only
     * because a shared view shouldn't assume a caller wants it.
     */
    var compact: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * One analysis of the sweep. [solved] is false for a combination the engine
     * skipped; [frameIndex] is its position in the result viewer, or -1 when it
     * was skipped and has no frame to open.
     */
    data class Node(
        val subset: Int,
        val step: Int,
        /** Strain window in data points; null for a sweep stored before windows were counted in points. */
        val window: Int?,
        /** VSG in px, the y axis: what the engine was handed. */
        val vsg: Int,
        val solved: Boolean,
        val frameIndex: Int = -1,
        val failureReason: String = "",
        /** Native / run code when [solved] is false; drives the FAQ Why? hop. */
        val failureCode: Int? = null,
    )

    /** Invoked when a solved node is tapped. */
    var onNodeClick: ((Node) -> Unit)? = null
    var onNodeDoubleClick: ((Node) -> Unit)? = null
    var onNodeLongClick: ((Node) -> Unit)? = null

    /** Solved frame currently focused by the host screen, or -1 for none. */
    var selectedFrameIndex: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    /** Where each node was last drawn, for hit-testing taps. */
    private class Placed(val node: Node, val x: Float, val y: Float)
    private val placed = ArrayList<Placed>()

    private companion object {
        const val AXIS_LABEL_SP = 11f

        /** Full gutters (wizard sweep preview): tick labels + a separate axis title. */
        const val PAD_LEFT_FULL_DP = 44f
        const val PAD_BOTTOM_FULL_DP = 44f

        /** [compact] gutters (result lattice): tick labels only, unit folded in. */
        const val PAD_LEFT_COMPACT_DP = 30f
        const val PAD_BOTTOM_COMPACT_DP = 20f

        const val PAD_RIGHT_DP = 14f
        const val PAD_TOP_DP = 14f
        const val NODE_RADIUS_DP = 5f
        const val NODE_STROKE_DP = 2f
        const val CONNECTOR_DP = 1.5f
        const val GRID_DP = 1f
        const val TICK_GAP_DP = 5f
        const val Y_TICKS = 4

        /** Y-axis floor: VSG ticks / origin start at 1. */
        const val AXIS_MIN = 1

        /** Head-room above the VSG range so nodes are not clipped. */
        const val Y_MARGIN_FRACTION = 0.12f

        /** Baseline nudge that centres a tick label on its gridline. */
        const val TICK_BASELINE = 0.34f

        /** Half a column, so a column's nodes sit at its centre. */
        const val HALF_COLUMN = 0.5f

        /** How far below the x-axis ticks the axis title sits, in text heights. */
        const val AXIS_TITLE_OFFSET = 2.4f

        /** Tap tolerance around a node centre, in dp. */
        const val TOUCH_RADIUS_DP = 22f
        const val SELECT_RING_DP = 3f
    }

    /** Axis labels in px, scaled for the user's font-size setting. */
    private val axisLabelPx =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, AXIS_LABEL_SP, resources.displayMetrics)

    // Reused every draw — onDraw runs on each lattice interaction.
    private val columnX = HashMap<Int, Float>()
    private val frame = Frame()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(GRID_DP)
        color = ContextCompat.getColor(context, R.color.viewer_plot_grid)
    }
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(CONNECTOR_DP)
        color = ContextCompat.getColor(context, R.color.viewer_plot_connector)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(NODE_STROKE_DP)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = axisLabelPx
        color = ContextCompat.getColor(context, R.color.viewer_plot_ink)
        typeface = Typeface.MONOSPACE
    }
    private val path = Path()

    private var nodes: List<Node> = emptyList()
    private var columns: List<Int> = emptyList()
    private var winMin = AXIS_MIN
    private var winMax = AXIS_MIN + 1

    fun setNodes(nodes: List<Node>) {
        this.nodes = nodes
        columns = nodes.map { it.subset }.distinct().sorted()
        val windows = nodes.map { it.vsg }
        // Frame the windows actually swept rather than anchoring at 1: the
        // sweep's own range is the interesting span, and starting below it threw
        // away most of the plot.
        val lo = windows.minOrNull() ?: AXIS_MIN
        val hi = windows.maxOrNull() ?: AXIS_MIN
        val yMargin = ((hi - lo) * Y_MARGIN_FRACTION).toInt().coerceAtLeast(1)
        winMin = (lo - yMargin).coerceAtLeast(0)
        winMax = maxOf(hi + yMargin, winMin + 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        placed.clear()
        if (nodes.isEmpty() || columns.isEmpty()) return

        val left = dp(if (compact) PAD_LEFT_COMPACT_DP else PAD_LEFT_FULL_DP)
        val right = width - dp(PAD_RIGHT_DP)
        val top = dp(PAD_TOP_DP)
        val bottom = height - dp(if (compact) PAD_BOTTOM_COMPACT_DP else PAD_BOTTOM_FULL_DP)
        if (right <= left || bottom <= top) return

        columnX.clear()
        columns.forEachIndexed { i, subset ->
            columnX[subset] = left + (i + HALF_COLUMN) / columns.size * (right - left)
        }
        frame.set(left, right, top, bottom)
        fun yFor(win: Int) = bottom - (win - winMin).toFloat() / (winMax - winMin) * (bottom - top)

        drawGrid(canvas, columnX, frame)
        drawConnectors(canvas, columnX, ::yFor)
        drawNodes(canvas, columnX, ::yFor)
        drawLabels(canvas, columnX, frame)
    }

    /** Node where the current press started, for gesture resolution. */
    private var pressedNode: Node? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                pressedNode = nodeAt(e.x, e.y)
                val hit = pressedNode != null
                parent?.requestDisallowInterceptTouchEvent(hit)
                return hit
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val node = nodeAt(e.x, e.y)
                if (node != null) {
                    performClick()
                    onNodeClick?.invoke(node)
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val node = nodeAt(e.x, e.y)
                if (node != null) {
                    onNodeDoubleClick?.invoke(node)
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                val node = nodeAt(e.x, e.y)
                if (node != null) onNodeLongClick?.invoke(node)
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean = pressedNode != null
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactionEnabled) return super.onTouchEvent(event)
        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            pressedNode = null
        }
        return handled || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** The nearest node within the tap tolerance of (x, y), or null. */
    private fun nodeAt(x: Float, y: Float): Node? {
        val hit = placed
            .minByOrNull { hypot(it.x - x, it.y - y) }
            ?: return null
        return if (hypot(hit.x - x, hit.y - y) <= dp(TOUCH_RADIUS_DP)) hit.node else null
    }

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

    private fun drawGrid(canvas: Canvas, columnX: Map<Int, Float>, f: Frame) {
        columnX.values.forEach { x -> canvas.drawLine(x, f.top, x, f.bottom, gridPaint) }
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = ContextCompat.getColor(context, R.color.viewer_plot_ink)
        for (i in 0..Y_TICKS) {
            val y = f.bottom - (f.bottom - f.top) * i / Y_TICKS
            canvas.drawLine(f.left, y, f.right, y, gridPaint)
            val vsg = winMin + (winMax - winMin) * i / Y_TICKS
            val baseline = y + textPaint.textSize * TICK_BASELINE
            canvas.drawText(vsg.toString(), f.left - dp(TICK_GAP_DP), baseline, textPaint)
        }
    }

    /** A faint ladder down each column, so a subset's VSG series reads as one run. */
    private fun drawConnectors(canvas: Canvas, columnX: Map<Int, Float>, yFor: (Int) -> Float) {
        columns.forEach { subset ->
            val x = columnX[subset] ?: return@forEach
            val ladder = nodes.filter { it.subset == subset }.sortedBy { it.vsg }
            if (ladder.size < 2) return@forEach
            path.reset()
            ladder.forEachIndexed { i, node ->
                if (i == 0) path.moveTo(x, yFor(node.vsg)) else path.lineTo(x, yFor(node.vsg))
            }
            canvas.drawPath(path, connectorPaint)
        }
    }

    private fun drawNodes(canvas: Canvas, columnX: Map<Int, Float>, yFor: (Int) -> Float) {
        val radius = dp(NODE_RADIUS_DP)
        val selectedRadius = radius + dp(SELECT_RING_DP)
        // Single solved colour, not one per curve (VsgPlotView.paletteColor is
        // reserved for the plot's own emphasis -- keying the node fill to it too
        // taught a colour that never matched once more than one node was solved,
        // since only the FOCUSED curve is ever shown in colour there now).
        val solved = ContextCompat.getColor(context, R.color.viewer_plot_node_solved)
        val skipped = ContextCompat.getColor(context, R.color.viewer_plot_node_skipped)
        val ring = ContextCompat.getColor(context, R.color.viewer_plot_ink_strong)
        nodes.forEach { node ->
            val x = columnX[node.subset] ?: return@forEach
            val y = yFor(node.vsg)
            placed.add(Placed(node, x, y))
            if (node.solved) {
                fillPaint.color = solved
                canvas.drawCircle(x, y, radius, fillPaint)
                if (selectedFrameIndex >= 0 && node.frameIndex == selectedFrameIndex) {
                    strokePaint.color = ring
                    canvas.drawCircle(x, y, selectedRadius, strokePaint)
                }
            } else {
                // Hollow ring, no fill: a combination that was attempted and
                // failed. Deliberately transparent at the centre rather than a
                // background-matched disc -- this view is hosted on different
                // surfaces (result lattice, wizard preview card), and only a
                // truly empty centre is guaranteed to match all of them.
                strokePaint.color = skipped
                canvas.drawCircle(x, y, radius, strokePaint)
            }
        }
    }

    private fun drawLabels(canvas: Canvas, columnX: Map<Int, Float>, f: Frame) {
        textPaint.color = ContextCompat.getColor(context, R.color.viewer_plot_ink)
        textPaint.textAlign = Paint.Align.CENTER
        val lastColumn = columns.lastOrNull()
        columns.forEach { subset ->
            val x = columnX[subset] ?: return@forEach
            // Compact mode has no separate "Subset size (px)" title, so the
            // rightmost tick carries the unit instead.
            val label = if (compact && subset == lastColumn) {
                context.getString(R.string.vsg_lattice_axis_subset_unit_fmt, subset)
            } else {
                subset.toString()
            }
            canvas.drawText(label, x, f.bottom + textPaint.textSize + dp(TICK_GAP_DP), textPaint)
        }
        if (compact) {
            textPaint.textAlign = Paint.Align.LEFT
            return
        }

        textPaint.color = ContextCompat.getColor(context, R.color.viewer_plot_ink_strong)
        canvas.drawText(
            context.getString(R.string.vsg_lattice_axis_subset),
            (f.left + f.right) / 2f,
            f.bottom + textPaint.textSize * AXIS_TITLE_OFFSET + dp(TICK_GAP_DP),
            textPaint,
        )
        // Pivot at the frame's vertical centre, not bottom/2f -- the old pivot
        // ignored top's offset (PAD_TOP_DP), so the rotated title sat high.
        canvas.withSave {
            val pivot = (f.top + f.bottom) / 2f
            rotate(-QUARTER_TURN, textPaint.textSize, pivot)
            drawText(context.getString(R.string.vsg_lattice_axis_vsg), textPaint.textSize, pivot, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
    }
}

private const val QUARTER_TURN = 90f
