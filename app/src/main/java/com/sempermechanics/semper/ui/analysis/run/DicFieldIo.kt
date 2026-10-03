package com.sempermechanics.semper.ui.analysis.run

import com.sempermechanics.semper.field.DicResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct-buffer allocate, overrun guard, and `.dat` write shared by the batch
 * run loop and the VSG sweep. This is only the buffer around the solve: the
 * batch keeps its JNI `computeFullFieldDirect` call inline in its one loop
 * (`DicBatchRunner`), and the sweep solves through [SemperEngine.solve].
 */
internal object DicFieldIo {

    fun allocateDirect(maxPoints: Int): ByteBuffer =
        ByteBuffer
            .allocateDirect(maxOf(1, maxPoints) * DicResult.BYTES_PER_POINT)
            .order(ByteOrder.nativeOrder())

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
