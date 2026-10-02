package com.indicvision.semper.data.net.drive

import com.indicvision.semper.data.net.IndicApi
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.OkHttpClient
import java.io.File

/**
 * Direct-to-Drive byte transfer: resumable upload / probe ([DriveUploader]) and
 * attested download ([DriveDownloader]). Owned by [IndicApi]; not a public
 * entry point.
 */
internal class DriveTransfer(
    client: OkHttpClient,
    downloadClient: OkHttpClient,
    octet: MediaType,
) {
    private val uploader = DriveUploader(client, octet)
    private val downloader = DriveDownloader(downloadClient)

    /** See [DriveUploader.uploadResumable]. */
    suspend fun uploadResumable(
        uploadUrl: String,
        file: File,
        chunkSize: Int,
        onBytes: (Long) -> Unit = {},
    ): DriveUpload = uploader.uploadResumable(uploadUrl, file, chunkSize, onBytes)

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
     *
     * @param rangeStart absolute offset in the remote object that [dest] should begin
     *   at. Non-zero fetches a **window** rather than the whole object — used by
     *   restore to pull a legacy backup's central directory and then only the prefix
     *   of entries it actually needs. [expectedBytes] is then the window's *length*
     *   and is required, since the object's own total no longer describes the target.
     */
    @Suppress("LongParameterList") // the call's shape; all but the first three and the signer are defaulted
    suspend fun downloadFile(
        fileId: String,
        dest: File,
        baseUrl: String,
        expectedBytes: Long = -1L,
        rangeStart: Long = 0L,
        onBytes: suspend (haveBytes: Long) -> Unit = {},
        signedGetHeaders: (path: String) -> Headers,
    ) = downloader.download(
        DriveDownload(fileId, dest, baseUrl, expectedBytes, rangeStart, onBytes),
        signedGetHeaders,
    )
}
