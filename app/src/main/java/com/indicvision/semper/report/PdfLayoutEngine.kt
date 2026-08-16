// PDF rendering code: page coordinates, paint sizes and long canvas draw calls
// are literal by nature and read clearest inline, so MagicNumber is suppressed
// for this whole file rather than named one offset at a time.

@file:Suppress("MagicNumber", "TooManyFunctions")

package com.indicvision.semper.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.toColorInt
import java.util.Locale

class PdfLayoutEngine(
    private val pdfDocument: PdfDocument,
    private val brandLogo: Bitmap? = null,
) {
    val pageWidth = 2480f
    val pageHeight = 3508f
    val margin = 150f
    val contentWidth = pageWidth - (margin * 2)

    private var currentPage: PdfDocument.Page? = null
    var canvas: Canvas? = null
        private set
    var cursorY = 0f
        private set
    private var pageNumber = 0

    // Design System Colors
    private val colorPrimary = "#1A237E".toColorInt() // Navy Blue
    private val colorText = "#37474F".toColorInt() // Slate Gray
    private val colorBorder = "#CFD8DC".toColorInt() // Light Gray
    private val colorZebra = "#F8F9FA".toColorInt() // Faint Gray

    // Typography
    private val h1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorPrimary
        textSize = 90f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val h2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textSize = 55f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val bodyPaintLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textSize = 38f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val bodyPaintRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textSize = 38f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val tableHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 35f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    // Smooth Upscaling Paint for our tiny Bitmaps
    private val upscalerPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Smart Mathematical Formatter for PDF Tables!
    private fun formatMetric(value: Float): String {
        val absVal = kotlin.math.abs(value)
        return if (absVal > 0f && (absVal < 0.001f || absVal >= 10000f)) {
            String.format(Locale.US, "%.2e", value)
        } else {
            String.format(Locale.US, "%.5f", value)
        }
    }

    fun newPage(): Canvas {
        currentPage?.let { pdfDocument.finishPage(it) }
        pageNumber++
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), pageNumber).create()
        val page = pdfDocument.startPage(pageInfo)
        currentPage = page
        canvas = page.canvas
        cursorY = margin
        drawBrandHeader()
        drawFooter()
        return page.canvas
    }

    fun finishCurrentPage() {
        currentPage?.let { pdfDocument.finishPage(it) }
        currentPage = null
        canvas = null
    }

    private fun drawFooter() {
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorBorder
            textSize = 30f
            textAlign = Paint.Align.CENTER
        }
        canvas?.drawLine(
            margin,
            pageHeight - margin,
            pageWidth - margin,
            pageHeight - margin,
            Paint().apply {
                color = colorBorder
                strokeWidth = 2f
            },
        )
        canvas?.drawText(
            "Semper Metrology Report • Page $pageNumber",
            pageWidth / 2f,
            pageHeight - (margin / 2f),
            footerPaint,
        )
    }

    fun drawBrandHeader() {
        val logo = brandLogo
        if (logo == null || logo.isRecycled || logo.width <= 0) return
        val targetW = BRAND_LOGO_WIDTH
        val scale = targetW / logo.width.toFloat()
        val targetH = logo.height * scale
        val dest = RectF(margin, cursorY, margin + targetW, cursorY + targetH)
        canvas?.drawBitmap(logo, null, dest, upscalerPaint)
        cursorY += targetH + BRAND_LOGO_GAP
    }

    fun drawTitle(title: String) {
        canvas?.drawText(title, margin, cursorY + 80f, h1Paint)
        cursorY += 120f
        canvas?.drawLine(
            margin,
            cursorY,
            pageWidth - margin,
            cursorY,
            Paint().apply {
                color = colorPrimary
                strokeWidth = 6f
            },
        )
        cursorY += 60f
    }

    fun drawSectionHeader(title: String) {
        canvas?.drawText(title, margin, cursorY + 60f, h2Paint)
        cursorY += 100f
    }

    fun drawKeyValue(key: String, value: String) {
        canvas?.drawText(key, margin, cursorY + 40f, bodyPaintLeft)
        canvas?.drawText(value, pageWidth - margin, cursorY + 40f, bodyPaintRight)
        cursorY += 60f
        canvas?.drawLine(
            margin,
            cursorY,
            pageWidth - margin,
            cursorY,
            Paint().apply {
                color = colorZebra
                strokeWidth = 2f
            },
        )
        cursorY += 20f
    }

    fun advanceY(amount: Float) {
        cursorY += amount
    }

    fun drawTable(headers: List<String>, rows: List<List<String>>, colWeights: List<Float>) {
        val rowHeight = 80f
        val colWidths = colWeights.map { it * contentWidth }

        canvas?.drawRect(
            margin,
            cursorY,
            pageWidth - margin,
            cursorY + rowHeight,
            Paint().apply { color = colorPrimary },
        )

        var currentX = margin
        for ((i, header) in headers.withIndex()) {
            val alignX = if (i == 0) currentX + 20f else currentX + colWidths[i] - 20f
            val paint = if (i == 0) {
                tableHeaderPaint
            } else {
                Paint(tableHeaderPaint).apply { textAlign = Paint.Align.RIGHT }
            }
            canvas?.drawText(header, alignX, cursorY + 55f, paint)
            currentX += colWidths[i]
        }
        cursorY += rowHeight

        val rowBgZebra = Paint().apply { color = colorZebra }
        for ((rowIndex, row) in rows.withIndex()) {
            if (rowIndex % 2 == 1) {
                canvas?.drawRect(margin, cursorY, pageWidth - margin, cursorY + rowHeight, rowBgZebra)
            }

            currentX = margin
            for ((colIndex, cell) in row.withIndex()) {
                val alignX = if (colIndex == 0) currentX + 20f else currentX + colWidths[colIndex] - 20f
                val paint = if (colIndex == 0) bodyPaintLeft else bodyPaintRight
                canvas?.drawText(cell, alignX, cursorY + 55f, paint)
                currentX += colWidths[colIndex]
            }
            cursorY += rowHeight
        }
        canvas?.drawLine(
            margin,
            cursorY,
            pageWidth - margin,
            cursorY,
            Paint().apply {
                color = colorPrimary
                strokeWidth = 4f
            },
        )
        cursorY += 60f
    }

    fun drawInputVerificationCard(refBmp: Bitmap, refName: String, defBmp: Bitmap, defName: String) {
        val imgWidth = (contentWidth - 60f) / 2f
        val startY = cursorY + 40f

        val refRatio = imgWidth / refBmp.width
        val refHeight = refBmp.height * refRatio

        val defRatio = imgWidth / defBmp.width
        val defHeight = defBmp.height * defRatio

        val maxImgHeight = maxOf(refHeight, defHeight)

        val cardRect = RectF(margin, cursorY, pageWidth - margin, startY + maxImgHeight + 100f)
        canvas?.drawRoundRect(cardRect, 20f, 20f, Paint().apply { color = Color.WHITE })
        canvas?.drawRoundRect(
            cardRect,
            20f,
            20f,
            Paint().apply {
                color = colorBorder
                style = Paint.Style.STROKE
                strokeWidth = 4f
            },
        )

        canvas?.drawBitmap(
            refBmp,
            null,
            RectF(margin + 20f, startY, margin + 20f + imgWidth, startY + refHeight),
            upscalerPaint,
        )
        canvas?.drawBitmap(
            defBmp,
            null,
            RectF(margin + 40f + imgWidth, startY, margin + 40f + imgWidth * 2f, startY + defHeight),
            upscalerPaint,
        )

        val labelPaint = Paint(bodyPaintLeft).apply {
            textAlign = Paint.Align.CENTER
            textSize = 32f
        }
        canvas?.drawText("Ref: $refName", margin + 20f + (imgWidth / 2f), startY + maxImgHeight + 60f, labelPaint)
        canvas?.drawText(
            "Def: $defName",
            margin + 40f + imgWidth + (imgWidth / 2f),
            startY + maxImgHeight + 60f,
            labelPaint,
        )

        cursorY += maxImgHeight + 160f
    }

    fun drawFieldBlock(field: FieldResult, blockHeight: Float) {
        val startY = cursorY

        canvas?.drawText("${field.fieldName}  [${field.unit}]", margin, cursorY + 60f, h2Paint)
        cursorY += 100f

        // Dynamic Scientific Notation applied here!
        drawTable(
            headers = listOf("Metric", "Value", "Location (X,Y)"),
            rows = listOf(
                listOf("Maximum (+)", formatMetric(field.maxValue), "(${field.maxCoordX}, ${field.maxCoordY})"),
                listOf("Minimum (-)", formatMetric(field.minValue), "(${field.minCoordX}, ${field.minCoordY})"),
                listOf(field.meanType, formatMetric(field.meanValue), "—"),
                listOf("Standard Dev.", formatMetric(field.stdDevValue), "—"),
            ),
            colWeights = listOf(0.4f, 0.3f, 0.3f),
        )

        val remainingSpace = blockHeight - (cursorY - startY) - 40f
        val scale = contentWidth / field.bakedHeatmap.width
        var drawH = field.bakedHeatmap.height * scale
        var drawW = contentWidth

        if (drawH > remainingSpace) {
            drawH = remainingSpace
            drawW = field.bakedHeatmap.width * (remainingSpace / field.bakedHeatmap.height)
        }

        val centerOffset = (contentWidth - drawW) / 2f
        val destRect = RectF(margin + centerOffset, cursorY, margin + centerOffset + drawW, cursorY + drawH)

        canvas?.drawBitmap(field.bakedHeatmap, null, destRect, upscalerPaint)
        canvas?.drawRect(
            destRect,
            Paint().apply {
                color = colorBorder
                style = Paint.Style.STROKE
                strokeWidth = 3f
            },
        )

        cursorY = startY + blockHeight
    }

    fun drawDiagnosticBlock(title: String, bitmap: Bitmap, blockHeight: Float) {
        val startY = cursorY

        canvas?.drawText(title, margin, cursorY + 60f, h2Paint)
        cursorY += 100f

        val remainingSpace = blockHeight - (cursorY - startY) - 40f
        val scale = contentWidth / bitmap.width
        var drawH = bitmap.height * scale
        var drawW = contentWidth

        if (drawH > remainingSpace) {
            drawH = remainingSpace
            drawW = bitmap.width * (remainingSpace / bitmap.height)
        }

        val centerOffset = (contentWidth - drawW) / 2f
        val destRect = RectF(margin + centerOffset, cursorY, margin + centerOffset + drawW, cursorY + drawH)

        canvas?.drawBitmap(bitmap, null, destRect, upscalerPaint)
        canvas?.drawRect(
            destRect,
            Paint().apply {
                color = colorBorder
                style = Paint.Style.STROKE
                strokeWidth = 3f
            },
        )

        cursorY = startY + blockHeight
    }

    private companion object {
        const val BRAND_LOGO_WIDTH = 520f
        const val BRAND_LOGO_GAP = 40f
    }
}
