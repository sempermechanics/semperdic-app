// Zip record layout: the literal field offsets and masks ARE the PKZIP format, and
// read clearest inline against the spec — same rationale as DicResult's on-disk
// layout. The early returns are the point too: every unrecognised shape must exit
// to "download the whole archive" rather than guess, so a low return count here
// would mean *less* safety, not more.
@file:Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")

package com.indicvision.semper.data.session

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

    /**
     * Parse the central directory out of [tail], the last bytes of an archive of
     * [fileSize] bytes starting at absolute offset [tailStart].
     *
     * Returns null when the directory is not fully inside [tail] (the caller should
     * retry with a larger tail), or when the archive uses anything this reader
     * deliberately does not handle.
     */
    fun parse(tail: ByteArray, tailStart: Long, fileSize: Long): List<Entry>? {
        val eocd = findEocd(tail) ?: return null
        val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)

        // Multi-disk archives are never produced here and would make offsets lie.
        if (buf.getShort(eocd + 4).toInt() != 0 || buf.getShort(eocd + 6).toInt() != 0) return null

        val count = buf.getShort(eocd + 10).toInt() and 0xFFFF
        val cdSize = buf.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = buf.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        // 0xFFFF/0xFFFFFFFF are the zip64 "look elsewhere" sentinels.
        if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return null
        if (cdOffset + cdSize > fileSize) return null
        if (cdOffset < tailStart) return null // directory not inside the tail we fetched

        var pos = (cdOffset - tailStart).toInt()
        val end = pos + cdSize.toInt()
        if (pos < 0 || end > tail.size) return null

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
            val name = String(tail, nameStart, nameLen, Charsets.UTF_8)
            entries.add(Entry(name, localOffset, crc))
            pos = nameStart + nameLen + extraLen + commentLen
        }
        return entries
    }

    /**
     * Byte offset marking the end of the entries whose name starts with any of
     * [keepPrefixes], or null when they are not a clean prefix of the archive.
     *
     * The cut is the *lowest* local-header offset among the entries being skipped —
     * i.e. exactly where the wanted data stops — so `[0, cut)` is a self-contained
     * run of complete local entries that `ZipInputStream` can walk.
     *
     * Null (fall back to a full download) when any wanted entry sits after any
     * skipped one, since a partial fetch would then miss data.
     */
    fun prefixCut(entries: List<Entry>, keepPrefixes: Set<String>, cdOffset: Long): Long? {
        if (entries.isEmpty()) return null
        val (keep, skip) = entries.partition { entry -> keepPrefixes.any { entry.name.startsWith(it) } }
        if (keep.isEmpty()) return null
        // Nothing to skip: the whole archive is already the restore payload.
        val cut = skip.minOfOrNull { it.localHeaderOffset } ?: cdOffset
        if (keep.any { it.localHeaderOffset >= cut }) return null
        return cut
    }

    /** Offset of the central directory, read from the same EOCD [parse] used. */
    fun centralDirectoryOffset(tail: ByteArray): Long? {
        val eocd = findEocd(tail) ?: return null
        val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val cdOffset = buf.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        return cdOffset.takeIf { it != 0xFFFFFFFFL }
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
