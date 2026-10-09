package com.sempermechanics.semper.ui.common.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.WorkerThread

/** What a picked image is called when its provider gives no name. */
private const val UNNAMED_IMAGE = "Image_File"

/**
 * A picked file's display name, e.g. `tensile_03.mp4`; null when the provider
 * gives none (a `file:` Uri, or a provider without the column). A
 * content-provider query, so off the main thread.
 */
@WorkerThread
fun displayNameOrNull(resolver: ContentResolver, uri: Uri): String? {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    return resolver.query(uri, null, null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }?.takeIf { it.isNotBlank() }
}

/** A picked image's [displayNameOrNull], else "Image_File". */
@WorkerThread
fun displayNameOf(resolver: ContentResolver, uri: Uri): String = displayNameOrNull(resolver, uri) ?: UNNAMED_IMAGE
