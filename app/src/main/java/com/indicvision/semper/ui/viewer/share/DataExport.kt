package com.indicvision.semper.ui.viewer.share

import android.content.res.Resources
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.report.AnalysisCsvWriter
import com.indicvision.semper.report.FieldRangesStore
import com.indicvision.semper.report.PdfReportGenerator
import com.indicvision.semper.report.ReportImageNames
import com.indicvision.semper.ui.viewer.summary.SummaryAnimation
import java.io.File

/**
 * The batch-wide files of a [ShareExportBuilder] job: the data table
 * ([ShareKind.CSV]), every frame's report in one PDF ([ShareKind.PDF]) and the
 * field animations ([ShareKind.GIFS]).
 */
internal class DataExport(
    private val s: ShareCenter.Snapshot,
    private val resources: Resources,
    private val outDir: File,
) {

    /**
     * All five fields as looping GIFs, each covering every frame on that field's
     * whole-sequence colour scale.
     *
     * Shared as a set rather than one at a time: the point of the animations is
     * that they are directly comparable, which only holds if you have them all.
     * Fields the viewer has not rendered yet are built here, so sharing works
     * the moment the screen opens.
     */
    suspend fun fieldAnimations(): List<File> {
        val animation = s.summary ?: return emptyList()
        val bounds = animationBounds()
        return SummaryAnimation.FIELDS.mapNotNull { (label, index) ->
            val fieldBounds = bounds[index] ?: return@mapNotNull null
            animation.build(index, label, fieldBounds)
        }
    }

    /**
     * Each field's GIF scale: what the viewer knew when the job started (a fixed
     * scale, else the sequence range). A job started before the viewer's range
     * pass finished reads the ranges itself (from the sidecar when there is one),
     * rather than leaving the fields out.
     */
    private suspend fun animationBounds(): Map<Int, Pair<Float, Float>> {
        if (SummaryAnimation.FIELDS.all { it.second in s.summaryBounds }) return s.summaryBounds
        val rangesFile = s.batchFiles.firstOrNull()?.parentFile?.let { File(it, FieldRangesStore.FILE_NAME) }
        return SummaryAnimation.globalRanges(s.batchFiles, rangesFile) + s.summaryBounds
    }

    /**
     * One CSV covering every frame's solved points, via the shared
     * [AnalysisCsvWriter] the cloud upload uses too. A sweep leads each row with
     * its settings columns; an ordinary analysis leads with the image name.
     */
    fun batchCsv(): File {
        val sweep = s.isSweep
        // A sweep ran every combination against the one image; a batch has one
        // image per frame.
        val sweepImage = s.defImagePaths.firstOrNull()?.let { File(it).name } ?: "image"
        val source = s.reportSource
        val frames = s.batchFiles.mapIndexed { index, file ->
            val params = s.frameParams.at(index)
            AnalysisCsvWriter.Frame(
                // Named as the cloud bundle's CSV names it, by the planned frame.
                image = if (sweep) sweepImage else ReportImageNames.deformed(source.frameNames, s.plannedAt(index)),
                subset = params.subset,
                step = params.step,
                strainWindow = params.strainWindow,
                data = { DicResult.decodeDatFile(file) },
            )
        }
        val roi = source.roi
        val metadata = AnalysisCsvWriter.Metadata(
            referenceName = source.args.refName.ifBlank { s.baseName },
            strainMethod = source.args.strainMethod,
            imgW = s.imageSize.width,
            imgH = s.imageSize.height,
            roiX = roi.x,
            roiY = roi.y,
            roiW = roi.w,
            roiH = roi.h,
        )
        val f = File(outDir, "${s.baseName}_data.csv")
        AnalysisCsvWriter.write(f, sweep, frames, metadata)
        return f
    }

    /**
     * One PDF holding every frame's full report, concatenated: each frame gets
     * the same cover / field-pages structure a single-frame report has, and one
     * telemetry page closes the document.
     */
    suspend fun allFramesPdf(report: (Int, String) -> Unit = { _, _ -> }): File {
        val f = File(outDir, "${s.baseName}_report.pdf")
        f.outputStream().use { out ->
            PdfReportGenerator.generateBatch(
                frameCount = s.batchFiles.size,
                dataAt = { index -> frameReport(index) },
                outputStream = out,
                frameTitle = { index -> frameTitle(index) },
                resources = resources,
            ).collect { progress ->
                when (progress) {
                    // generateBatch reports failures as a Flow event rather than
                    // throwing; surface it so the share job actually fails (and logs)
                    // instead of silently handing back an empty PDF.
                    is PdfReportGenerator.Progress.Error -> throw progress.ex
                    is PdfReportGenerator.Progress.Status -> report(progress.percent, progress.message)
                    PdfReportGenerator.Progress.Complete -> Unit
                }
            }
        }
        return f
    }

    /**
     * The frame's own report data. Built one frame at a time — the generator
     * recycles each frame's bitmaps before asking for the next.
     */
    private fun frameReport(index: Int): com.indicvision.semper.report.ReportData? {
        val data = DicResult.decodeDatFile(s.batchFiles[index]) ?: return null
        return ViewerReportFactory.buildReportData(s.reportSource, index, data)
    }

    private fun frameTitle(index: Int): String {
        val name = s.nameAt(index)
        return if (name == null) {
            "DIC Analysis Report — Frame ${s.plannedAt(index) + 1}"
        } else {
            "DIC Analysis Report — $name"
        }
    }
}
