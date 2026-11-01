package com.sempermechanics.semper.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.WorkerThread
import timber.log.Timber
import java.io.File

/**
 * The "Save to Files" write: a finished export copied into the document the
 * user picked through the Storage Access Framework. The viewer's save-as job
 * and the share sheet's Save-to-Files proxy both write through it, and the
 * restore download and the save-as job delete an unfilled document through
 * [discard].
 */
object SafCopy {

    /**
     * Copies [file] into the document at [uri]. True only when bytes landed:
     * a provider that opens an empty stream, or none, has not saved anything.
     * A failure is logged and reads false. Blocking; never on the main thread.
     */
    @WorkerThread
    fun copy(resolver: ContentResolver, uri: Uri, file: File): Boolean =
        runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: 0L
        }.onFailure { Timber.e(it, "Save to Files failed") }.getOrDefault(0L) > 0L

    /**
     * Deletes the document at [uri], so a save that wrote nothing (cancelled,
     * failed or empty) leaves no empty file where the user picked. A failure is
     * logged only. Blocking; never on the main thread.
     */
    @WorkerThread
    fun discard(resolver: ContentResolver, uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            .onFailure { Timber.w(it, "Could not delete the unfilled document") }
    }
}
