package com.sempermechanics.semper.results

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.viewer.inspect.PointSpatialIndex
import com.sempermechanics.semper.ui.viewer.inspect.ViewerInspectHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * The viewer's deformed-frame render: each frame's map drawn where its points
 * moved to, so it lies on that frame's own photo. Robolectric for the Bitmap
 * half, pinned to 34 like the other Robolectric tests here.
 */
@RunWith(RobolectricTestRunner::class)
class DeformedHeatmapTest {

    private val step = 4
    private val cols = 12
    private val rows = 9
    private val w = 80
    private val h = 60

    /** A `step`-spaced grid starting at ([x0], [y0]), Exx rising left to right, every point moved by ([u], [v]). */
    private fun field(u: Float, v: Float, x0: Int = 16, y0: Int = 12): FloatArray {
        val out = FloatArray(cols * rows * DicResult.STRIDE)
        var p = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                out[p + DicResult.IDX_X] = (x0 + c * step).toFloat()
                out[p + DicResult.IDX_Y] = (y0 + r * step).toFloat()
                out[p + DicResult.IDX_U] = u
                out[p + DicResult.IDX_V] = v
                out[p + DicResult.IDX_EXX] = c.toFloat() / cols
                out[p + DicResult.IDX_ZNSSD] = 0.01f
                p += DicResult.STRIDE
            }
        }
        return out
    }

    private fun VisualizationEngine.IndexPlane.at(x: Int, y: Int): Int = indices[y * width + x].toInt() and 0xFF

    private fun reference(data: FloatArray) =
        VisualizationEngine.generateHeatmapIndices(data, w, h, DicResult.IDX_EXX, step)

    private fun deformed(data: FloatArray) =
        VisualizationEngine.generateDeformedHeatmapIndices(data, w, h, DicResult.IDX_EXX, step)

    @Test
    fun `with no displacement it is the reference render`() {
        val data = field(u = 0f, v = 0f)
        val ref = reference(data)
        val def = deformed(data)

        assertEquals(ref.min, def.min, 0f)
        assertEquals(ref.max, def.max, 0f)
        var compared = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = ref.at(x, y)
                if (r != VisualizationEngine.TRANSPARENT_INDEX) {
                    val d = def.at(x, y)
                    assertTrue("($x,$y) ref=$r def=$d", d != VisualizationEngine.TRANSPARENT_INDEX && abs(d - r) <= 1)
                    compared++
                }
            }
        }
        assertEquals("every reference cell pixel", (cols - 1) * step * (rows - 1) * step, compared)
    }

    @Test
    fun `a moved specimen is drawn where it moved to`() {
        val still = deformed(field(u = 0f, v = 0f))
        val moved = deformed(field(u = 6f, v = -3f))

        for (y in 0 until h) {
            for (x in 0 until w) {
                val s = still.at(x, y)
                if (s != VisualizationEngine.TRANSPARENT_INDEX) {
                    val m = moved.at(x + 6, y - 3)
                    assertTrue("($x,$y) still=$s moved=$m", abs(m - s) <= 1)
                }
            }
        }
        // And nothing is left behind where the specimen used to start.
        assertEquals(VisualizationEngine.TRANSPARENT_INDEX, moved.at(16, 20))
    }

    @Test
    fun `a stretched cell is filled across its full stretched width`() {
        // Each column moves right by c px: cells widen from 4 to 5 px.
        val data = field(u = 0f, v = 0f)
        for ((p, c) in (0 until cols * rows).map { it * DicResult.STRIDE to it % cols }) {
            data[p + DicResult.IDX_U] = c.toFloat()
        }
        val plane = deformed(data)
        val row = 12 + 2 * step
        val lastX = 16 + (cols - 1) * step + (cols - 1)
        for (x in 16..lastX) {
            assertTrue("x=$x", plane.at(x, row) != VisualizationEngine.TRANSPARENT_INDEX)
        }
        assertEquals(VisualizationEngine.TRANSPARENT_INDEX, plane.at(lastX + 1, row))
    }

    @Test
    fun `the bitmap is the index plane in viewer colours`() {
        val data = field(u = 2.5f, v = 1.5f)
        val plane = deformed(data)
        val (bitmap, min, max) = VisualizationEngine.generateDeformedHeatmap(data, w, h, DicResult.IDX_EXX, step)

        assertEquals(plane.min, min, 0f)
        assertEquals(plane.max, max, 0f)
        val palette = VisualizationEngine.gifPalette(background = 0x000000)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = plane.at(x, y)
                val argb = bitmap.getPixel(x, y)
                if (index == VisualizationEngine.TRANSPARENT_INDEX) {
                    assertEquals("($x,$y)", 0, argb)
                } else {
                    assertEquals("($x,$y)", palette[index] and 0xFFFFFF, argb and 0xFFFFFF)
                }
            }
        }
    }

    @Test
    fun `a display cap scales the moved positions with the image`() {
        val data = field(u = 8f, v = 4f)
        val plane = VisualizationEngine.generateDeformedHeatmapIndices(
            data,
            w,
            h,
            DicResult.IDX_EXX,
            step,
            maxLongEdge = w / 2,
        )
        assertEquals(w / 2, plane.width)
        assertEquals(h / 2, plane.height)
        // Grid starts at (16, 12), moved by (8, 4): (24, 16) in the frame, (12, 8) at half scale.
        assertTrue(plane.at(12, 8) != VisualizationEngine.TRANSPARENT_INDEX)
        assertEquals(VisualizationEngine.TRANSPARENT_INDEX, plane.at(11, 8))
    }

    @Test
    fun `nothing accepted draws nothing`() {
        val data = field(u = 1f, v = 1f)
        for (p in data.indices step DicResult.STRIDE) data[p + DicResult.IDX_ZNSSD] = -1f
        val plane = deformed(data)
        assertTrue(plane.indices.all { (it.toInt() and 0xFF) == VisualizationEngine.TRANSPARENT_INDEX })
    }

    @Test
    fun `displaced bounds box where the points moved to`() {
        val data = field(u = 5f, v = -2f)
        val right = 16f + (cols - 1) * step
        val bottom = 12f + (rows - 1) * step
        assertArrayEquals(floatArrayOf(16f, 12f, right, bottom), DicResult.acceptedPointsBounds(data), 0f)
        assertArrayEquals(
            floatArrayOf(21f, 10f, right + 5f, bottom - 2f),
            DicResult.acceptedPointsBounds(data, displaced = true),
            0f,
        )
    }

    @Test
    fun `a tap on the deformed frame finds the point that moved there`() {
        val data = field(u = 10f, v = 0f)
        val index = PointSpatialIndex.build(ViewerInspectHelper.displacedPositions(data), step)

        // The point that started at (20, 12) now sits at (30, 12).
        val found = index.nearest(30f, 12f, step * 1.5f)
        assertEquals(20f, data[found + DicResult.IDX_X], 0f)
        assertEquals(12f, data[found + DicResult.IDX_Y], 0f)
        // The copy leaves the frame data itself alone.
        assertEquals(16f, data[DicResult.IDX_X], 0f)
    }
}
