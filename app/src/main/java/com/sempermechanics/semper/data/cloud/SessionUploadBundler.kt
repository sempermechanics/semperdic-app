package com.sempermechanics.semper.data.cloud

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.StagingLayout
import com.sempermechanics.semper.data.session.imageSize
import com.sempermechanics.semper.data.session.paramsAt
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.imaging.BitmapDecode
import com.sempermechanics.semper.imaging.ImageEncode
import com.sempermechanics.semper.report.AnalysisCsvWriter
import com.sempermechanics.semper.report.FieldResult
import com.sempermechanics.semper.report.PdfReportGenerator
import com.sempermechanics.semper.report.ReportBuilder
import com.sempermechanics.semper.report.ReportSource
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.viewer.HeatmapFit
import com.sempermechanics.semper.ui.viewer.summary.SummaryAnimation
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
     *  - `reports/Master_Report_<frame>.pdf`
     *  - `processed/<frame>/<field>.png` — the U/V/Exx/Eyy/Exy heatmaps
     *  - `processed/animations/` — per-field GIFs, for a single-setting run
     *
     * They're built together deliberately: [ReportBuilder.buildReport] already
     * bakes the field heatmaps to make the PDF, so writing them out here costs
     * nothing extra — and the CSV reuses the same decoded `.dat`.
     *
     * The PDF is rendered to a scratch file reused per frame, and each frame's
     * bitmaps are recycled before moving on, so memory stays flat regardless of
     * frame count. Parallel bake is intentionally avoided (OOM risk on large ROIs).
     */
    @Suppress("LongParameterList") // the entry point the upload and the Save-to-Files export share
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
        val staging = StagingLayout(stagingDir)
        if (writeReports) {
            staging.reportsDir.mkdirs()
            staging.processedDir.mkdirs()
        }
        val canReport = writeReports && record.imageSize.isKnown
        if (writeReports && !canReport) {
            Timber.e("Bad image dimensions for %s — skipping reports", record.id)
        }

        val frameTotal = record.defNames.size.coerceAtLeast(1)
        // Surface 0% immediately — decoding the PLC reference can take minutes
        // before the first frame callback, which looked like a hung prepare badge.
        onFrame(0, frameTotal)

        val baseImg = if (canReport) scaledBaseImage(record, refFile, rawDeformedDir) else null
        val render = baseImg?.let {
            RenderContext(record, it, File(context.cacheDir, "upload_${record.id}_frame.pdf"), context.resources)
        }
        val csv = csvFile?.let { AnalysisCsvWriter.open(it, record.isSweep, csvMetadata(record)) }
        val pass = FramePass(record, sessionDir, rawDeformedDir, staging, render, csv)
        try {
            record.defNames.forEachIndexed { index, defName ->
                // Rendering is blocking; check per frame so a cancelled export
                // or upload stops within one frame, not one session.
                ensureActive()
                pass.stageFrame(index, defName)
                onFrame(index + 1, frameTotal)
            }
        } finally {
            csv?.close()
            render?.scratch?.delete()
            baseImg?.recycle()
        }
        // Per-field looping GIFs for single-setting backups only. Sweeps are
        // parameter combinations, not a time series — no animations folder.
        ensureActive()
        val animations = if (canReport && !record.isSweep) {
            stageAnimations(context, record, sessionDir, staging.processedDir)
        } else {
            0
        }
        if (writeReports) {
            Timber.i(
                "Staged %d frame reports, %d processed images, %d animations",
                pass.reports,
                pass.processed,
                animations,
            )
        }
        BundleCounts(pass.reports, pass.processed)
    }

    /**
     * Frame [frameIndex]'s report inputs: [ReportSource.forRecord], so the PDF
     * prints the names, settings and engine stats the on-device report prints
     * (not the bundle's folder names), and marks the MAX only.
     */
    internal fun reportParams(
        record: SessionRecord,
        frameIndex: Int,
        data: FloatArray,
        baseImg: Bitmap,
        coverImg: Bitmap,
    ): ReportBuilder.ReportBuildParams = ReportSource.forRecord(record).forFrame(frameIndex, data, baseImg, coverImg)

    private fun csvMetadata(record: SessionRecord) = AnalysisCsvWriter.Metadata(
        referenceName = record.refName,
        strainMethod = record.strainMethod.ifBlank { ReportSource.DEFAULT_STRAIN_METHOD },
        imgW = record.imgW,
        imgH = record.imgH,
        roiX = record.roiX,
        roiY = record.roiY,
        roiW = record.roiW,
        roiH = record.roiH,
    )

    /**
     * The reference is the SAME image in every frame's report — decode and
     * scale it once for the whole session, not once per frame. Falls back to a
     * deformed frame if the reference won't decode. Capped to
     * [VisualizationEngine.REPORT_MAX_EDGE]: the report only ever downscales it
     * (to 600 px), so a full-res resident base is pure memory pressure.
     */
    private fun scaledBaseImage(record: SessionRecord, refFile: File, rawDeformedDir: File): Bitmap? {
        val (baseW, baseH) =
            VisualizationEngine.cappedDims(record.imgW, record.imgH, VisualizationEngine.REPORT_MAX_EDGE)
        val original = decodeBaseImage(refFile, rawDeformedDir, record.defNames.firstOrNull(), record.imgW, record.imgH)
        if (original == null) {
            // Size only: the path holds the user's file name, and ERROR reaches Crashlytics.
            Timber.e("No decodable base image (reference %d B) — skipping reports", refFile.length())
            return null
        }
        val scaled = original.scale(baseW, baseH)
        if (scaled !== original) original.recycle()
        return scaled
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
                stepAt = { i -> record.paramsAt(i).step },
                outputDir = File(processedDir, StagingLayout.ANIMATIONS_SUBDIR).apply { mkdirs() },
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
        val ranges = SummaryAnimation.globalRanges(batchFiles, SessionLayout(sessionDir).fieldRanges)
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

    /**
     * One frame at a time: its CSV rows from the decoded `.dat`, then (when
     * [render] is set) its report and heatmaps. Counts what it wrote.
     */
    private class FramePass(
        private val record: SessionRecord,
        private val sessionDir: File,
        private val rawDeformedDir: File,
        private val staging: StagingLayout,
        private val render: RenderContext?,
        private val csv: AnalysisCsvWriter.Appender?,
    ) {
        var reports = 0
            private set
        var processed = 0
            private set

        /** A sweep repeats its one image in every row. */
        private val sweepImage = record.defNames.firstOrNull().orEmpty()

        /** Stage frame [index]: nothing without a decodable `.dat`, the CSV rows only without a renderer. */
        suspend fun stageFrame(index: Int, defName: String) {
            val data = SessionPaths.frameDat(sessionDir, index)
                .takeIf { it.exists() }
                ?.let { DicResult.decodeDatFile(it) }
                ?: return
            appendCsv(index, data)
            if (render == null) return
            val frameName = frameFolderName(index)
            if (stageReport(render, index, defName, frameName, data)) {
                reports++
            } else {
                Timber.w("Report generation failed for %s", frameName)
            }
        }

        private fun appendCsv(index: Int, data: FloatArray) {
            val params = record.paramsAt(index)
            val frame = AnalysisCsvWriter.Frame(
                image = if (record.isSweep) sweepImage else record.defNames.getOrElse(index) { "Frame_${index + 1}" },
                subset = params.subset,
                step = params.step,
                strainWindow = params.strainWindow,
                data = { data },
            )
            csv?.appendFieldStats(frame, data)
            csv?.append(frame)
        }

        /** The frame's folder and PDF name: a sweep's label with path separators made safe, else `Frame_N`. */
        private fun frameFolderName(index: Int): String = if (record.isSweep) {
            record.sweepLabels.getOrElse(index) { "Combination_${index + 1}" }
                .replace('/', '-').replace('\\', '-')
        } else {
            "Frame_${index + 1}"
        }

        /**
         * Render the frame's PDF into the scratch file and its heatmaps into
         * `processed/<frame>/` — one subfolder per frame, so its five field maps
         * stay together — then copy the PDF to `reports/`. False when the PDF
         * failed or came out empty.
         */
        private suspend fun stageReport(
            render: RenderContext,
            index: Int,
            defName: String,
            frameName: String,
            data: FloatArray,
        ): Boolean {
            val frameDir = File(staging.processedDir, frameName)
            frameDir.mkdirs()
            val ok = render.frame(data, File(rawDeformedDir, defName), frameName, index) { fields ->
                fields.forEach { field ->
                    File(frameDir, "${field.fieldKey}.png").outputStream().buffered().use { out ->
                        field.bakedHeatmap.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, out)
                    }
                    processed++
                }
            }
            if (!ok || render.scratch.length() == 0L) return false
            render.scratch.copyTo(File(staging.reportsDir, "Master_Report_$frameName.pdf"), overwrite = true)
            return true
        }
    }

    /** Per-session state shared by every frame's report render. */
    private class RenderContext(
        val record: SessionRecord,
        /** Reference image, already scaled to engine dimensions. NOT owned by [frame]. */
        val baseImg: Bitmap,
        /** Scratch PDF file, reused per frame. */
        val scratch: File,
        val resources: Resources,
    ) {
        /**
         * Build one frame's report: writes the classic single-frame PDF to
         * [scratch] and hands the freshly baked per-field heatmaps to
         * [onFieldHeatmaps] before they are recycled. The reference bitmap is
         * shared across frames — never recycled here.
         */
        suspend fun frame(
            data: FloatArray,
            defFile: File,
            frameName: String,
            frameIndex: Int,
            onFieldHeatmaps: (List<FieldResult>) -> Unit,
        ): Boolean = withContext(Dispatchers.Default) {
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
            val defImg = originalDefImg?.scale(coverW, coverH) ?: baseImg
            val reportData = ReportBuilder.buildReport(reportParams(record, frameIndex, data, baseImg, defImg))

            var ok = true
            try {
                scratch.outputStream().use { stream ->
                    PdfReportGenerator.generate(reportData, stream, resources).collect { progress ->
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
                if (defImg !== baseImg && defImg !== originalDefImg) defImg.recycle()
                if (originalDefImg !== null && originalDefImg !== defImg) originalDefImg.recycle()
            }
            ok
        }
    }
}
