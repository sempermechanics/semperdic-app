package com.sempermechanics.semper.util

import java.io.File
import java.io.InputStream
import java.util.zip.CRC32

/** Shared sizes for the chunked copy and checksum loops. */
object Streams {
    /** One chunk: 64 KiB, the buffer `SessionZip` and restore's unpack already use. */
    const val CHUNK_BYTES = 1 shl 16
}

/**
 * Reads this stream to its end, handing each chunk to [action] as
 * `(buffer, count)`; only `buffer[0 until count]` is valid, and the same
 * [buffer] is reused for the next read. Does not close the stream.
 *
 * Inline, so [action] may suspend (an `ensureActive()` per chunk) and may
 * write to an outer stream or update a checksum without allocating.
 *
 * Ends at end of stream (`read` returns -1). A zero-length read, which an
 * `InputStream` never returns for a non-empty buffer, is skipped rather than
 * taken as the end.
 *
 * @return the number of bytes read.
 */
inline fun InputStream.forEachChunk(
    buffer: ByteArray = ByteArray(Streams.CHUNK_BYTES),
    action: (buffer: ByteArray, count: Int) -> Unit,
): Long {
    require(buffer.isNotEmpty()) { "forEachChunk needs a non-empty buffer" }
    var total = 0L
    var n = read(buffer)
    while (n >= 0) {
        if (n > 0) {
            action(buffer, n)
            total += n
        }
        n = read(buffer)
    }
    return total
}

/** CRC-32 of this file's bytes, as `ZipEntry.crc` records it. */
fun File.crc32(buffer: ByteArray = ByteArray(Streams.CHUNK_BYTES)): Long {
    val crc = CRC32()
    inputStream().use { input -> input.forEachChunk(buffer) { b, n -> crc.update(b, 0, n) } }
    return crc.value
}
