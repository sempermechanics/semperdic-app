package com.sempermechanics.semper.results

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.fixtures.Goldens
import com.sempermechanics.semper.ui.viewer.summary.SummaryAnimation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The summary GIF — what the viewer's summary slot shows and Share sends —
 * pinned byte for byte (`oracles/summary_u.gif`, `oracles/summary_exx.gif`).
 *
 * The whole path runs: `.dat` decode, the fit box, the reference-configuration
 * heatmap render to palette indices, the jet palette and the LZW encoder. The
 * other GIF tests check a round trip and that the encoder agrees with itself;
 * these fail on any change to the bytes a user receives, including one that
 * moves the encoder and its reader together.
 *
 * Robolectric for the jet palette, which is built from `Color.rgb`.
 */
@RunWith(RobolectricTestRunner::class)
class SummaryGifOracleTest {

    @get:Rule
    val temp = TemporaryFolder()

    private companion object {
        const val GRID = 12
        const val STEP = 4
        const val FRAMES = 3
    }

    /** A field that varies across the grid and frame to frame, and leaves a few points rejected. */
    private fun writeFrames(dir: File): List<File> = (0 until FRAMES).map { f ->
        val points = GRID * GRID
        val buffer = ByteBuffer.allocate(points * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        for (i in 0 until points) {
            val gx = i % GRID
            val gy = i / GRID
            buffer.putFloat(gx * STEP.toFloat())
            buffer.putFloat(gy * STEP.toFloat())
            buffer.putFloat(f * 0.5f + gx * 0.05f) // u
            buffer.putFloat(gy * 0.02f) // v
            buffer.putFloat((gx - GRID / 2) * 0.0002f * (f + 1)) // exx
            buffer.putFloat(0f).putFloat(0f)
            // Every 13th point over the ZNSSD gate, so rejection is in the picture too.
            buffer.putFloat(if (i % 13 == 0) 0.5f else 0.01f)
        }
        File(dir, "frame_%04d.dat".format(f)).apply { writeBytes(buffer.array()) }
    }

    private fun build(dataIndex: Int, label: String, bounds: Pair<Float, Float>): ByteArray {
        val anim = SummaryAnimation(
            SummaryAnimation.Spec(
                batchFiles = writeFrames(temp.newFolder()),
                imgW = GRID * STEP,
                imgH = GRID * STEP,
                stepAt = { STEP },
                outputDir = temp.newFolder(),
                backgroundColor = 0xFF101518.toInt(),
            ),
        )
        val file = runBlocking { anim.build(dataIndex, label, bounds) }
        assertNotNull("the $label animation was not built", file)
        return file!!.readBytes()
    }

    @Test
    fun `the U summary GIF matches its golden`() {
        Goldens.assertMatches("summary_u.gif", build(DicResult.IDX_U, "U", 0f to 2f))
    }

    @Test
    fun `the exx summary GIF matches its golden`() {
        // Bounds in the stored unit (strain), as the colour scale compares them.
        Goldens.assertMatches("summary_exx.gif", build(DicResult.IDX_EXX, "Exx", -0.0015f to 0.0015f))
    }
}
