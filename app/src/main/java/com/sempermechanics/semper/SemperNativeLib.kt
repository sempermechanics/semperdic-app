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

    external fun getPreviewFromBytes(imageBytes: ByteArray, maxDim: Int): android.graphics.Bitmap?

    external fun getImageDimensions(imageBytes: ByteArray): IntArray
}
