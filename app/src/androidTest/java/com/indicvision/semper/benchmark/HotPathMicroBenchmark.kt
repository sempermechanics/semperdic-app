@file:Suppress("MagicNumber")

package com.indicvision.semper.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.GifEncoder
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.RoiData
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.ui.analysis.sweep.VsgStudy
import com.indicvision.semper.ui.viewer.inspect.PointSpatialIndex
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder

/**
 * Micro-benchmarks of the round-1/round-2 hot paths. Each `measureRepeated` block
 * reports **median timeNs and allocationCount** — allocationCount is the direct
 * evidence for the de-boxing work (a boxed `Float`/`Integer` per point/pixel is one
 * allocation; the primitive-array rewrites drop those to zero).
 *
 * This file uses **only public signatures that also exist on `main`** so the exact
 * same class can be built and run against both the pre-round-2 baseline and this
 * branch, giving an apples-to-apples diff. Run:
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -P android.testInstrumentationRunnerArguments.class=com.indicvision.semper.benchmark.HotPathMicroBenchmark \
 *   -P android.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,DEBUGGABLE,LOW-BATTERY,UNLOCKED
 * ```
 * Numbers from a debug build on an emulator are **relative**, not production absolutes.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class HotPathMicroBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    // ── One representative frame, and a 150-frame batch of them ──────────────

    private val oneFrame: FloatArray = syntheticFrame(seed = 1)
    private val batch150: List<FloatArray> = List(FRAMES_LARGE) { syntheticFrame(seed = it) }

    // ── Whole-frame value passes (summary pre-pass, A2) ──────────────────────

    @Test
    fun valueRanges_oneFrame() = benchmarkRule.measureRepeated {
        VisualizationEngine.valueRanges(oneFrame, FIELDS)
    }

    /** The summary's whole-batch range pre-pass over 150 frames — the A2 allocation story. */
    @Test
    fun valueRanges_150frames() = benchmarkRule.measureRepeated {
        for (f in batch150) VisualizationEngine.valueRanges(f, FIELDS)
    }

    // ── Report build (C2 collect-fusion + A4 raster; needs Bitmaps) ──────────

    @Test
    fun buildReport_oneFrame() {
        val base = createBitmap(IMG_W, IMG_H)
        val def = createBitmap(IMG_W, IMG_H)
        benchmarkRule.measureRepeated {
            ReportBuilder.buildReport(reportParams(oneFrame, base, def))
        }
    }

    @Test
    fun computeFieldExtrema_oneFrame() = benchmarkRule.measureRepeated {
        ReportBuilder.computeFieldExtrema(oneFrame, DicResult.IDX_EXX, absoluteStrainValues = false)
    }

    @Test
    fun generateHeatmap_oneFrame() = benchmarkRule.measureRepeated {
        val (bmp, _, _) = VisualizationEngine.generateHeatmap(
            oneFrame,
            IMG_W,
            IMG_H,
            DicResult.IDX_EXX,
            STEP,
            maxLongEdge = VisualizationEngine.DISPLAY_MAX_EDGE,
        )
        bmp.recycle()
    }

    // ── GIF LZW (C1: HashMap<Int,Int> per pixel → shared IntArray) ───────────

    @Test
    fun gifEncode_10frames() = encodeGif(FRAMES_SMALL)

    @Test
    fun gifEncode_150frames() = encodeGif(FRAMES_LARGE)

    private fun encodeGif(frameCount: Int) {
        val palette = VisualizationEngine.gifPalette(background = 0x000000)
        val indices = ByteArray(GIF_EDGE * GIF_EDGE) { ((it * 7) % 200).toByte() }
        benchmarkRule.measureRepeated {
            val out = ByteArrayOutputStream(frameCount * 512)
            GifEncoder(out, GIF_EDGE, GIF_EDGE, palette).use { gif ->
                repeat(frameCount) { gif.addFrame(indices, delayCentis = 5) }
            }
        }
    }

    // ── Spatial index build (round-1 de-box) ─────────────────────────────────

    @Test
    fun pointSpatialIndexBuild_oneFrame() = benchmarkRule.measureRepeated {
        PointSpatialIndex.build(oneFrame, STEP)
    }

    // ── Line-cut profile (control: unchanged single-component path) ──────────

    @Test
    fun profileAlong_threeComponents() {
        val line = VsgStudy.centreLine(0, 0, IMG_W, IMG_H, horizontal = true)
        benchmarkRule.measureRepeated {
            for (c in VsgStudy.STRAIN_COMPONENTS) {
                VsgStudy.profileAlong(oneFrame, c, line, STEP / 2f)
            }
        }
    }

    // ── Decode (round-1 mmap) ────────────────────────────────────────────────

    @Test
    fun decodeDatFile_oneFrame() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "microbench_frame.dat")
        file.writeBytes(frameBytes(oneFrame))
        benchmarkRule.measureRepeated {
            DicResult.decodeDatFile(file)
        }
    }

    // ── Synthetic data ───────────────────────────────────────────────────────

    private fun reportParams(data: FloatArray, base: android.graphics.Bitmap, def: android.graphics.Bitmap) =
        ReportBuilder.ReportBuildParams(
            data = data,
            baseImg = base,
            defImgForCover = def,
            imgW = IMG_W,
            imgH = IMG_H,
            step = STEP,
            sessionId = "bench",
            specimenName = "bench",
            analysisDate = "2026-01-01 00:00:00",
            subsetSize = 41,
            strainWindow = 15,
            strainMethod = "VSG",
            roiData = RoiData(0, 0, IMG_W, IMG_H),
            engineStats = EngineStats.fromArray(FloatArray(EngineStats.SLOT_COUNT)),
            referenceImageName = "ref.png",
            deformedImageName = "def.png",
        )

    private fun frameBytes(floats: FloatArray): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(floats)
        return buf.array()
    }

    companion object {
        private const val COLS = 160
        private const val ROWS = 120
        private const val STEP = 4
        private const val IMG_W = COLS * STEP
        private const val IMG_H = ROWS * STEP
        private const val GIF_EDGE = 96
        private const val FRAMES_SMALL = 10
        private const val FRAMES_LARGE = 150

        private val FIELDS = intArrayOf(
            DicResult.IDX_U,
            DicResult.IDX_V,
            DicResult.IDX_EXX,
            DicResult.IDX_EYY,
            DicResult.IDX_EXY,
        )

        /** A filled grid, all points accepted (ZNSSD in [0,0.15]); [seed] varies the values. */
        private fun syntheticFrame(seed: Int): FloatArray {
            val out = FloatArray(COLS * ROWS * DicResult.STRIDE)
            var p = 0
            var k = 0
            for (r in 0 until ROWS) {
                for (c in 0 until COLS) {
                    out[p + DicResult.IDX_X] = (c * STEP).toFloat()
                    out[p + DicResult.IDX_Y] = (r * STEP).toFloat()
                    out[p + DicResult.IDX_U] = (k - COLS * ROWS / 2) * 0.031f + seed
                    out[p + DicResult.IDX_V] = (COLS * ROWS / 2 - k) * 0.017f
                    out[p + DicResult.IDX_EXX] = (k % 9 - 4) * 0.00042f
                    out[p + DicResult.IDX_EYY] = (k % 6 - 3) * 0.00071f
                    out[p + DicResult.IDX_EXY] = (k % 4 - 2) * 0.00023f
                    out[p + DicResult.IDX_ZNSSD] = 0.01f
                    p += DicResult.STRIDE
                    k++
                }
            }
            return out
        }
    }
}
