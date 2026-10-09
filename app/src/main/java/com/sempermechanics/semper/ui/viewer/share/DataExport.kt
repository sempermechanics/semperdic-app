package com.sempermechanics.semper.ui.viewer.share

import android.content.res.Resources
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.AnalysisCsvWriter
import com.sempermechanics.semper.report.FieldRangesStore
import com.sempermechanics.semper.report.PdfReportGenerator
import com.sempermechanics.semper.report.PdfReportGenerator.Stage
import com.sempermechanics.semper.report.ReportImageNames
import com.sempermechanics.semper.ui.viewer.summary.SummaryAnimation
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
     *
     * [report] hears each frame as it is encoded: every field is an equal part
     * of the bar, and a range pass, when one is needed, one part more. A field
     * whose GIF is already built reports nothing and the bar moves on to the
     * next. Progress is read from [SummaryAnimation.build]'s own callback; the
     * encode itself is untouched.
     */
    suspend fun fieldAnimations(report: ExportReport = NO_REPORT): List<File> {
        val animation = s.summary ?: return emptyList()
        val fields = SummaryAnimation.FIELDS
        // What the viewer knew when the job started: a fixed scale, else the sequence range.
        val known = fields.all { it.second in s.summaryBounds }
        val parts = fields.size + if (known) 0 else 1
        val bounds = if (known) s.summaryBounds else readBounds(report.part(0, parts))
        val firstField = parts - fields.size
        val frames = s.batchFiles.size
        return fields.mapIndexedNotNull { i, (label, index) ->
            val fieldBounds = bounds[index] ?: return@mapIndexedNotNull null
            val field = report.part(firstField + i, parts)
            field.step(resources, 0, frames, R.string.share_progress_gif_fmt, label)
            animation.build(index, label, fieldBounds) { done, total ->
                field.step(resources, done, total, R.string.share_progress_gif_fmt, label)
            }
        }
    }

    /**
     * Each field's GIF scale when the viewer did not know them all as the job
     * started: a job started before the viewer's range pass finished reads the
     * ranges itself (from the sidecar when there is one), rather than leaving
     * the fields out.
     */
    private suspend fun readBounds(report: ExportReport): Map<Int, Pair<Float, Float>> {
        val rangesFile = s.batchFiles.firstOrNull()?.parentFile?.let { File(it, FieldRangesStore.FILE_NAME) }
        report.step(resources, 0, s.batchFiles.size, R.string.share_progress_gif_ranges_fmt)
        return SummaryAnimation.globalRanges(s.batchFiles, rangesFile) { done, total ->
            report.step(resources, done, total, R.string.share_progress_gif_ranges_fmt)
        } + s.summaryBounds
    }

    /**
     * One CSV covering every frame's solved points, via the shared
     * [AnalysisCsvWriter] the cloud upload uses too. A sweep leads each row with
     * its settings columns; an ordinary analysis leads with the image name.
     *
     * [report] hears each frame of both of the writer's passes: the field
     * statistics (the first [CSV_STATS_SHARE] percent), then the point rows.
     */
    fun batchCsv(report: ExportReport = NO_REPORT): File {
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
        val stats = report.within(0.0, CSV_STATS_SHARE)
        val points = report.within(CSV_STATS_SHARE, FULL)
        AnalysisCsvWriter.write(f, sweep, frames, metadata) { pointRows, done ->
            if (pointRows) {
                points.step(resources, done, frames.size, R.string.share_progress_csv_points_fmt)
            } else {
                stats.step(resources, done, frames.size, R.string.share_progress_csv_stats_fmt)
            }
        }
        return f
    }

    /**
     * One PDF holding every frame's full report, concatenated: each frame gets
     * the same cover / field-pages structure a single-frame report has, and one
     * telemetry page closes the document.
     */
    suspend fun allFramesPdf(report: ExportReport = NO_REPORT): File {
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
                    is PdfReportGenerator.Progress.Status ->
                        report(progress.percent.toDouble(), progress.text(resources))
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
    private fun frameReport(index: Int): com.sempermechanics.semper.report.ReportData? {
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

    private companion object {
        /** The CSV's statistics pass only decodes each frame; the point rows are most of the work. */
        const val CSV_STATS_SHARE = 20.0
        const val FULL = 100.0
    }
}

/** The status line for a PDF [PdfReportGenerator.Progress.Status], which carries only its stage and frame. */
internal fun PdfReportGenerator.Progress.Status.text(res: Resources): String = when (stage) {
    Stage.FRAME -> res.getString(R.string.share_progress_pdf_frame_fmt, frame, frameCount)
    Stage.COVER -> res.getString(R.string.share_progress_pdf_cover)
    Stage.MAPS -> res.getString(R.string.share_progress_pdf_maps)
    Stage.TELEMETRY -> res.getString(R.string.share_progress_pdf_telemetry)
    Stage.FINISHING -> res.getString(R.string.share_progress_pdf_finishing)
}
