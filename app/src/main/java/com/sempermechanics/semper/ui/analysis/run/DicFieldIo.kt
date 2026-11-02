package com.sempermechanics.semper.ui.analysis.run

import com.sempermechanics.semper.field.DicResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct-buffer allocate, overrun guard, and `.dat` write shared by the batch
 * run loop and the parameter sweep. This is only the buffer around the solve: the
 * batch keeps its JNI `computeFullFieldDirect` call inline in its one loop
 * (`BatchAnalysis`), and the sweep solves through [SemperEngine.solve].
 */
internal object DicFieldIo {

    /**
     * The most points one direct buffer can hold: its capacity is an `Int` of
     * bytes, [DicResult.BYTES_PER_POINT] a point (about 67 million points).
     */
    const val MAX_POINTS: Long = Int.MAX_VALUE.toLong() / DicResult.BYTES_PER_POINT

    /**
     * How many copies of a run's grid, at [DicResult.BYTES_PER_POINT] a point,
     * the heap has to hold: the direct buffer (`ByteBuffer.allocateDirect`
     * lives in the Java heap on Android), [write]'s byte array, the batch's
     * float array for the summary ranges, and one share for everything else
     * (the reference and the frame being solved).
     */
    const val HEAP_SHARES = 4

    /** Points the engine solves over a [roiW] × [roiH] region at [step], as a `Long`: (w / step) × (h / step). */
    fun gridPoints(roiW: Int, roiH: Int, step: Int): Long =
        if (step <= 0 || roiW <= 0 || roiH <= 0) 0L else (roiW / step).toLong() * (roiH / step)

    /**
     * The most grid points a run may solve on a heap of [maxHeapBytes]
     * (`Runtime.maxMemory`): [HEAP_SHARES] copies of them must fit, and one
     * direct buffer must hold them ([MAX_POINTS]).
     */
    fun pointCap(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Long =
        minOf(maxHeapBytes / (HEAP_SHARES.toLong() * DicResult.BYTES_PER_POINT), MAX_POINTS)

    /** Whether a grid of [points] fits [pointCap] of [maxHeapBytes]. */
    fun fits(points: Long, maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Boolean =
        points <= pointCap(maxHeapBytes)

    /**
     * A buffer for [maxPoints] points, at least one. Refuses a count whose
     * byte size would not fit a buffer's `Int` capacity ([MAX_POINTS]) rather
     * than let the multiply wrap to a small buffer.
     */
    fun allocateDirect(maxPoints: Long): ByteBuffer {
        require(maxPoints <= MAX_POINTS) { "A grid of $maxPoints points does not fit one buffer" }
        return ByteBuffer
            .allocateDirect(maxOf(1L, maxPoints).toInt() * DicResult.BYTES_PER_POINT)
            .order(ByteOrder.nativeOrder())
    }

    fun capacityPoints(buffer: ByteBuffer): Int =
        buffer.capacity() / DicResult.BYTES_PER_POINT

    /** True when writing [solved] points would read past [buffer]. */
    fun wouldOverrun(solved: Int, buffer: ByteBuffer): Boolean =
        solved > capacityPoints(buffer)

    fun write(buffer: ByteBuffer, validPoints: Int, target: File) {
        val bytes = ByteArray(validPoints * DicResult.BYTES_PER_POINT)
        buffer.position(0)
        buffer.get(bytes, 0, bytes.size)
        target.outputStream().use { it.write(bytes) }
    }
}

/** Filename without any directory prefix, handling both '/' and '\' separators. */
internal fun String.baseName(): String = substringAfterLast('/').substringAfterLast('\\')
