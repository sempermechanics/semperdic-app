package com.indicvision.semper.data.net

/**
 * A finished Drive resumable upload: the [driveFileId] Drive assigned and the
 * [md5Hex] of the local file, which the backend's `:complete` checks against
 * Drive's own checksum.
 *
 * [DriveTransfer] returns this. `CloudApi.uploadResumable` still returns the
 * `Pair<String, String>` ([toPair]) that `DicUploadWorker` destructures; the
 * property order matches it, so `val (driveId, md5) = ...` reads the same
 * when that signature moves over.
 */
data class DriveUpload(val driveFileId: String, val md5Hex: String) {

    /** `driveFileId to md5Hex`, the form `uploadResumable` returns today. */
    fun toPair(): Pair<String, String> = driveFileId to md5Hex

    companion object {
        /** From `uploadResumable`'s `(driveFileId, md5Hex)`. */
        fun of(pair: Pair<String, String>): DriveUpload = DriveUpload(pair.first, pair.second)
    }
}
