package com.sempermechanics.semper.analysis

import com.sempermechanics.semper.ui.analysis.wizard.deformedRangeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wizard's deformed card listed the staged cache copies
 * ("0000_IMG_1234.JPG") instead of the names the user picked.
 */
class DeformedRangeLabelTest {

    private val staged = listOf("/cache/import/0000_IMG_1234.JPG", "/cache/import/0001_IMG_1240.JPG")

    @Test
    fun `the range shows the user's own names`() {
        assertEquals(
            "IMG_1234.JPG … IMG_1240.JPG",
            deformedRangeLabel(staged, listOf("IMG_1234.JPG", "IMG_1240.JPG")),
        )
    }

    @Test
    fun `one frame shows just its name`() {
        assertEquals("IMG_1234.JPG", deformedRangeLabel(staged.take(1), listOf("IMG_1234.JPG")))
    }

    @Test
    fun `staged names stand in only where no original is known`() {
        assertEquals("0000_IMG_1234.JPG … 0001_IMG_1240.JPG", deformedRangeLabel(staged, emptyList()))
        assertEquals("IMG_1234.JPG … 0001_IMG_1240.JPG", deformedRangeLabel(staged, listOf("IMG_1234.JPG", "")))
    }

    @Test
    fun `no frames is an empty label`() {
        assertEquals("", deformedRangeLabel(emptyList(), emptyList()))
    }
}
