package com.indicvision.semper.util

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One zip entry per call, for the archive writers that spelled the
 * put / copy / close sequence out by hand (session export, share bundles,
 * `Session.zip`).
 *
 * None of these calls [ZipOutputStream.setLevel]: toggling the level inside
 * one archive corrupts it on Android's Deflater (see `SessionZip`), so the
 * level stays the caller's decision, made once.
 */
object Zips {

    /**
     * [file] as a compressed entry named [entryName], at the stream's current
     * method and level (a bare [ZipEntry], as the export writers used).
     * [onBytes] is called with each chunk's size as it is written.
     */
    fun putFile(
        zip: ZipOutputStream,
        entryName: String,
        file: File,
        onBytes: (Long) -> Unit = {},
    ) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { input ->
            input.forEachChunk { buffer, n ->
                zip.write(buffer, 0, n)
                onBytes(n.toLong())
            }
        }
        zip.closeEntry()
    }

    /**
     * [putFile], skipped when [file] is missing or not a regular file (the
     * session exporter's optional members). True when the entry was written.
     */
    fun putFileIfPresent(zip: ZipOutputStream, entryName: String, file: File): Boolean {
        if (!file.isFile) return false
        putFile(zip, entryName, file)
        return true
    }

    /**
     * [file] as a [ZipEntry.STORED] entry. STORED needs its size and CRC before
     * the first byte, so the file is read twice: a CRC pass, then the copy.
     * [onBytes] reports the copy only, not the CRC pass.
     *
     * @return the entry's CRC-32, for a round-trip check against the archive.
     */
    fun putStored(
        zip: ZipOutputStream,
        entryName: String,
        file: File,
        onBytes: (Long) -> Unit = {},
    ): Long {
        val buffer = ByteArray(Streams.CHUNK_BYTES)
        val crc = file.crc32(buffer)
        val size = file.length()
        zip.putNextEntry(storedEntry(entryName, size, crc))
        file.inputStream().use { input ->
            input.forEachChunk(buffer) { b, n ->
                zip.write(b, 0, n)
                onBytes(n.toLong())
            }
        }
        zip.closeEntry()
        return crc
    }

    /**
     * Bytes already in memory as a [ZipEntry.STORED] entry; [onBytes] is told
     * the whole size once, after the write.
     *
     * @return the entry's CRC-32.
     */
    fun putStoredBytes(
        zip: ZipOutputStream,
        entryName: String,
        bytes: ByteArray,
        onBytes: (Long) -> Unit = {},
    ): Long {
        val crc = CRC32().apply { update(bytes) }.value
        zip.putNextEntry(storedEntry(entryName, bytes.size.toLong(), crc))
        zip.write(bytes)
        zip.closeEntry()
        onBytes(bytes.size.toLong())
        return crc
    }

    private fun storedEntry(name: String, size: Long, crc: Long): ZipEntry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        this.size = size
        compressedSize = size
        this.crc = crc
    }
}
