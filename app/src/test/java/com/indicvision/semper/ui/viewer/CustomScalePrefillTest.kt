package com.indicvision.semper.ui.viewer

import com.indicvision.semper.field.ValueRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The custom colour-scale dialog opens on the scale the bar shows (WORKFLOWS 8.1.7). */
class CustomScalePrefillTest {

    @Test
    fun `with no custom scale it opens on the auto bounds the bar shows`() {
        assertEquals("-0.50000" to "2.25000", CustomScalePrefill.text(null, ValueRange(-0.5f, 2.25f), 1f))
    }

    @Test
    fun `strain bounds are shown in display units`() {
        // 0.0012 strain at the viewer's x1000 reads 1.2 mε, as on the bar.
        assertEquals("-0.40000" to "1.20000", CustomScalePrefill.text(null, ValueRange(-0.0004f, 0.0012f), 1000f))
    }

    @Test
    fun `a custom scale wins over the auto one`() {
        assertEquals(
            "0.00000" to "3.00000",
            CustomScalePrefill.text(ValueRange(0f, 3f), ValueRange(-0.5f, 2.25f), 1f),
        )
    }

    @Test
    fun `values the bar prints in scientific notation parse back`() {
        val (min, max) = CustomScalePrefill.text(null, ValueRange(1.5e-5f, 2.5e4f), 1f)!!
        assertEquals("1.50e-05" to "2.50e+04", min to max)
        assertEquals(1.5e-5f, min.toFloat(), 1e-7f)
        assertEquals(2.5e4f, max.toFloat(), 1f)
    }

    @Test
    fun `nothing on screen, or a flat field, leaves the fields empty`() {
        assertNull(CustomScalePrefill.text(null, null, 1f))
        assertNull(CustomScalePrefill.text(null, ValueRange(1f, 1f), 1f))
        assertNull(CustomScalePrefill.text(null, ValueRange(Float.NaN, 1f), 1f))
    }
}
