package com.sempermechanics.semper.util

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrandAssetsTest {

    @Test
    fun `pure black plate becomes transparent`() {
        val src = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        src.setPixel(0, 0, Color.BLACK)
        src.setPixel(1, 0, Color.rgb(0x17, 0x17, 0x19))
        val out = BrandAssets.punchBlackPlate(src)
        assertEquals(Color.TRANSPARENT, out.getPixel(0, 0))
        assertNotEquals(Color.TRANSPARENT, out.getPixel(1, 0))
    }

    @Test
    fun `cyan glyph is kept`() {
        val src = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val cyan = Color.rgb(0x0A, 0xA2, 0xA1)
        src.setPixel(0, 0, cyan)
        val out = BrandAssets.punchBlackPlate(src)
        assertEquals(cyan, out.getPixel(0, 0))
    }
}
