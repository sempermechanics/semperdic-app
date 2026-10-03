package com.sempermechanics.semper.data.session

import com.sempermechanics.semper.util.Digests
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The names an analysis is given: its auto-name, and its name inside file and archive names. */
object SessionNaming {

    /** A run of characters a file or entry name must not carry. */
    private val UNSAFE_RUN = Regex("[^A-Za-z0-9._-]+")

    /** File and entry names stay readable and well clear of any path limit. */
    private const val MAX_NAME_CHARS = 40

    /** How much of a session id an export entry name keeps. */
    private const val ID_CHARS = 8

    /** Hex digits of a backup id's hash in a cache file name: 48 bits, ample for one phone's backups. */
    private const val CACHE_ID_HEX = 12

    /**
     * The name a run gives its session: the reference's base name and the
     * session's creation time, e.g. `specimen · Sep 30, 14:02:11`.
     */
    fun defaultSessionName(refFileName: String, createdAt: Long): String {
        val base = refFileName.substringBeforeLast('.').ifBlank { "Analysis" }
        val stamp = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(createdAt))
        return "$base · $stamp"
    }

    /**
     * [name] made safe for a file or archive entry name: each run of characters
     * outside `A-Za-z0-9._-` becomes one `_`, edge `_`s are trimmed, [fallback]
     * stands in when nothing is left, and the result is at most 40 characters.
     */
    fun fileSafe(name: String, fallback: String): String =
        name.replace(UNSAFE_RUN, "_").trim('_').ifBlank { fallback }.take(MAX_NAME_CHARS)

    /** A Save-to-Files name for analysis [displayName]'s `Session.zip`. */
    fun bundleFileName(displayName: String): String = "${fileSafe(displayName, "analysis")}_Session.zip"

    /**
     * The cache file a Save-to-Files download of backup [cloudSessionId] is built in:
     * [bundleFileName] behind a short hash of the id, so two backups with the same
     * display name never share (or delete) one file. Not a persisted name.
     */
    fun bundleCacheFileName(displayName: String, cloudSessionId: String): String {
        val idHash = Digests.toHex(Digests.sha256(cloudSessionId.toByteArray(Charsets.UTF_8))).take(CACHE_ID_HEX)
        return "${idHash}_${bundleFileName(displayName)}"
    }

    /** The name of session [id]'s archive inside a master export, without its `.zip`. */
    fun exportEntryName(name: String, id: String): String = fileSafe(name, "session") + "_" + id.take(ID_CHARS)
}

/**
 * The name frame [index] was picked as, or [fallback] when none is known. A
 * restored draft pads a frame with no recorded name with "", so a blank name
 * counts as none.
 */
internal fun List<String>.originalNameOr(index: Int, fallback: String): String =
    getOrNull(index)?.takeIf { it.isNotBlank() } ?: fallback
