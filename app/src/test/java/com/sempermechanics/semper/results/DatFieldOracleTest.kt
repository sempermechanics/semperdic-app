package com.sempermechanics.semper.results

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.fixtures.Goldens
import com.sempermechanics.semper.ui.analysis.run.DicFieldIo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteOrder

/**
 * The `.dat` a batch run writes, pinned byte for byte (`oracles/field_small.dat`).
 *
 * A fixed field goes into the same direct buffer the JNI fills and out through
 * [DicFieldIo.write], as the batch loop does. The golden fixes the record layout
 * (`x y u v exx eyy exy znssd`, 32 bytes, little-endian) and that a frame holds
 * only its valid points — what the viewer, the exports, the cloud backup and
 * restore all read. The decode half checks the reader against the same bytes.
 */
class DatFieldOracleTest {

    @get:Rule
    val temp = TemporaryFolder()

    private companion object {
        const val GRID_W = 6
        const val GRID_H = 5
        const val STEP = 8

        /** Only these points are "valid"; the rest of the buffer is never written. */
        const val VALID = 27
    }

    /** A field with every component distinct and non-trivial, from arithmetic only. */
    private fun field(): FloatArray = FloatArray(GRID_W * GRID_H * DicResult.STRIDE).also { f ->
        for (i in 0 until GRID_W * GRID_H) {
            val o = i * DicResult.STRIDE
            val gx = i % GRID_W
            val gy = i / GRID_W
            f[o + DicResult.IDX_X] = (gx * STEP + 3).toFloat()
            f[o + DicResult.IDX_Y] = (gy * STEP + 5).toFloat()
            f[o + DicResult.IDX_U] = 1.25f + gx * 0.0625f - gy * 0.03125f
            f[o + DicResult.IDX_V] = -0.5f + gy * 0.1f
            f[o + DicResult.IDX_EXX] = 0.0001f * (gx - 2)
            f[o + DicResult.IDX_EYY] = -0.00025f * gy
            f[o + DicResult.IDX_EXY] = 0.00003f * (gx + gy)
            f[o + DicResult.IDX_ZNSSD] = 0.01f + 0.002f * (i % 7)
        }
    }

    @Test
    fun `a written frame matches its golden byte for byte and decodes to the same field`() {
        // The golden is little-endian; every Android ABI the app ships is too.
        assumeTrue(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
        val all = field()
        val buffer = DicFieldIo.allocateDirect(GRID_W * GRID_H)
        buffer.asFloatBuffer().put(all)

        val out = temp.newFile("frame_0000.dat")
        DicFieldIo.write(buffer, VALID, out)

        val bytes = out.readBytes()
        assertEquals(VALID * DicResult.BYTES_PER_POINT, bytes.size)
        Goldens.assertMatches("field_small.dat", bytes)
        assertArrayEquals(all.copyOf(VALID * DicResult.STRIDE), DicResult.decodeDatFile(out), 0f)
    }
}
