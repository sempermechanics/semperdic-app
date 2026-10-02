package com.indicvision.semper.ui.analysis.sweep

import com.indicvision.semper.field.DicResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The lattice's line-cut profiles are looked up by frame index (the node a
 * profile is drawn for). One unreadable `.dat` must not move every later
 * profile onto the node before it.
 */
class SweepFrameProfilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private companion object {
        const val GRID = 4
        const val STEP = 4
        val EXX = intArrayOf(DicResult.IDX_EXX)
        val LINE = VsgStudy.StudyLine(horizontal = true, position = STEP.toFloat())
    }

    /** A frame whose every point has Exx = (frame + 1) millistrain. */
    private fun frame(dir: File, index: Int): File {
        val points = GRID * GRID
        val buffer = ByteBuffer.allocate(points * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        for (i in 0 until points) {
            buffer.putFloat((i % GRID) * STEP.toFloat())
            buffer.putFloat((i / GRID) * STEP.toFloat())
            buffer.putFloat(0f).putFloat(0f)
            buffer.putFloat((index + 1) * 0.001f).putFloat(0f).putFloat(0f)
            buffer.putFloat(0.01f)
        }
        return File(dir, "frame_%04d.dat".format(index)).apply { writeBytes(buffer.array()) }
    }

    /** The one Exx value every point of a frame's profile carries. */
    private fun Map<Int, List<Pair<Float, Float>>>.exx(): Float =
        getValue(DicResult.IDX_EXX).map { it.second }.distinct().single()

    @Test
    fun `an unreadable frame leaves a gap instead of shifting later frames`() {
        val dir = temp.newFolder("sweep")
        val files = listOf(
            frame(dir, 0),
            File(dir, "frame_0001.dat").apply { writeBytes(ByteArray(7)) },
            frame(dir, 2),
        )

        val profiles = sweepFrameProfiles(files, listOf(STEP, STEP, STEP), STEP, EXX, LINE)

        assertEquals(listOf(0, 2), profiles.keys.toList())
        assertEquals(1f, profiles.getValue(0).exx(), 1e-4f)
        assertEquals("frame 2's own profile, on frame 2", 3f, profiles.getValue(2).exx(), 1e-4f)
    }

    @Test
    fun `a frame missing from disk keeps the frames after it on their own index`() {
        val dir = temp.newFolder("sweep")
        val files = listOf(frame(dir, 0), frame(dir, 2), frame(dir, 3))

        val profiles = sweepFrameProfiles(files, listOf(STEP, STEP, STEP, STEP), STEP, EXX, LINE)

        assertEquals(listOf(0, 2, 3), profiles.keys.toList())
        assertEquals(4f, profiles.getValue(3).exx(), 1e-4f)
    }

    @Test
    fun `each frame is cut with its own step`() {
        val dir = temp.newFolder("sweep")
        val files = listOf(frame(dir, 0), frame(dir, 1))
        // Frame 1's step is so small the half-step band misses the row at y = 4.
        val profiles = sweepFrameProfiles(files, listOf(STEP, 1), STEP, EXX, VsgStudy.StudyLine(true, 5.4f))

        assertEquals(GRID, profiles.getValue(0).getValue(DicResult.IDX_EXX).size)
        assertEquals(0, profiles.getValue(1).getValue(DicResult.IDX_EXX).size)
    }
}
