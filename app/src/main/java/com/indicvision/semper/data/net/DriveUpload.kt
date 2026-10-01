package com.indicvision.semper.data.net

/**
 * A finished Drive resumable upload: the [driveFileId] Drive assigned and the
 * [md5Hex] of the local file, which the backend's `:complete` checks against
 * Drive's own checksum.
 *
 * `CloudApi.uploadResumable` / `IndicApi` / `DriveTransfer` return this today
 * as `Pair<String, String>` (driveFileId, md5Hex), destructured by
 * `DicUploadWorker` into a `FileCompleteRequest`. The property order matches
 * the `Pair`, so `val (driveId, md5) = ...` reads the same after adoption.
 */
data class DriveUpload(val driveFileId: String, val md5Hex: String) {

    /** `driveFileId to md5Hex`, the form `uploadResumable` returns today. */
    fun toPair(): Pair<String, String> = driveFileId to md5Hex

    companion object {
        /** From `uploadResumable`'s `(driveFileId, md5Hex)`. */
        fun of(pair: Pair<String, String>): DriveUpload = DriveUpload(pair.first, pair.second)
    }
}
