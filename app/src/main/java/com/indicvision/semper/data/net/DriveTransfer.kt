package com.indicvision.semper.data.net

import com.indicvision.semper.data.cloud.restore.RestoreDownloadOutcomes
import com.indicvision.semper.util.AtomicFiles
import com.indicvision.semper.util.Digests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
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
 * Consecutive failures without byte progress before giving up. Reset whenever
 * a chunk lands so a large Session.zip is not capped at five total requests.
 */
private const val DOWNLOAD_MAX_ATTEMPTS = 8

/** Copy buffer for proxied restore downloads. */
private const val DOWNLOAD_COPY_BUFFER = 1 shl 16

/** Log/exception preview length for non-success download bodies. */
private const val DOWNLOAD_ERROR_BODY_PREVIEW = 120

/**
 * Bounded Range window for each proxied GET — never open-ended (the `/content`
 * route's Cloud Run + gateway deadline is 300s; see `backend/gateway/openapi.yaml`
 * and `--timeout=300` in the deploy workflow). Window size is *adaptive*, not
 * fixed, because the right size depends on the link, not the device:
 *
 * - A slow link must stay well under the 300s budget — a window sized for a
 *   fast connection would time out and lose all its bytes on a slow one.
 * - A fast link benefits from a bigger window: each window costs a fresh
 *   attestation challenge (Firestore read+write+delete + an audit write), so
 *   fewer, larger windows cut real Firestore-quota cost.
 * - Progress reporting is per-window ([onBytes] fires once a window lands), so
 *   an oversized window on a slow link also makes restore progress look stuck.
 *
 * [nextWindowBytes] adapts the size after every completed window toward
 * [TARGET_WINDOW_SECONDS] of transfer at the just-observed throughput, so a
 * slow link naturally stays small (frequent progress, cheap retries) and a
 * fast one grows toward [MAX_DOWNLOAD_WINDOW_BYTES] (fewer requests) — without
 * ever risking the gateway deadline.
 */
private const val MIN_DOWNLOAD_WINDOW_BYTES = 1 shl 20 // 1 MiB
private const val MAX_DOWNLOAD_WINDOW_BYTES = 16 shl 20 // 16 MiB — ~40s at the ~420 KB/s

// measured on-device rate; the 300s deadline still leaves a >7x margin at that rate.
private const val INITIAL_DOWNLOAD_WINDOW_BYTES = 4 shl 20 // 4 MiB — a mid-range starting

// guess so a fast link converges up and a slow one converges down within a couple windows.
private const val TARGET_WINDOW_SECONDS = 30.0 // ~10x margin under the 300s gateway deadline
private const val MILLIS_PER_SECOND = 1000.0

/**
 * Given the throughput observed on the just-completed window, pick the next
 * window size: `throughput * TARGET_WINDOW_SECONDS`, clamped to
 * [[MIN_DOWNLOAD_WINDOW_BYTES], [MAX_DOWNLOAD_WINDOW_BYTES]] and rounded down to
 * a power of two (Drive chunk-alignment friendly, and avoids oscillating on
 * small throughput jitter between adjacent non-power-of-two sizes).
 */
internal fun nextWindowBytes(bytesInWindow: Long, elapsedMs: Long): Int {
    if (bytesInWindow <= 0L || elapsedMs <= 0L) return INITIAL_DOWNLOAD_WINDOW_BYTES
    val throughputBytesPerSec = bytesInWindow * MILLIS_PER_SECOND / elapsedMs
    val target = (throughputBytesPerSec * TARGET_WINDOW_SECONDS)
        .coerceIn(MIN_DOWNLOAD_WINDOW_BYTES.toDouble(), MAX_DOWNLOAD_WINDOW_BYTES.toDouble())
    // Round down to a power of two >= MIN_DOWNLOAD_WINDOW_BYTES.
    var pow = MIN_DOWNLOAD_WINDOW_BYTES
    while (pow.toLong() * 2 <= target.toLong() && pow < MAX_DOWNLOAD_WINDOW_BYTES) pow *= 2
    return pow
}

/**
 * Direct-to-Drive byte transfer: resumable upload / probe and attested download.
 * Owned by [IndicApi]; not a public entry point.
 */
internal class DriveTransfer(
    private val client: OkHttpClient,
    private val downloadClient: OkHttpClient,
    private val octet: MediaType,
) {

    /**
     * Resumable upload of [file] to a Drive [uploadUrl], in [chunkSize] chunks
     * (multiple of 256 KiB). Resumes from the server offset on reconnect. Bytes
     * go straight to Drive — not through the backend.
     *
     * Returns (driveFileId, md5Hex) where md5 is always the local MD5 of [file]
     * (Drive's completion JSON often omits `md5Checksum` under API v3 partial
     * responses; the backend requires a matching client md5 at `:complete`).
     */
    suspend fun uploadResumable(
        uploadUrl: String,
        file: File,
        chunkSize: Int,
        onBytes: (Long) -> Unit = {},
    ): Pair<String, String> = withContext(Dispatchers.IO) {
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
        probe.result?.let { (driveId, _) ->
            return@withContext driveId to Digests.md5Hex(file)
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
                            val (driveId, _) = IndicApiHttp.parseDriveResult(resp.body.string())
                            return@withContext driveId to Digests.toHex(digest.digest())
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
        val finalized = probeStatus(uploadUrl, total).result
            ?: throw IOException("upload finished without a final Drive response")
        // Digest already covers the whole file from the prefix+chunk updates.
        finalized.first to Digests.toHex(digest.digest())
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

    /** Current state of a resumable session: continue at [offset], or already [result]. */
    private data class UploadProbe(val offset: Long, val result: Pair<String, String?>?)

    /** Ask Drive what it already has: PUT `bytes * /total` with an empty body. */
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
                HttpStatus.OK, HttpStatus.CREATED -> UploadProbe(
                    total,
                    IndicApiHttp.parseDriveResult(resp.body.string()),
                )
                // 404/410 = session expired; start fresh (caller re-inits on retry).
                else -> UploadProbe(0L, null)
            }
        }
    }

    /**
     * GET /v1/files/{id}/content — stream a file back from Drive into [dest].
     * These bytes are proxied by the backend (Drive has no anonymous download),
     * so this is the one path where the backend touches file content.
     *
     * Device-attested like writes: [signedGetHeaders] supplies a fresh nonce +
     * ECDSA over method/path/empty body per attempt. `Range` is an unsigned
     * header (not part of the signed message) so resume offsets can change
     * without rehashing the body.
     *
     * Downloads in bounded, [nextWindowBytes]-adapted windows (`bytes=N-M`), not one
     * open-ended stream — even with the `/content` route's 300s Cloud Run + gateway
     * deadline, a single request for the whole object risks that budget on a slow
     * link. A sibling `.part` file accumulates windows; a failed window resumes from
     * its length.
     *
     * [expectedBytes] is the Firestore-declared size (when known). We never
     * rename `.part` → [dest] until the on-disk length matches that size (or a
     * Content-Range total), so a truncated proxy body cannot become a
     * "successful" corrupt Session.zip (`ZipException: invalid distance…`).
     *
     * [onBytes] receives the cumulative bytes on disk after each successful
     * chunk (and while streaming a full-body 200) so restore UI can show
     * download percent instead of sitting at 0% for the whole Session.zip.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "LongParameterList")
    /**
     * @param rangeStart absolute offset in the remote object that [dest] should begin
     *   at. Non-zero fetches a **window** rather than the whole object — used by
     *   restore to pull a legacy backup's central directory and then only the prefix
     *   of entries it actually needs. [expectedBytes] is then the window's *length*
     *   and is required, since the object's own total no longer describes the target.
     */
    suspend fun downloadFile(
        fileId: String,
        dest: File,
        baseUrl: String,
        expectedBytes: Long = -1L,
        rangeStart: Long = 0L,
        onBytes: suspend (haveBytes: Long) -> Unit = {},
        signedGetHeaders: (path: String) -> Headers,
    ) = withContext(Dispatchers.IO) {
        require(rangeStart >= 0L) { "rangeStart must not be negative" }
        require(rangeStart == 0L || expectedBytes > 0L) {
            "a windowed download must declare its length"
        }
        dest.parentFile?.mkdirs()
        val part = AtomicFiles.partOf(dest)
        // Stale complete from a prior corrupt finalize — always rebuild.
        if (dest.exists()) dest.delete()
        val path = "/v1/files/$fileId/content"
        var attempt = 0
        var reportedTotal = -1L
        // Adapts toward the observed link's throughput after every completed window —
        // see nextWindowBytes' doc. Persists across retries within this call (a single
        // transient failure doesn't mean the link itself got slower).
        var windowBytes = INITIAL_DOWNLOAD_WINDOW_BYTES
        while (true) {
            attempt++
            val offset = if (part.exists()) part.length() else 0L
            if (offset > 0L) onBytes(offset)
            if (
                RestoreDownloadOutcomes.isComplete(
                    haveBytes = offset,
                    expectedBytes = expectedBytes,
                    reportedTotal = reportedTotal,
                )
            ) {
                finalizeDownload(part, dest)
                onBytes(dest.length())
                return@withContext
            }
            try {
                // Fresh challenge per chunk so a resumed Range never replays a nonce.
                val headers = signedGetHeaders(path)
                // `offset` is a position within the window; `remoteOffset` is the
                // absolute position in the remote object, which is what both the wire
                // Range header and the Content-Range response must agree on (checked
                // below). Using window-relative `offset` in the header here was a bug —
                // harmless for a whole-object fetch (rangeStart == 0, so the two
                // coincide) but wrong for any windowed fetch (rangeStart > 0), where it
                // requested `bytes=0-…` instead of the intended window.
                val remoteOffset = rangeStart + offset
                val windowEnd = if (expectedBytes > 0L) rangeStart + expectedBytes - 1 else Long.MAX_VALUE
                val end = minOf(remoteOffset + windowBytes - 1, windowEnd)
                val builder = Request.Builder()
                    .url("$baseUrl$path")
                    .headers(headers)
                    // identity: OkHttp's default Accept-Encoding: gzip + Range
                    // can corrupt binary zips (partial gzip windows inflate to
                    // garbage → ZipException: invalid distance too far back).
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=$remoteOffset-$end")
                    .get()
                val windowStartMs = System.currentTimeMillis()
                downloadClient.newCall(builder.build()).execute().use { resp ->
                    when (resp.code) {
                        HttpStatus.OK -> {
                            // Proxy ignored Range and sent a full-body reply.
                            // Write to a scratch file first — a truncated 200
                            // must not wipe a good partial `.part`.
                            val scratch = AtomicFiles.fullOf(dest)
                            scratch.delete()
                            java.io.FileOutputStream(scratch, false).use { out ->
                                resp.body.byteStream().use { input ->
                                    copyWithProgress(input, out, onBytes)
                                }
                            }
                            val got = scratch.length()
                            // A proxy that ignores Range hands back the whole object.
                            // For a windowed fetch that is still usable — slice out the
                            // window instead of failing and retrying forever.
                            if (rangeStart > 0L || (expectedBytes in 1 until got)) {
                                if (got < rangeStart + expectedBytes) {
                                    scratch.delete()
                                    throw IOException(
                                        "full-body download for $fileId is $got B, " +
                                            "too short for window $rangeStart+$expectedBytes",
                                    )
                                }
                                sliceInPlace(scratch, rangeStart, expectedBytes)
                            }
                            val sliced = scratch.length()
                            if (expectedBytes > 0L && sliced != expectedBytes) {
                                scratch.delete()
                                throw IOException(
                                    "truncated full-body download for $fileId: got $sliced, expected $expectedBytes",
                                )
                            }
                            if (sliced <= 0L) {
                                scratch.delete()
                                throw IOException("empty full-body download for $fileId")
                            }
                            part.delete()
                            AtomicFiles.promote(scratch, part)
                            finalizeDownload(part, dest)
                            onBytes(dest.length())
                            return@withContext
                        }
                        HttpStatus.PARTIAL_CONTENT -> {
                            val range = RestoreDownloadOutcomes.parseContentRange(
                                resp.header("Content-Range"),
                            ) ?: throw IOException(
                                "206 without Content-Range at offset $offset for $fileId",
                            )
                            // Appending a window that does not start where we asked
                            // would splice the wrong bytes into the destination.
                            if (range.start != remoteOffset) {
                                throw IOException(
                                    "Content-Range start ${range.start} != offset $remoteOffset for $fileId",
                                )
                            }
                            // For a windowed fetch the object's total says nothing
                            // about the target length; expectedBytes is authoritative.
                            if (rangeStart == 0L) range.total?.let { reportedTotal = it }
                            val before = offset
                            java.io.FileOutputStream(part, true).use { out ->
                                resp.body.byteStream().use { input ->
                                    input.copyTo(out, DOWNLOAD_COPY_BUFFER)
                                }
                            }
                            val after = part.length()
                            val wrote = after - before
                            if (wrote <= 0L) {
                                throw IOException("empty 206 body at offset $offset for $fileId")
                            }
                            val expectedWrote = range.end - range.start + 1
                            if (wrote != expectedWrote) {
                                // Truncated chunk — rewind to [before] and retry.
                                RandomAccessFile(part, "rw").use { it.setLength(before) }
                                throw IOException(
                                    "short 206 for $fileId: wrote $wrote, Content-Range expected $expectedWrote",
                                )
                            }
                            attempt = 0
                            // Size the *next* window from this one's throughput — see
                            // nextWindowBytes' doc. A short/failed window above never
                            // reaches here, so a transient stall doesn't shrink the
                            // window on bad data.
                            windowBytes = nextWindowBytes(wrote, System.currentTimeMillis() - windowStartMs)
                            onBytes(after)
                            if (
                                RestoreDownloadOutcomes.isComplete(
                                    haveBytes = after,
                                    expectedBytes = expectedBytes,
                                    reportedTotal = reportedTotal,
                                )
                            ) {
                                finalizeDownload(part, dest)
                                onBytes(dest.length())
                                return@withContext
                            }
                        }
                        HttpStatus.RANGE_NOT_SATISFIABLE -> {
                            if (
                                RestoreDownloadOutcomes.isComplete(
                                    haveBytes = offset,
                                    expectedBytes = expectedBytes,
                                    reportedTotal = reportedTotal,
                                )
                            ) {
                                finalizeDownload(part, dest)
                                return@withContext
                            }
                            if (offset > 0L && attempt < DOWNLOAD_MAX_ATTEMPTS) {
                                part.delete()
                                reportedTotal = -1L
                                throw IOException("range_not_satisfiable; restarting $fileId")
                            }
                            throw IndicApiHttp.apiException(resp)
                        }
                        else -> {
                            val body = IndicApiHttp.bodyText(resp)
                            if (ClientNonce.isRefusal(resp.code, body) && ClientNonce.usable()) {
                                // Signed with a client nonce the server would not
                                // take: go back to challenges and re-sign this window.
                                ClientNonce.markRefused()
                                throw IOException("client nonce refused downloading $fileId")
                            }
                            val resume = RestoreDownloadOutcomes.shouldResumeAfterHttp(
                                code = resp.code,
                                attempt = attempt,
                                maxAttempts = DOWNLOAD_MAX_ATTEMPTS,
                            )
                            if (resume) {
                                val preview = body.take(DOWNLOAD_ERROR_BODY_PREVIEW)
                                throw IOException(
                                    "transient HTTP ${resp.code} downloading $fileId" +
                                        if (preview.isNotBlank()) ": $preview" else "",
                                )
                            }
                            throw IndicApi.ApiException(resp.code, body, IndicApiHttp.requestIdOf(resp))
                        }
                    }
                }
            } catch (e: IndicApi.ApiException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= DOWNLOAD_MAX_ATTEMPTS) throw e
                Timber.w(
                    e,
                    "download %s interrupted at %d bytes (attempt %d); resuming",
                    fileId,
                    if (part.exists()) part.length() else 0L,
                    attempt,
                )
            }
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

    private fun finalizeDownload(part: File, dest: File) {
        if (dest.exists() && !dest.delete()) {
            // Size, not the path: it names the user's files, and WARN reaches Crashlytics.
            Timber.w("Could not replace existing download target (%d B)", dest.length())
        }
        AtomicFiles.promote(part, dest)
    }

    /** Copy [input] → [out], reporting cumulative bytes via [onBytes] each buffer. */
    private suspend fun copyWithProgress(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        onBytes: suspend (haveBytes: Long) -> Unit,
    ) {
        val buf = ByteArray(DOWNLOAD_COPY_BUFFER)
        var have = 0L
        var lastReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            have += n
            if (have - lastReport >= DOWNLOAD_COPY_BUFFER) {
                lastReport = have
                onBytes(have)
            }
        }
        onBytes(have)
    }
}
