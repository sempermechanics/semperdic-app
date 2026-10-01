// Bundler stages a whole session's files (reference, raw frames, .dat, csv,
// reports) in one cohesive pass over the full file set; kept together so the
// staging order stays in one place.
@file:Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")

package com.indicvision.semper.data

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.ImageEncode
import com.indicvision.semper.report.AnalysisCsvWriter
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.FieldRangesStore
import com.indicvision.semper.report.FieldResult
import com.indicvision.semper.report.PdfReportGenerator
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.ReportImageNames
import com.indicvision.semper.report.RoiData
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.ui.viewer.HeatmapFit
import com.indicvision.semper.ui.viewer.SummaryAnimation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Builds per-frame upload artifacts: combined analysis CSV rows, PDF reports,
 * and processed field heatmaps staged under `reports/` and `processed/`.
 *
 * CSV and report bake share one `.dat` decode per frame so upload staging does
 * not pay for decoding twice.
 */
object SessionUploadBundler {

    data class BundleCounts(val reports: Int, val processed: Int)

    /**
     * One pass over the frames: optional combined CSV plus the artifact sets as
     * plain files in the staging dir (Session.zip compresses everything at the
     * end, so there is no point deflating them twice into nested archives):
     *  - `csv/analysis_data.csv` (via [csvFile])
     *  - `reports/Master_Report_Frame_N.pdf`
     *  - `processed/Frame_N_<field>.png` — the U/V/Exx/Eyy/Exy heatmaps
     *
     * They're built together deliberately: [ReportBuilder.buildReport] already
     * bakes the field heatmaps to make the PDF, so writing them out here costs
     * nothing extra — and the CSV reuses the same decoded `.dat`.
     *
     * The PDF is rendered to a scratch file reused per frame, and each frame's
     * bitmaps are recycled before moving on, so memory stays flat regardless of
     * frame count. Parallel bake is intentionally avoided (OOM risk on large ROIs).
     */
    suspend fun stageCsvAndBundles(
        context: Context,
        record: SessionRecord,
        sessionDir: File,
        refFile: File,
        rawDeformedDir: File,
        stagingDir: File,
        csvFile: File?,
        writeReports: Boolean,
        onFrame: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BundleCounts = withContext(Dispatchers.Default) {
        val reportsDir = File(stagingDir, "reports").apply { if (writeReports) mkdirs() }
        val processedDir = File(stagingDir, "processed").apply { if (writeReports) mkdirs() }
        var reports = 0
        var processed = 0

        val canReport = writeReports && record.imgW > 0 && record.imgH > 0
        if (writeReports && !canReport) {
            Timber.e("Bad image dimensions for %s — skipping reports", record.id)
        }

        val frameTotal = record.defNames.size.coerceAtLeast(1)
        // Surface 0% immediately — decoding the PLC reference can take minutes
        // before the first frame callback, which looked like a hung prepare badge.
        onFrame(0, frameTotal)

        // The reference is the SAME image in every frame's report — decode and
        // scale it once for the whole session, not once per frame. Falls back
        // to a deformed frame if the reference won't decode. Capped to
        // REPORT_MAX_EDGE: the report only ever downscales it (to 600 px), so a
        // full-res resident base is pure memory pressure.
        val (baseW, baseH) =
            VisualizationEngine.cappedDims(record.imgW, record.imgH, VisualizationEngine.REPORT_MAX_EDGE)
        val baseImg: Bitmap? = if (canReport) {
            val originalBaseImg = decodeBaseImage(
                refFile,
                rawDeformedDir,
                record.defNames.firstOrNull(),
                record.imgW,
                record.imgH,
            )
            if (originalBaseImg == null) {
                // Size only: the path holds the user's file name, and ERROR reaches Crashlytics.
                Timber.e("No decodable base image (reference %d B) — skipping reports", refFile.length())
                null
            } else {
                val scaled = originalBaseImg.scale(baseW, baseH)
                if (scaled !== originalBaseImg) originalBaseImg.recycle()
                scaled
            }
        } else {
            null
        }

        val scratch = if (baseImg != null) {
            File(context.cacheDir, "upload_${record.id}_frame.pdf")
        } else {
            null
        }
        val ctx = if (baseImg != null && scratch != null) {
            RenderContext(record, baseImg, scratch, context.resources)
        } else {
            null
        }

        val sweepImage = record.defNames.firstOrNull().orEmpty()
        val csvMetadata = AnalysisCsvWriter.Metadata(
            referenceName = record.refName,
            strainMethod = record.strainMethod.ifBlank { "VSG" },
            imgW = record.imgW,
            imgH = record.imgH,
            roiX = record.roiX,
            roiY = record.roiY,
            roiW = record.roiW,
            roiH = record.roiH,
        )
        val csvAppender = csvFile?.let { AnalysisCsvWriter.open(it, record.isSweep, csvMetadata) }
        try {
            record.defNames.forEachIndexed { index, defName ->
                // Rendering is blocking; check per frame so a cancelled export
                // or upload stops within one frame, not one session.
                ensureActive()
                val datFile = SessionPaths.frameDat(sessionDir, index)
                if (!datFile.exists()) {
                    onFrame(index + 1, frameTotal)
                    return@forEachIndexed
                }
                val data = DicResult.decodeDatFile(datFile)
                if (data == null) {
                    onFrame(index + 1, frameTotal)
                    return@forEachIndexed
                }

                val frame = AnalysisCsvWriter.Frame(
                    image = if (record.isSweep) {
                        sweepImage
                    } else {
                        record.defNames.getOrElse(index) { "Frame_${index + 1}" }
                    },
                    subset = record.sweepSubsets.getOrElse(index) { record.subset },
                    step = record.sweepSteps.getOrElse(index) { record.step },
                    strainWindow = record.sweepStrainWindows.getOrElse(index) { record.strainWindow },
                    data = { data },
                )
                csvAppender?.appendFieldStats(frame, data)
                csvAppender?.append(frame)

                if (ctx == null) {
                    onFrame(index + 1, frameTotal)
                    return@forEachIndexed
                }

                val frameName = if (record.isSweep) {
                    record.sweepLabels.getOrElse(index) { "Combination_${index + 1}" }
                        .replace('/', '-').replace('\\', '-')
                } else {
                    "Frame_${index + 1}"
                }
                val defFile = File(rawDeformedDir, defName)

                // One processed/<frame>/ subfolder per combination, so its five
                // field maps stay together instead of all frames' maps landing
                // flat in processed/.
                val frameDir = File(processedDir, frameName)
                frameDir.mkdirs()
                val ok = renderFrame(ctx, data, defFile, frameName, index) { fields ->
                    fields.forEach { field ->
                        File(frameDir, "${field.fieldKey}.png").outputStream().buffered().use { out ->
                            field.bakedHeatmap.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, out)
                        }
                        processed++
                    }
                }
                if (!ok || scratch!!.length() == 0L) {
                    Timber.w("Report generation failed for %s", frameName)
                    onFrame(index + 1, frameTotal)
                    return@forEachIndexed
                }
                scratch.copyTo(File(reportsDir, "Master_Report_$frameName.pdf"), overwrite = true)
                reports++
                onFrame(index + 1, frameTotal)
            }
        } finally {
            csvAppender?.close()
            scratch?.delete()
            baseImg?.recycle()
        }
        // Per-field looping GIFs for single-setting backups only. Sweeps are
        // parameter combinations, not a time series — no animations folder.
        ensureActive()
        val animations =
            if (canReport && !record.isSweep) {
                stageAnimations(context, record, sessionDir, processedDir)
            } else {
                0
            }
        if (writeReports) {
            Timber.i(
                "Staged %d frame reports, %d processed images, %d animations",
                reports,
                processed,
                animations,
            )
        }
        BundleCounts(reports, processed)
    }

    /**
     * Builds the five per-field animation GIFs into `processed/animations/` via the
     * headless [SummaryAnimation]. One failed field is logged and skipped rather
     * than aborting the whole backup. Returns the number of GIFs written.
     */
    private suspend fun stageAnimations(
        context: Context,
        record: SessionRecord,
        sessionDir: File,
        processedDir: File,
    ): Int {
        val batchFiles = record.defNames.indices
            .map { i -> SessionPaths.frameDat(sessionDir, i) }
            .filter { it.exists() }
        if (batchFiles.isEmpty()) return 0

        val animation = SummaryAnimation(
            SummaryAnimation.Spec(
                batchFiles = batchFiles,
                imgW = record.imgW,
                imgH = record.imgH,
                stepAt = { i -> record.sweepSteps.getOrElse(i) { record.step } },
                outputDir = File(processedDir, "animations").apply { mkdirs() },
                backgroundColor = ContextCompat.getColor(context, R.color.viewer_canvas),
                fitBounds = HeatmapFit.resolve(
                    record.imgW,
                    record.imgH,
                    record.roiX,
                    record.roiY,
                    record.roiW,
                    record.roiH,
                ),
            ),
        )
        val rangesFile = File(sessionDir, FieldRangesStore.FILE_NAME)
        val ranges = SummaryAnimation.globalRanges(batchFiles, rangesFile)
        var gifs = 0
        for ((label, dataIndex) in SummaryAnimation.FIELDS) {
            currentCoroutineContext().ensureActive() // one GIF per check, like the frame loop
            val bounds = ranges[dataIndex] ?: continue
            try {
                if (animation.build(dataIndex, label, bounds) != null) gifs++
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                Timber.w(e, "Skipping %s animation for %s in backup", label, record.id)
            }
        }
        return gifs
    }

    /** Per-session state shared by every frame's report render. */
    private class RenderContext(
        val record: SessionRecord,
        /** Reference image, already scaled to engine dimensions. NOT owned by renderFrame. */
        val baseImg: Bitmap,
        /** Scratch PDF file, reused per frame. */
        val scratch: File,
        val resources: android.content.res.Resources,
    )

    /**
     * Build one frame's report: writes the classic single-frame PDF to
     * [RenderContext.scratch] and hands the freshly baked per-field heatmaps to
     * [onFieldHeatmaps] before they are recycled. The reference bitmap comes
     * pre-scaled from the context and is shared across frames — never recycled
     * here.
     */
    @Suppress("LongParameterList") // per-frame render inputs plus the heatmap callback
    private suspend fun renderFrame(
        ctx: RenderContext,
        data: FloatArray,
        defFile: File,
        frameName: String,
        frameIndex: Int,
        onFieldHeatmaps: (List<FieldResult>) -> Unit,
    ): Boolean = withContext(Dispatchers.Default) {
        val record = ctx.record

        // The deformed original is only the cover image (downscaled to 600 px in
        // the report); decode + scale it capped, and fall back to the reference
        // rather than losing the whole report over it.
        val (coverW, coverH) =
            VisualizationEngine.cappedDims(record.imgW, record.imgH, VisualizationEngine.REPORT_MAX_EDGE)
        val originalDefImg = BitmapDecode.decodeFileForView(
            defFile.absolutePath,
            coverW,
            coverH,
            VisualizationEngine.REPORT_MAX_EDGE,
            rawWidth = record.imgW,
            rawHeight = record.imgH,
        )
        val defImg = if (originalDefImg != null) {
            originalDefImg.scale(coverW, coverH)
        } else {
            ctx.baseImg
        }

        val frameSubset = record.sweepSubsets.getOrElse(frameIndex) { record.subset }
        val frameStep = record.sweepSteps.getOrElse(frameIndex) { record.step }
        val frameWindow = record.sweepStrainWindows.getOrElse(frameIndex) { record.strainWindow }
        val reportData = ReportBuilder.buildReport(
            ReportBuilder.ReportBuildParams(
                data = data,
                baseImg = ctx.baseImg,
                defImgForCover = defImg,
                imgW = record.imgW,
                imgH = record.imgH,
                step = frameStep,
                sessionId = record.id,
                specimenName = ReportImageNames.specimen(record.refName),
                analysisDate = ReportBuilder.currentAnalysisDate(),
                subsetSize = frameSubset,
                strainWindow = frameWindow,
                strainMethod = record.strainMethod.ifBlank { "VSG" },
                roiData = RoiData(record.roiX, record.roiY, record.roiW, record.roiH),
                engineStats = reportEngineStats(record.engineStats),
                // The names the on-device report prints (ViewerReportFactory),
                // not the bundle's folder names.
                referenceImageName = ReportImageNames.reference(record.refName),
                deformedImageName = ReportImageNames.deformed(record.frameNames, frameIndex),
                drawMinMarker = false,
            ),
        )

        var ok = true
        try {
            ctx.scratch.outputStream().use { stream ->
                PdfReportGenerator.generate(reportData, stream, ctx.resources).collect { progress ->
                    if (progress is PdfReportGenerator.Progress.Error) {
                        Timber.e(progress.ex, "PDF generation failed for %s", frameName)
                        ok = false
                    }
                }
            }
            onFieldHeatmaps(reportData.fieldResults)
        } finally {
            reportData.fieldResults.forEach { it.bakedHeatmap.recycle() }
            reportData.znssdHeatmap.recycle()
            if (defImg !== ctx.baseImg && defImg !== originalDefImg) defImg.recycle()
            if (originalDefImg !== null && originalDefImg !== defImg) originalDefImg.recycle()
        }
        ok
    }

    /**
     * [stats] as the report reads them. Every slot the run stored is kept — the
     * engine writes [EngineStats.SLOT_COUNT], and cutting that to the 16 core
     * slots printed "Unknown" for mesh seeding and 0 ms for simplex / ICGN in
     * every cloud PDF. Never padded past what was stored: a legacy 16-slot
     * record read as 17 would claim mesh quality 0 ("Fallback") instead of
     * unknown. Shorter (or empty) records are padded to the core slots, as before.
     */
    internal fun reportEngineStats(stats: List<Float>): EngineStats {
        val size = stats.size.coerceIn(EngineStats.CORE_SLOT_COUNT, EngineStats.SLOT_COUNT)
        return EngineStats.fromArray(FloatArray(size) { stats.getOrElse(it) { 0f } })
    }

    /**
     * The base image for a session's reports: the reference, or a deformed frame
     * if the reference won't decode. Decoded capped to [VisualizationEngine.REPORT_MAX_EDGE]
     * so a huge reference never lands full-res in memory.
     */
    private fun decodeBaseImage(
        refFile: File,
        rawDeformedDir: File,
        defName: String?,
        imgW: Int,
        imgH: Int,
    ): Bitmap? {
        val edge = VisualizationEngine.REPORT_MAX_EDGE
        return BitmapDecode.decodeFileForView(
            refFile.absolutePath,
            edge,
            edge,
            edge,
            rawWidth = imgW,
            rawHeight = imgH,
        ) ?: defName?.let {
            BitmapDecode.decodeFileForView(
                File(rawDeformedDir, it).absolutePath,
                edge,
                edge,
                edge,
                rawWidth = imgW,
                rawHeight = imgH,
            )
        }
    }
}
