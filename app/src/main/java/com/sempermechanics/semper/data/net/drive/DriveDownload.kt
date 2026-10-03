package com.sempermechanics.semper.data.net.drive

import com.sempermechanics.semper.data.cloud.restore.RestoreDownloadOutcomes
import com.sempermechanics.semper.util.AtomicFiles
import com.sempermechanics.semper.util.Digests
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Hex characters of the fileId hash in [DriveDownload.label]. */
private const val LABEL_HEX_CHARS = 8

/** One [DriveTransfer.downloadFile] call: what it fetches, where the bytes go, and how far it got. */
internal class DriveDownload(
    val fileId: String,
    val dest: File,
    val baseUrl: String,
    val expectedBytes: Long,
    val rangeStart: Long,
    val onBytes: suspend (haveBytes: Long) -> Unit,
) {
    init {
        require(rangeStart >= 0L) { "rangeStart must not be negative" }
        require(rangeStart == 0L || expectedBytes > 0L) {
            "a windowed download must declare its length"
        }
    }

    val path = "/v1/files/$fileId/content"

    /**
     * How logs and messages name this download. Not [fileId]: that is
     * `{session}_{role}_{name}`, and a legacy backup's name is the user's own
     * image name. A short hash still tells two downloads apart.
     */
    val label: String = "file " + Digests.toHex(Digests.sha256(fileId.toByteArray())).take(LABEL_HEX_CHARS)

    val part: File = AtomicFiles.partOf(dest)
    var attempt = 0

    /** The object's size from a whole-object fetch's Content-Range, or -1. */
    var reportedTotal = -1L

    /**
     * Adapts toward the link's throughput after every completed window (see
     * [nextWindowBytes]). Kept across retries: a single transient failure
     * doesn't mean the link itself got slower.
     */
    var windowBytes = INITIAL_DOWNLOAD_WINDOW_BYTES

    fun haveBytes(): Long = if (part.exists()) part.length() else 0L

    fun isComplete(haveBytes: Long): Boolean =
        RestoreDownloadOutcomes.isComplete(haveBytes, expectedBytes, reportedTotal)

    /** Reports [haveBytes] and, when they are the whole target, promotes them. True when done. */
    suspend fun finishIfComplete(haveBytes: Long): Boolean {
        if (haveBytes > 0L) onBytes(haveBytes)
        if (!isComplete(haveBytes)) return false
        promoteToDest()
        onBytes(dest.length())
        return true
    }

    /** Renames the finished `.part` onto [dest]. */
    fun promoteToDest() {
        if (dest.exists() && !dest.delete()) {
            // Size, not the path: it names the user's files, and WARN reaches Crashlytics.
            Timber.w("Could not replace existing download target (%d B)", dest.length())
        }
        AtomicFiles.promote(part, dest)
    }

    /**
     * A proxy that ignores Range hands back the whole object. For a windowed
     * fetch that is still usable: slice the window out of [scratch] instead of
     * failing and retrying forever.
     */
    fun sliceToWindow(scratch: File) {
        val got = scratch.length()
        if (rangeStart == 0L && expectedBytes !in 1 until got) return
        if (got < rangeStart + expectedBytes) {
            scratch.delete()
            throw DownloadWindowException(
                "full-body download for $label is $got B, too short for window $rangeStart+$expectedBytes",
            )
        }
        sliceInPlace(scratch, rangeStart, expectedBytes)
    }

    /**
     * The 206's Content-Range, which must start where we asked: appending a
     * window that starts elsewhere would splice the wrong bytes into the file.
     */
    fun contentRangeAt(resp: Response, offset: Long): RestoreDownloadOutcomes.ContentRange {
        val range = RestoreDownloadOutcomes.parseContentRange(resp.header("Content-Range"))
            ?: throw DownloadWindowException("206 without Content-Range at offset $offset for $label")
        val remoteOffset = rangeStart + offset
        if (range.start != remoteOffset) {
            throw DownloadWindowException("Content-Range start ${range.start} != offset $remoteOffset for $label")
        }
        return range
    }

    /**
     * Appends the 206 body after [offset] bytes and returns how many it wrote.
     * A body that is not exactly [range] long is rewound to [offset] and thrown.
     */
    fun appendWindow(resp: Response, offset: Long, range: RestoreDownloadOutcomes.ContentRange): Long {
        FileOutputStream(part, true).use { out ->
            resp.body.byteStream().use { input -> input.copyTo(out, DOWNLOAD_COPY_BUFFER) }
        }
        val wrote = part.length() - offset
        val expectedWrote = range.end - range.start + 1
        if (wrote == expectedWrote) return wrote
        if (wrote > 0L) RandomAccessFile(part, "rw").use { it.setLength(offset) }
        throw DownloadWindowException(
            if (wrote <= 0L) {
                "empty 206 body at offset $offset for $label"
            } else {
                "short 206 for $label: wrote $wrote, Content-Range expected $expectedWrote"
            },
        )
    }
}

/**
 * Reduce [file] in place to the [length] bytes starting at [start] — the window a
 * Range-ignoring proxy forced us to download in full.
 */
private fun sliceInPlace(file: File, start: Long, length: Long) {
    RandomAccessFile(file, "rw").use { raf ->
        val buffer = ByteArray(DOWNLOAD_COPY_BUFFER)
        var read = start
        var write = 0L
        var remaining = length
        while (remaining > 0L) {
            raf.seek(read)
            val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n <= 0) break
            raf.seek(write)
            raf.write(buffer, 0, n)
            read += n
            write += n
            remaining -= n
        }
        raf.setLength(write)
    }
}
