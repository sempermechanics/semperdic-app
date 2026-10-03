package com.sempermechanics.semper.analysis

import com.sempermechanics.semper.ui.analysis.recommend.DicGoodPractice
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DicGoodPracticeTest {

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
    fun `a subset spans three speckles, snapped odd`() {
        val subset = DicGoodPractice.subsetForSpeckle(9.0)!!
        assertEquals(1, subset % 2)
        assertTrue("subset $subset", subset >= 27)
    }

    @Test
    fun `a subset never leaves the range the engine accepts`() {
        assertEquals(SubsetRecommender.MIN_SUBSET, DicGoodPractice.subsetForSpeckle(1.0))
        val coarse = DicGoodPractice.subsetForSpeckle(30.0)
        assertNotNull(coarse)
        assertTrue("subset $coarse", coarse!! <= SubsetRecommender.MAX_SUBSET)
    }

    @Test
    fun `a speckle too coarse for any accepted subset yields none`() {
        // 3 x 50 px = 151, past MAX_SUBSET. Clamping to 121 would report a
        // size that spans barely two speckles as if it spanned three.
        assertNull(DicGoodPractice.subsetForSpeckle(50.0))
        assertNull(DicGoodPractice.subsetForSpeckle(500.0))
    }

    @Test
    fun `an unmeasurable speckle yields no subset`() {
        assertNull(DicGoodPractice.subsetForSpeckle(0.0))
        assertNull(DicGoodPractice.subsetForSpeckle(Double.NaN))
    }

    @Test
    fun `the recommended size sits inside the band it is recommended from`() {
        // Guards the constants against being edited out of order: a
        // "recommended" value outside its own band would make every measured
        // pattern look wrong.
        assertTrue(DicGoodPractice.RECOMMENDED_SPECKLE_PX > DicGoodPractice.MIN_SPECKLE_PX)
        assertTrue(DicGoodPractice.RECOMMENDED_SPECKLE_PX < DicGoodPractice.MAX_SPECKLE_PX)
        assertEquals(
            DicGoodPractice.Verdict.USABLE,
            DicGoodPractice.verdictFor(DicGoodPractice.RECOMMENDED_SPECKLE_PX),
        )
    }
}
