package com.sempermechanics.semper.report

import android.graphics.pdf.PdfDocument

/**
 * An A4 PDF page. [PdfDocument] sizes pages in PDF points (1/72 in), and A4 is
 * 595 × 842 pt. [PdfLayoutEngine] draws in a 2480 × 3508 design space (A4 at
 * 300 dpi) so its text sizes and rules read as pixels; each page's canvas is
 * scaled onto the real page. Declaring the design size as the page size, as the
 * layout once did, made an 87.5 × 123.8 cm page that printed at a quarter scale.
 */
internal object PdfA4 {
    const val DESIGN_WIDTH = 2480f
    const val DESIGN_HEIGHT = 3508f
    const val WIDTH_PT = 595
    const val HEIGHT_PT = 842

    /** Design units to points; the same both ways, since the design space is A4 too. */
    const val SCALE = WIDTH_PT / DESIGN_WIDTH

    /** Starts page [number] of [document] with its canvas in design units. */
    fun startPage(document: PdfDocument, number: Int): PdfDocument.Page {
        val page = document.startPage(PdfDocument.PageInfo.Builder(WIDTH_PT, HEIGHT_PT, number).create())
        page.canvas.scale(SCALE, SCALE)
        return page
    }
}
