package com.sempermechanics.semper.util

import java.io.File

/**
 * Write-then-move for files a reader must never see half written.
 *
 * A download, an export or an index is written to a sidecar first and moved
 * onto its real name only once complete, so a crash or cancellation leaves a
 * sidecar to clean up rather than a truncated file that looks finished. The
 * idiom was spelled out at each site, and so were the sidecar names — rename
 * one and every other path that cleans it up silently stops (FI-14).
 */
object AtomicFiles {
    /** Where a download or encode is written before it is moved into place. */
    const val PART_SUFFIX = ".part"

    /** Scratch body of a whole-file (non-ranged) download, moved onto [PART_SUFFIX]. */
    const val FULL_SUFFIX = ".full"

    fun partOf(dest: File): File = File(dest.parentFile, dest.name + PART_SUFFIX)

    fun fullOf(dest: File): File = File(dest.parentFile, dest.name + FULL_SUFFIX)

    /** Removes whatever an interrupted transfer into [dest] left beside it. */
    fun deleteSidecars(dest: File) {
        partOf(dest).delete()
        fullOf(dest).delete()
    }

    /**
     * Moves [tmp] onto [dest]: a rename, which replaces [dest] in one step on
     * the same volume, or a copy and delete when the rename is refused (the
     * rare cross-volume case). [dest] is not deleted first — a caller whose
     * [dest] must never be missing (the session index) relies on the rename
     * replacing it.
     */
    fun promote(tmp: File, dest: File) {
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }
}
