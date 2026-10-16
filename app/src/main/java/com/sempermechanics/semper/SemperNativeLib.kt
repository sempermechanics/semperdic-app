// JNI bridge: the external declarations mirror the native C++ engine signatures
// verbatim, so their parameter count and line length follow the engine, not this
// layer — hence LongParameterList is suppressed here.

@file:Suppress("LongParameterList")

package com.sempermechanics.semper

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

interface ProgressCallback {
    fun onProgressUpdate(percentage: Int)
}

object SemperNativeLib {
    init {
        System.loadLibrary("semper_core")
    }

    /**
     * NATIVE THREAD PINNING: A single persistent OS thread for ALL JNI/OpenMP calls.
     * OpenMP on Android is sensitive to being called from different threads, which
     * can cause internal assertion failures and SIGABRT.
     */
    val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Semper-NativeThread").also { it.isDaemon = true }
    }

    val nativeDispatcher: CoroutineDispatcher = nativeExecutor.asCoroutineDispatcher()

    // Call this ONCE before a batch starts to cache the reference image
    // This stops the engine from rebuilding it 50 times and crashing the memory!
    external fun initializeReference(
        refBytes: ByteArray,
        maskBytes: ByteArray?,
        width: Int,
        height: Int,
    )
    external fun setDebugOutputDir(debugDir: String?)

    /**
     * Stops the solve that is running now, rather than at the end of the frame.
     *
     * The engine polls this inside its point loops, so a cancel lands within a
     * point or two. Call with `false` before starting a run — the flag survives
     * the solve it stopped. Safe to call from any thread while a solve is in
     * flight; that is what it is for.
     */
    external fun setCancelRequested(cancel: Boolean)

    external fun computeFullFieldDirect(
        refBytes: ByteArray,
        defBytes: ByteArray,
        maskData: ByteArray,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        step: Int,
        subset: Int,
        strainWin: Int,
        // UI toggle selecting the Keys 6x6 interpolation kernel
        use6x6Interpolator: Boolean,
        outputBuffer: ByteBuffer,
        callback: ProgressCallback,
        // 17-slot engine telemetry (see EngineStats.fromArray)
        outMetrics: FloatArray,
    ): Int

    /**
     * Opens an incremental (updated-reference) session at the first image,
     * replacing the cached reference of [initializeReference]. The reference
     * moves to the latest solved frame every [updateInterval] frames; results
     * stay total displacement from the first image, on its grid. Returns 0 or
     * a negative engine code (-2 ROI, -3 init, -4 strain window too small).
     * Close it with [releaseIncremental].
     */
    external fun initializeIncremental(
        refBytes: ByteArray,
        maskBytes: ByteArray?,
        width: Int,
        height: Int,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        step: Int,
        subset: Int,
        strainWin: Int,
        use6x6Interpolator: Boolean,
        updateInterval: Int,
    ): Int

    /**
     * Solves the next frame of the session [initializeIncremental] opened.
     * Output, progress and metrics as [computeFullFieldDirect]; the metrics
     * describe the solve against the current reference. [outInfo] (length
     * [INC_INFO_LEN]) receives the frame's bookkeeping, indexed by `INC_INFO_*`.
     */
    external fun computeIncrementalFrame(
        defBytes: ByteArray,
        outputBuffer: ByteBuffer,
        callback: ProgressCallback,
        outMetrics: FloatArray,
        outInfo: IntArray,
    ): Int

    /** Closes the incremental session and frees its reference. */
    external fun releaseIncremental()

    /** [computeIncrementalFrame]'s `outInfo` slots. */
    const val INC_INFO_REFERENCE_FRAME = 0
    const val INC_INFO_REFERENCE_UPDATED = 1
    const val INC_INFO_TRACKED_POINTS = 2
    const val INC_INFO_FRAME0_POINTS = 3
    const val INC_INFO_TAYLOR_FALLBACKS = 4
    const val INC_INFO_LOST_THIS_FRAME = 5
    const val INC_INFO_LEN = 6

    external fun getPreviewFromBytes(imageBytes: ByteArray, maxDim: Int): android.graphics.Bitmap?

    external fun getImageDimensions(imageBytes: ByteArray): IntArray
}
