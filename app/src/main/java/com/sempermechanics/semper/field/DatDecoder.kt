// Binary record layout: byte sizes are the on-disk format and read clearest inline.

@file:Suppress("MagicNumber")

package com.sempermechanics.semper.field

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Reads `.dat` frames from disk for [DicResult.decodeDatFile] without holding a second
 * full-size [ByteArray]: memory-mapped, with a chunked-read fallback. [decodeInto] can
 * also refill a caller's array, for passes over a whole batch (TD-87).
 */
object DatDecoder {

    /** Read chunk for the fallback: a small scratch buffer, not a second full copy. */
    private const val DECODE_CHUNK_BYTES = DicResult.BYTES_PER_POINT * 1024

    /** A decoded frame: the first [floatCount] floats of [data]. */
    class DecodedDat(val data: FloatArray, val floatCount: Int)

    /**
     * Decodes [file] into [reuse] when it holds at least the file's floats, else into a
     * new array of exactly that size. Only the first [DecodedDat.floatCount] floats are
     * the frame; a reused array's tail keeps whatever an earlier, larger frame left.
     * Null for a file whose length is not whole points, or that cannot be read in full.
     */
    fun decodeInto(file: File, reuse: FloatArray?): DecodedDat? {
        val len = file.length()
        if (len <= 0L || len > Int.MAX_VALUE.toLong() || len % DicResult.BYTES_PER_POINT != 0L) return null
        val floatCount = (len / 4L).toInt()
        val out = reuse?.takeIf { it.size >= floatCount } ?: FloatArray(floatCount)
        val mapped = runCatching {
            FileInputStream(file).channel.use { channel ->
                val map = channel.map(FileChannel.MapMode.READ_ONLY, 0, len)
                map.order(ByteOrder.nativeOrder()).asFloatBuffer().get(out, 0, floatCount)
            }
            true
        }.getOrDefault(false)
        val decoded = if (mapped) out else decodeChunked(file, floatCount, out)
        return decoded?.let { DecodedDat(it, floatCount) }
    }

    /**
     * Chunked-read fallback for [decodeInto]: reads the file through a small heap
     * buffer into [out]. Kept for filesystems/sizes that cannot be memory-mapped.
     */
    private fun decodeChunked(file: File, floatCount: Int, out: FloatArray): FloatArray? {
        val complete = FileInputStream(file).channel.use { channel ->
            val buf = ByteBuffer.allocate(DECODE_CHUNK_BYTES).order(ByteOrder.nativeOrder())
            var written = 0
            var intact = true
            while (written < floatCount && intact) {
                buf.clear()
                val n = channel.read(buf)
                if (n <= 0 || n % 4 != 0) {
                    intact = false
                } else {
                    buf.flip()
                    val floats = n / 4
                    buf.asFloatBuffer().get(out, written, floats)
                    written += floats
                }
            }
            intact && written == floatCount
        }
        return if (complete) out else null
    }
}
