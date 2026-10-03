// The bar's literal proportions of the image read clearest inline.
@file:Suppress("MagicNumber")

package com.sempermechanics.semper.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.report.ReportBuilder.formatMetric

/** The colour bar on a report's field image, with its max, middle and min labels. */
internal object ReportColorBar {

    /**
     * Draws the bar for [display] (display units: the labels' values) at the
     * right of a field image [width] × [height], its labels in [textPaint]'s
     * face without its shadow.
     */
    fun draw(canvas: Canvas, width: Int, height: Int, textPaint: Paint, display: ValueRange) {
        val maxVal = display.max
        val minVal = display.min
        val textSize = ReportAnnotations.textSizeFor(width)
        val padding = ReportAnnotations.paddingFor(width)

        val barWidth = width * 0.03f
        val barHeight = height * 0.5f
        val barLeft = width - padding - barWidth - (textSize * 4.5f)
        val barTop = (height - barHeight) / 2f
        val barRight = barLeft + barWidth
        val barBottom = barTop + barHeight

        // The map's own ramp, lowest value at the bottom. A six-stop jet drawn here
        // before put pure red at 80 % of the scale where the map has it at 87.5 %,
        // off by up to 83 levels in a channel, so values read off the bar were wrong.
        canvas.drawRect(
            barLeft,
            barTop,
            barRight,
            barBottom,
            Paint().apply {
                shader = LinearGradient(
                    0f,
                    barBottom,
                    0f,
                    barTop,
                    VisualizationEngine.rampColors(),
                    null,
                    Shader.TileMode.CLAMP,
                )
            },
        )
        canvas.drawRect(
            barLeft,
            barTop,
            barRight,
            barBottom,
            Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 3f
            },
        )

        val scaleTextPaint = Paint(textPaint).apply {
            textAlign = Paint.Align.LEFT
            clearShadowLayer()
            color = Color.BLACK
        }
        val whiteBgPaint = Paint().apply { color = Color.argb(200, 255, 255, 255) }
        fun drawScaleLabel(text: String, y: Float) {
            val w = scaleTextPaint.measureText(text)
            canvas.drawRect(
                barRight + padding * 0.5f - 5f,
                y - textSize,
                barRight + padding * 0.5f + w + 5f,
                y + (textSize * 0.3f),
                whiteBgPaint,
            )
            canvas.drawText(text, barRight + padding * 0.5f, y, scaleTextPaint)
        }

        drawScaleLabel(formatMetric(maxVal), barTop + (textSize * 0.3f))
        drawScaleLabel(formatMetric((maxVal + minVal) / 2f), barTop + (barHeight / 2f) + (textSize * 0.3f))
        drawScaleLabel(formatMetric(minVal), barBottom)
    }
}
