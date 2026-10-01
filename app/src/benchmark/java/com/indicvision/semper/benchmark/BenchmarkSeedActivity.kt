@file:Suppress("MagicNumber")

package com.indicvision.semper.benchmark

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.report.FieldRangesStore
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.ui.viewer.ViewerArgs
import com.indicvision.semper.ui.viewer.summary.SummaryAnimation
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug-free, benchmark-variant-only launcher that fabricates a synthetic N-frame DIC
 * session and opens it in [com.indicvision.semper.ui.viewer.ResultViewerActivity], so
 * Macrobenchmark can profile the viewer without real credentials, images, or a native
 * engine run. Lives in `src/benchmark` ⇒ compiled **only** into the release-like
 * `benchmark` variant, never into `debug` or the shipped `release`.
 *
 * Extras: `frameCount` (default 150) and `pointsPerFrame` is fixed by the COLS×ROWS grid
 * below so the payload matches [HotPathMicroBenchmark]. Frames are cached on disk keyed
 * by frame count, so repeated benchmark iterations pay the fabrication cost once.
 */
class BenchmarkSeedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frameCount = intent.getIntExtra(EXTRA_FRAME_COUNT, DEFAULT_FRAMES)
        val dir = seedSession(frameCount)

        val names = ArrayList<String>(frameCount)
        for (i in 0 until frameCount) names.add("Frame_${i + 1}")

        val viewer = ViewerArgs.ofFrames(
            batchDir = dir.absolutePath,
            imgW = IMG_W,
            imgH = IMG_H,
            step = STEP,
            frameNames = names,
            startFrame = 0,
        ).toIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(viewer)
        finish()
    }

    /**
     * Writes `frame_%04d.dat` for [frameCount] frames into a cached session dir.
     *
     * The scratch `FloatArray`/`ByteBuffer` are allocated **once** and refilled per
     * frame. Allocating them per frame (~1.2 MB each) grew the Java heap by well over
     * 100 MB while seeding 150 frames, and since that happens inside the app process it
     * landed in `MemoryUsageMetric` — i.e. the harness would have been measuring its own
     * fabrication cost instead of the viewer's.
     *
     * It also writes the [FieldRangesStore] sidecar that `DicBatchRunner` writes for a
     * real batch, so the viewer's colour-scale pass reads it instead of decoding every
     * frame. Without it the benchmark measured only the no-sidecar fallback (older or
     * restored sessions), whose ~1 MB per frame of garbage set the 150-frame heap
     * (TD-87). The per-frame `valueRanges` columns are allocated here, in setup and on
     * the first iteration only.
     */
    private fun seedSession(frameCount: Int): File {
        val dir = File(File(filesDir, "sessions"), "bench_${frameCount}_${COLS}x$ROWS")
        dir.mkdirs()
        val expected = SessionPaths.frameDat(dir, frameCount - 1)
        val rangesFile = File(dir, FieldRangesStore.FILE_NAME)
        if (expected.exists() && rangesFile.exists()) return dir // already seeded
        val fieldIndices = SummaryAnimation.FIELDS.map { it.second }.toIntArray()
        val perFrameRanges = ArrayList<Map<Int, Pair<Float, Float>?>>(frameCount)
        val floats = FloatArray(COLS * ROWS * DicResult.STRIDE)
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frameCount) {
            fillFrame(floats, seed = i)
            buf.clear()
            buf.asFloatBuffer().put(floats)
            SessionPaths.frameDat(dir, i).writeBytes(buf.array())
            perFrameRanges.add(VisualizationEngine.valueRanges(floats, fieldIndices))
        }
        FieldRangesStore.write(rangesFile, fieldIndices, perFrameRanges)
        return dir
    }

    private fun fillFrame(floats: FloatArray, seed: Int) {
        var p = 0
        var k = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                floats[p + DicResult.IDX_X] = (c * STEP).toFloat()
                floats[p + DicResult.IDX_Y] = (r * STEP).toFloat()
                floats[p + DicResult.IDX_U] = (k - COLS * ROWS / 2) * 0.031f + seed
                floats[p + DicResult.IDX_V] = (COLS * ROWS / 2 - k) * 0.017f
                floats[p + DicResult.IDX_EXX] = (k % 9 - 4) * 0.00042f
                floats[p + DicResult.IDX_EYY] = (k % 6 - 3) * 0.00071f
                floats[p + DicResult.IDX_EXY] = (k % 4 - 2) * 0.00023f
                floats[p + DicResult.IDX_ZNSSD] = 0.01f
                p += DicResult.STRIDE
                k++
            }
        }
    }

    private companion object {
        const val EXTRA_FRAME_COUNT = "frameCount"
        const val DEFAULT_FRAMES = 150
        const val COLS = 160
        const val ROWS = 120
        const val STEP = 4
        const val IMG_W = COLS * STEP
        const val IMG_H = ROWS * STEP
    }
}
