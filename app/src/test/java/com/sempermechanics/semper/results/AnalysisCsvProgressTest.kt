package com.sempermechanics.semper.results

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.AnalysisCsvWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A frame's point rows report inside the frame every
 * [AnalysisCsvWriter.ROWS_PER_REPORT] rows, so a dense frame's seconds of
 * formatting move the export bar; the reports only watch, so the bytes match a
 * write without them.
 */
class AnalysisCsvProgressTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val metadata = AnalysisCsvWriter.Metadata(
        referenceName = "ref.png",
        strainMethod = "VSG",
        imgW = 640,
        imgH = 480,
        roiX = 0,
        roiY = 0,
        roiW = 640,
        roiH = 480,
    )

    private companion object {
        /** Every third point failed, so solved rows and scanned points differ. */
        const val FAILED_EVERY = 3
        const val DENSE_POINTS = 3 * AnalysisCsvWriter.ROWS_PER_REPORT + 500
    }

    private fun field(points: Int, u: Float): FloatArray {
        val data = FloatArray(points * DicResult.STRIDE)
        for (p in 0 until points) {
            val i = p * DicResult.STRIDE
            data[i + DicResult.IDX_X] = (p % 100) * 5f
            data[i + DicResult.IDX_Y] = (p / 100) * 5f
            data[i + DicResult.IDX_U] = u + p * 1e-4f
            data[i + DicResult.IDX_V] = -u
            data[i + DicResult.IDX_EXX] = 1e-6f * p
            data[i + DicResult.IDX_EYY] = 5e-4f
            data[i + DicResult.IDX_EXY] = 2e-4f
            data[i + DicResult.IDX_ZNSSD] = if (p % FAILED_EVERY == 0) -1f else 0.05f
        }
        return data
    }

    private fun solved(points: Int) = points - (points + FAILED_EVERY - 1) / FAILED_EVERY

    private fun frames(vararg points: Int): List<AnalysisCsvWriter.Frame> = points.mapIndexed { n, count ->
        val data = field(count, 0.5f * (n + 1))
        AnalysisCsvWriter.Frame(image = "frame_$n.png", subset = 41, step = 5, strainWindow = 15, data = { data })
    }

    @Test
    fun `a dense frame reports inside itself, rising and below one`() {
        val seen = mutableListOf<Pair<Int, Double>>()
        val frames = frames(DENSE_POINTS, DENSE_POINTS)
        AnalysisCsvWriter.write(temp.newFile("dense.csv"), sweep = false, frames, metadata) { done, fraction ->
            seen += done to fraction
        }

        val perFrame = solved(DENSE_POINTS) / AnalysisCsvWriter.ROWS_PER_REPORT
        assertTrue("too few rows for a report", perFrame >= 2)
        for (frame in frames.indices) {
            val fractions = seen.filter { it.first == frame }.map { it.second }
            assertEquals("frame $frame", perFrame, fractions.size)
            assertEquals(fractions.sorted(), fractions)
            assertEquals(fractions.size, fractions.toSet().size)
            assertTrue(fractions.toString(), fractions.all { it >= 0.0 && it < 1.0 })
        }
        // Frames in order: every report of frame 0 before any of frame 1.
        assertEquals(seen.map { it.first }.sorted(), seen.map { it.first })
    }

    @Test
    fun `a frame under the report interval reports nothing inside itself`() {
        var reports = 0
        val frames = frames(AnalysisCsvWriter.ROWS_PER_REPORT)
        AnalysisCsvWriter.write(temp.newFile("small.csv"), sweep = false, frames, metadata) { _, _ -> reports++ }
        assertEquals(0, reports)
    }

    @Test
    fun `the reports leave the bytes as they were`() {
        for (sweep in listOf(false, true)) {
            val plain = temp.newFile("plain_$sweep.csv")
            val watched = temp.newFile("watched_$sweep.csv")
            AnalysisCsvWriter.write(plain, sweep, frames(DENSE_POINTS, 10, DENSE_POINTS), metadata)
            var reports = 0
            AnalysisCsvWriter.write(
                watched,
                sweep,
                frames(DENSE_POINTS, 10, DENSE_POINTS),
                metadata,
                onFrame = { _, _ -> reports++ },
                onRows = { _, _ -> reports++ },
            )
            assertTrue(reports > 0)
            assertArrayEquals("sweep=$sweep", plain.readBytes(), watched.readBytes())
        }
    }

    @Test
    fun `the appender reports the same way and writes the same bytes`() {
        val frame = frames(DENSE_POINTS).single()
        val plain = temp.newFile("plain.csv")
        val watched = temp.newFile("watched.csv")
        AnalysisCsvWriter.open(plain, sweep = false, metadata).use { it.append(frame) }
        val fractions = mutableListOf<Double>()
        AnalysisCsvWriter.open(watched, sweep = false, metadata).use { it.append(frame) { f -> fractions += f } }

        assertEquals(solved(DENSE_POINTS) / AnalysisCsvWriter.ROWS_PER_REPORT, fractions.size)
        assertArrayEquals(plain.readBytes(), watched.readBytes())
    }
}
