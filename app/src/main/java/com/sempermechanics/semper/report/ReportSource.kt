package com.sempermechanics.semper.report

import android.graphics.Bitmap
import com.sempermechanics.semper.data.session.SessionRecord

/**
 * The per-session half of a report's inputs: what every frame's
 * [ReportBuilder.ReportBuildParams] shares, plus the per-frame settings a
 * sweep varies. [forFrame] adds the frame's own data and images.
 *
 * Two places assembled the same params by hand: the cloud bundle
 * ([forRecord], from a [SessionRecord]) and the viewer's report
 * (`ViewerReportFactory`, from its `Source`). They differ only in the values
 * they feed in, which is what the two factories capture.
 *
 * The per-frame lists are empty for an ordinary analysis; a frame past the
 * end of one uses the session value, as both callers did.
 */
data class ReportSource(
    val sessionId: String,
    /** The reference as the user named it; the report derives the specimen from it. */
    val refName: String,
    /** The names a frame's report prints, looked up by [forFrame]'s `nameIndex`. */
    val frameNames: List<String>,
    val imgW: Int,
    val imgH: Int,
    val roi: RoiData,
    val strainMethod: String,
    val subset: Int,
    val step: Int,
    val strainWindow: Int,
    val subsetPerFrame: List<Int> = emptyList(),
    val stepPerFrame: List<Int> = emptyList(),
    val strainWindowPerFrame: List<Int> = emptyList(),
    val engineStats: EngineStats = EngineStats.EMPTY,
    /** The viewer's PDFs draw the MIN marker too; the cloud bundle's draw MAX only. */
    val drawMinMarker: Boolean = true,
) {
    fun subsetAt(frameIndex: Int): Int = subsetPerFrame.getOrElse(frameIndex) { subset }

    fun stepAt(frameIndex: Int): Int = stepPerFrame.getOrElse(frameIndex) { step }

    fun strainWindowAt(frameIndex: Int): Int = strainWindowPerFrame.getOrElse(frameIndex) { strainWindow }

    /**
     * Frame [frameIndex]'s build params. [nameIndex] picks its printed name
     * from [frameNames]: the viewer passes the planned frame (they differ past
     * a skipped frame), a sweep and the cloud bundle the frame itself.
     * [analysisDate] defaults to now, as both callers stamp it.
     */
    @Suppress("LongParameterList") // the frame's own inputs; the rest is this source
    fun forFrame(
        frameIndex: Int,
        data: FloatArray,
        baseImg: Bitmap,
        defImgForCover: Bitmap,
        nameIndex: Int = frameIndex,
        analysisDate: String = ReportBuilder.currentAnalysisDate(),
    ): ReportBuilder.ReportBuildParams = ReportBuilder.ReportBuildParams(
        data = data,
        baseImg = baseImg,
        defImgForCover = defImgForCover,
        imgW = imgW,
        imgH = imgH,
        step = stepAt(frameIndex),
        sessionId = sessionId,
        specimenName = ReportImageNames.specimen(refName),
        analysisDate = analysisDate,
        subsetSize = subsetAt(frameIndex),
        strainWindow = strainWindowAt(frameIndex),
        strainMethod = strainMethod,
        roiData = roi,
        engineStats = engineStats,
        referenceImageName = ReportImageNames.reference(refName),
        deformedImageName = ReportImageNames.deformed(frameNames, nameIndex),
        drawMinMarker = drawMinMarker,
    )

    companion object {
        /** The viewer's session id when the analysis has none (never synced, or run offline). */
        const val OFFLINE_SESSION_ID = "Local_Offline_Mode"

        /** The strain method a record that stored none was run with. */
        const val DEFAULT_STRAIN_METHOD = "VSG"

        /** The cloud bundle's source (`SessionUploadBundler.renderFrame`). */
        fun forRecord(record: SessionRecord): ReportSource = ReportSource(
            sessionId = record.id,
            refName = record.refName,
            frameNames = record.frameNames,
            imgW = record.imgW,
            imgH = record.imgH,
            roi = RoiData(record.roiX, record.roiY, record.roiW, record.roiH),
            strainMethod = record.strainMethod.ifBlank { DEFAULT_STRAIN_METHOD },
            subset = record.subset,
            step = record.step,
            strainWindow = record.strainWindow,
            subsetPerFrame = record.sweepSubsets,
            stepPerFrame = record.sweepSteps,
            strainWindowPerFrame = record.sweepStrainWindows,
            engineStats = EngineStats.fromList(record.engineStats),
            drawMinMarker = false,
        )
    }
}
