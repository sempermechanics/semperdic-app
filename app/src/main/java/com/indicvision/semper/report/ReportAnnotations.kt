// Field-image annotations: literal proportions of the image read clearest
// inline, and the bake keeps its draw order in one function.
@file:Suppress("MagicNumber", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")

package com.indicvision.semper.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.field.ValueRange
import com.indicvision.semper.report.ReportBuilder.FieldAnnotation
import com.indicvision.semper.report.ReportBuilder.FieldExtrema
import com.indicvision.semper.report.ReportBuilder.formatMetric

/**
 * What a baked field image carries over its heatmap: the info box, the colour
 * bar ([ReportColorBar]) and the MAX / MIN markers.
 * [ReportBuilder.bakeAnnotationsToCanvas] is its public face.
 */
internal object ReportAnnotations {

    /** The annotation text size on a field image [width] wide. */
    fun textSizeFor(width: Int): Float = width * 0.025f

    /** The annotation padding on a field image [width] wide. */
    fun paddingFor(width: Int): Float = width * 0.02f

    /**
     * Draws the info box, the colour bar for [range] (stored units) and the
     * MAX / MIN markers at [extrema] onto a field image [width] × [height].
     * [data] coordinates are scaled by [coordScale] to land on a capped canvas.
     */
    fun bakeAnnotationsToCanvas(
        canvas: Canvas,
        width: Int,
        height: Int,
        range: ValueRange,
        extrema: FieldExtrema,
        data: FloatArray,
        annotation: FieldAnnotation,
        coordScale: Float = 1f,
    ) {
        val unit = annotation.unit
        val dataIndex = annotation.dataIndex
        val multiplier = if (unit == "mε") DicResult.STRAIN_TO_MILLISTRAIN else 1f
        val maxVal = range.max * multiplier
        val minVal = range.min * multiplier
        val maxIdx = extrema.maxIdx
        val minIdx = extrema.minIdx

        val textSize = textSizeFor(width)
        val padding = paddingFor(width)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        val bgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }

        val infoText = listOfNotNull(
            "Semper Analysis Report",
            annotation.imageName?.takeIf { it.isNotBlank() }?.let { "Image: $it" },
            "Field: ${annotation.typeString} [$unit]",
            // The marked points' values; the colour bar below keeps the scale's ends.
            "Max: ${formatMetric(dataIndex?.let { extrema.maxValue(data, it) } ?: maxVal)}",
            "Min: ${formatMetric(dataIndex?.let { extrema.minValue(data, it) } ?: minVal)}",
        )
        var maxTextWidth = 0f
        for (line in infoText) {
            val w = textPaint.measureText(line)
            if (w > maxTextWidth) maxTextWidth = w
        }

        canvas.drawRect(
            padding * 0.5f,
            padding * 0.5f,
            padding * 1.5f + maxTextWidth,
            padding + (infoText.size * (textSize * 1.4f)) + padding,
            bgPaint,
        )
        var currentY = padding + textSize
        for (line in infoText) {
            canvas.drawText(line, padding, currentY, textPaint)
            currentY += textSize * 1.4f
        }

        ReportColorBar.draw(canvas, width, height, textPaint, ValueRange(minVal, maxVal))

        if (maxIdx != -1 && minIdx != -1) {
            // Data coords are in full-resolution image space; scale them to the
            // (possibly capped) canvas so markers land correctly.
            val maxX = data[maxIdx] * coordScale
            val maxY = data[maxIdx + 1] * coordScale
            val minX = data[minIdx] * coordScale
            val minY = data[minIdx + 1] * coordScale
            val targetRadius = width * 0.015f
            val crosshairLen = targetRadius * 1.5f
            val whiteOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 6f
            }
            val markerTextPaint = Paint(textPaint).apply { this.textSize = width * 0.018f }

            fun drawTarget(x: Float, y: Float, label: String, coreColor: Int) {
                canvas.drawCircle(x, y, targetRadius, whiteOutline)
                canvas.drawLine(x - crosshairLen, y, x + crosshairLen, y, whiteOutline)
                canvas.drawLine(x, y - crosshairLen, x, y + crosshairLen, whiteOutline)
                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = coreColor
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
                canvas.drawCircle(x, y, targetRadius, corePaint)
                canvas.drawLine(x - crosshairLen, y, x + crosshairLen, y, corePaint)
                canvas.drawLine(x, y - crosshairLen, x, y + crosshairLen, corePaint)
                canvas.drawText(label, x + targetRadius + 5f, y - targetRadius - 5f, markerTextPaint)
            }
            drawTarget(maxX, maxY, "MAX", Color.RED)
            if (annotation.drawMinMarker) {
                drawTarget(minX, minY, "MIN", Color.BLUE)
            }
        }
    }
}
