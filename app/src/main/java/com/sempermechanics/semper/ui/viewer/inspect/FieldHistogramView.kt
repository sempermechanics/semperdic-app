package com.sempermechanics.semper.ui.viewer.inspect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.FieldHistogram
import com.sempermechanics.semper.report.ReportBuilder
import com.sempermechanics.semper.ui.common.dp

/**
 * Gray histogram of one field's accepted values. No grid, no top or right spine.
 * X runs from the data min to the data max; Y runs from 0 to the tallest bin.
 */
class FieldHistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onBinSelected: ((Int) -> Unit)? = null

    private var histogram: FieldHistogram? = null
    private var unit: String = ""
    private var selectedBin: Int = -1

    private val labelPx =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_SP, resources.displayMetrics)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = labelPx
        typeface = Typeface.MONOSPACE
    }
    private val barRect = RectF()

    init {
        isClickable = true
    }

    fun setHistogram(histogram: FieldHistogram, unit: String) {
        this.histogram = histogram
        this.unit = unit
        selectedBin = -1
        contentDescription = context.resources.getQuantityString(
            R.plurals.viewer_histogram_desc_fmt,
            histogram.n,
            histogram.n,
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val hist = histogram
        val peak = hist?.counts?.maxOrNull() ?: 0
        if (hist == null || peak <= 0) return

        val muted = ContextCompat.getColor(context, R.color.viewer_plot_ink)
        val strong = ContextCompat.getColor(context, R.color.viewer_plot_ink_strong)
        axisPaint.color = muted
        textPaint.color = muted

        val left = dp(PAD_LEFT_DP)
        val right = width - dp(PAD_RIGHT_DP)
        val top = dp(PAD_TOP_DP)
        val bottom = height - dp(PAD_BOTTOM_DP)
        if (right <= left || bottom <= top) return

        val k = hist.binCount
        val innerW = right - left
        val gap = if (innerW / k > dp(2f)) dp(1f) else 0f
        val barW = (innerW - gap * (k - 1).coerceAtLeast(0)) / k
        val plotH = bottom - top

        for (i in 0 until k) {
            val h = hist.counts[i].toFloat() / peak * plotH
            val x = left + i * (barW + gap)
            barPaint.color = if (i == selectedBin) strong else muted
            barRect.set(x, bottom - h, x + barW, bottom)
            canvas.drawRect(barRect, barPaint)
        }

        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, bottom, left, top, axisPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        val tickGap = dp(TICK_GAP_DP)
        canvas.drawText("0", left - tickGap, bottom + textPaint.textSize * TICK_BASELINE, textPaint)
        canvas.drawText(
            peak.toString(),
            left - tickGap,
            top + textPaint.textSize * TICK_BASELINE,
            textPaint,
        )

        val baseline = bottom + textPaint.textSize + tickGap
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(ReportBuilder.formatMetric(hist.min), left, baseline, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        val maxLabel = "${ReportBuilder.formatMetric(hist.max)} $unit"
        canvas.drawText(maxLabel, right, baseline, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (histogram == null) return super.onTouchEvent(event)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP -> {
                selectAt(event.x)
                performClick()
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun selectAt(x: Float) {
        val hist = histogram ?: return
        val left = dp(PAD_LEFT_DP)
        val right = width - dp(PAD_RIGHT_DP)
        if (right <= left) return
        val rel = ((x - left) / (right - left)).coerceIn(0f, LAST_BIN_EDGE)
        val index = (rel * hist.binCount).toInt().coerceIn(0, hist.binCount - 1)
        selectedBin = index
        onBinSelected?.invoke(index)
        invalidate()
    }

    companion object {
        private const val PAD_LEFT_DP = 42f
        private const val PAD_RIGHT_DP = 8f
        private const val PAD_TOP_DP = 10f
        private const val PAD_BOTTOM_DP = 20f
        private const val TICK_GAP_DP = 4f
        private const val TICK_BASELINE = 0.33f
        private const val LABEL_SP = 11f

        /** Just short of the right edge, so a touch there selects the last bin. */
        private const val LAST_BIN_EDGE = 0.9999f
    }
}
