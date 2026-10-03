package com.sempermechanics.semper.results

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.AnalysisCsvWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The CSV's two sections stay apart whichever order the appender is driven in
 * (TD-66): the upload bundle decodes each frame once and so interleaves
 * [AnalysisCsvWriter.Appender.appendFieldStats] with
 * [AnalysisCsvWriter.Appender.append], yet must produce the same file as the
 * share-sheet [AnalysisCsvWriter.write].
 */
class AnalysisCsvSectionsTest {

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

    private fun grid(u: Float, points: Int = 6): FloatArray {
        val data = FloatArray(points * DicResult.STRIDE)
        for (p in 0 until points) {
            val i = p * DicResult.STRIDE
            data[i + DicResult.IDX_X] = p * 10f
            data[i + DicResult.IDX_Y] = (p % 3) * 10f
            data[i + DicResult.IDX_U] = u + p * 0.01f
            data[i + DicResult.IDX_V] = -u
            data[i + DicResult.IDX_EXX] = 0.001f * p
            data[i + DicResult.IDX_EYY] = 0.0005f
            data[i + DicResult.IDX_EXY] = 0.0002f
            data[i + DicResult.IDX_ZNSSD] = 0.05f
        }
        return data
    }

    private fun frames(): List<AnalysisCsvWriter.Frame> = listOf(0.5f, 1.0f, 1.5f).mapIndexed { n, u ->
        val data = grid(u)
        AnalysisCsvWriter.Frame(image = "frame_$n.png", subset = 41, step = 5, strainWindow = 15, data = { data })
    }

    /** What the upload bundle does: stats then points, frame by frame. */
    private fun writeOnePass(out: File, sweep: Boolean, frames: List<AnalysisCsvWriter.Frame>) {
        AnalysisCsvWriter.open(out, sweep, metadata).use { appender ->
            frames.forEach { frame ->
                appender.appendFieldStats(frame, frame.data()!!)
                appender.append(frame)
            }
        }
    }

    @Test
    fun `one pass writes every stats row before the point section`() {
        val out = temp.newFile("upload.csv")
        writeOnePass(out, sweep = false, frames())

        val lines = out.readLines()
        val header = lines.indexOf(AnalysisCsvWriter.pointHeader(sweep = false))
        assertTrue("no point header", header > 0)
        assertEquals(1, lines.count { it.startsWith("image,") })
        assertEquals("", lines[header - 1])
        assertEquals(1, lines.count { it.isEmpty() })
        assertTrue(lines.subList(0, header - 1).all { it.startsWith("#") })
        assertTrue(lines.subList(header + 1, lines.size).none { it.startsWith("#") })
        assertEquals(15, lines.count { it.startsWith("# frame_") })
        assertEquals(18, lines.count { it.startsWith("frame_") })
    }

    @Test
    fun `one pass matches the share export byte for byte`() {
        for (sweep in listOf(false, true)) {
            val share = temp.newFile("share_$sweep.csv")
            val upload = temp.newFile("upload_$sweep.csv")
            AnalysisCsvWriter.write(share, sweep, frames(), metadata)
            writeOnePass(upload, sweep, frames())
            assertEquals("sweep=$sweep", share.readText(), upload.readText())
        }
    }

    @Test
    fun `the staging file is gone after close`() {
        val out = temp.newFile("upload.csv")
        writeOnePass(out, sweep = false, frames())
        assertEquals(listOf("upload.csv"), temp.root.list()!!.toList())
    }

    @Test
    fun `no frames still gives a point header`() {
        val share = temp.newFile("share.csv")
        val upload = temp.newFile("upload.csv")
        AnalysisCsvWriter.write(share, sweep = false, emptyList(), metadata)
        writeOnePass(upload, sweep = false, emptyList())
        assertEquals(share.readText(), upload.readText())
        assertTrue(upload.readLines().last().startsWith("image,"))
    }

    @Test
    fun `stats after the point section started are refused`() {
        val out = temp.newFile("late.csv")
        val frame = frames().first()
        AnalysisCsvWriter.open(out, sweep = false, metadata).use { appender ->
            appender.startPointSection()
            assertThrows(IllegalStateException::class.java) {
                appender.appendFieldStats(frame, frame.data()!!)
            }
        }
        assertFalse(out.readText().contains("# frame_0"))
    }
}
