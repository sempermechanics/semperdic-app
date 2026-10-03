package com.sempermechanics.semper.ui.analysis.run

import androidx.annotation.VisibleForTesting
import com.sempermechanics.semper.ProgressCallback
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.report.newMetrics
import java.nio.ByteBuffer

/**
 * One full-field solve for the single-shot callers: the VSG sweep's per-node
 * solve and the noise-floor probe. Both cleared the output buffer, passed a
 * silent progress callback and handed back the engine's point count, around
 * the same JNI call; this is that wrapper.
 *
 * Not for the batch run: `DicBatchRunner` keeps its
 * [SemperNativeLib.computeFullFieldDirect] call inline in its one loop (it
 * reports progress, and its buffer handling is pinned there).
 *
 * The reference must already be initialised ([SemperNativeLib.initializeReference]).
 * Runs on the caller's thread; the callers already run on the native one.
 */
internal object SemperEngine {

    /** The JNI signature, so a JVM test can stand in for the native library. */
    @Suppress("LongParameterList") // mirrors the JNI entry point
    fun interface FullFieldSolver {
        fun solve(
            refBytes: ByteArray,
            defBytes: ByteArray,
            maskData: ByteArray,
            roiX: Int,
            roiY: Int,
            roiW: Int,
            roiH: Int,
            step: Int,
            subset: Int,
            strainWindow: Int,
            use6x6Interpolator: Boolean,
            outputBuffer: ByteBuffer,
            callback: ProgressCallback,
            outMetrics: FloatArray,
        ): Int
    }

    /** A progress callback that ignores progress. */
    val SILENT: ProgressCallback = object : ProgressCallback {
        override fun onProgressUpdate(percentage: Int) = Unit
    }

    /** No mask: every pixel of the region is solved. */
    val NO_MASK = ByteArray(0)

    /**
     * The engine. A lambda, not a reference to [SemperNativeLib], so loading
     * this object does not load the native library.
     */
    @VisibleForTesting
    @Volatile
    var solver = FullFieldSolver { ref, def, mask, x, y, w, h, step, subset, window, use6x6, out, callback, metrics ->
        SemperNativeLib.computeFullFieldDirect(
            ref, def, mask,
            x, y, w, h,
            step, subset, window,
            use6x6,
            out, callback, metrics,
        )
    }

    /**
     * What one solve computes: the region, the grid and the solver options.
     *
     * Construct it with named arguments, always. The JNI takes `(step, subset,
     * strainWindow)`, and so does this constructor, but `NoiseFloorProbe`'s own
     * solve takes `(subset, step, strainWindow)`: a positional port of either
     * caller can swap two `Int`s without a compile error.
     */
    @Suppress("LongParameterList") // the solve's own inputs, named once here
    class Params(
        val roiX: Int,
        val roiY: Int,
        val roiW: Int,
        val roiH: Int,
        val step: Int,
        val subset: Int,
        val strainWindow: Int,
        val maskData: ByteArray = NO_MASK,
        val use6x6: Boolean = false,
    ) {
        override fun toString(): String =
            "Params(roi=$roiX,$roiY ${roiW}x$roiH, step=$step, subset=$subset, " +
                "strainWindow=$strainWindow, mask=${maskData.size}B, use6x6=$use6x6)"
    }

    /**
     * Solves [params]' region of [defBytes] against [refBytes] into [buffer],
     * which is cleared first. [metrics] receives the telemetry slots
     * ([EngineStats.fromArray]); the sweep passes [EngineStats.newMetrics],
     * the probe a plain zeroed array. Progress goes to [SILENT]: both
     * callers ignored it, and the batch, which reports it, is not a caller.
     *
     * @return the engine's result: points written, or a negative engine code.
     */
    fun solve(
        refBytes: ByteArray,
        defBytes: ByteArray,
        params: Params,
        buffer: ByteBuffer,
        metrics: FloatArray = EngineStats.newMetrics(),
    ): Int {
        buffer.clear()
        return solver.solve(
            refBytes, defBytes, params.maskData,
            params.roiX, params.roiY, params.roiW, params.roiH,
            params.step, params.subset, params.strainWindow,
            params.use6x6,
            buffer, SILENT, metrics,
        )
    }
}
