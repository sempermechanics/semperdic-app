package com.indicvision.semper.cloud

import com.indicvision.semper.data.session.DatCodec
import com.indicvision.semper.data.session.SessionZip
import com.indicvision.semper.fixtures.packDat
import com.indicvision.semper.util.Digests
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

/** Pins STORED TIFF/DAT packing and round-trip verify before upload. */
class SessionZipTest {

    @Test
    fun `tiff and dat are stored not deflated`() {
        assertTrue(SessionZip.shouldStore("oht_cfrp_01.tiff"))
        assertTrue(SessionZip.shouldStore("frame_0000.dat"))
        assertTrue(SessionZip.shouldStore("Reference.png"))
        assertTrue(SessionZip.shouldStore("anim.gif"))
        assertFalse(SessionZip.shouldStore("analysis_data.csv"))
        assertFalse(SessionZip.shouldStore("metadata.json"))
    }

    @Test
    fun `build stores tiff and round-trips with csv`() {
        val dir = createTempDirectory(prefix = "session-zip-").toFile()
        try {
            val tiff = File(dir, "oht_cfrp_01.tiff").also { f ->
                // Semi-random payload large enough to stress deflate-if-misused.
                val bytes = ByteArray(256 * 1024) { i -> (i * 17 + 31).toByte() }
                f.writeBytes(bytes)
            }
            val csv = File(dir, "analysis_data.csv").also {
                it.writeText("image,x,y\noht_cfrp_01.tiff,1,2\n")
            }
            val gif = File(dir, "preview.gif").also {
                it.writeBytes(ByteArray(4096) { it.toByte() })
            }
            val out = File(dir, "Session.zip")
            val hex = SessionZip.build(
                listOf(
                    SessionZip.Member("raw", tiff.name, tiff),
                    SessionZip.Member("csv", csv.name, csv),
                    SessionZip.Member("processed", "Frame_1/${gif.name}", gif),
                ),
                out,
            )
            assertEquals(64, hex.length)
            assertEquals(hex, Digests.sha256Hex(out))

            ZipFile(out).use { zf ->
                val tiffEntry = zf.getEntry("raw/oht_cfrp_01.tiff")!!
                assertEquals(java.util.zip.ZipEntry.STORED, tiffEntry.method)
                val csvEntry = zf.getEntry("csv/analysis_data.csv")!!
                assertEquals(java.util.zip.ZipEntry.DEFLATED, csvEntry.method)
            }

            val extracted = mutableMapOf<String, ByteArray>()
            SessionZip.forEachEntry(out) { role, name, input ->
                extracted["$role/$name"] = input.readBytes()
            }
            assertArrayEquals(tiff.readBytes(), extracted["raw/oht_cfrp_01.tiff"])
            assertArrayEquals(csv.readBytes(), extracted["csv/analysis_data.csv"])
            assertArrayEquals(gif.readBytes(), extracted["processed/Frame_1/preview.gif"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `verifyRoundTrip catches a STORED entry's CRC32 mismatch instead of trusting it silently`() {
        // Pins that the CRC32 fast path (STORED entries) actually detects corruption,
        // not just that it's faster — a regression here would silently promote a
        // corrupt archive.
        val dir = createTempDirectory(prefix = "session-zip-crc-").toFile()
        try {
            val tiff = File(dir, "oht_cfrp_01.tiff").also {
                it.writeBytes(ByteArray(4096) { i -> i.toByte() })
            }
            val out = File(dir, "Session.zip")
            val member = SessionZip.Member("raw", tiff.name, tiff)
            SessionZip.build(listOf(member), out)

            // Mutate the source after the archive was written — the archive's
            // central-directory CRC32 now reflects stale bytes.
            tiff.writeBytes(ByteArray(4096) { i -> (i + 1).toByte() })

            val failure = assertThrows(IllegalStateException::class.java) {
                SessionZip.verifyRoundTrip(out, listOf(member))
            }
            assertTrue(
                "expected a CRC-mismatch message, got: ${failure.message}",
                failure.message.orEmpty().contains("CRC mismatch"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A real (small) DIC point buffer — .dat entries need this shape, not arbitrary bytes. */
    private fun sampleDatBytes(): ByteArray = packDat(
        listOf(
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 0.01f),
            floatArrayOf(4f, 0f, 1f, 1f, 0f, 0f, 0f, 0.01f),
            floatArrayOf(0f, 4f, 1f, 1f, 0f, 0f, 0f, 0.01f),
            floatArrayOf(4f, 4f, 1f, 1f, 0f, 0f, 0f, 0.01f),
        ),
    )

    @Test
    fun `dat entries are stored raw by default (encodeDatEntries=false)`() {
        // The default matches every existing caller that doesn't pass
        // encodeDatEntries explicitly — an already-installed client with no
        // DatCodec awareness must still be able to read what it uploads today.
        val dir = createTempDirectory(prefix = "session-zip-dat-").toFile()
        try {
            val datBytes = sampleDatBytes()
            val dat = File(dir, "frame_0000.dat").also { it.writeBytes(datBytes) }
            val out = File(dir, "Session.zip")
            SessionZip.build(listOf(SessionZip.Member("dat", dat.name, dat)), out)

            ZipFile(out).use { zf ->
                val entry = zf.getEntry("dat/frame_0000.dat")!!
                assertEquals(ZipEntry.STORED, entry.method)
                val archiveBytes = zf.getInputStream(entry).use { it.readBytes() }
                assertArrayEquals(
                    "an already-installed client must be able to read this entry directly",
                    datBytes,
                    archiveBytes,
                )
            }

            var decoded: ByteArray? = null
            SessionZip.forEachEntry(out) { role, name, input ->
                if (role == "dat" && name == "frame_0000.dat") decoded = input.readBytes()
            }
            assertArrayEquals(datBytes, decoded)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `dat entries are DatCodec-encoded when encodeDatEntries=true, and read back exactly`() {
        val dir = createTempDirectory(prefix = "session-zip-dat-encoded-").toFile()
        try {
            val datBytes = sampleDatBytes()
            val dat = File(dir, "frame_0000.dat").also { it.writeBytes(datBytes) }
            val out = File(dir, "Session.zip")
            SessionZip.build(
                listOf(SessionZip.Member("dat", dat.name, dat)),
                out,
                encodeDatEntries = true,
            )

            ZipFile(out).use { zf ->
                val entry = zf.getEntry("dat/frame_0000.dat")!!
                val archiveBytes = zf.getInputStream(entry).use { it.readBytes() }
                assertTrue(
                    "the archive entry should be the encoded form, not the raw source bytes",
                    !archiveBytes.contentEquals(datBytes),
                )
                assertArrayEquals(
                    "encode() then decode() must round-trip exactly",
                    datBytes,
                    DatCodec.decode(archiveBytes),
                )
            }

            var decoded: ByteArray? = null
            SessionZip.forEachEntry(out) { role, name, input ->
                if (role == "dat" && name == "frame_0000.dat") decoded = input.readBytes()
            }
            assertArrayEquals(
                "forEachEntry must transparently decode on read",
                datBytes,
                decoded,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `verifyRoundTrip decodes an encoded dat entry back and compares against the source`() {
        // The CRC32/SHA-256 paths in checkMember compare archive bytes against
        // source bytes directly — deliberately wrong for an encoded entry, whose
        // archive bytes never equal the source. This pins the decode-then-compare
        // path that must run instead, and that it actually catches a real corruption
        // rather than trivially passing because it stopped comparing anything.
        val dir = createTempDirectory(prefix = "session-zip-dat-verify-").toFile()
        try {
            val datBytes = sampleDatBytes()
            val dat = File(dir, "frame_0000.dat").also { it.writeBytes(datBytes) }
            val member = SessionZip.Member("dat", dat.name, dat)
            val out = File(dir, "Session.zip")

            // build() already calls verifyRoundTrip internally — reaching this
            // line without an exception is itself proof the happy path works.
            SessionZip.build(listOf(member), out, encodeDatEntries = true)

            // Mutate the source after the archive was written, then re-verify
            // explicitly — the archive's encoded entry now decodes to stale bytes.
            dat.writeBytes(datBytes.copyOf().also { it[0] = (it[0] + 1).toByte() })
            val failure = assertThrows(IllegalStateException::class.java) {
                SessionZip.verifyRoundTrip(out, listOf(member), encodeDatEntries = true)
            }
            assertTrue(
                "expected a round-trip hash mismatch, got: ${failure.message}",
                failure.message.orEmpty().contains("round-trip hash mismatch"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** One [encode] case of `merge preserves a dat entry's bytes exactly regardless of source encoding`. */
    private fun assertMergePreservesDatBytes(dir: File, datBytes: ByteArray, encode: Boolean) {
        val dat = File(dir, "frame_0000.dat").also { it.writeBytes(datBytes) }
        val bundle = File(dir, "bundle_$encode.zip")
        SessionZip.build(listOf(SessionZip.Member("dat", dat.name, dat)), bundle, encodeDatEntries = encode)

        val merged = File(dir, "merged_$encode.zip")
        SessionZip.merge(listOf(bundle), merged)

        val mergedBytes = ZipFile(merged).use { zf ->
            val e = zf.getEntry("dat/frame_0000.dat")!!
            zf.getInputStream(e).use { it.readBytes() }
        }
        assertArrayEquals(
            "Save to Files must hand over a directly-usable .dat (encodeDatEntries=$encode)",
            datBytes,
            mergedBytes,
        )
    }

    @Test
    fun `merge preserves a dat entry's bytes exactly regardless of source encoding`() {
        val dir = createTempDirectory(prefix = "session-zip-merge-dat-").toFile()
        try {
            val datBytes = sampleDatBytes()
            assertMergePreservesDatBytes(dir, datBytes, encode = false)
            assertMergePreservesDatBytes(dir, datBytes, encode = true)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A hand-built zip with one plain STORED entry — no SessionZip/DatCodec involvement. */
    private fun writeRawStoredEntry(zip: File, entryName: String, bytes: ByteArray) {
        val entry = ZipEntry(entryName).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = java.util.zip.CRC32().apply { update(bytes) }.value
        }
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(entry)
            zos.write(bytes)
            zos.closeEntry()
        }
    }

    @Test
    fun `a legacy pre-codec dat entry (raw, unencoded) still reads back unchanged`() {
        // Simulates a Session.zip uploaded before DatCodec existed: a plain STORED
        // .dat entry with no codec header. decodeIfEncoded must pass it through.
        val dir = createTempDirectory(prefix = "session-zip-legacy-dat-").toFile()
        try {
            val datBytes = sampleDatBytes()
            val legacyZip = File(dir, "Legacy.zip")
            writeRawStoredEntry(legacyZip, "dat/frame_0000.dat", datBytes)

            var readBack: ByteArray? = null
            SessionZip.forEachEntry(legacyZip) { role, name, input ->
                if (role == "dat" && name == "frame_0000.dat") readBack = input.readBytes()
            }
            assertArrayEquals(
                "a pre-codec archive's raw .dat bytes must pass through unchanged",
                datBytes,
                readBack,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a DatCodec-encoded dat entry (future upload) is still decoded correctly on read`() {
        // Simulates what a client WILL upload once encoding is turned on: hand-build
        // an archive with a DatCodec-encoded .dat entry (bypassing SessionZip.build,
        // which does not encode today) and confirm forEachEntry already decodes it
        // transparently — this is the forward-compatibility half DatCodec.encode's
        // eventual rollout depends on, and it must already be true.
        val dir = createTempDirectory(prefix = "session-zip-future-dat-").toFile()
        try {
            val datBytes = sampleDatBytes()
            val encoded = DatCodec.encode(datBytes)
            val futureZip = File(dir, "Future.zip")
            writeRawStoredEntry(futureZip, "dat/frame_0000.dat", encoded)

            var decoded: ByteArray? = null
            SessionZip.forEachEntry(futureZip) { role, name, input ->
                if (role == "dat" && name == "frame_0000.dat") decoded = input.readBytes()
            }
            assertArrayEquals(datBytes, decoded)
        } finally {
            dir.deleteRecursively()
        }
    }
}
