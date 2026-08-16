package com.indicvision.semper.results

import android.graphics.pdf.PdfDocument
import com.indicvision.semper.report.PdfLayoutEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cursor and layout arithmetic in [PdfLayoutEngine].
 *
 * Canvas *output* is not meaningfully assertable off-device, but the vertical
 * cursor maths is: every `drawX` advances [PdfLayoutEngine.cursorY] by a fixed
 * amount, and a drift of one row height silently overlaps or orphans report
 * content. The draw calls are null-safe (`canvas?.drawText(...)`), so these
 * exercise the arithmetic without starting a real PDF page — that keeps the
 * native PDF surface out of the unit test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfLayoutEngineTest {

    private lateinit var doc: PdfDocument
    private lateinit var engine: PdfLayoutEngine

    /** Row height and trailing rule inside [PdfLayoutEngine.drawTable]. */
    private val tableRowHeight = 80f
    private val tableTrailingGap = 60f

    @Before
    fun setUp() {
        doc = PdfDocument()
        engine = PdfLayoutEngine(doc)
    }

    @Test
    fun `content width is the page minus both margins`() {
        assertEquals(engine.pageWidth - (engine.margin * 2), engine.contentWidth, 0.001f)
        assertEquals(2180f, engine.contentWidth, 0.001f)
    }

    @Test
    fun `a fresh engine starts at the top of the page`() {
        assertEquals(0f, engine.cursorY, 0.001f)
    }

    @Test
    fun `advanceY accumulates`() {
        engine.advanceY(100f)
        engine.advanceY(37.5f)
        assertEquals(137.5f, engine.cursorY, 0.001f)
    }

    @Test
    fun `drawBrandHeader with no logo does not advance the cursor`() {
        engine.drawBrandHeader()
        assertEquals(0f, engine.cursorY, 0.001f)
    }

    @Test
    fun `drawTitle advances past the rule beneath it`() {
        engine.drawTitle("Semper Metrology Report")
        // 120f for the title block, then 60f clearance under the rule.
        assertEquals(180f, engine.cursorY, 0.001f)
    }

    @Test
    fun `drawSectionHeader advances one header block`() {
        engine.drawSectionHeader("Inputs")
        assertEquals(100f, engine.cursorY, 0.001f)
    }

    @Test
    fun `drawKeyValue advances one row plus its separator`() {
        engine.drawKeyValue("Subset", "21")
        assertEquals(80f, engine.cursorY, 0.001f)
    }

    @Test
    fun `stacked key-value rows advance linearly`() {
        repeat(5) { engine.drawKeyValue("k$it", "v$it") }
        assertEquals(400f, engine.cursorY, 0.001f)
    }

    @Test
    fun `drawTable advances one header row plus one row per entry plus the trailing gap`() {
        val rows = listOf(
            listOf("exx", "0.00100"),
            listOf("eyy", "0.00200"),
            listOf("exy", "0.00300"),
        )

        engine.drawTable(
            headers = listOf("Metric", "Value"),
            rows = rows,
            colWeights = listOf(0.5f, 0.5f),
        )

        val expected = tableRowHeight * (1 + rows.size) + tableTrailingGap
        assertEquals(expected, engine.cursorY, 0.001f)
    }

    @Test
    fun `an empty table still costs a header row and the trailing gap`() {
        engine.drawTable(
            headers = listOf("Metric", "Value"),
            rows = emptyList(),
            colWeights = listOf(0.5f, 0.5f),
        )
        assertEquals(tableRowHeight + tableTrailingGap, engine.cursorY, 0.001f)
    }

    @Test
    fun `layout blocks compose without drifting`() {
        engine.drawTitle("Report") // 180
        engine.drawSectionHeader("Summary") // 100
        engine.drawKeyValue("Frames", "12") // 80
        engine.advanceY(20f) // 20

        assertEquals(380f, engine.cursorY, 0.001f)
    }

    /**
     * Characterization, not endorsement: [PdfLayoutEngine.drawTable] has no
     * page-break check, so a table longer than the page runs straight off the
     * bottom instead of calling [PdfLayoutEngine.newPage]. This test pins the
     * behaviour that exists today — if pagination is added, it should fail and
     * be rewritten to assert the break.
     */
    @Test
    fun `a long table currently overflows the page instead of breaking`() {
        val rows = List(60) { listOf("row$it", "0.001") }

        engine.drawTable(
            headers = listOf("Metric", "Value"),
            rows = rows,
            colWeights = listOf(0.5f, 0.5f),
        )

        assertTrue(
            "60 rows exceed one page; drawTable does not paginate today",
            engine.cursorY > engine.pageHeight,
        )
    }
}
