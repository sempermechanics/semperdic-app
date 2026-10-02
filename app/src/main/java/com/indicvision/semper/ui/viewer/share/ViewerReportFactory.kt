package com.indicvision.semper.ui.viewer.share

import android.graphics.Bitmap
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.field.FrameParams
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.Roi
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.ReportData
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import com.indicvision.semper.ui.viewer.ViewerArgs
import java.io.File

/**
 * Builds [ReportData] for the current (or a given) frame. Frame-varying fields
 * — step, subset, strain window, deformed name — are read by index so an
 * all-frames report describes each frame correctly.
 */
object ViewerReportFactory {

    /**
     * Everything a report page reads from the viewer, captured on the main
     * thread. Plain data: an export that outlives a rotation builds its pages
     * from this, never from the destroyed viewer.
     */
    data class Source(
        val args: ViewerArgs,
        /** The reference's true size; the viewer reads it from the header when [args] lack it. */
        val imageSize: ImageSize,
        /** The planned frame behind each `.dat` on disk, by position. */
        val plannedFrames: List<Int>,
        val defImagePaths: List<String>,
        /** The viewer's display-size reference, for when the reference file is gone. Never recycled by it. */
        val displayBase: Bitmap?,
    ) {
        val isSweep: Boolean get() = args.sweep != null

        /** Every frame's solver parameters. */
        /** Read from [args] once per source: every page and CSV row of an export asks for it. */
        val frameParams: FrameParams = args.frameParams

        val roi: Roi get() = args.roi

        /** Frame names (a sweep's combination labels) in planned-frame order. */
        val frameNames: List<String> get() = args.frameNames

        /** The planned frame behind the frame at [position]; see [ResultViewerActivity.plannedFrameIndex]. */
        fun plannedAt(position: Int): Int = plannedFrames.getOrElse(position) { position }

        /** This frame's own photo on disk, or null; see [deformedImagePath]. */
        fun deformedImagePathAt(position: Int): String? =
            deformedImagePath(args, isSweep, defImagePaths, frameNames, plannedAt(position))
    }

    /**
     * The deformed image solved at a frame whose planned index is [planned], or
     * null when it is not on disk. Every node of a sweep solves the one deformed
     * image. A batch looks its frame up by the name the run persisted it under,
     * since `raw_deformed/` keeps the user's own file names and sorts them
     * alphabetically, not in frame order. Disk reads: off the main thread.
     */
    internal fun deformedImagePath(
        args: ViewerArgs,
        isSweep: Boolean,
        defImagePaths: List<String>,
        frameNames: List<String>,
        planned: Int,
    ): String? {
        if (isSweep) return defImagePaths.firstOrNull()
        val rawDir = args.batchDirPath?.let { File(it, SessionPaths.RAW_DEFORMED_SUBDIR) }
        val persisted = frameNames.getOrNull(planned)?.let { name -> rawDir?.let { File(it, name) } }
        return persisted?.takeIf { it.isFile }?.absolutePath
            ?: args.defFilePaths.getOrNull(planned)?.takeIf { File(it).isFile }
    }

    fun buildReportData(
        source: Source,
        frameIndex: Int,
        data: FloatArray,
    ): ReportData? {
        // buildReport downscales every cover to 600 px, so a full-resolution decode of
        // a 26 MP reference (~104 MB, and once per frame in an all-frames report) is
        // pure waste. Decode no larger than REPORT_MAX_EDGE — the report's own render
        // cap — via inSampleSize, so peak stays a few MB.
        val size = source.imageSize
        val cap = VisualizationEngine.REPORT_MAX_EDGE
        val capped = VisualizationEngine.cappedDims(size.width, size.height, cap)
        fun decodeCapped(path: String): Bitmap? = BitmapDecode.decodeFileForView(
            path,
            capped.width,
            capped.height,
            cap,
            rawWidth = size.width,
            rawHeight = size.height,
        )
        val decodedBase = source.args.refPath.ifBlank { null }?.let(::decodeCapped)
        // Without a reference file, the viewer's display-size reference stands in
        // as it is: buildReport scales whatever it is given into its capped
        // composite and keeps only 600 px copies, so scaling it up first (it was
        // scaled to the full sensor size, ~104 MB at 26 MP) bought nothing.
        val baseImg = decodedBase ?: source.displayBase ?: return null

        // buildReport keeps only a downscaled copy of the cover images, so the
        // decodes here are ours to free — on a throw too, since an all-frames
        // report calls this once per frame.
        var defImg: Bitmap? = null
        try {
            // This frame's own image. It used to be the viewer's opening one for
            // every page — the first frame after a run, the reference from Home —
            // under each frame's own "Def:" name.
            defImg = source.deformedImagePathAt(frameIndex)?.let(::decodeCapped)
            // Named from the same planned frame as the cover image, so the two
            // agree past a frame the batch skipped. A sweep's names are its
            // combination labels, one per node.
            val params = source.toReportSource().forFrame(
                frameIndex,
                data,
                baseImg,
                defImg ?: baseImg,
                nameIndex = source.nameIndexAt(frameIndex),
            )
            return ReportBuilder.buildReport(params)
        } finally {
            defImg?.recycle()
            decodedBase?.recycle()
        }
    }
}
