package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.sweep.PlotStyle
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import java.util.Locale

/**
 * The run overlay's convergence per frame: one point per planned frame on a
 * 0–100% scale, a dashed line where [ConvergenceGate] counts a frame as low,
 * red dots on the frames under it, and a dot on the latest solved frame.
 *
 * Frames not solved yet (NaN) leave the rest of the axis empty, so the line
 * also shows how far the run has come. A frame that kept no points is a gap.
 */
class ConvergenceLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var values = FloatArray(0)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = PlotStyle.px(context, LINE_DP)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.sky_primary)
    }
    private val axisPaint = PlotStyle.gridPaint(context)
    private val gatePaint = PlotStyle.gridPaint(context, R.color.viewer_plot_muted).apply {
        val dash = PlotStyle.px(context, DASH_DP)
        pathEffect = DashPathEffect(floatArrayOf(dash, dash), 0f)
    }
    private val gateLabelPaint = PlotStyle.axisTextPaint(context, R.color.viewer_plot_muted).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val lowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.semantic_danger)
    }
    private val latestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PlotStyle.inkStrong(context) }
    private val path = Path()
    private val box = RectF()
    private val gateLabel = String.format(Locale.US, "%.0f%%", AnalysisViewModel.MIN_CONVERGENCE_PERCENT)

    /** Each planned frame's convergence, NaN until solved. Kept, not copied: pass a snapshot. */
    fun setValues(values: FloatArray) {
        this.values = values
        contentDescription = ConvergenceTrace.latest(values)?.let {
            context.getString(R.string.run_convergence_cd, it)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        box.set(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            (width - paddingRight).toFloat(),
            (height - paddingBottom).toFloat(),
        )
        if (box.width() <= 0f || box.height() <= 0f) return
        drawAxes(canvas, box)
        val latest = drawLine(canvas, box)
        drawDots(canvas, box, latest)
    }

    private fun x(box: RectF, i: Int): Float =
        if (values.size <= 1) box.centerX() else box.left + i * box.width() / (values.size - 1)

    private fun y(box: RectF, v: Float): Float = box.bottom - v.coerceIn(0f, FULL) / FULL * box.height()

    private fun solved(v: Float) = !v.isNaN() && v >= 0f

    private fun drawAxes(canvas: Canvas, box: RectF) {
        canvas.drawLine(box.left, box.bottom, box.right, box.bottom, axisPaint)
        val gateY = y(box, AnalysisViewModel.MIN_CONVERGENCE_PERCENT)
        canvas.drawLine(box.left, gateY, box.right, gateY, gatePaint)
        canvas.drawText(gateLabel, box.right, gateY - PlotStyle.px(context, PlotStyle.TICK_GAP_DP), gateLabelPaint)
    }

    /** Draws the line through solved frames, broken at gaps; returns the latest solved index, or -1. */
    private fun drawLine(canvas: Canvas, box: RectF): Int {
        path.reset()
        var penDown = false
        var latest = -1
        values.forEachIndexed { i, v ->
            if (!solved(v)) {
                penDown = false
                return@forEachIndexed
            }
            if (penDown) path.lineTo(x(box, i), y(box, v)) else path.moveTo(x(box, i), y(box, v))
            penDown = true
            latest = i
        }
        canvas.drawPath(path, linePaint)
        return latest
    }

    private fun drawDots(canvas: Canvas, box: RectF, latest: Int) {
        val lowRadius = PlotStyle.px(context, LOW_DOT_DP)
        values.forEachIndexed { i, v ->
            if (solved(v) && v < AnalysisViewModel.MIN_CONVERGENCE_PERCENT) {
                canvas.drawCircle(x(box, i), y(box, v), lowRadius, lowPaint)
            }
        }
        if (latest >= 0) {
            canvas.drawCircle(x(box, latest), y(box, values[latest]), PlotStyle.px(context, LATEST_DOT_DP), latestPaint)
        }
    }

    private companion object {
        const val FULL = 100f
        const val LINE_DP = 1.8f
        const val DASH_DP = 3f
        const val LOW_DOT_DP = 2.6f
        const val LATEST_DOT_DP = 3f
    }
}
