package com.sempermechanics.semper.data.net.drive

import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.HttpStatus
import com.sempermechanics.semper.data.net.SemperApiHttp
import com.sempermechanics.semper.data.net.UploadLinkExpiredException
import com.sempermechanics.semper.util.Digests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

// internal, not private: DicUploadWorker sizes its chunk request against the
// device's available memory before ever reaching uploadResumable, and must
// share these bounds rather than duplicate them.

/** Drive resumable chunks must be 256 KiB multiples (except the final one). */
internal const val MIN_CHUNK_BYTES = 256 * 1024

/** Upper bound on the per-chunk buffer allocation, whatever the server says. */
internal const val MAX_CHUNK_BYTES = 32 * 1024 * 1024

/**
 * PUTs in a row after which Drive has still kept nothing past the furthest
 * offset it ever reported: the attempt fails (and the worker retries later)
 * instead of re-sending the same chunk forever.
 */
internal const val MAX_STALLED_PUTS = 3

/** The only `Range` form Drive sends on a 308: bytes 0..N are persisted. */
private const val PERSISTED_RANGE_PREFIX = "bytes=0-"

/**
 * Where to continue after a 308, from its `Range` header: `bytes=0-N` means
 * Drive holds bytes 0..N, so N + 1; no header means it holds nothing yet, so 0.
 *
 * Null when the header is there but in another form, or N lies outside a file
 * of [total] bytes: a guess would either re-send bytes Drive has or leave a gap
 * that fails the size/md5 check at `:complete`.
 */
internal fun resumeOffsetOf(range: String?, total: Long): Long? {
    if (range == null) return 0L
    val last = range.trim()
        .takeIf { it.startsWith(PERSISTED_RANGE_PREFIX) }
        ?.removePrefix(PERSISTED_RANGE_PREFIX)
        ?.toLongOrNull()
    return last?.takeIf { it in 0 until total }?.plus(1)
}

/** The resumable upload straight to Drive behind [DriveTransfer.uploadResumable]. */
internal class DriveUploader(private val client: OkHttpClient, private val octet: MediaType) {

    /**
     * Resumable upload of [file] to a Drive [uploadUrl], in [chunkSize] chunks
     * (multiple of 256 KiB). Resumes from the server offset on reconnect, and
     * after every chunk continues where Drive's `Range` says it stopped, which
     * may be short of what was sent. Bytes go straight to Drive — not through
     * the backend.
     *
     * Returns the Drive file id with the local MD5 of [file], always
     * (Drive's completion JSON often omits `md5Checksum` under API v3 partial
     * responses; the backend requires a matching client md5 at `:complete`).
     */
    suspend fun uploadResumable(
        uploadUrl: String,
        file: File,
        chunkSize: Int,
        onBytes: (Long) -> Unit = {},
    ): DriveUpload = withContext(Dispatchers.IO) {
        val total = file.length()
        // The buffer is allocated at chunk size — clamp what the server
        // sent so a misconfigured value can never OOM the app. Drive needs
        // chunks in 256 KiB multiples (except the last).
        val chunk = chunkSize.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)

        // Where does Drive want us to continue — or does it already have the
        // whole file? A file fully uploaded in a prior attempt (but whose
        // completeFile never ran) reports COMPLETE here; return its id with
        // a local md5 instead of trying to re-send zero bytes and failing.
        // Never Drive's own md5Checksum: `:complete` checks the client md5
        // against Drive's, so echoing Drive's back would prove nothing about
        // whether Drive holds this file's bytes.
        val probe = probeStatus(uploadUrl, total)
        probe.driveFileId?.let { driveId ->
            return@withContext DriveUpload(driveId, Digests.md5Hex(file))
        }
        var offset = probe.offset
        // The furthest Drive has ever said it holds, and how many PUTs in a
        // row have not moved it: a Drive that keeps nothing must not loop us.
        var furthest = offset
        var stalls = 0

        val digest = Digests.md5()
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(chunk)
            // Prefix already on Drive must be hashed so the digest covers the
            // whole file, not only the bytes we send on this resume.
            if (offset > 0L) {
                hashPrefix(raf, buf, offset, digest)
            }
            while (offset < total) {
                // The PUTs below block, so a stopped worker would otherwise
                // push every remaining chunk before it noticed.
                currentCoroutineContext().ensureActive()
                raf.seek(offset)
                val n = raf.read(buf, 0, minOf(chunk.toLong(), total - offset).toInt())
                if (n <= 0) throw IOException("unexpected EOF at $offset/$total")
                digest.update(buf, 0, n)
                val end = offset + n - 1
                val req = Request.Builder().url(uploadUrl)
                    .header("Content-Range", "bytes $offset-$end/$total")
                    .put(buf.toRequestBody(octet, 0, n)).build()
                client.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        HttpStatus.RESUME_INCOMPLETE -> {
                            // Not `end + 1`: Drive may have kept less than it was sent.
                            val kept = keptAfter(resp, total)
                            if (kept > furthest) {
                                furthest = kept
                                stalls = 0
                            } else if (++stalls >= MAX_STALLED_PUTS) {
                                throw ApiException(resp.code, "Drive kept no new bytes at $offset/$total")
                            }
                            onBytes(kept - offset)
                            // The digest covers [0, end]; make it cover what Drive
                            // holds. Only then, so the common case hashes once.
                            if (kept != end + 1) hashPrefix(raf, buf, kept, digest)
                            offset = kept
                        }
                        HttpStatus.OK, HttpStatus.CREATED -> {
                            onBytes(n.toLong())
                            val driveId = SemperApiHttp.driveFileIdOf(resp.body.string())
                            return@withContext DriveUpload(driveId, Digests.toHex(digest.digest()))
                        }
                        // Drive's resumable endpoint, not the Semper backend, so
                        // there is no X-Request-Id to correlate with.
                        else -> throw ApiException(resp.code, SemperApiHttp.bodyText(resp))
                    }
                }
            }
        }

        // Loop reached `total` without a final 200/201 — the last bytes were
        // already on Drive from a previous attempt. Re-probe to finalize and
        // get the resource, rather than failing.
        val finalized = probeStatus(uploadUrl, total).driveFileId
            ?: throw IOException("upload finished without a final Drive response")
        // Digest already covers the whole file from the prefix+chunk updates.
        DriveUpload(finalized, Digests.toHex(digest.digest()))
    }

    /** Reset [digest] to cover [0, end) of [raf], using [buf] as a scratch buffer. */
    private fun hashPrefix(
        raf: RandomAccessFile,
        buf: ByteArray,
        end: Long,
        digest: MessageDigest,
    ) {
        digest.reset()
        var pos = 0L
        while (pos < end) {
            raf.seek(pos)
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
            if (n <= 0) throw IOException("unexpected EOF hashing $pos/$end")
            digest.update(buf, 0, n)
            pos += n
        }
    }

    /**
     * The offset to continue at after a 308 ([resumeOffsetOf]). A `Range` that
     * cannot be read fails the attempt like any other Drive refusal (the
     * worker retries it, keeping staging) rather than being taken as zero.
     */
    private fun keptAfter(resp: Response, total: Long): Long {
        val range = resp.header("Range")
        return resumeOffsetOf(range, total) ?: throw ApiException(resp.code, "unreadable Range: $range")
    }

    /** Current state of a resumable session: continue at [offset], or already finished as [driveFileId]. */
    private data class UploadProbe(val offset: Long, val driveFileId: String?)

    /**
     * Ask Drive what it already has: PUT `bytes * /total` with an empty body.
     *
     * Any answer but those three is a failure, not "start at zero": re-sending
     * bytes Drive already holds is what made a resumed upload fail its size
     * check, and a gone link (404/410, or 499 once cancelled) fails every PUT
     * the same way.
     */
    private fun probeStatus(uploadUrl: String, total: Long): UploadProbe {
        val req = Request.Builder().url(uploadUrl)
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(octet)).build()
        client.newCall(req).execute().use { resp ->
            return when (resp.code) {
                // Resume Incomplete: Range tells us the last byte received (may be absent = nothing yet).
                HttpStatus.RESUME_INCOMPLETE -> UploadProbe(keptAfter(resp, total), null)
                // Already complete — the body is the Drive file resource.
                HttpStatus.OK, HttpStatus.CREATED -> UploadProbe(total, SemperApiHttp.driveFileIdOf(resp.body.string()))
                HttpStatus.NOT_FOUND, HttpStatus.GONE, HttpStatus.CLIENT_CLOSED ->
                    throw UploadLinkExpiredException(resp.code)
                // Drive's resumable endpoint, not the Semper backend: no X-Request-Id.
                else -> throw ApiException(resp.code, SemperApiHttp.bodyText(resp))
            }
        }
    }
}
