// Report assembly maps many result fields and engine-stat indices into the
// report model; the literal indices/constants read clearest inline.
@file:Suppress("CyclomaticComplexMethod", "MagicNumber")

package com.indicvision.semper.ui.viewer

import android.graphics.Bitmap
import com.indicvision.semper.data.SessionPaths
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.ReportData
import com.indicvision.semper.report.ReportImageNames
import com.indicvision.semper.report.RoiData
import com.indicvision.semper.report.VisualizationEngine
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
    @Suppress("LongParameterList") // a plain value holder, built in one place
    class Source(
        val args: ViewerArgs,
        val imgW: Int,
        val imgH: Int,
        val baseStep: Int,
        val sweepSteps: IntArray?,
        val sweepSubsets: IntArray?,
        val sweepStrainWins: IntArray?,
        val roi: RoiData,
        val frameNames: List<String>,
        val plannedFrames: List<Int>,
        val defImagePaths: List<String>,
        /** The viewer's display-size reference, for when the reference file is gone. Never recycled by it. */
        val displayBase: Bitmap?,
    ) {
        val isSweep: Boolean get() = sweepSteps != null

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
        val cap = VisualizationEngine.REPORT_MAX_EDGE
        val (capW, capH) = VisualizationEngine.cappedDims(source.imgW, source.imgH, cap)
        val refPath = source.args.refPath.ifBlank { null }
        val decodedCapped = refPath?.let {
            BitmapDecode.decodeFileForView(
                it,
                capW,
                capH,
                cap,
                rawWidth = source.imgW,
                rawHeight = source.imgH,
            )
        }
        // Without a reference file, the viewer's display-size reference stands in
        // as it is: buildReport scales whatever it is given into its capped
        // composite and keeps only 600 px copies, so scaling it up first (it was
        // scaled to the full sensor size, ~104 MB at 26 MP) bought nothing.
        val baseImg = decodedCapped ?: source.displayBase ?: return null
        val ownsBase = baseImg === decodedCapped

        val frameStep = source.sweepSteps?.getOrNull(frameIndex) ?: source.baseStep
        val frameSubset = source.sweepSubsets?.getOrNull(frameIndex)
            ?: source.args.subsetSize
        val frameStrainWin = source.sweepStrainWins?.getOrNull(frameIndex)
            ?: source.args.strainWindow

        val statsArray = source.args.engineStatsArray() ?: FloatArray(16)
        val engineStats = if (statsArray.size >= 16) {
            EngineStats.fromArray(statsArray)
        } else {
            EngineStats(0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        // buildReport keeps only a downscaled copy of the cover images, so the
        // decodes here are ours to free — on a throw too, since an all-frames
        // report calls this once per frame.
        var realDefImg: Bitmap? = null
        try {
            // This frame's own image. It used to be the viewer's opening one for
            // every page — the first frame after a run, the reference from Home —
            // under each frame's own "Def:" name.
            val defImg = source.deformedImagePathAt(frameIndex)?.let {
                BitmapDecode.decodeFileForView(
                    it,
                    capW,
                    capH,
                    cap,
                    rawWidth = source.imgW,
                    rawHeight = source.imgH,
                )
            } ?: baseImg
            realDefImg = defImg

            return buildReportWith(
                source = source,
                data = data,
                baseImg = baseImg,
                realDefImg = defImg,
                frameIndex = frameIndex,
                frameStep = frameStep,
                frameSubset = frameSubset,
                frameStrainWin = frameStrainWin,
                engineStats = engineStats,
            )
        } finally {
            realDefImg?.takeIf { it !== baseImg }?.recycle()
            if (ownsBase) baseImg.recycle()
        }
    }

    @Suppress("LongParameterList") // one call site; all of it is per-frame state
    fun buildReportWith(
        source: Source,
        data: FloatArray,
        baseImg: Bitmap,
        realDefImg: Bitmap,
        frameIndex: Int,
        frameStep: Int,
        frameSubset: Int,
        frameStrainWin: Int,
        engineStats: EngineStats,
    ): ReportData {
        val args = source.args
        return ReportBuilder.buildReport(
            ReportBuilder.ReportBuildParams(
                data = data,
                baseImg = baseImg,
                defImgForCover = realDefImg,
                imgW = source.imgW,
                imgH = source.imgH,
                step = frameStep,
                sessionId = args.sessionId ?: "Local_Offline_Mode",
                specimenName = ReportImageNames.specimen(args.refName),
                analysisDate = ReportBuilder.currentAnalysisDate(),
                subsetSize = frameSubset,
                strainWindow = frameStrainWin,
                strainMethod = args.strainMethod,
                roiData = source.roi,
                engineStats = engineStats,
                referenceImageName = ReportImageNames.reference(args.refName),
                // Named from the same planned frame as the cover image, so the
                // two agree past a frame the batch skipped. A sweep's names are
                // its combination labels, one per node.
                deformedImageName = ReportImageNames.deformed(
                    source.frameNames,
                    if (source.isSweep) frameIndex else source.plannedAt(frameIndex),
                ),
            ),
        )
    }
}
