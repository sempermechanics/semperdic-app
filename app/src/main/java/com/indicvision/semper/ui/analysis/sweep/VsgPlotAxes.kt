package com.indicvision.semper.ui.analysis.sweep

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.withRotation
import com.indicvision.semper.ui.common.dp
import java.util.Locale
import kotlin.math.abs

/**
 * The [plot]'s axes: how wide its left gutter is, its tick labels and its
 * titles, all drawn in [text] (the plot's axis paint).
 */
internal class VsgPlotAxes(private val plot: View, private val text: Paint) {

    /** The left gutter for [b]: compact (ticks only) or full (ticks beside a rotated title). */
    fun leftPad(b: PlotBounds, compact: Boolean): Float = if (compact) {
        compactLeftPad(
            b.yMin,
            b.yMax,
            plot.dp(VsgPlotView.PAD_LEFT_COMPACT_DP),
            plot.dp(VsgPlotView.TICK_GAP_DP),
            text::measureText,
        )
    } else {
        fullLeftPad(b)
    }

    /**
     * The left gutter with a y title: the rotated title's band, then the
     * widest tick label, each with its gap. A fixed gutter let a wide tick
     * ("58.6", "435") run under the title.
     */
    private fun fullLeftPad(b: PlotBounds): Float {
        val widestTick = widestYTick(b.yMin, b.yMax, text::measureText)
        val titleBand = text.textSize * TITLE_BAND
        val gaps = plot.dp(VsgPlotView.TICK_GAP_DP) * FULL_PAD_GAPS
        return maxOf(plot.dp(VsgPlotView.PAD_LEFT_FULL_DP), titleBand + widestTick + gaps)
    }

    /** The y ticks down the gutter and the x range under the [frame]; [compact] folds the units in. */
    @Suppress("LongParameterList") // the range, where it is drawn, and the compact units
    fun drawTicks(canvas: Canvas, b: PlotBounds, frame: RectF, compact: Boolean, xUnit: String, yUnit: String) {
        val gap = plot.dp(VsgPlotView.TICK_GAP_DP)
        text.color = PlotStyle.ink(plot.context)
        text.textAlign = Paint.Align.RIGHT
        for (i in 0..VsgPlotView.GRID_LINES) {
            val y = frame.bottom - (frame.bottom - frame.top) * i / VsgPlotView.GRID_LINES
            val value = yTick(b.yMin, b.yMax, i)
            canvas.drawText(tickLabel(value), frame.left - gap, y + text.textSize * VsgPlotView.TICK_BASELINE, text)
        }
        if (compact && yUnit.isNotEmpty()) {
            // The compact gutter is sized to the numbers alone, so a number+unit
            // tick right-aligned into it would run past the view's own left
            // edge -- draw the unit on its own, left-aligned
            // into the data area's top-left corner instead, where there's slack.
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(yUnit, frame.left + gap, frame.top + text.textSize, text)
        }
        val baseline = frame.bottom + text.textSize + gap
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(tickLabel(b.xMin), frame.left, baseline, text)
        text.textAlign = Paint.Align.RIGHT
        val xMaxLabel = if (compact && xUnit.isNotEmpty()) "${tickLabel(b.xMax)} $xUnit" else tickLabel(b.xMax)
        canvas.drawText(xMaxLabel, frame.right, baseline, text)
    }

    /** The x title under the ticks and the y title rotated up the gutter. */
    fun drawTitles(canvas: Canvas, frame: RectF, xLabel: String, yLabel: String) {
        val gap = plot.dp(VsgPlotView.TICK_GAP_DP)
        text.textAlign = Paint.Align.CENTER
        text.color = PlotStyle.inkStrong(plot.context)
        canvas.drawText(
            xLabel,
            (frame.left + frame.right) / 2f,
            frame.bottom + text.textSize * 2f + gap,
            text,
        )
        // Pivot at the frame's vertical centre, not bottom/2f -- the old pivot
        // ignored top's offset (PAD_TOP_DP), so the rotated title sat high.
        val pivot = (frame.top + frame.bottom) / 2f
        canvas.withRotation(-QUARTER_TURN, gap + text.textSize, pivot) {
            drawText(yLabel, gap + text.textSize, pivot, text)
        }
        text.textAlign = Paint.Align.LEFT
    }

    companion object {
        /**
         * Compact tick label: enough digits to separate neighbouring gridlines.
         * A value that rounds to zero is written "0.00", never "-0.00": a padded
         * axis starting a hair below zero once read that way.
         */
        fun tickLabel(value: Float): String {
            val text = when {
                abs(value) >= LARGE_VALUE -> String.format(Locale.US, "%.0f", value)
                abs(value) >= SMALL_VALUE -> String.format(Locale.US, "%.1f", value)
                else -> String.format(Locale.US, "%.2f", value)
            }
            return if (text.startsWith('-') && text.all { it in "-0." }) text.drop(1) else text
        }

        /**
         * Where the scrub label starts: right of the line at [px], [clearance]
         * clear of the dot, or flipped to its left where it would run past
         * [right] — never across the line it labels. Held inside [left].
         */
        fun scrubLabelX(px: Float, width: Float, clearance: Float, left: Float, right: Float): Float {
            val x = if (px + clearance + width <= right) px + clearance else px - clearance - width
            return x.coerceAtLeast(left)
        }

        /** The i-th y tick value, 0 at the bottom gridline to [VsgPlotView.GRID_LINES] at the top. */
        fun yTick(yMin: Float, yMax: Float, i: Int): Float = yMin + (yMax - yMin) * i / VsgPlotView.GRID_LINES

        /** Width, by [measure], of the widest y tick label as [tickLabel] writes it. */
        fun widestYTick(yMin: Float, yMax: Float, measure: (String) -> Float): Float =
            (0..VsgPlotView.GRID_LINES).maxOf { measure(tickLabel(yTick(yMin, yMax, it))) }

        /**
         * The compact left gutter: the widest y tick with [gap] on either side,
         * never narrower than [minPad]. A fixed gutter cut the leading digits
         * off a bending load axis ("21686" read "1686").
         */
        fun compactLeftPad(
            yMin: Float,
            yMax: Float,
            minPad: Float,
            gap: Float,
            measure: (String) -> Float,
        ): Float = maxOf(minPad, widestYTick(yMin, yMax, measure) + gap * 2f)

        private const val QUARTER_TURN = 90f

        /** A rotated title's width across its baseline: ascent plus descent, in text sizes. */
        private const val TITLE_BAND = 1.25f

        /** Tick gaps in a full gutter: edge to title, title to ticks, ticks to the axis. */
        private const val FULL_PAD_GAPS = 3f
        private const val LARGE_VALUE = 100f
        private const val SMALL_VALUE = 1f
    }
}
