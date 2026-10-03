package com.sempermechanics.semper.results

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.TelemetrySummary
import com.sempermechanics.semper.report.ZnssdFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The all-frames PDF's telemetry page used to print the first readable frame's
 * average ZNSSD and convergence as "Global" / "Overall". The ZNSSD is now
 * pooled over every frame; the engine stats, which a session only keeps for
 * its first frame, are labelled as that frame's.
 */
class TelemetrySummaryTest {

    /** A field of [znssd] values, one point each; negative values are failed points. */
    private fun field(vararg znssd: Float): FloatArray {
        val data = FloatArray(znssd.size * DicResult.STRIDE)
        znssd.forEachIndexed { i, z -> data[i * DicResult.STRIDE + DicResult.IDX_ZNSSD] = z }
        return data
    }

    @Test
    fun `one frame's mean counts only accepted points`() {
        val frame = ZnssdFrame.of(field(0.02f, 0.04f, -1f))

        assertEquals(2, frame.points)
        assertEquals(0.03f, frame.mean, 1e-6f)
    }

    @Test
    fun `a frame with no accepted point has mean 0 over 0 points`() {
        assertEquals(ZnssdFrame(0f, 0), ZnssdFrame.of(field(-1f, -1f)))
    }

    @Test
    fun `the batch ZNSSD pools every frame's points, not the first frame's`() {
        // Frame 1: 1 point at 0.01. Frame 2: 3 points at 0.05. Pooled: 0.16 / 4.
        val summary = TelemetrySummary.batch(listOf(ZnssdFrame(0.01f, 1), ZnssdFrame(0.05f, 3)))

        assertEquals(0.04f, summary.avgZnssd, 1e-6f)
        assertEquals("Average ZNSSD (Correlation), all 2 frames", summary.avgZnssdLabel)
    }

    @Test
    fun `frames that kept no accepted point do not drag the pooled mean to 0`() {
        val summary = TelemetrySummary.batch(listOf(ZnssdFrame(0f, 0), ZnssdFrame(0.05f, 10)))

        assertEquals(0.05f, summary.avgZnssd, 1e-6f)
    }

    @Test
    fun `batch stats that are the first frame's say so`() {
        val summary = TelemetrySummary.batch(listOf(ZnssdFrame(0.01f, 1), ZnssdFrame(0.05f, 3)))

        assertEquals("Convergence Rate (first frame)", summary.convergenceLabel)
        val note = checkNotNull(summary.scopeNote)
        assertTrue(note, note.contains("first frame"))
        assertTrue(note, note.contains("2 frames"))
    }

    @Test
    fun `an empty batch reports 0 rather than dividing by zero`() {
        assertEquals(0f, TelemetrySummary.batch(emptyList()).avgZnssd, 0f)
    }
}
