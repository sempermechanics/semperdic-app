// Zip record layout: the literal field offsets and masks ARE the PKZIP format, and
// read clearest inline against the spec — same rationale as DicResult's on-disk
// layout. The early returns are the point too: every unrecognised shape must exit
// to "download the whole archive" rather than guess, so a low return count here
// would mean *less* safety, not more.
@file:Suppress("MagicNumber", "ReturnCount")

package com.sempermechanics.semper.data.session

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal read-only zip central-directory reader, used to fetch **part** of a
 * backup instead of all of it.
 *
 * Legacy backups (metadata schema < 3) put raw/, dat/, csv/, reports/ and
 * processed/ in one `Session.zip`, but a restore only needs raw/ + dat/. Those are
 * written first (see `DicUploadWorker`'s artifact order, preserved by
 * `SessionZip.build`), so they form a **contiguous prefix** and the rest can simply
 * not be downloaded.
 *
 * This parses only what that decision needs. Anything unusual — zip64, a multi-disk
 * archive, an interleaved layout — returns null so the caller falls back to
 * downloading the whole archive. Being wrong here would silently drop a frame, so
 * every branch fails toward "download everything".
 */
object ZipDirectory {

    /** One central-directory record: enough to locate and verify an entry. */
    data class Entry(
        val name: String,
        val localHeaderOffset: Long,
        val crc32: Long,
    )

    /** An archive's central directory: its records, and the absolute [offset] it starts at. */
    data class Directory(val entries: List<Entry>, val offset: Long) {

        /**
         * Byte offset marking the end of the entries whose name starts with any of
         * [keepPrefixes], or null when they are not a clean prefix of the archive.
         *
         * The cut is the *lowest* local-header offset among the entries being skipped —
         * i.e. exactly where the wanted data stops — so `[0, cut)` is a self-contained
         * run of complete local entries that `ZipInputStream` can walk. With nothing
         * to skip, the whole archive is the payload and the cut is the directory.
         *
         * Null (fall back to a full download) when any wanted entry sits after any
         * skipped one, since a partial fetch would then miss data.
         */
        fun prefixCut(keepPrefixes: Set<String>): Long? {
            val (keep, skip) = entries.partition { entry -> keepPrefixes.any { entry.name.startsWith(it) } }
            if (keep.isEmpty()) return null
            val cut = skip.minOfOrNull { it.localHeaderOffset } ?: offset
            return cut.takeIf { keep.none { it.localHeaderOffset >= cut } }
        }
    }

    /**
     * Parse the central directory out of [tail], the last bytes of an archive of
     * [fileSize] bytes starting at absolute offset [tailStart].
     *
     * Returns null when the directory is not fully inside [tail] (the caller should
     * retry with a larger tail), or when the archive uses anything this reader
     * deliberately does not handle.
     */
    fun parse(tail: ByteArray, tailStart: Long, fileSize: Long): Directory? {
        val eocd = readEocd(tail) ?: return null
        if (eocd.offset + eocd.size > fileSize) return null
        if (eocd.offset < tailStart) return null // directory not inside the tail we fetched

        val start = (eocd.offset - tailStart).toInt()
        val end = start + eocd.size.toInt()
        if (start < 0 || end > tail.size) return null
        val entries = readEntries(ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN), start, end, eocd.count)
        return entries?.let { Directory(it, eocd.offset) }
    }

    /** The [count] records between [start] and [end] of [buf], or null on anything unexpected. */
    private fun readEntries(buf: ByteBuffer, start: Int, end: Int, count: Int): List<Entry>? {
        var pos = start
        val entries = ArrayList<Entry>(count)
        repeat(count) {
            if (pos + CENTRAL_HEADER_BYTES > end) return null
            if (buf.getInt(pos) != CENTRAL_SIG) return null
            val crc = buf.getInt(pos + 16).toLong() and 0xFFFFFFFFL
            val nameLen = buf.getShort(pos + 28).toInt() and 0xFFFF
            val extraLen = buf.getShort(pos + 30).toInt() and 0xFFFF
            val commentLen = buf.getShort(pos + 32).toInt() and 0xFFFF
            val localOffset = buf.getInt(pos + 42).toLong() and 0xFFFFFFFFL
            if (localOffset == 0xFFFFFFFFL) return null // zip64
            val nameStart = pos + CENTRAL_HEADER_BYTES
            if (nameStart + nameLen > end) return null
            val name = String(buf.array(), nameStart, nameLen, Charsets.UTF_8)
            entries.add(Entry(name, localOffset, crc))
            pos = nameStart + nameLen + extraLen + commentLen
        }
        return entries
    }

    /** What the End Of Central Directory record says: how many records, how big, and where. */
    private data class Eocd(val count: Int, val size: Long, val offset: Long)

    /** The EOCD of [tail], or null when there is none or it describes a shape this reader does not handle. */
    private fun readEocd(tail: ByteArray): Eocd? {
        val at = findEocd(tail) ?: return null
        val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        // Multi-disk archives are never produced here and would make offsets lie.
        if (buf.getShort(at + 4).toInt() != 0 || buf.getShort(at + 6).toInt() != 0) return null
        val eocd = Eocd(
            count = buf.getShort(at + 10).toInt() and 0xFFFF,
            size = buf.getInt(at + 12).toLong() and 0xFFFFFFFFL,
            offset = buf.getInt(at + 16).toLong() and 0xFFFFFFFFL,
        )
        // 0xFFFF/0xFFFFFFFF are the zip64 "look elsewhere" sentinels.
        val zip64 = eocd.count == 0xFFFF || eocd.size == 0xFFFFFFFFL || eocd.offset == 0xFFFFFFFFL
        return eocd.takeUnless { zip64 }
    }

    /** Index of the End Of Central Directory record in [tail], scanning backwards. */
    private fun findEocd(tail: ByteArray): Int? {
        val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var i = tail.size - EOCD_MIN_BYTES
        while (i >= 0) {
            if (buf.getInt(i) == EOCD_SIG) {
                val commentLen = buf.getShort(i + 20).toInt() and 0xFFFF
                // The comment must run exactly to the end of the file, else this is
                // a false positive from payload bytes that happen to match.
                if (i + EOCD_MIN_BYTES + commentLen == tail.size) return i
            }
            i--
        }
        return null
    }

    private const val EOCD_SIG = 0x06054b50
    private const val CENTRAL_SIG = 0x02014b50
    private const val EOCD_MIN_BYTES = 22
    private const val CENTRAL_HEADER_BYTES = 46
}
