package com.sempermechanics.semper.data.cloud.restore

import com.sempermechanics.semper.data.cloud.CorruptTransferException
import com.sempermechanics.semper.util.Digests
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * The attestation a downloaded backup file must pass before anything reads it.
 * Each failure is a [CorruptTransferException]: the bytes are wrong, so the
 * workers give up rather than retry.
 */
internal object RestoreZipVerifier {

    /** ZIP local-file / empty-archive signature prefix (`PK`). */
    private val ZIP_MAGIC = "PK".toByteArray(Charsets.US_ASCII)

    private const val SHA256_HEX_CHARS = 64

    /** The declared sha256 in lowercase hex, or null when none (or a malformed one) was declared. */
    private fun expectedSha256(declared: String?): String? =
        declared?.lowercase()?.takeIf { it.length == SHA256_HEX_CHARS }

    /**
     * A downloaded `Session.zip`: its declared size (when known), its declared
     * sha256 (required), the zip magic, and with [requireEntries] at least one
     * readable entry. The first failure is thrown.
     */
    fun verifySessionZip(file: File, expectedSize: Long, sha256: String?, requireEntries: Boolean = false) {
        val err = checkZipSize(file, expectedSize)
            ?: checkZipSha256(file, sha256)
            ?: checkZipMagic(file)
            ?: if (requireEntries) checkZipEntries(file) else null
        if (err != null) throw err
    }

    /** `Extras.zip`'s declared sha256 is required; a mismatch reports the bundle's code, as it always has. */
    fun verifyExtrasZip(file: File, sha256: String?) {
        val expected = expectedSha256(sha256) ?: throw CorruptTransferException("extras_zip_sha256_missing")
        if (Digests.sha256Hex(file) != expected) throw CorruptTransferException("session_zip_sha256_mismatch")
    }

    /** `metadata.json`'s [bytes] against its sha256, checked only when one is declared. */
    fun verifyMetadata(bytes: ByteArray, sha256: String?) {
        val expected = expectedSha256(sha256) ?: return
        if (Digests.toHex(Digests.sha256(bytes)) != expected) {
            throw CorruptTransferException("metadata_sha256_mismatch")
        }
    }

    private fun checkZipSize(file: File, expectedSize: Long): CorruptTransferException? =
        if (expectedSize > 0L && file.length() != expectedSize) {
            CorruptTransferException("session_zip_size_mismatch")
        } else {
            null
        }

    private fun checkZipSha256(file: File, sha256: String?): CorruptTransferException? {
        val expected = expectedSha256(sha256)
        return when {
            expected == null -> CorruptTransferException("session_zip_sha256_missing")
            Digests.sha256Hex(file) != expected -> CorruptTransferException("session_zip_sha256_mismatch")
            else -> null
        }
    }

    private fun checkZipMagic(file: File): CorruptTransferException? {
        val magic = ByteArray(ZIP_MAGIC.size)
        val read = file.inputStream().use { it.read(magic) }
        return when {
            read < ZIP_MAGIC.size -> CorruptTransferException("session_zip_too_small")
            !magic.contentEquals(ZIP_MAGIC) -> CorruptTransferException("session_zip_bad_magic")
            else -> null
        }
    }

    private fun checkZipEntries(file: File): CorruptTransferException? = try {
        ZipFile(file).use { zf -> if (zf.size() <= 0) CorruptTransferException("session_zip_no_entries") else null }
    } catch (e: ZipException) {
        CorruptTransferException("session_zip_unreadable", e)
    }
}
