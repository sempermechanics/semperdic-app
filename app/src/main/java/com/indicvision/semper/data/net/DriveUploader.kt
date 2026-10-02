package com.indicvision.semper.data.net

import com.indicvision.semper.util.Digests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

/** The resumable upload straight to Drive behind [DriveTransfer.uploadResumable]. */
internal class DriveUploader(private val client: OkHttpClient, private val octet: MediaType) {

    /**
     * Resumable upload of [file] to a Drive [uploadUrl], in [chunkSize] chunks
     * (multiple of 256 KiB). Resumes from the server offset on reconnect. Bytes
     * go straight to Drive — not through the backend.
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

        val digest = Digests.md5()
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(chunk)
            // Prefix already on Drive must be hashed so the digest covers the
            // whole file, not only the bytes we send on this resume.
            if (offset > 0L) {
                hashRange(raf, buf, 0L, offset, digest)
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
                            offset = end + 1
                            onBytes(n.toLong())
                        }
                        HttpStatus.OK, HttpStatus.CREATED -> {
                            onBytes(n.toLong())
                            val driveId = IndicApiHttp.driveFileIdOf(resp.body.string())
                            return@withContext DriveUpload(driveId, Digests.toHex(digest.digest()))
                        }
                        // Drive's resumable endpoint, not the Semper backend, so
                        // there is no X-Request-Id to correlate with.
                        else -> throw IndicApi.ApiException(resp.code, IndicApiHttp.bodyText(resp))
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

    /** Hash [start, end) of [raf] into [digest] using [buf] as a scratch buffer. */
    private fun hashRange(
        raf: RandomAccessFile,
        buf: ByteArray,
        start: Long,
        end: Long,
        digest: MessageDigest,
    ) {
        var pos = start
        while (pos < end) {
            raf.seek(pos)
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
            if (n <= 0) throw IOException("unexpected EOF hashing $pos/$end")
            digest.update(buf, 0, n)
            pos += n
        }
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
                HttpStatus.RESUME_INCOMPLETE ->
                    UploadProbe(resp.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L, null)
                // Already complete — the body is the Drive file resource.
                HttpStatus.OK, HttpStatus.CREATED -> UploadProbe(total, IndicApiHttp.driveFileIdOf(resp.body.string()))
                HttpStatus.NOT_FOUND, HttpStatus.GONE, HttpStatus.CLIENT_CLOSED ->
                    throw IndicApi.UploadLinkExpiredException(resp.code)
                // Drive's resumable endpoint, not the Semper backend: no X-Request-Id.
                else -> throw IndicApi.ApiException(resp.code, IndicApiHttp.bodyText(resp))
            }
        }
    }
}
