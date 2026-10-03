package com.sempermechanics.semper.analysis

import com.sempermechanics.semper.ui.analysis.recommend.DicGoodPractice
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Checks the SSSIG subset-size criterion of Pan et al., Opt. Express 16, 7037
 * (2008) as implemented by [SubsetRecommender.subsetSizeForPatch].
 */
class SubsetRecommenderTest {

    private val maxSize = 101
    private val side = maxSize + 2

    /** Speckle-like patch: uniform noise over the full 8-bit range. */
    private fun randomPatch(seed: Int, amplitude: Float): FloatArray {
        val rng = Random(seed)
        return FloatArray(side * side) { rng.nextFloat() * amplitude }
    }

    /** SSSIG in x over the (2*half+1) subset at the patch center. */
    private fun sssigX(patch: FloatArray, half: Int): Double {
        val c = side / 2
        var sum = 0.0
        for (dy in -half..half) {
            for (dx in -half..half) {
                val x = c + dx
                val y = c + dy
                val gx = (patch[y * side + x + 1] - patch[y * side + x - 1]) / 2.0
                sum += gx * gx
            }
        }
        return sum
    }

    @Test
    fun `threshold follows the paper's inversion of the SD-error model`() {
        // 4 / 0.007^2 — the paper rounds this to the 1e5 it uses in section 5.4.
        assertEquals(81632.65, SubsetRecommender.SSSIG_THRESHOLD, 1.0)
        // Round-trip: a subset sitting exactly on the threshold yields the
        // target SD error of Eqs. (18) and (19).
        assertEquals(
            SubsetRecommender.TARGET_SD_ERROR_PX,
            sqrt(SubsetRecommender.NOISE_VARIANCE / SubsetRecommender.SSSIG_THRESHOLD),
            1e-9,
        )
    }

    @Test
    fun `chosen size is the smallest one clearing the threshold`() {
        val patch = randomPatch(seed = 7, amplitude = 255f)
        val size = SubsetRecommender.subsetSizeForPatch(patch, side, minSize = 15, maxSize = maxSize)

        assertTrue("size must be odd", size % 2 == 1)
        assertTrue(size >= 15 && size <= maxSize)
        assertTrue(
            "chosen subset clears the threshold",
            sssigX(patch, size / 2) >= SubsetRecommender.SSSIG_THRESHOLD,
        )
        if (size > 15) {
            assertTrue(
                "one step smaller does not",
                sssigX(patch, size / 2 - 1) < SubsetRecommender.SSSIG_THRESHOLD,
            )
        }
    }

    @Test
    fun `low contrast needs a larger subset than high contrast`() {
        val strong = SubsetRecommender.subsetSizeForPatch(
            randomPatch(seed = 3, amplitude = 255f),
            side,
            15,
            maxSize,
        )
        val weak = SubsetRecommender.subsetSizeForPatch(
            randomPatch(seed = 3, amplitude = 40f),
            side,
            15,
            maxSize,
        )
        assertTrue("weak speckle ($weak) must not beat strong speckle ($strong)", weak > strong)
    }

    @Test
    fun `flat patch is capped at the largest allowed subset`() {
        val flat = FloatArray(side * side) { 128f }
        assertEquals(maxSize, SubsetRecommender.subsetSizeForPatch(flat, side, 15, maxSize))
    }

    @Test
    fun `minimum size is respected even when a tiny subset would qualify`() {
        val patch = randomPatch(seed = 11, amplitude = 255f)
        val size = SubsetRecommender.subsetSizeForPatch(patch, side, minSize = 41, maxSize = maxSize)
        assertTrue(size >= 41)
    }

    // ------------------------------------------------------------------
    // What the recommendation reports about the speckle itself
    // ------------------------------------------------------------------

    private fun result(subset: Int, speckle: Double?) = SubsetRecommender.Recommendation(
        subsetSize = subset,
        samples = 16,
        cappedSamples = 0,
        speckleDiameterPx = speckle,
    )

    @Test
    fun `an unmeasured speckle reports no verdict and asks for no subset`() {
        val rec = result(subset = 41, speckle = null)
        assertNull(rec.speckleVerdict)
        assertNull(rec.subsetSpanningSpeckles)
    }

    @Test
    fun `the verdict is the good-practice band applied to the measurement`() {
        assertEquals(DicGoodPractice.Verdict.UNDER_RESOLVED, result(41, 2.0).speckleVerdict)
        assertEquals(DicGoodPractice.Verdict.USABLE, result(41, 5.0).speckleVerdict)
        assertEquals(DicGoodPractice.Verdict.OVER_RESOLVED, result(41, 14.0).speckleVerdict)
    }

    @Test
    fun `the requirement is stated whether or not the recommendation meets it`() {
        // It is a property of the pattern, not of the SSSIG answer: the caller
        // compares it against the size the user actually has dialled in, which
        // the recommendation cannot know. So a fine speckle still reports one
        // even though 41 px comfortably clears it.
        val wanted = result(subset = 41, speckle = 5.0).subsetSpanningSpeckles
        assertNotNull(wanted)
        assertTrue("wanted $wanted", wanted!! < 41)
    }

    @Test
    fun `a coarse pattern asks for more subset than SSSIG did`() {
        // The case this cross-check exists for: SSSIG is a *sum* over the
        // subset, so a coarse, high-contrast pattern clears the threshold at a
        // size that spans barely one dot and correlates against the wrong dot
        // as readily as the right one.
        val rec = result(subset = 21, speckle = 20.0)
        val wanted = rec.subsetSpanningSpeckles
        assertNotNull(wanted)
        assertTrue("wanted $wanted", wanted!! > rec.subsetSize)
        assertTrue("wanted $wanted", wanted >= 20 * DicGoodPractice.MIN_SPECKLES_PER_SUBSET)
        assertEquals(1, wanted % 2)
    }

    @Test
    fun `the requirement never exceeds what the engine would accept`() {
        // A pattern too coarse for any allowed subset reports no requirement at
        // all. Clamping to MAX_SUBSET would name a size the user could reach
        // while it spans nowhere near three speckles — a chip telling them to
        // do something that would not fix the problem. The over-resolved size
        // chip is what speaks for a pattern this coarse.
        assertNull(result(subset = 41, speckle = 400.0).subsetSpanningSpeckles)
        assertTrue(
            "a reachable requirement should still be reported",
            result(subset = 21, speckle = 20.0).subsetSpanningSpeckles!! <= SubsetRecommender.MAX_SUBSET,
        )
    }
}
