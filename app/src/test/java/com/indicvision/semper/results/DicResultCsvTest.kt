package com.indicvision.semper.results

import com.indicvision.semper.field.DicResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Checks the shared CSV point format ([DicResult.CsvPointFormatter]). */
class DicResultCsvTest {

    private fun point(): FloatArray = FloatArray(DicResult.STRIDE).apply {
        this[DicResult.IDX_X] = 550f
        this[DicResult.IDX_Y] = 88f
        this[DicResult.IDX_U] = 202.15619f
        this[DicResult.IDX_V] = -36.55538f
        this[DicResult.IDX_EXX] = 8.3724044e-5f
        this[DicResult.IDX_EYY] = 8.217384e-6f
        this[DicResult.IDX_EXY] = -1.0675939e-5f
        this[DicResult.IDX_ZNSSD] = 0.0663984f
    }

    private fun format(data: FloatArray): List<String> {
        val row = StringBuffer()
        DicResult.CsvPointFormatter().appendPoint(row, data, 0)
        return row.toString().split(",")
    }

    @Test
    fun `a point becomes eight comma-separated columns`() {
        val fields = format(point())
        assertEquals(DicResult.CSV_POINT_HEADER.split(",").size, fields.size)
        assertEquals(8, fields.size)
    }

    @Test
    fun `x and y are written as grid integers`() {
        val fields = format(point())
        assertEquals("550", fields[0])
        assertEquals("88", fields[1])
        assertFalse(fields[0].contains("."))
    }

    @Test
    fun `strains use scientific notation, displacements do not`() {
        val fields = format(point())
        assertTrue("exx should be scientific", fields[4].contains("E"))
        assertTrue("exy should be scientific", fields[6].contains("E"))
        assertFalse("u should be plain decimal", fields[2].contains("E"))
    }

    @Test
    fun `values round-trip to the input`() {
        val fields = format(point())
        assertEquals(202.15619, fields[2].toDouble(), 1e-3)
        assertEquals(-36.55538, fields[3].toDouble(), 1e-3)
        assertEquals(8.3724044e-5, fields[4].toDouble(), 1e-9)
        assertEquals(0.0663984, fields[7].toDouble(), 1e-5)
    }

    @Test
    fun `formatting is locale-independent`() {
        // Under a comma-decimal locale, a locale-sensitive formatter would put
        // commas inside the numbers and split them into extra fields. Getting
        // exactly eight columns proves Locale.US is honoured.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val fields = format(point())
            assertEquals(8, fields.size)
            assertEquals(202.15619, fields[2].toDouble(), 1e-3)
        } finally {
            Locale.setDefault(original)
        }
    }
}
