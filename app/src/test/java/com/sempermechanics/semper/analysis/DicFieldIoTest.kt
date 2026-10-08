package com.sempermechanics.semper.analysis

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.ui.analysis.run.DicFieldIo
import com.sempermechanics.semper.ui.analysis.run.baseName
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `baseName drops either kind of directory prefix`() {
        assertEquals("a.png", "/x/y/a.png".baseName())
        assertEquals("a.png", "C:\\x\\a.png".baseName())
        assertEquals("a.png", "a.png".baseName())
    }
}
