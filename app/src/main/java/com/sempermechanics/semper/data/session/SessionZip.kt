package com.sempermechanics.semper.data.session

import com.sempermechanics.semper.data.cloud.CorruptTransferException
import com.sempermechanics.semper.data.net.ArtifactRoles
import com.sempermechanics.semper.util.AtomicFiles
import com.sempermechanics.semper.util.Digests
import com.sempermechanics.semper.util.Zips
import com.sempermechanics.semper.util.crc32
import com.sempermechanics.semper.util.writeVia
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Session.zip build / verify helpers shared by upload.
 *
 * Large binaries (TIFF / DAT / PNG / GIF / …) are [ZipEntry.STORED].
 *
 * Do **not** toggle [ZipOutputStream.setLevel] between [Deflater.NO_COMPRESSION]
 * and [Deflater.DEFAULT_COMPRESSION] on Android: a Device Session.zip from Drive
 * had a valid central directory / sha256, but the first entry after each
 * level-0 to level-9 switch (raw TIFF, then Exx animation GIF) carried a
 * 7685-byte junk prefix before a good deflate stream — inflate then fails
 * with "invalid stored block lengths" or "invalid code lengths set".
 * Desktop OpenJDK does not reproduce; Pixel-class Android Deflater does.
 * Textable payloads (csv/json) stay DEFLATED at a single level.
 */
@Suppress("TooManyFunctions") // build / verify / STORED+DEFLATED entry writers stay together
internal object SessionZip {

    data class Member(val role: String, val name: String, val file: File)

    /** Fixed name of the reference image artifact (role `raw`). */
    const val REFERENCE_NAME = "Reference.png"

    /**
     * Whether a [role] belongs in the restore-essential bundle: every original
     * image (`raw/` — the reference **and** the deformed originals) plus the engine
     * results (`dat/`). Restoring a session brings back everything a local analysis
     * run would have produced, so on-device re-export and the report covers work
     * without a second fetch.
     *
     * `csv`, `reports`, `processed` are excluded: they are derived and regenerated
     * on export (`SessionEverythingExporter`), so nothing reads them back after a
     * restore — those alone go to `Extras.zip`.
     *
     * Upload uses this to decide what goes in `Session.zip` vs `Extras.zip`. The
     * decision is role-level (not per-artifact) now that every `raw/` entry is
     * restore-essential; kept as a named predicate rather than an inline role-set
     * check because restore's ranged-prefix reader ([RESTORE_ENTRY_PREFIXES]) needs
     * to stay in step with it.
     */
    fun isRestoreEssential(role: String): Boolean = role == ArtifactRoles.DAT || role == ArtifactRoles.RAW

    /**
     * Entry-name prefixes a restore fetches from a **legacy** single-archive backup.
     * Same set as [isRestoreEssential] admits by role, expressed as zip-entry
     * prefixes for the ranged-prefix reader, which works on entry names rather than
     * artifact records.
     */
    val RESTORE_ENTRY_PREFIXES: Set<String> = setOf("raw/", "dat/")

    /** Already-compressed or large binary payloads — store, do not deflate. */
    val STORE_EXTENSIONS: Set<String> = setOf(
        "jpg", "jpeg", "png", "pdf", "webp", "zip", "gif", "bmp",
        "tif", "tiff", "dat",
    )

    fun entryName(role: String, name: String): String = "$role/$name"

    fun shouldStore(fileName: String): Boolean =
        fileName.substringAfterLast('.').lowercase(Locale.US) in STORE_EXTENSIONS

    /**
     * `.dat` gets its own path on both read and write. *Read* — [DatCodec.decodeIfEncoded]
     * is applied in [readEntry] and [copyEntry], so this archive transparently
     * understands a codec-encoded entry (self-describing by magic header; a raw,
     * legacy entry passes through unchanged) regardless of which side of the
     * version gate produced it. *Write* — [putMember] calls [DatCodec.encode] only
     * when the caller's [build] passes `encodeDatEntries = true`, which [build]'s
     * own callers gate on [com.sempermechanics.semper.data.net.AppRemoteConfig]'s
     * `datCodecEncodingEnabled` (backend-controlled rollout — see that function's
     * doc and `backend/app/config.py`'s `DAT_CODEC_ENCODING_ENABLED` for why this
     * cannot just default to on: an already-installed client with no [DatCodec]
     * awareness at all writes a restored `.dat` straight to disk with no decode
     * step, so an encoded entry reaching it would silently corrupt that restore).
     */
    private fun isDatEntry(fileName: String): Boolean =
        fileName.substringAfterLast('.').equals("dat", ignoreCase = true)

    /**
     * Write [members] to [out] (via `*.tmp` + rename). Returns lowercase sha256
     * of the finished archive. Verifies every entry round-trips before promote.
     *
     * @param onBytes invoked with source bytes written into the archive (not
     * the CRC pre-pass for STORED entries). Used for Home "preparing %" on
     * large PLC bundles where zip dominates prepare time.
     * @param encodeDatEntries version-gated — see [isDatEntry]'s doc. `false`
     * (today's raw-STORED behaviour) unless the caller has confirmed via
     * [com.sempermechanics.semper.data.net.AppRemoteConfig] that the account may
     * upload the codec-encoded format.
     */
    fun build(
        members: List<Member>,
        out: File,
        onBytes: (Long) -> Unit = {},
        encodeDatEntries: Boolean = false,
    ): String {
        require(members.isNotEmpty()) { "Session.zip payload is empty" }
        val digest = Digests.sha256()
        AtomicFiles.writeVia(out, tmp = File(out.parentFile, "${out.name}.tmp"), clearDest = true) { tmp ->
            val storedCrcs = writeArchive(tmp, members, digest, onBytes, encodeDatEntries)
            verifyRoundTrip(tmp, members, encodeDatEntries, storedCrcs)
        }
        Timber.i("Bundled %d artifacts into %s (%d bytes)", members.size, out.name, out.length())
        return Digests.toHex(digest.digest())
    }

    /**
     * Stream every entry via [ZipFile] (not [java.util.zip.ZipInputStream]).
     * [onEntry] must fully consume [input] before returning.
     */
    fun forEachEntry(
        zip: File,
        onEntry: (role: String, name: String, input: InputStream) -> Unit,
    ) {
        ZipFile(zip).use { zf ->
            zf.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                readEntry(zf, entry, onEntry)
            }
        }
    }

    private fun writeArchive(
        tmp: File,
        members: List<Member>,
        digest: java.security.MessageDigest,
        onBytes: (Long) -> Unit,
        encodeDatEntries: Boolean,
    ): Map<String, Long> {
        val storedCrcs = mutableMapOf<String, Long>()
        DigestOutputStream(BufferedOutputStream(tmp.outputStream()), digest).use { digOut ->
            ZipOutputStream(digOut).use { zip ->
                members.forEach { putMember(zip, it, onBytes, encodeDatEntries, storedCrcs) }
            }
        }
        return storedCrcs
    }

    /**
     * Concatenate [sources] into one archive at [out], first source winning on a
     * duplicate entry name.
     *
     * Used by "Save to Files", which must still hand over a single complete archive
     * now that upload splits the payload across `Session.zip` and `Extras.zip`.
     * Each entry keeps its original compression method, so `STORED` payloads are
     * copied rather than re-compressed. Note this deliberately never calls
     * [ZipOutputStream.setLevel] — see the class comment on the Android Deflater
     * corruption caused by toggling levels mid-archive.
     */
    fun merge(sources: List<File>, out: File) {
        require(sources.isNotEmpty()) { "merge needs at least one source" }
        AtomicFiles.writeVia(out, tmp = File(out.parentFile, "${out.name}.merge"), clearDest = true) { tmp ->
            ZipOutputStream(BufferedOutputStream(tmp.outputStream())).use { zos ->
                val seen = HashSet<String>()
                for (source in sources) {
                    copyEntriesInto(source, zos, seen)
                }
            }
        }
    }

    /** Copy every not-yet-[seen] entry of [source] into [zos], preserving its method. */
    private fun copyEntriesInto(source: File, zos: ZipOutputStream, seen: MutableSet<String>) {
        ZipFile(source).use { zf ->
            zf.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { seen.add(it.name) }
                .forEach { entry -> copyEntry(zf, entry, zos) }
        }
    }

    /** Copy one entry verbatim, keeping its compression method and STORED sizes. */
    private fun copyEntry(from: ZipFile, entry: ZipEntry, zos: ZipOutputStream) {
        val fileName = entry.name.substringAfterLast('/')
        if (isDatEntry(fileName)) {
            // Save to Files hands the user a real, directly-usable session archive —
            // a DatCodec-encoded .dat inside it would not be a valid .dat to anything
            // outside this app, so decode it back to the real layout on the way out.
            // A payload that will not decode is a corrupt transfer, as on restore.
            Zips.putStoredBytes(zos, entry.name, decodeDatEntryOrThrow(from, entry))
            return
        }
        val copy = ZipEntry(entry.name).apply {
            method = entry.method
            if (entry.method == ZipEntry.STORED) {
                size = entry.size
                compressedSize = entry.compressedSize
                crc = entry.crc
            }
            if (entry.time >= 0L) time = entry.time
        }
        zos.putNextEntry(copy)
        from.getInputStream(entry).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private fun readEntry(
        zf: ZipFile,
        entry: ZipEntry,
        onEntry: (role: String, name: String, input: InputStream) -> Unit,
    ) {
        val role = entry.name.substringBefore('/', missingDelimiterValue = "")
        val name = entry.name.substringAfter('/', missingDelimiterValue = entry.name)
        try {
            if (isDatEntry(name)) {
                // .dat is small enough to buffer whole (a few MB even at LARGE scale)
                // — everything else keeps streaming straight through, unbuffered.
                val decoded = decodeDatEntryOrThrow(zf, entry)
                onEntry(role, name, decoded.inputStream())
            } else {
                zf.getInputStream(entry).use { input -> onEntry(role, name, input) }
            }
        } catch (e: ZipException) {
            throw CorruptTransferException(
                "entry_inflate_failed",
                IllegalArgumentException(entry.name, e),
            )
        } catch (e: IOException) {
            throw CorruptTransferException(
                "entry_read_failed",
                IllegalArgumentException(entry.name, e),
            )
        }
    }

    /**
     * [DatCodec.decodeIfEncoded], with decode failures folded into the same "corrupt
     * transfer" story. [DatCodec.decode] signals a malformed archive via
     * `require`/`check` — [IllegalArgumentException] / [IllegalStateException] — not
     * an [IOException], so those are what a corrupt `.dat` payload actually raises.
     */
    private fun decodeDatEntryOrThrow(zf: ZipFile, entry: ZipEntry): ByteArray = try {
        DatCodec.decodeIfEncoded(zf.getInputStream(entry).use { it.readBytes() })
    } catch (e: IllegalArgumentException) {
        throw CorruptTransferException(
            "entry_datcodec_decode_failed",
            IllegalArgumentException(entry.name, e),
        )
    } catch (e: IllegalStateException) {
        throw CorruptTransferException(
            "entry_datcodec_decode_failed",
            IllegalArgumentException(entry.name, e),
        )
    }

    /**
     * [DatCodec.encode] is applied to a `.dat` member only when [encodeDatEntries]
     * is true — see [isDatEntry]'s class-doc note on why this is version-gated
     * rather than unconditional. Every other member is unaffected either way.
     */
    private fun putMember(
        zip: ZipOutputStream,
        member: Member,
        onBytes: (Long) -> Unit,
        encodeDatEntries: Boolean,
        storedCrcs: MutableMap<String, Long>,
    ) {
        val entryName = entryName(member.role, member.name)
        if (encodeDatEntries && isDatEntry(member.name)) {
            storedCrcs[entryName] =
                Zips.putStoredBytes(zip, entryName, DatCodec.encode(member.file.readBytes()), onBytes)
        } else if (shouldStore(member.name)) {
            storedCrcs[entryName] = Zips.putStored(zip, entryName, member.file, onBytes)
        } else {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            Zips.putFile(zip, entryName, member.file, onBytes)
        }
    }

    /** Ensure every member extracts byte-identical to its source before upload. */
    fun verifyRoundTrip(
        zip: File,
        members: List<Member>,
        encodeDatEntries: Boolean = false,
        storedCrcs: Map<String, Long> = emptyMap(),
    ) {
        ZipFile(zip).use { zf ->
            check(zf.size() == members.size) {
                "Session.zip entry count ${zf.size()} != payload ${members.size}"
            }
            members.forEach { member -> checkMember(zf, member, encodeDatEntries, storedCrcs) }
        }
    }

    private fun checkMember(
        zf: ZipFile,
        member: Member,
        encodeDatEntries: Boolean,
        storedCrcs: Map<String, Long>,
    ) {
        val name = entryName(member.role, member.name)
        val entry = zf.getEntry(name)
            ?: error("Session.zip missing entry $name after bundling")
        if (encodeDatEntries && isDatEntry(member.name)) {
            // A DatCodec-encoded entry's bytes never equal the source file's own
            // bytes (that's the point), so neither the CRC32 nor the SHA-256 path
            // below applies — decode the entry back and compare THAT against the
            // source. This exercises the decoder on every single upload, which is
            // a strictly stronger guarantee than comparing raw bytes: a codec bug
            // that corrupts data would be caught right here, before promote, not
            // discovered later on someone's restore.
            val decoded = DatCodec.decode(zf.getInputStream(entry).use { it.readBytes() })
            val got = Digests.toHex(Digests.sha256(decoded))
            val expect = Digests.sha256Hex(member.file)
            check(got == expect) {
                "Session.zip entry $name round-trip hash mismatch after DatCodec encode+decode"
            }
            return
        }
        if (entry.method == ZipEntry.STORED) {
            // STORED entries already carry a CRC32 in the archive's central
            // directory — putStored computed it in the same pre-pass that set
            // entry.size, before ever writing a byte. Comparing that against a
            // fresh CRC32 of the source (not a fresh SHA-256 of *both* sides)
            // means this check never re-reads the just-written archive entry at
            // all, and CRC32 — not a cryptographic digest — is exactly the
            // algorithm the zip format itself uses to catch accidental byte
            // corruption, which is the only threat model here (this promotes a
            // local file we just wrote, not data received from an untrusted party).
            val expectCrc = storedCrcs[name] ?: member.file.crc32()
            check(entry.crc == expectCrc) {
                "Session.zip entry $name round-trip CRC mismatch after bundling " +
                    "(archive=${entry.crc}, source=$expectCrc)"
            }
            return
        }
        val got = Digests.sha256HexStream(zf.getInputStream(entry))
        val expect = Digests.sha256Hex(member.file)
        check(got == expect) {
            "Session.zip entry $name round-trip hash mismatch after bundling"
        }
    }
}
