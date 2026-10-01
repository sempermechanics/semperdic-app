package com.indicvision.semper.analysis

import com.indicvision.semper.ui.analysis.recommend.NoiseFloorPixels
import com.indicvision.semper.ui.analysis.recommend.SubsetRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The two measurements that decide whether a phone's own `D(eta)` can be
 * believed, and what happens to the subset when it cannot.
 *
 * Both are guards against the same device finding: one phone reported a noise
 * variance an order of magnitude below the other's while its logs showed a
 * vendor denoiser running underneath a pipeline the app had frozen. The
 * correlation is what catches that; the clamp is what keeps it from costing a
 * run either way.
 */
class NoiseCorrelationTest {

    private fun zeros(n: Int) = FloatArray(n)

    private fun whiteNoise(edge: Int, seed: Int): FloatArray {
        val random = Random(seed)
        return FloatArray(edge * edge) { (random.nextDouble() - 0.5).toFloat() * 20f }
    }

    /** A 3-wide box filter along each row: what a spatial denoiser does to noise. */
    private fun smoothedNoise(edge: Int, seed: Int): FloatArray {
        val raw = whiteNoise(edge, seed)
        return FloatArray(raw.size) { i -> rowMean(raw, edge, i) }
    }

    /** Mean of index [i] and whichever of its two row neighbours exist. */
    private fun rowMean(raw: FloatArray, edge: Int, i: Int): Float {
        val col = i % edge
        val first = if (col == 0) i else i - 1
        val last = if (col == edge - 1) i else i + 1
        var sum = 0f
        for (j in first..last) sum += raw[j]
        return sum / (last - first + 1)
    }

    @Test
    fun `independent noise is uncorrelated between neighbours`() {
        val correlation = NoiseFloorPixels.noiseCorrelationOf(whiteNoise(64, 7), zeros(64 * 64))
        assertTrue("white noise read $correlation", kotlin.math.abs(correlation) < 0.15)
    }

    @Test
    fun `smoothed noise reads far above the denoise threshold`() {
        val correlation = NoiseFloorPixels.noiseCorrelationOf(smoothedNoise(64, 7), zeros(64 * 64))
        assertTrue("smoothed noise read $correlation", correlation > 0.5)
    }

    @Test
    fun `a window that is not square has no neighbours to compare`() {
        // 20 samples is not a square, so the row width is unknown and the
        // pixel to the right of the last in a row is not actually its neighbour.
        assertTrue(NoiseFloorPixels.noiseCorrelationOf(FloatArray(20), FloatArray(20)).isNaN())
    }

    @Test
    fun `a window below the minimum edge is refused`() {
        assertTrue(NoiseFloorPixels.noiseCorrelationOf(FloatArray(9), FloatArray(9)).isNaN())
    }

    @Test
    fun `identical frames have no difference to correlate`() {
        val frame = whiteNoise(32, 3)
        assertTrue(NoiseFloorPixels.noiseCorrelationOf(frame, frame.copyOf()).isNaN())
    }

    @Test
    fun `a missing window is not a measurement`() {
        assertTrue(NoiseFloorPixels.noiseCorrelationOf(null, FloatArray(16)).isNaN())
        assertTrue(NoiseFloorPixels.noiseCorrelationOf(FloatArray(16), null).isNaN())
        assertTrue(NoiseFloorPixels.noiseCorrelationOf(FloatArray(16), FloatArray(25)).isNaN())
    }

    @Test
    fun `noisier than the lab camera raises the subset threshold`() {
        val measured = SubsetRecommender.thresholdFor(36.0)
        assertTrue(measured > SubsetRecommender.SSSIG_THRESHOLD)
        assertEquals(
            36.0 / (SubsetRecommender.TARGET_SD_ERROR_PX * SubsetRecommender.TARGET_SD_ERROR_PX),
            measured,
            1.0,
        )
    }

    @Test
    fun `a phone reporting less noise than the paper never shrinks the subset`() {
        // The Pixel's 0.35 came from a denoiser, not a quieter sensor. Believing
        // it would recommend a subset smaller than the paper's own default.
        assertEquals(
            SubsetRecommender.SSSIG_THRESHOLD,
            SubsetRecommender.thresholdFor(0.35),
            1e-6,
        )
    }

    @Test
    fun `no measurement falls back to the paper's constant`() {
        assertEquals(SubsetRecommender.SSSIG_THRESHOLD, SubsetRecommender.thresholdFor(Double.NaN), 1e-6)
        assertEquals(SubsetRecommender.SSSIG_THRESHOLD, SubsetRecommender.thresholdFor(0.0), 1e-6)
        assertEquals(SubsetRecommender.SSSIG_THRESHOLD, SubsetRecommender.thresholdFor(-4.0), 1e-6)
    }
}
