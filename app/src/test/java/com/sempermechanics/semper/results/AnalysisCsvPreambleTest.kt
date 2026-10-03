package com.sempermechanics.semper.results

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.AnalysisCsvWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The `#` preamble and point header of the analysis CSV.
 */
class AnalysisCsvPreambleTest {

    private fun translatedGrid(u: Float = 1.5f, v: Float = -2.25f): FloatArray {
        val data = FloatArray(10 * 10 * DicResult.STRIDE)
        var i = 0
        for (row in 0 until 10) {
            for (col in 0 until 10) {
                data[i + DicResult.IDX_X] = col * 20f
                data[i + DicResult.IDX_Y] = row * 20f
                data[i + DicResult.IDX_U] = u
                data[i + DicResult.IDX_V] = v
                data[i + DicResult.IDX_EXX] = 0.0001f
                data[i + DicResult.IDX_EYY] = 0.0001f
                data[i + DicResult.IDX_EXY] = 0.0001f
                data[i + DicResult.IDX_ZNSSD] = 0.05f
                i += DicResult.STRIDE
            }
        }
        return data
    }

    @Test
    fun `import csv opens with metadata field stats and base point header`() {
        val out = File.createTempFile("semper_csv", ".csv")
        out.deleteOnExit()
        val data = translatedGrid(u = 0.1f, v = 0.2f)
        val frames = listOf(
            AnalysisCsvWriter.Frame(
                image = "frame_a.jpg",
                subset = 41,
                step = 5,
                strainWindow = 15,
                data = { data },
            ),
        )
        val metadata = AnalysisCsvWriter.Metadata(
            referenceName = "ref.jpg",
            strainMethod = "VSG",
            imgW = 640,
            imgH = 480,
            roiX = 10,
            roiY = 20,
            roiW = 300,
            roiH = 200,
        )
        AnalysisCsvWriter.write(out, sweep = false, frames, metadata)
        val text = out.readText()
        assertTrue(text.contains("# semper_csv_version,1\n"))
        assertTrue(text.contains("# reference,ref.jpg\n"))
        assertTrue(text.contains("# roi_x,10\n"))
        assertTrue(!text.contains("noise_floor"))
        assertTrue(text.contains("# field_stats\n"))
        assertTrue(text.contains("# frame_a.jpg,41,5,15,U,"))
        assertTrue(
            text.contains(
                "\nimage,x_px,y_px,u_px,v_px,exx,eyy,exy,znssd," +
                    "shift_u_px,shift_v_px,shift_rot_deg\n",
            ),
        )
        assertTrue(text.contains("\nframe_a.jpg,0,0,0.1,0.2,"))
    }

    @Test
    fun `every csv carries the motion columns in the point header and rows`() {
        val out = File.createTempFile("semper_csv_rec", ".csv")
        out.deleteOnExit()
        val data = translatedGrid(u = 1.5f, v = -2.25f)
        val frames = listOf(
            AnalysisCsvWriter.Frame(
                image = "frame_a.jpg",
                subset = 41,
                step = 5,
                strainWindow = 15,
                data = { data },
            ),
        )
        val metadata = AnalysisCsvWriter.Metadata(
            referenceName = "ref.jpg",
            strainMethod = "VSG",
            imgW = 640,
            imgH = 480,
            roiX = 0,
            roiY = 0,
            roiW = 640,
            roiH = 480,
        )
        AnalysisCsvWriter.write(out, sweep = false, frames, metadata)
        val text = out.readText()
        assertTrue(
            text.contains(
                "\nimage,x_px,y_px,u_px,v_px,exx,eyy,exy,znssd," +
                    "shift_u_px,shift_v_px,shift_rot_deg\n",
            ),
        )
        assertTrue(text.contains("frame_a.jpg,0,0,1.5,-2.25,"))
        assertTrue(text.contains(",1.5000,-2.2500,"))
    }

    /**
     * Every data row has exactly as many fields as the header names.
     *
     * The `contains(…)` assertions above pin the text of the header and of
     * individual values, and would all still pass if the separator between the
     * point columns and the motion suffix were dropped or doubled — the one
     * mistake the suffix assembly can make. This counts instead, on a real
     * file, for both the single and the sweep header (whose prefix carries four
     * extra columns) and for the null-fit path (a frame whose data provider
     * returns an unsolvable field writes no rows, so the fit is exercised by
     * the solved frame beside it).
     */
    private fun assertFieldCountParity(sweep: Boolean) {
        val out = File.createTempFile("semper_csv_parity", ".csv")
        out.deleteOnExit()
        val data = translatedGrid()
        val frames = listOf(
            AnalysisCsvWriter.Frame(
                image = "frame_a.jpg",
                subset = 41,
                step = 5,
                strainWindow = 15,
                data = { data },
            ),
            AnalysisCsvWriter.Frame(
                image = "frame_b.jpg",
                subset = 41,
                step = 5,
                strainWindow = 15,
                data = { null },
            ),
        )
        val metadata = AnalysisCsvWriter.Metadata(
            referenceName = "ref.jpg",
            strainMethod = "VSG",
            imgW = 640,
            imgH = 480,
            roiX = 0,
            roiY = 0,
            roiW = 640,
            roiH = 480,
        )
        AnalysisCsvWriter.write(out, sweep, frames, metadata)

        val lines = out.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val header = lines.first()
        assertTrue("header should name the motion columns: $header", header.endsWith(",shift_rot_deg"))
        val expected = header.split(',').size
        val rows = lines.drop(1)
        assertTrue("no data rows were written", rows.isNotEmpty())
        rows.forEachIndexed { index, line ->
            assertEquals(
                "row $index has the wrong field count (sweep=$sweep): $line",
                expected,
                line.split(',').size,
            )
        }
    }

    @Test
    fun `every point row has as many fields as the single header`() {
        assertFieldCountParity(sweep = false)
    }

    @Test
    fun `every point row has as many fields as the sweep header`() {
        assertFieldCountParity(sweep = true)
    }
}
