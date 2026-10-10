package com.sempermechanics.semper.data.session

import com.sempermechanics.semper.util.Digests

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
     * The extensions an auto-name drops: the image and video files a run can
     * start from. Any other dotted tail (`tensile.v2`) is part of the name.
     */
    private val MEDIA_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "jpe", "jfif", "tif", "tiff", "bmp", "webp", "gif", "heic", "heif",
        "dng", "raw", "pgm", "ppm", "pnm", "mp4", "m4v", "mov", "3gp", "3gpp", "mkv", "webm", "avi",
    )

    /** A name's ` (n)` suffix, as [uniqueName] adds it. */
    private val NUMBER_SUFFIX = Regex("""^(.+) \((\d+)\)$""")

    /**
     * The name a run gives its session: the reference's name without its image
     * extension ([withoutMediaExtension]), e.g. `specimen`. The Home row already
     * shows the date, so the name does not repeat it; a name another session
     * already has ([taken]) gets the first free ` (2)`, ` (3)`, … suffix ([uniqueName]).
     */
    fun defaultSessionName(refFileName: String, taken: Collection<String>): String =
        uniqueName(withoutMediaExtension(refFileName).trim().ifBlank { "Analysis" }, taken)

    /**
     * A run's auto-name: [defaultSessionName] of its reference [refName],
     * except when the reference is the first frame of video [videoName]. A
     * clip's name ([clipName]) has no extension left to drop, so it is taken whole.
     */
    fun runSessionName(refName: String, videoName: String?, taken: Collection<String>): String =
        if (videoName != null && videoName == refName) {
            uniqueName(videoName, taken)
        } else {
            defaultSessionName(refName, taken)
        }

    /**
     * A video analysis's name from its clip's display name: `tensile_03.mp4`
     * reads `tensile_03`. Null when the clip has no usable name; the caller
     * then says "Video".
     */
    fun clipName(displayName: String?): String? =
        displayName?.let(::withoutMediaExtension)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * [fileName] without an image or video extension, in any case:
     * `steel_00.png` reads `steel_00`, `tensile.v2.mp4` reads `tensile.v2`.
     * A dotted tail that is not one (`tensile.v2`) stays, as does a name
     * with no dot.
     */
    fun withoutMediaExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0) return fileName
        val extension = fileName.substring(dot + 1).lowercase()
        return if (extension in MEDIA_EXTENSIONS) fileName.substring(0, dot) else fileName
    }

    /**
     * [base] itself when no name in [taken] is it, else the first free
     * `base (n)`, n ≥ 2. A [base] that already ends in ` (n)` (a rename to
     * `steel_00 (2)`, or two saves racing for it) counts on from its stem:
     * `steel_00 (3)`, never `steel_00 (2) (2)`.
     */
    fun uniqueName(base: String, taken: Collection<String>): String {
        if (base !in taken) return base
        val stem = NUMBER_SUFFIX.matchEntire(base)?.groupValues?.get(1) ?: base
        var n = 2
        while ("$stem ($n)" in taken) n++
        return "$stem ($n)"
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
