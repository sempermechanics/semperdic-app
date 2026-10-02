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

class PdfLayoutEngine(
    private val pdfDocument: PdfDocument,
    private val brandLogo: Bitmap? = null,
) {
    val pageWidth = PdfA4.DESIGN_WIDTH
    val pageHeight = PdfA4.DESIGN_HEIGHT
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
    private val colorWarn = "#B71C1C".toColorInt() // Deep Red
    private val colorWarnFill = "#FFEBEE".toColorInt() // Pale Red

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
    private val noticePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorWarn
        textSize = 40f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val tableHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 35f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    // Smooth Upscaling Paint for our tiny Bitmaps
    private val upscalerPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** A solid fill of [color]. */
    private fun fill(color: Int) = Paint().apply { this.color = color }

    /** A line of [color], [width] wide; [rule]'s paint. */
    private fun line(color: Int, width: Float) = Paint().apply {
        this.color = color
        strokeWidth = width
    }

    /** The outline of a shape in [color], [width] wide. */
    private fun outline(color: Int, width: Float, antiAlias: Boolean = false) =
        (if (antiAlias) Paint(Paint.ANTI_ALIAS_FLAG) else Paint()).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
        }

    /** A rule across the content width at [y]. */
    private fun rule(y: Float, color: Int, width: Float) {
        canvas?.drawLine(margin, y, pageWidth - margin, y, line(color, width))
    }

    fun newPage(): Canvas {
        currentPage?.let { pdfDocument.finishPage(it) }
        pageNumber++
        val page = PdfA4.startPage(pdfDocument, pageNumber)
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
        rule(pageHeight - margin, colorBorder, 2f)
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
        canvas?.drawRect(dest, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas?.drawBitmap(logo, null, dest, upscalerPaint)
        cursorY += targetH + BRAND_LOGO_GAP
    }

    fun drawTitle(title: String) {
        canvas?.drawText(title, margin, cursorY + 80f, h1Paint)
        cursorY += 120f
        rule(cursorY, colorPrimary, 6f)
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
        rule(cursorY, colorZebra, 2f)
        cursorY += 20f
    }

    fun advanceY(amount: Float) {
        cursorY += amount
    }

    /**
     * A boxed warning that reads before the numbers it qualifies do.
     *
     * Given its own primitive rather than a [drawKeyValue] pair because a
     * caveat rendered as one more grey row of the settings table is a caveat
     * nobody reads — and the one caveat this report carries is the one that
     * says which of its strain values are measurement and which are noise.
     * Wraps on words, so a longer sentence grows the box rather than running
     * off the page.
     */
    fun drawNotice(text: String) {
        val lines = wrap(text, noticePaint, contentWidth - (NOTICE_PAD * 2))
        val height = NOTICE_PAD * 2 + lines.size * NOTICE_LINE
        val box = RectF(margin, cursorY, pageWidth - margin, cursorY + height)
        canvas?.drawRoundRect(box, 12f, 12f, fill(colorWarnFill))
        canvas?.drawRoundRect(box, 12f, 12f, outline(colorWarn, 3f, antiAlias = true))
        lines.forEachIndexed { index, line ->
            canvas?.drawText(
                line,
                margin + NOTICE_PAD,
                cursorY + NOTICE_PAD + NOTICE_LINE * index + 45f,
                noticePaint,
            )
        }
        cursorY += height + 20f
    }

    /** Greedy word wrap; a single word wider than [width] gets its own line. */
    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        text.split(' ').forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= width || line.isEmpty()) {
                line = StringBuilder(candidate)
            } else {
                lines.add(line.toString())
                line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    fun drawTable(headers: List<String>, rows: List<List<String>>, colWeights: List<Float>) {
        val rowHeight = 80f
        val colWidths = colWeights.map { it * contentWidth }

        canvas?.drawRect(margin, cursorY, pageWidth - margin, cursorY + rowHeight, fill(colorPrimary))

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

        val rowBgZebra = fill(colorZebra)
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
        rule(cursorY, colorPrimary, 4f)
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
        canvas?.drawRoundRect(cardRect, 20f, 20f, fill(Color.WHITE))
        canvas?.drawRoundRect(cardRect, 20f, 20f, outline(colorBorder, 4f))

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
        val format = ReportBuilder::formatMetric
        drawTable(
            headers = listOf("Metric", "Value", "Location (X,Y)"),
            rows = listOf(
                listOf("Maximum (+)", format(field.maxValue), "(${field.maxCoordX}, ${field.maxCoordY})"),
                listOf("Minimum (-)", format(field.minValue), "(${field.minCoordX}, ${field.minCoordY})"),
                listOf(field.meanType, format(field.meanValue), "—"),
                listOf("Standard Dev.", format(field.stdDevValue), "—"),
            ),
            colWeights = listOf(0.4f, 0.3f, 0.3f),
        )
        canvas?.drawText(EXTREMA_NOTE, margin, cursorY, bodyPaintLeft)
        cursorY += 60f

        drawFittedBitmap(field.bakedHeatmap, startY, blockHeight)
    }

    fun drawDiagnosticBlock(title: String, bitmap: Bitmap, blockHeight: Float) {
        val startY = cursorY

        canvas?.drawText(title, margin, cursorY + 60f, h2Paint)
        cursorY += 100f

        drawFittedBitmap(bitmap, startY, blockHeight)
    }

    /**
     * Draws [bitmap] outlined at the cursor, as wide as the content or as tall
     * as what is left of the block that started at [startY] and is
     * [blockHeight] tall, whichever is smaller, and centred across. The cursor
     * then moves to the end of the block.
     */
    private fun drawFittedBitmap(bitmap: Bitmap, startY: Float, blockHeight: Float) {
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
        canvas?.drawRect(destRect, outline(colorBorder, 3f))

        cursorY = startY + blockHeight
    }

    private companion object {
        const val BRAND_LOGO_WIDTH = 520f
        const val BRAND_LOGO_GAP = 40f

        /** Inset and line pitch of [drawNotice]'s box. */
        const val NOTICE_PAD = 40f
        const val NOTICE_LINE = 55f

        /** Why the field table's max / min can differ from the CSV's. */
        const val EXTREMA_NOTE =
            "Max / min leave out the top and bottom 2% of points as outliers; the CSV gives raw extremes."
    }
}
