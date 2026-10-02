package com.indicvision.semper.data.cloud.restore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import timber.log.Timber
import java.io.File

/**
 * The document a Save-to-Files download writes: [uri], created by the user's
 * pick before the work was queued, with a persistable write grant. Each
 * operation logs its own failure rather than throwing; the worker decides.
 */
internal class SafDestination(private val context: Context, val uri: Uri) {

    /** Copies [file] into the document; false when nothing could be written. */
    fun write(file: File): Boolean =
        runCatching {
            val copied = context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: 0L
            copied > 0L
        }.onFailure { Timber.e(it, "Write Session.zip to destination failed") }
            .getOrDefault(false)

    /** Removes the document, so a failed download leaves no empty file behind. */
    fun delete() {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            .onFailure { Timber.w(it, "Could not delete empty destination document") }
    }

    /** Gives up the persistable grant once no attempt will write here again. */
    fun releaseGrant() {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not release destination URI grant") }
    }
}
