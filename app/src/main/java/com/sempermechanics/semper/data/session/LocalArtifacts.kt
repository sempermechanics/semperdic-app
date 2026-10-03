package com.sempermechanics.semper.data.session

import java.io.File

/**
 * Which of a session directory's entries are the heavy, re-downloadable
 * artifacts that "Free up space" drops: `.dat` frames, raw images, processed
 * and upload-staging trees. Everything else — `reference.png` (the Home
 * thumbnail), `metadata.json`, other small files — stays.
 *
 * One rule for both the drop ([SessionStore.dropLocalArtifacts]) and the
 * Storage screen's preview ([StorageBudget.reclaimableBytes]), so the preview
 * can never promise bytes the drop keeps.
 */
internal object LocalArtifacts {

    private val DROPPED_SUBDIRS = setOf(
        SessionPaths.RAW_DEFORMED_SUBDIR,
        SessionPaths.PROCESSED_SUBDIR,
        SessionPaths.UPLOAD_STAGING_SUBDIR,
    )

    /** Whether [child], a direct entry of a session directory, is dropped. */
    fun isDropped(child: File): Boolean = when {
        child.isFile -> child.extension.equals("dat", ignoreCase = true)
        child.isDirectory -> child.name in DROPPED_SUBDIRS
        else -> false
    }

    /** The entries of [sessionDir] a drop removes. */
    fun droppedIn(sessionDir: File): List<File> = sessionDir.listFiles()?.filter(::isDropped).orEmpty()

    /** Bytes a drop of [sessionDir] would free. */
    fun droppedBytes(sessionDir: File): Long = droppedIn(sessionDir).sumOf { CacheJanitor.sizeOf(it) }
}
