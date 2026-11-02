package com.sempermechanics.semper.data.session

import com.sempermechanics.semper.data.DicUploadWorker
import com.sempermechanics.semper.data.cloud.restore.CloudRestore
import java.io.File
import java.util.Locale

/**
 * On-disk path segments shared by session writers (AnalysisViewModel),
 * upload ([DicUploadWorker]), and restore ([CloudRestore]).
 */
object SessionPaths {
    /** `filesDir` subfolder holding every session directory and the index. */
    const val SESSIONS_ROOT = "sessions"

    /** The session index behind the Home list, in [SESSIONS_ROOT] (`SessionStore`). */
    const val INDEX_JSON = "index.json"

    /** What a session id may be: it names the session's directory, so never `.`, `/` or `\`. */
    private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")

    /**
     * Whether [id] is safe to name a session directory with: 1–128 ASCII
     * letters, digits, `-` or `_`, as every id the app mints is (a UUID
     * prefix, `restored-…`). A backup's `localSessionId` comes from the
     * server, so a restore checks it here before it names a row or a
     * directory: `..` would make the sessions root's parent the session dir,
     * and a failed restore deletes that (L5-1). The backend's `SessionCreate`
     * allows the same characters.
     */
    fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)

    /** [id] cut down to the characters [isSafeId] allows (the length is left to the caller). */
    fun safeIdChars(id: String): String =
        id.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    /** Session-dir subfolder holding the persisted raw deformed originals. */
    const val RAW_DEFORMED_SUBDIR = "raw_deformed"

    /** Optional heatmaps / animation trees kept under the session or staging. */
    const val PROCESSED_SUBDIR = "processed"

    /** Transient upload pack folder under the session dir. */
    const val UPLOAD_STAGING_SUBDIR = "upload_staging"

    /**
     * Where a run writes its `.dat` results (and nothing else) until it ends,
     * under the session dir. A run that ends any way but a cancel moves them up
     * into the session dir; a cancelled one deletes this folder, so the
     * session's previous results are never touched by it.
     */
    const val RUN_STAGING_SUBDIR = "run_staging"

    /**
     * What Home's Re-run starts a new analysis from, under the session dir:
     * the run's inputs as the wizard held them, its original reference and
     * mask, and the frames the run never reached (`SessionLayout.rerun*`).
     * Kept on this phone only: never uploaded, so never restored.
     */
    const val RERUN_SUBDIR = "rerun"

    /** [RUN_STAGING_SUBDIR] of [sessionDir]. */
    fun runStaging(sessionDir: File): File = File(sessionDir, RUN_STAGING_SUBDIR)

    /** Engine result filename pattern under a session dir (`frame_0000.dat`). */
    const val FRAME_DAT_FMT = "frame_%04d.dat"

    fun frameDatName(index: Int): String = String.format(Locale.US, FRAME_DAT_FMT, index)

    fun frameDat(sessionDir: File, index: Int): File = File(sessionDir, frameDatName(index))

    /**
     * Home's result thumbnail: the last frame's U field, rendered once and
     * cached here. Derived, never uploaded or restored, and stale once a frame
     * `.dat` is newer than it (a re-run or a restore rewrote the frames).
     */
    const val RESULT_THUMB_PNG = "thumb_result.png"

    fun resultThumb(sessionDir: File): File = File(sessionDir, RESULT_THUMB_PNG)

    private val FRAME_DAT_NAME = Regex("""frame_(\d+)\.dat""")

    /**
     * The planned frame index a `frame_%04d.dat` name was written for, or null.
     * A frame the batch skipped leaves a gap in the numbering, so the position
     * of a file in the sorted listing is not its index once one has.
     */
    fun frameIndexOf(name: String): Int? =
        FRAME_DAT_NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The planned frame index behind each `.dat` of a sorted listing, by
     * position. A name that does not parse keeps its position. Everything that
     * names a frame (viewer, share, reports) looks its name up by this index
     * (`ReportImageNames.frameName`), as the cloud bundle does, so the two
     * agree past a skipped frame.
     */
    fun plannedFrameIndices(datFiles: List<File>): List<Int> =
        datFiles.mapIndexed { position, file -> frameIndexOf(file.name) ?: position }

    /**
     * True when [file] belongs to a session other than [sessionDir]: it is
     * under the sessions root ([sessionDir]'s parent, as `SessionStore.dirFor`
     * makes it) but not under [sessionDir]. A run must copy such an image,
     * never move it: it is that other session's own input, which the wizard
     * reaches after a run of the other kind (single and sweep never share a
     * session).
     */
    fun isInOtherSession(file: File, sessionDir: File): Boolean {
        val own = sessionDir.absoluteFile.normalize()
        val root = own.parentFile ?: return false
        val path = file.absoluteFile.normalize().path
        fun isUnder(dir: File) = path.startsWith(dir.path + File.separator)
        return isUnder(root) && !isUnder(own)
    }
}
