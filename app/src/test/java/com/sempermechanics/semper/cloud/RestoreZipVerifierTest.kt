package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.cloud.CorruptTransferException
import com.sempermechanics.semper.data.cloud.restore.RestoreZipVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The attestation every downloaded backup file passes before anything reads
 * it. Each failure carries its own code (the restore reports it, and the
 * workers give up rather than retry), so each is pinned by that code.
 */
class RestoreZipVerifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val validZip: ByteArray = FakeRestoreApi.zipOf(listOf("raw/Reference.png" to byteArrayOf(1, 2, 3)))

    private fun write(bytes: ByteArray): File = temp.newFile().apply { writeBytes(bytes) }

    private fun verifyRequiringEntries(bytes: ByteArray) =
        RestoreZipVerifier.verifySessionZip(write(bytes), -1L, FakeRestoreApi.sha256Of(bytes), requireEntries = true)

    private fun code(block: () -> Unit): String = assertThrows(CorruptTransferException::class.java, block).message!!

    @Test
    fun `a whole, declared, readable Session zip passes`() {
        val file = write(validZip)
        RestoreZipVerifier.verifySessionZip(
            file,
            validZip.size.toLong(),
            FakeRestoreApi.sha256Of(validZip),
            requireEntries = true,
        )
    }

    @Test
    fun `the declared sha256 is matched without regard to case`() {
        val file = write(validZip)
        RestoreZipVerifier.verifySessionZip(file, -1L, FakeRestoreApi.sha256Of(validZip).uppercase())
    }

    @Test
    fun `a size other than the declared one fails first`() {
        val file = write(validZip)
        assertEquals(
            "session_zip_size_mismatch",
            code { RestoreZipVerifier.verifySessionZip(file, validZip.size + 1L, FakeRestoreApi.sha256Of(validZip)) },
        )
    }

    @Test
    fun `no declared sha256, or a malformed one, is refused`() {
        val file = write(validZip)
        assertEquals("session_zip_sha256_missing", code { RestoreZipVerifier.verifySessionZip(file, -1L, null) })
        assertEquals("session_zip_sha256_missing", code { RestoreZipVerifier.verifySessionZip(file, -1L, "abc123") })
    }

    @Test
    fun `bytes that do not hash to the declared sha256 are refused`() {
        val file = write(validZip)
        val other = FakeRestoreApi.sha256Of("something else".toByteArray())
        assertEquals("session_zip_sha256_mismatch", code { RestoreZipVerifier.verifySessionZip(file, -1L, other) })
    }

    @Test
    fun `a file that is not a zip, or too short to tell, is refused by its magic`() {
        val html = "<html>quota</html>".toByteArray()
        assertEquals(
            "session_zip_bad_magic",
            code { RestoreZipVerifier.verifySessionZip(write(html), -1L, FakeRestoreApi.sha256Of(html)) },
        )
        val one = byteArrayOf('P'.code.toByte())
        assertEquals(
            "session_zip_too_small",
            code { RestoreZipVerifier.verifySessionZip(write(one), -1L, FakeRestoreApi.sha256Of(one)) },
        )
    }

    @Test
    fun `when entries are required, an empty or unreadable archive is refused`() {
        val empty = FakeRestoreApi.zipOf(emptyList())
        assertEquals(
            "session_zip_no_entries",
            code { verifyRequiringEntries(empty) },
        )
        // Right magic, nothing a zip reader can open.
        val torn = "PK".toByteArray() + ByteArray(64) { 7 }
        assertEquals(
            "session_zip_unreadable",
            code { verifyRequiringEntries(torn) },
        )
        // Without the requirement the magic is the last check.
        RestoreZipVerifier.verifySessionZip(write(torn), -1L, FakeRestoreApi.sha256Of(torn))
    }

    @Test
    fun `Extras zip must declare a sha256 and match it`() {
        val file = write(validZip)
        assertEquals("extras_zip_sha256_missing", code { RestoreZipVerifier.verifyExtrasZip(file, null) })
        assertEquals(
            "session_zip_sha256_mismatch",
            code { RestoreZipVerifier.verifyExtrasZip(file, FakeRestoreApi.sha256Of(byteArrayOf(9))) },
        )
        RestoreZipVerifier.verifyExtrasZip(file, FakeRestoreApi.sha256Of(validZip))
    }

    @Test
    fun `metadata is checked only against a declared sha256`() {
        val json = FakeRestoreApi.metadataJson()
        RestoreZipVerifier.verifyMetadata(json, null)
        RestoreZipVerifier.verifyMetadata(json, FakeRestoreApi.sha256Of(json))
        assertEquals(
            "metadata_sha256_mismatch",
            code { RestoreZipVerifier.verifyMetadata(json, FakeRestoreApi.sha256Of("{}".toByteArray())) },
        )
    }
}
