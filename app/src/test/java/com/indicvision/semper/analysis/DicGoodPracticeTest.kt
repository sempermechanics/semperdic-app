package com.indicvision.semper.analysis

import com.indicvision.semper.ui.analysis.DicGoodPractice
import com.indicvision.semper.ui.analysis.SubsetRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DicGoodPracticeTest {

    @Test
    fun `the useful band brackets the recommended resolution`() {
        // A speckle spanning 17 px on a 3072 px edge: the band is where that
        // dot lands between 3 and 9 px, and the recommendation puts it at 5.
        val band = DicGoodPractice.usefulLongEdges(diameterPx = 17.0, atLongEdge = 3072)!!
        assertTrue(band.minimum < band.recommended)
        assertTrue(band.recommended < band.maximum)
        assertEquals(3072 * 5.0 / 17.0, band.recommended.toDouble(), 1.0)
    }

    @Test
    fun `the band's own edges land on the band's own speckle sizes`() {
        // Round-trip: scale the speckle onto each recommended edge and it must
        // come back at the size that edge was solved for.
        val measured = 17.0
        val at = 3072
        val band = DicGoodPractice.usefulLongEdges(measured, at)!!
        assertEquals(
            DicGoodPractice.MIN_SPECKLE_PX,
            measured * band.minimum / at,
            0.05,
        )
        assertEquals(
            DicGoodPractice.MAX_SPECKLE_PX,
            measured * band.maximum / at,
            0.05,
        )
    }

    @Test
    fun `a fine speckle demands a bigger frame than a coarse one`() {
        val fine = DicGoodPractice.usefulLongEdges(diameterPx = 4.0, atLongEdge = 3000)!!
        val coarse = DicGoodPractice.usefulLongEdges(diameterPx = 20.0, atLongEdge = 3000)!!
        assertTrue("${fine.recommended} vs ${coarse.recommended}", fine.recommended > coarse.recommended)
    }

    @Test
    fun `a speckle below three pixels is under-resolved`() {
        assertEquals(DicGoodPractice.Verdict.UNDER_RESOLVED, DicGoodPractice.verdictFor(2.99))
    }

    @Test
    fun `exactly three pixels is usable, not a failure`() {
        // The boundary is inclusive on both sides: the guidance names 3 px as
        // the minimum, not as the first bad value.
        assertEquals(DicGoodPractice.Verdict.USABLE, DicGoodPractice.verdictFor(3.0))
        assertEquals(DicGoodPractice.Verdict.USABLE, DicGoodPractice.verdictFor(9.0))
    }

    @Test
    fun `a speckle above nine pixels is over-resolved, not wrong`() {
        // Over-resolved is a recommendation to record smaller and gain frame
        // rate, not a correlation problem. The distinction drives which of the
        // two messages the user is shown.
        assertEquals(DicGoodPractice.Verdict.OVER_RESOLVED, DicGoodPractice.verdictFor(9.01))
    }

    @Test
    fun `an over-resolved plan has a smaller resolution to recommend`() {
        // 24 px dots on a 4000 px edge: the recommendation must come back
        // *below* 4000, which is what buys the frame rate back.
        val band = DicGoodPractice.usefulLongEdges(diameterPx = 24.0, atLongEdge = 4000)!!
        assertTrue("recommended ${band.recommended}", band.recommended < 4000)
    }

    @Test
    fun `a subset spans three speckles, snapped odd`() {
        val subset = DicGoodPractice.subsetForSpeckle(9.0)!!
        assertEquals(1, subset % 2)
        assertTrue("subset $subset", subset >= 27)
    }

    @Test
    fun `a subset never leaves the range the engine accepts`() {
        assertEquals(SubsetRecommender.MIN_SUBSET, DicGoodPractice.subsetForSpeckle(1.0))
        assertEquals(SubsetRecommender.MAX_SUBSET, DicGoodPractice.subsetForSpeckle(500.0))
    }

    @Test
    fun `an unmeasurable speckle yields no band and no subset`() {
        assertNull(DicGoodPractice.usefulLongEdges(Double.NaN, 3000))
        assertNull(DicGoodPractice.usefulLongEdges(0.0, 3000))
        assertNull(DicGoodPractice.usefulLongEdges(5.0, 0))
        assertNull(DicGoodPractice.subsetForSpeckle(0.0))
    }

    @Test
    fun `the band converts to millimetres through a known scale`() {
        val (min, recommended, max) = DicGoodPractice.bandInMillimetres(mmPerPx = 0.1)
        assertEquals(0.3, min, 1e-9)
        assertEquals(0.5, recommended, 1e-9)
        assertEquals(0.9, max, 1e-9)
    }
}
