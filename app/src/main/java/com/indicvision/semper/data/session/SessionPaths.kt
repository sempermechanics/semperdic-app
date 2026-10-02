package com.indicvision.semper.data.session

import com.indicvision.semper.data.DicUploadWorker
import com.indicvision.semper.data.cloud.restore.CloudRestore
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

    /** Session-dir subfolder holding the persisted raw deformed originals. */
    const val RAW_DEFORMED_SUBDIR = "raw_deformed"

    /** Optional heatmaps / animation trees kept under the session or staging. */
    const val PROCESSED_SUBDIR = "processed"

    /** Transient upload pack folder under the session dir. */
    const val UPLOAD_STAGING_SUBDIR = "upload_staging"

    /** Engine result filename pattern under a session dir (`frame_0000.dat`). */
    const val FRAME_DAT_FMT = "frame_%04d.dat"

    fun frameDatName(index: Int): String = String.format(Locale.US, FRAME_DAT_FMT, index)

    fun frameDat(sessionDir: File, index: Int): File = File(sessionDir, frameDatName(index))

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
}
