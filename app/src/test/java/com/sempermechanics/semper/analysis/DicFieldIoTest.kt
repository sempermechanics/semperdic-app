package com.sempermechanics.semper.analysis

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.ui.analysis.recommend.RunEstimate
import com.sempermechanics.semper.ui.analysis.run.DicFieldIo
import com.sempermechanics.semper.ui.analysis.run.baseName
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteOrder

/**
 * The buffer around the batch loop's JNI solve: sized for the ROI grid, guarded
 * against an engine that reports more points than it holds, and written out as
 * exactly the points the engine reported.
 */
class DicFieldIoTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the buffer holds maxPoints records in native order, direct for the JNI`() {
        val buffer = DicFieldIo.allocateDirect(144)
        assertTrue(buffer.isDirect)
        assertEquals(ByteOrder.nativeOrder(), buffer.order())
        assertEquals(144 * DicResult.BYTES_PER_POINT, buffer.capacity())
        assertEquals(144, DicFieldIo.capacityPoints(buffer))
    }

    @Test
    fun `an empty grid still gets one record, so the JNI never sees a zero-length buffer`() {
        assertEquals(1, DicFieldIo.capacityPoints(DicFieldIo.allocateDirect(0)))
        assertEquals(1, DicFieldIo.capacityPoints(DicFieldIo.allocateDirect(-3)))
    }

    @Test
    fun `wouldOverrun is false up to capacity and true one past it`() {
        val buffer = DicFieldIo.allocateDirect(10)
        assertFalse(DicFieldIo.wouldOverrun(0, buffer))
        assertFalse(DicFieldIo.wouldOverrun(10, buffer))
        assertTrue(DicFieldIo.wouldOverrun(11, buffer))
        assertTrue(DicFieldIo.wouldOverrun(Int.MAX_VALUE, buffer))
    }

    @Test
    fun `write saves exactly the valid points, from the start, wherever the buffer was left`() {
        val buffer = DicFieldIo.allocateDirect(4)
        val floats = buffer.asFloatBuffer()
        for (i in 0 until 4 * DicResult.STRIDE) floats.put(i.toFloat())
        // The batch loop reads the floats back after the write; a position
        // left anywhere must not shift what the next write saves.
        buffer.position(3 * DicResult.BYTES_PER_POINT)

        val target = temp.newFile("frame_0000.dat")
        DicFieldIo.write(buffer, 2, target)

        val written = target.readBytes()
        assertEquals(2 * DicResult.BYTES_PER_POINT, written.size)
        val decoded = DicResult.decodeDatFile(target)!!
        assertArrayEquals(FloatArray(2 * DicResult.STRIDE) { it.toFloat() }, decoded, 0f)
    }

    @Test
    fun `write of zero points leaves an empty file`() {
        val target = temp.newFile("empty.dat")
        target.writeBytes(byteArrayOf(1, 2, 3))
        DicFieldIo.write(DicFieldIo.allocateDirect(4), 0, target)
        assertEquals(0L, target.length())
    }

    @Test
    fun `the grid is counted in a Long, past where an Int wraps`() {
        assertEquals(12_000_000L, DicFieldIo.gridPoints(4000, 3000, 1))
        assertEquals(3_000_000L, DicFieldIo.gridPoints(4000, 3000, 2))
        // 50,000 x 50,000 = 2.5 billion: an Int product wraps to a negative count.
        assertEquals(2_500_000_000L, DicFieldIo.gridPoints(50_000, 50_000, 1))
        assertEquals(0L, DicFieldIo.gridPoints(4000, 3000, 0))
        assertEquals(Int.MAX_VALUE, RunEstimate.gridPoints(50_000, 50_000, 1))
    }

    @Test
    fun `a 4000 x 3000 ROI at step 1 is over the cap on a 512 MB heap`() {
        val heap = 512L * 1024 * 1024
        // Four copies of 32 bytes a point: the buffer, the write, the summary's floats and the rest.
        assertEquals(heap / (4 * DicResult.BYTES_PER_POINT), DicFieldIo.pointCap(heap))
        assertFalse(DicFieldIo.fits(DicFieldIo.gridPoints(4000, 3000, 1), heap))
        assertTrue(DicFieldIo.fits(DicFieldIo.gridPoints(4000, 3000, 2), heap))
        // However large the heap, one buffer holds no more than an Int of bytes.
        assertEquals(DicFieldIo.MAX_POINTS, DicFieldIo.pointCap(Long.MAX_VALUE))
    }

    @Test
    fun `a count whose bytes overflow an Int is refused, not wrapped to a small buffer`() {
        // 70 million x 32 bytes is 2.24 GB: as an Int it wrapped negative.
        assertThrows(IllegalArgumentException::class.java) { DicFieldIo.allocateDirect(70_000_000L) }
        assertThrows(IllegalArgumentException::class.java) { DicFieldIo.allocateDirect(DicFieldIo.MAX_POINTS + 1) }
    }

    @Test
    fun `baseName drops either kind of directory prefix`() {
        assertEquals("a.png", "/x/y/a.png".baseName())
        assertEquals("a.png", "C:\\x\\a.png".baseName())
        assertEquals("a.png", "a.png".baseName())
    }
}
