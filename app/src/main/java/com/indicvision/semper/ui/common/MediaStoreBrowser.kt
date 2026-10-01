package com.indicvision.semper.ui.common

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat

/**
 * MediaStore listing for the in-sheet picker. Permissions are requested by the
 * host Activity; this only queries.
 */
object MediaStoreBrowser {

    data class Item(
        val id: Long,
        val uri: Uri,
        val name: String,
        val mime: String,
        val isVideo: Boolean,
    )

    fun permissions(sdk: Int, includeVideo: Boolean): Array<String> =
        if (sdk >= SDK_READ_MEDIA) {
            buildList {
                add("android.permission.READ_MEDIA_IMAGES")
                if (includeVideo) add("android.permission.READ_MEDIA_VIDEO")
            }.toTypedArray()
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasReadAccess(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= SDK_READ_MEDIA) {
            "android.permission.READ_MEDIA_IMAGES"
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** A content-resolver query over the gallery: call it off the main thread. */
    @WorkerThread
    fun query(
        context: Context,
        includeVideo: Boolean,
        limit: Int = MAX_ITEMS,
    ): List<Item> {
        val (selection, selectionArgs) = filter(includeVideo)
        val out = ArrayList<Item>(limit)
        context.contentResolver.query(
            collectionUri(),
            PROJECTION,
            selection,
            selectionArgs,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { cursor -> readRows(cursor, limit, out) }
        return out
    }

    private fun collectionUri(): Uri =
        if (Build.VERSION.SDK_INT >= SDK_RELATIVE_PATH) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

    private fun filter(includeVideo: Boolean): Pair<String, Array<String>> {
        val typeClause = if (includeVideo) {
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)"
        } else {
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        }
        val args = buildList {
            add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            if (includeVideo) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
        }
        return typeClause to args.toTypedArray()
    }

    private fun readRows(
        cursor: Cursor,
        limit: Int,
        out: MutableList<Item>,
    ) {
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
        val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        while (cursor.moveToNext() && out.size < limit) {
            val id = cursor.getLong(idCol)
            val isVideo = cursor.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            out.add(
                Item(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    name = cursor.getString(nameCol).orEmpty(),
                    mime = cursor.getString(mimeCol).orEmpty(),
                    isVideo = isVideo,
                ),
            )
        }
    }

    private val PROJECTION = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
    )
    private const val MAX_ITEMS = 400
    private const val SDK_READ_MEDIA = 33
    private const val SDK_RELATIVE_PATH = 29
}
