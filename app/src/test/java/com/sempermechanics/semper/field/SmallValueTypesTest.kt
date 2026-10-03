package com.sempermechanics.semper.field

import android.graphics.Bitmap
import com.sempermechanics.semper.data.net.drive.DriveUpload
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.report.BakedHeatmap
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudy
import com.sempermechanics.semper.ui.analysis.sweep.toDicParams
import com.sempermechanics.semper.ui.analysis.sweep.toSkippedNode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The pair- and triple-shaped value types round-trip with the forms the code passes today. */
@RunWith(RobolectricTestRunner::class) // Bitmap
class SmallValueTypesTest {

    @Test
    fun `value range is a Pair's first and second`() {
        val pair = -1.5f to 2.25f
        assertEquals(ValueRange(-1.5f, 2.25f), ValueRange.of(pair))
        assertEquals(pair, ValueRange.of(pair).toPair())
    }

    @Test
    fun `custom range needs both bounds`() {
        assertEquals(ValueRange(1f, 2f), ValueRange.custom(1f, 2f))
        assertNull(ValueRange.custom(null, 2f))
        assertNull(ValueRange.custom(1f, null))
        assertNull(ValueRange.custom(null, null))
    }

    @Test
    fun `field stats use DicResult fieldStats' max-min-mean layout`() {
        // Two accepted points (ZNSSD 0.01) with u = 1 and u = 3.
        val data = floatArrayOf(
            0f, 0f, 1f, 0f, 0f, 0f, 0f, 0.01f,
            5f, 0f, 3f, 0f, 0f, 0f, 0f, 0.01f,
        )
        val raw = DicResult.fieldStats(data, DicResult.IDX_U)
        val stats = FieldStats.fromArray(raw)
        assertEquals(FieldStats(max = 3f, min = 1f, mean = 2f), stats)
        assertArrayEquals(raw, stats!!.toArray(), 0f)
        assertNull(FieldStats.fromArray(null))
        assertNull(FieldStats.fromArray(floatArrayOf(1f, 2f)))
    }

    @Test
    fun `baked heatmap is the engine's Triple, in the same order`() {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val triple = Triple(bmp, -0.5f, 0.75f)
        val baked = BakedHeatmap.of(triple)
        val (bitmap, min, max) = baked
        assertSame(bmp, bitmap)
        assertEquals(-0.5f, min, 0f)
        assertEquals(0.75f, max, 0f)
        assertEquals(ValueRange(-0.5f, 0.75f), baked.range)
        assertEquals(triple, baked.toTriple())
    }

    @Test
    fun `drive upload is uploadResumable's id and md5 pair`() {
        val pair = "drive-id" to "d41d8cd98f00b204e9800998ecf8427e"
        val upload = DriveUpload.of(pair)
        val (id, md5) = upload
        assertEquals("drive-id", id)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5)
        assertEquals(pair, upload.toPair())
    }

    @Test
    fun `a skipped node of a sweep point records its VSG in px, as the view model builds it`() {
        val point = VsgStudy.Point(subset = 41, step = 10, window = 5)
        val code = -2
        assertEquals(SkippedNode(point.subset, point.step, point.vsg, code), point.toSkippedNode(code))
        assertEquals(41, point.vsg)
        assertEquals(DicParams(41, 10, 41), point.toDicParams())
    }
}
