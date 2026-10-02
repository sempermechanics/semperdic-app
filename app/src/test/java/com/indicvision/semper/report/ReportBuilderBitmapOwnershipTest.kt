package com.indicvision.semper.report

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.indicvision.semper.field.DicResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bitmaps [ReportBuilder.buildReport] makes are the caller's only once the
 * report comes back. A build that fails part-way recycles the fields it had
 * already baked rather than leaving them for the GC (each is a 600 px
 * RGB_565 bitmap, and a batch PDF builds one report per frame).
 */
@RunWith(RobolectricTestRunner::class)
class ReportBuilderBitmapOwnershipTest {

    private val step = 4
    private val cols = 12
    private val rows = 9

    private fun field(): FloatArray {
        val out = FloatArray(cols * rows * DicResult.STRIDE)
        var p = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val k = r * cols + c
                out[p + DicResult.IDX_X] = (c * step).toFloat()
                out[p + DicResult.IDX_Y] = (r * step).toFloat()
                out[p + DicResult.IDX_U] = k * 0.03f
                out[p + DicResult.IDX_V] = -k * 0.02f
                out[p + DicResult.IDX_EXX] = (k % 5 - 2) * 0.0004f
                out[p + DicResult.IDX_EYY] = (k % 7 - 3) * 0.0003f
                out[p + DicResult.IDX_EXY] = (k % 3 - 1) * 0.0002f
                out[p + DicResult.IDX_ZNSSD] = 0.01f
                p += DicResult.STRIDE
            }
        }
        return out
    }

    private fun params(): ReportBuilder.ReportBuildParams {
        val w = cols * step
        val h = rows * step
        return ReportBuilder.ReportBuildParams(
            data = field(),
            baseImg = createBitmap(w, h),
            defImgForCover = createBitmap(w, h),
            imgW = w,
            imgH = h,
            step = step,
            sessionId = "s",
            specimenName = "s",
            analysisDate = "2026-01-01 00:00:00",
            subsetSize = 41,
            strainWindow = 15,
            strainMethod = "VSG",
            roiData = RoiData(0, 0, w, h),
            engineStats = EngineStats.EMPTY,
            referenceImageName = "ref.png",
            deformedImageName = "def.png",
        )
    }

    private class LaterFieldFailed : RuntimeException()

    /** The images the caller passed in are the caller's: a failed build leaves them alone. */
    private fun assertCallerImagesKept(params: ReportBuilder.ReportBuildParams) {
        assertFalse("the reference passed in", params.baseImg.isRecycled)
        assertFalse("the cover's deformed image passed in", params.defImgForCover.isRecycled)
    }

    @Test
    fun `a field that fails recycles the fields baked before it`() {
        val made = mutableListOf<Bitmap>()
        val params = params()
        try {
            ReportBuilder.buildReport(params) { bitmap ->
                made += bitmap
                // The third field's render fails after U and V are baked.
                if (made.size == 3) throw LaterFieldFailed()
            }
            fail("the build should have thrown")
        } catch (_: LaterFieldFailed) {
            // expected
        }

        assertEquals(3, made.size)
        made.forEachIndexed { i, bitmap -> assertTrue("bitmap $i recycled", bitmap.isRecycled) }
        assertCallerImagesKept(params)
    }

    @Test
    fun `a cover that fails recycles every baked field`() {
        val made = mutableListOf<Bitmap>()
        val params = params()
        // The six fields, then the reference cover: fail on the deformed cover.
        val fieldsAndReference = 7
        try {
            ReportBuilder.buildReport(params) { bitmap ->
                made += bitmap
                if (made.size == fieldsAndReference + 1) throw LaterFieldFailed()
            }
            fail("the build should have thrown")
        } catch (_: LaterFieldFailed) {
            // expected
        }

        assertTrue(made.all { it.isRecycled })
        assertCallerImagesKept(params)
    }

    @Test
    fun `a report that is returned keeps every bitmap it holds`() {
        val made = mutableListOf<Bitmap>()

        val report = ReportBuilder.buildReport(params()) { made += it }

        assertFalse(made.any { it.isRecycled })
        val held = report.fieldResults.map { it.bakedHeatmap } +
            listOf(report.znssdHeatmap, report.referenceImage, report.deformedImage, report.solverPathMap)
        assertEquals(made.size, held.size)
        held.forEach { bitmap -> assertTrue(made.any { it === bitmap }) }
        assertSame(made[5], report.znssdHeatmap)
    }
}
