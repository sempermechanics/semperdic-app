package com.sempermechanics.semper.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.ProgressCallback
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.run.BatchRun
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.run.runBatchAnalysisBody
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.BatchProgressUpdate
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

/**
 * On-device / emulator smoke tests of the REAL native pipeline.
 *
 * These exercise what the host-side C++ suite cannot: `System.loadLibrary`
 * of the stripped `.so` (static OpenMP, static libc++, 16 KB page
 * alignment), the OpenMP + std::thread parallel solve on the Android
 * runtime, OpenCV's imgcodecs decode path, and the JNI marshalling of the
 * direct ByteBuffer + metrics array.
 *
 * A deterministic speckle reference is generated on-device; each test warps
 * it with a known [Matrix] (translation, rotation, skew) and/or degrades it
 * (contrast change, blur). Ground truth at every grid point is exactly
 * `M·p − p`, so the recovered displacement field is asserted point-wise
 * against prediction (median absolute error).
 */
@RunWith(AndroidJUnit4::class)
class EnginePipelineSmokeTest {

    private companion object {
        const val W = 320
        const val H = 320
        const val CX = W / 2f
        const val CY = H / 2f
        const val STEP = 5
        const val SUBSET = 31
    }

    // ── Synthetic imagery ────────────────────────────────────────────────

    /** Deterministic speckle pattern (seeded — identical on every run/device). */
    private fun makeReference(): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(128, 128, 128))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rng = Random(12345)
        repeat(1500) {
            val x = rng.nextFloat() * W
            val y = rng.nextFloat() * H
            val r = 1.5f + rng.nextFloat() * 2.5f
            val g = rng.nextInt(0, 256)
            paint.color = Color.rgb(g, g, g)
            canvas.drawCircle(x, y, r, paint)
        }
        return bmp
    }

    /** Deformed image: dest pixel q shows ref at M⁻¹q, so a feature at p moves to M·p. */
    private fun warp(src: Bitmap, m: Matrix, colorFilter: ColorMatrixColorFilter? = null): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(128, 128, 128))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (colorFilter != null) paint.colorFilter = colorFilter
        canvas.drawBitmap(src, m, paint)
        return bmp
    }

    /** Mild defocus: half-resolution round trip (bilinear both ways). */
    private fun soften(src: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(src, W / 2, H / 2, true)
        val back = Bitmap.createScaledBitmap(small, W, H, true)
        small.recycle()
        return back
    }

    private fun Bitmap.toPngBytes(): ByteArray = ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    // ── Solve + field assertion helpers ──────────────────────────────────

    private class SolveResult(val data: FloatArray, val metrics: FloatArray, val validPoints: Int, val maxPoints: Int)

    private fun solve(refBytes: ByteArray, defBytes: ByteArray): SolveResult {
        val dims = SemperNativeLib.getImageDimensions(refBytes)
        assertTrue("PNG decode failed in native imgcodecs", dims[0] == W && dims[1] == H)

        SemperNativeLib.initializeReference(refBytes, null, W, H)

        val maxPoints = (W / STEP) * (H / STEP)
        val buffer = ByteBuffer.allocateDirect(maxPoints * DicResult.BYTES_PER_POINT)
            .order(ByteOrder.nativeOrder())
        val metrics = FloatArray(17) { if (it == 16) -1f else 0f }
        val callback = object : ProgressCallback {
            override fun onProgressUpdate(percentage: Int) {}
        }

        // Full parallel pipeline: AKAZE seeding, OpenMP Hessian pre-pass,
        // Delaunay mesh solve, threaded RGDIC propagation.
        val validPoints = SemperNativeLib.computeFullFieldDirect(
            refBytes, defBytes, ByteArray(0),
            0, 0, W, H,
            STEP, SUBSET, 15,
            false,
            buffer, callback, metrics,
        )
        assertTrue("Engine returned error code $validPoints", validPoints > 0)

        val data = FloatArray(validPoints * DicResult.STRIDE)
        buffer.position(0)
        buffer.asFloatBuffer().get(data)
        return SolveResult(data, metrics, validPoints, maxPoints)
    }

    /**
     * Median |measured − predicted| over all accepted points, where the
     * prediction at ref point p is M·p − p.
     */
    private fun assertFieldMatchesWarp(res: SolveResult, m: Matrix, tolPx: Float, minCoverageFrac: Float) {
        assertTrue(
            "Too few solved points: ${res.validPoints}/${res.maxPoints}",
            res.validPoints > (res.maxPoints * minCoverageFrac).toInt(),
        )

        val errsU = ArrayList<Float>()
        val errsV = ArrayList<Float>()
        val pt = FloatArray(2)
        var i = 0
        while (i < res.data.size) {
            if (DicResult.isAcceptedPoint(res.data[i + DicResult.IDX_ZNSSD])) {
                val x = res.data[i + DicResult.IDX_X]
                val y = res.data[i + DicResult.IDX_Y]
                pt[0] = x
                pt[1] = y
                m.mapPoints(pt)
                errsU.add(abs(res.data[i + DicResult.IDX_U] - (pt[0] - x)))
                errsV.add(abs(res.data[i + DicResult.IDX_V] - (pt[1] - y)))
            }
            i += DicResult.STRIDE
        }
        assertTrue("No accepted points in output", errsU.isNotEmpty())

        errsU.sort()
        errsV.sort()
        val medU = errsU[errsU.size / 2]
        val medV = errsV[errsV.size / 2]
        assertTrue("Median |U error| = $medU px (tol $tolPx)", medU < tolPx)
        assertTrue("Median |V error| = $medV px (tol $tolPx)", medV < tolPx)
    }

    // ── Scenarios ─────────────────────────────────────────────────────────

    @Test
    fun translationIsRecovered() {
        val ref = makeReference()
        val m = Matrix().apply { setTranslate(3f, 2f) }
        val res = solve(ref.toPngBytes(), warp(ref, m).toPngBytes())

        assertFieldMatchesWarp(res, m, tolPx = 0.25f, minCoverageFrac = 0.25f)

        // Telemetry contract (checked once, on the cleanest scenario):
        // convergence % (slot 15) sane, seeding status (slot 16) written by
        // the engine (0/1/2, not the -1 preset).
        assertTrue("Convergence ${res.metrics[15]}%", res.metrics[15] > 50f)
        assertTrue("Mesh seeding status not exported", res.metrics[16] >= 0f)
    }

    @Test
    fun rotationIsRecovered() {
        // 1° about the image center: corner displacement ≈ 4 px, spatially
        // varying U/V — exercises the full 6-DOF shape function per subset.
        val ref = makeReference()
        val m = Matrix().apply { setRotate(1.0f, CX, CY) }
        val res = solve(ref.toPngBytes(), warp(ref, m).toPngBytes())
        assertFieldMatchesWarp(res, m, tolPx = 0.25f, minCoverageFrac = 0.25f)
    }

    @Test
    fun skewIsRecovered() {
        // Horizontal shear x' = x + 0.01·(y − cy): a pure uy gradient field.
        val ref = makeReference()
        val m = Matrix().apply { setSkew(0.01f, 0f, CX, CY) }
        val res = solve(ref.toPngBytes(), warp(ref, m).toPngBytes())
        assertFieldMatchesWarp(res, m, tolPx = 0.25f, minCoverageFrac = 0.25f)
    }

    @Test
    fun translationIsStillRecoveredAfterAContrastChange() {
        // Deformed image at 70% gain + 30 offset: ZNSSD normalization must
        // make the solve invariant to lighting drift between frames.
        val ref = makeReference()
        val m = Matrix().apply { setTranslate(3f, 2f) }
        val filter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.7f, 0f, 0f, 0f, 30f,
                    0f, 0.7f, 0f, 0f, 30f,
                    0f, 0f, 0.7f, 0f, 30f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        val res = solve(ref.toPngBytes(), warp(ref, m, filter).toPngBytes())
        assertFieldMatchesWarp(res, m, tolPx = 0.25f, minCoverageFrac = 0.25f)
    }

    @Test
    fun translationIsStillRecoveredFromABlurredDeformedFrame() {
        // Mild defocus on the deformed frame only (half-res round trip).
        // Blur flattens speckle gradients, so accept looser error and
        // sparser coverage — the engine must degrade gracefully, not fail.
        val ref = makeReference()
        val m = Matrix().apply { setTranslate(3f, 2f) }
        val res = solve(ref.toPngBytes(), soften(warp(ref, m)).toPngBytes())
        assertFieldMatchesWarp(res, m, tolPx = 0.4f, minCoverageFrac = 0.10f)
    }

    @Test
    fun repeatSolveIsBitIdentical() {
        // TD-65: the same inputs solved again on the same device must give the
        // same bytes. A rotation leaves the border cells outside the AKAZE
        // mesh to the threaded Path B flood fill, which used to pick each
        // cell's initial guess by thread timing.
        val ref = makeReference().toPngBytes()
        val def = warp(makeReference(), Matrix().apply { setRotate(1.0f, CX, CY) }).toPngBytes()
        val first = solve(ref, def)
        repeat(2) { run ->
            val again = solve(ref, def)
            assertTrue(
                "Run ${run + 1}: ${again.validPoints} points vs ${first.validPoints}",
                again.validPoints == first.validPoints,
            )
            assertTrue("Run ${run + 1}: packed field differs", again.data.contentEquals(first.data))
            // Slots 3 and 4: points solved by Path A and by Path B.
            assertTrue("Run ${run + 1}: path split differs", again.metrics[3] == first.metrics[3])
            assertTrue("Run ${run + 1}: path split differs", again.metrics[4] == first.metrics[4])
        }
    }

    // ── Contract characterization (pins behaviour before any refactor) ────────

    /** Bare compute call, returning the raw engine code (may be negative). */
    private fun compute(
        refBytes: ByteArray,
        defBytes: ByteArray,
        roiX: Int = 0,
        roiY: Int = 0,
        roiW: Int = W,
        roiH: Int = H,
        bufferPoints: Int = (W / STEP) * (H / STEP),
    ): Int {
        SemperNativeLib.initializeReference(refBytes, null, W, H)
        val buffer = ByteBuffer.allocateDirect(maxOf(1, bufferPoints) * DicResult.BYTES_PER_POINT)
            .order(ByteOrder.nativeOrder())
        val metrics = FloatArray(17) { if (it == 16) -1f else 0f }
        val cb = object : ProgressCallback {
            override fun onProgressUpdate(percentage: Int) {}
        }
        return SemperNativeLib.computeFullFieldDirect(
            refBytes, defBytes, ByteArray(0),
            roiX, roiY, roiW, roiH, STEP, SUBSET, 15, false, buffer, cb, metrics,
        )
    }

    @Test
    fun degenerateRoiReturnsRoiError() {
        // rectW < step ⇒ gridW == 0 ⇒ documented ROI error (-2), not a crash.
        val ref = makeReference().toPngBytes()
        val def = warp(makeReference(), Matrix().apply { setTranslate(3f, 2f) }).toPngBytes()
        assertTrue("expected -2 for degenerate ROI", compute(ref, def, roiW = STEP - 1, roiH = STEP - 1) == -2)
    }

    @Test
    fun emptyDeformedReturnsInitError() {
        // A def image the codec cannot decode ⇒ init/decode error (-3).
        val ref = makeReference().toPngBytes()
        assertTrue("expected -3 for undecodable deformed", compute(ref, ByteArray(0)) == -3)
    }

    @Test
    fun staleCancelDoesNotAbortNextSolve() {
        // 1.8: the solver clears the process-global cancel flag on entry, so a
        // cancel left set from a prior run must NOT abort a fresh solve.
        val ref = makeReference().toPngBytes()
        val def = warp(makeReference(), Matrix().apply { setTranslate(3f, 2f) }).toPngBytes()
        SemperNativeLib.setCancelRequested(true) // stale request, never cleared by us
        val n = compute(ref, def)
        SemperNativeLib.setCancelRequested(false)
        assertTrue("stale cancel aborted the solve (got $n)", n > 0)
    }

    @Test
    fun undersizedBufferTruncatesWithoutOverflow() {
        // 0.1: an output buffer far smaller than gridW*gridH must not overflow;
        // the solver caps the write and returns a bounded count (no crash).
        val ref = makeReference().toPngBytes()
        val def = warp(makeReference(), Matrix().apply { setTranslate(3f, 2f) }).toPngBytes()
        val n = compute(ref, def, bufferPoints = 4) // room for only 4 points
        assertTrue("expected a bounded, non-crashing count, got $n", n in 0..4)
    }

    // ── The batch loop itself ────────────────────────────────────────────

    /**
     * [runBatchAnalysisBody] end to end: the one loop that calls
     * `computeFullFieldDirect` per frame and writes the `.dat` files. The JVM
     * suite cannot load the engine, so this is the only test that runs it.
     * Three frames, each shifted further: every frame's file must exist and
     * hold its own translation, and the run must save its Home row.
     */
    @Test
    fun batchLoopWritesEveryFrameAndSavesTheSession() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val shifts = listOf(1f to 0f, 2f to 1f, 3f to 2f)
        val ref = makeReference()
        val inputs = File(ctx.cacheDir, "batch-loop-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val frames = shifts.mapIndexed { i, (dx, dy) ->
            val file = File(inputs, "def_$i.png")
            file.writeBytes(warp(ref, Matrix().apply { setTranslate(dx, dy) }).toPngBytes())
            DeformedFrame(file.absolutePath, file.name)
        }

        // A known session id: a re-run of a row costs no quota, so the run
        // never consults the account's session limit.
        val sessionId = "batchlooptest"
        SessionStore.forget(ctx, sessionId)
        val vm = AnalysisViewModel().apply {
            workingLocalId = sessionId
            refBytes = ref.toPngBytes()
            realRefWidth = W
            realRefHeight = H
            refName = "ref.png"
            deformedFrames = frames
        }
        val spec = RunSpec.of(DicParams(subset = SUBSET, step = STEP, strainWindow = 15), Roi(0, 0, W, H), null, false, null)

        try {
            val ticks = mutableListOf<BatchProgressUpdate>()
            val outcome = vm.runBatchAnalysisBody(ctx, BatchRun(spec, ctx.cacheDir, System.currentTimeMillis(), Job())) {
                ticks += it
            }

            assertEquals(RunStop.Finished, outcome.stop)
            assertEquals(shifts.size, outcome.totalFrames)
            assertTrue("the run's Home row was not saved", outcome.saved)
            assertEquals(shifts.size, SessionStore.get(ctx, sessionId)?.frameCount)

            val batchDir = File(outcome.batchDirPath)
            shifts.forEachIndexed { i, (dx, dy) ->
                val dat = SessionPaths.frameDat(batchDir, i)
                val decoded = DicResult.decodeDatFile(dat)
                assertNotNull("frame $i has no readable .dat", decoded)
                val data = decoded!!
                val points = data.size / DicResult.STRIDE
                assertFieldMatchesWarp(
                    SolveResult(data, FloatArray(0), points, (W / STEP) * (H / STEP)),
                    Matrix().apply { setTranslate(dx, dy) },
                    tolPx = 0.25f,
                    minCoverageFrac = 0.25f,
                )
            }

            // What the overlay draws: frame-by-frame ticks, then a completed tick
            // carrying every frame's convergence.
            val frameTicks = ticks.filter { it.frameIndex in shifts.indices }
            assertEquals(shifts.indices.toSet(), frameTicks.map { it.frameIndex }.toSet())
            assertTrue(frameTicks.all { it.plannedFrames == shifts.size && it.framePercent in 0f..100f })
            val done = ticks.last()
            assertEquals("the last tick is the completed one", shifts.size, done.frameIndex)
            assertNotNull("the completed tick carries the convergence", done.perFrameConvergence)
            val convergence = done.perFrameConvergence!!
            assertEquals(shifts.size, convergence.size)
            assertTrue("every solved frame has a convergence", convergence.none { it.isNaN() })
        } finally {
            SessionStore.forget(ctx, sessionId)
            SessionStore.dirFor(ctx, sessionId).deleteRecursively()
            inputs.deleteRecursively()
        }
    }
}
