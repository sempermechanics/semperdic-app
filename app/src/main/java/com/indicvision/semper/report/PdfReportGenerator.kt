// PDF assembly: literal DPI/page dimensions are inherent to layout, and the
// broad catches guard a whole document render (any failure aborts that page),
// so MagicNumber / TooGenericExceptionCaught are suppressed for this file.
@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.indicvision.semper.report

import android.graphics.pdf.PdfDocument
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.indicvision.semper.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.OutputStream

/**
 * Renders a [ReportData] into the multi-page PDF report (cover, field
 * statistics + heatmaps, engine telemetry), emitting progress as a Flow.
 * Page drawing primitives live in [PdfLayoutEngine].
 */
object PdfReportGenerator {

    sealed class Progress {
        data class Status(val message: String, val percent: Int) : Progress()
        object Complete : Progress()
        data class Error(val ex: Exception) : Progress()
    }

    /** Field visualisations are laid out strictly two to a page. */
    private const val FIELD_BLOCK_HEIGHT = 1604f

    // Progress budget: the frames share the middle of the bar, leaving a little
    // at each end for setup and the closing telemetry page.
    private const val FRAMES_PROGRESS_START = 2
    private const val FRAMES_PROGRESS_SPAN = 92
    private const val TELEMETRY_PROGRESS = 96

    /**
     * The all-frames PDF: the single-frame report of [generate], repeated once
     * per frame and concatenated, with one engine-telemetry page at the end.
     *
     * Each frame therefore gets the full treatment — its own cover with the
     * parameters and input images it was solved with, then its five field
     * blocks two to a page and the ZNSSD diagnostic — rather than a condensed
     * summary. That is what makes the frames of a batch, and the parameter
     * combinations of a sweep, directly comparable page for page.
     *
     * [dataAt] is called one frame at a time and each frame's bitmaps are
     * recycled before the next is built, so a 50-frame report never holds more
     * than one frame's images in memory. It returns null for a frame that
     * cannot be read, which is skipped.
     */
    fun generateBatch(
        frameCount: Int,
        dataAt: (Int) -> ReportData?,
        outputStream: OutputStream,
        frameTitle: (Int) -> String = { "DIC Analysis Report — Frame ${it + 1}" },
        resources: Resources? = null,
    ): Flow<Progress> = flow {
        val pdfDocument = PdfDocument()
        val brandLogo = decodeBrandLogo(resources)
        val layout = PdfLayoutEngine(pdfDocument, brandLogo)
        try {
            // Telemetry is per-analysis, not per-frame, so one page closes the
            // document. Holds no bitmaps, so it survives the recycling below.
            var telemetrySource: ReportData? = null

            for (index in 0 until frameCount) {
                currentCoroutineContext().ensureActive()
                val percent = FRAMES_PROGRESS_START + (index * FRAMES_PROGRESS_SPAN / frameCount)
                emit(Progress.Status("Frame ${index + 1} of $frameCount…", percent))
                val data = dataAt(index) ?: continue
                if (telemetrySource == null) telemetrySource = data

                drawCoverPage(layout, data, frameTitle(index), frameCount)
                drawFieldPages(layout, data)
                recycleImages(data)
            }

            telemetrySource?.let {
                emit(Progress.Status("Compiling Engine Telemetry...", TELEMETRY_PROGRESS))
                drawTelemetryPage(layout, it)
            }

            // Finish the still-open page before writing — PdfDocument rejects
            // writeTo()/close() while any page is unfinished.
            layout.finishCurrentPage()
            pdfDocument.writeTo(outputStream)
            emit(Progress.Complete)
        } catch (e: Exception) {
            emit(Progress.Error(e))
        } finally {
            pdfDocument.close()
            recycleLogo(brandLogo)
        }
    }.flowOn(Dispatchers.Default)

    fun generate(
        data: ReportData,
        outputStream: OutputStream,
        resources: Resources? = null,
    ): Flow<Progress> = flow {
        val pdfDocument = PdfDocument()
        val brandLogo = decodeBrandLogo(resources)
        val layout = PdfLayoutEngine(pdfDocument, brandLogo)

        try {
            currentCoroutineContext().ensureActive()
            emit(Progress.Status("Building Cover Page...", 10))
            drawCoverPage(layout, data, "Master DIC Analysis Report", frameCount = null)

            currentCoroutineContext().ensureActive()
            emit(Progress.Status("Rendering Visualization Maps...", 30))
            drawFieldPages(layout, data)

            currentCoroutineContext().ensureActive()
            emit(Progress.Status("Compiling Engine Telemetry...", 90))
            drawTelemetryPage(layout, data)

            emit(Progress.Status("Finalizing PDF...", 98))
            layout.finishCurrentPage()
            pdfDocument.writeTo(outputStream)

            emit(Progress.Complete)
        } catch (e: Exception) {
            emit(Progress.Error(e))
        } finally {
            pdfDocument.close()
            recycleLogo(brandLogo)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Page 1 of a report: session metadata, the parameters it was solved with,
     * the ROI, and the reference/deformed pair it was solved from.
     *
     * @param frameCount total frames, shown only in an all-frames report
     */
    private fun drawCoverPage(
        layout: PdfLayoutEngine,
        data: ReportData,
        title: String,
        frameCount: Int?,
    ) {
        layout.newPage()
        layout.drawTitle(title)

        layout.drawSectionHeader("Session Details")
        layout.drawKeyValue("Specimen / Target:", data.specimenName)
        layout.drawKeyValue("Date Generated:", data.analysisDate)
        layout.drawKeyValue("Session ID:", data.sessionId)
        frameCount?.let { layout.drawKeyValue("Frames:", it.toString()) }
        data.appBuild?.let { layout.drawKeyValue("App Build:", it) }
        layout.advanceY(40f)

        layout.drawSectionHeader("Algorithm Parameters")
        layout.drawKeyValue("Subset Size:", "${data.subsetSize} px")
        layout.drawKeyValue("Step Size:", "${data.stepSize} px")
        layout.drawKeyValue("Strain Method:", data.strainMethod)
        layout.drawKeyValue("Strain Window:", "${data.strainWindow} subsets")
        layout.advanceY(40f)

        layout.drawSectionHeader("Analysis Region (ROI)")
        layout.drawKeyValue("Origin (X, Y):", "(${data.roiData.startX}, ${data.roiData.startY})")
        layout.drawKeyValue("Dimensions:", "${data.roiData.width} x ${data.roiData.height} px")
        layout.advanceY(40f)

        layout.drawSectionHeader("Analyzed Images")
        layout.drawInputVerificationCard(
            data.referenceImage,
            data.referenceImageName,
            data.deformedImage,
            data.deformedImageName,
        )
    }

    /**
     * Pages 2+: the five field blocks, two to a page — U and V, then Exx and
     * Eyy, then Exy. Exy leaves a free slot, which the ZNSSD correlation-quality
     * map fills exactly.
     */
    private fun drawFieldPages(layout: PdfLayoutEngine, data: ReportData) {
        data.fieldResults.chunked(2).forEach { fieldsChunk ->
            layout.newPage()
            fieldsChunk.forEach { field -> layout.drawFieldBlock(field, FIELD_BLOCK_HEIGHT) }
            if (fieldsChunk.size == 1) {
                layout.drawDiagnosticBlock("ZNSSD Correlation Quality", data.znssdHeatmap, FIELD_BLOCK_HEIGHT)
            }
        }
    }

    /**
     * Frees one frame's page images once it has been drawn. Every bitmap in a
     * [ReportData] is a private downscaled copy made by ReportBuilder — none
     * alias the caller's originals — so all of them are ours to release. Without
     * this a 50-frame report would hold 50 frames' images at once.
     */
    private fun recycleImages(data: ReportData) {
        data.fieldResults.forEach { it.bakedHeatmap.recycle() }
        data.znssdHeatmap.recycle()
        data.solverPathMap.recycle()
        data.referenceImage.recycle()
        data.deformedImage.recycle()
    }

    /** Final page: engine telemetry (shared by single and batch reports). */
    private fun drawTelemetryPage(layout: PdfLayoutEngine, data: ReportData) {
        layout.newPage()
        layout.drawTitle("Engine Performance Log")

        val stats = data.engineStats

        layout.drawSectionHeader("1. Solver Pipeline (2-Pass Architecture)")
        layout.drawTable(
            headers = listOf("Pipeline Stage", "Points"),
            rows = listOf(
                listOf("Seeding Mode", stats.meshSeedingLabel()),
                listOf("Total Target Grid Points", "${stats.totalPointsAttempted}"),
                listOf("Phase 1: Solved by Delaunay Mesh", "${stats.pathAPoints}"),
                listOf("Phase 2: Saved by RGDIC Propagation", "${stats.pathBPoints}"),
                listOf("Final Unsolvable (Dead Points)", "${stats.totalPointsRejected}"),
            ),
            colWeights = listOf(0.7f, 0.3f),
        )

        layout.drawSectionHeader("2. Optimization & Quality")
        layout.drawTable(
            headers = listOf("Metric", "Value"),
            rows = listOf(
                listOf("Global Average ZNSSD (Correlation)", "%.5f".format(data.globalAvgZnssd)),
                listOf("Overall Convergence Rate", "%.2f %%".format(stats.convergencePercent)),
                listOf("Average ICGN Iterations", "%.2f".format(stats.avgIcgnIterations)),
            ),
            colWeights = listOf(0.7f, 0.3f),
        )

        layout.drawSectionHeader("3. Simplex Rescue Subsystem")
        layout.drawTable(
            headers = listOf("Intervention", "Triggered", "Saved"),
            rows = listOf(
                listOf("Simplex Interventions", "${stats.simplexCalls}", "${stats.simplexSaved}"),
            ),
            colWeights = listOf(0.5f, 0.25f, 0.25f),
        )

        layout.drawSectionHeader("4. Hardware Profiling (Wall Time)")
        layout.drawTable(
            headers = listOf("Execution Phase", "Time (ms)"),
            rows = listOf(
                listOf("AKAZE + RANSAC Phase", "%.1f ms".format(stats.akazeRansacMs)),
                listOf("Hessian Pre-Pass", "%.1f ms".format(stats.hessianPrepassMs)),
                listOf("Delaunay Mesh Phase", "%.1f ms".format(stats.delaunayMs)),
                listOf("Strain Calculation Phase", "%.1f ms".format(stats.strainMs)),
                listOf("TOTAL WALL TIME", "%.1f ms".format(stats.wallTimeMs)),
                listOf("Average Throughput", "%.2f pts/ms".format(stats.avgThroughputPtsPerMs)),
            ),
            colWeights = listOf(0.6f, 0.4f),
        )
    }

    private fun decodeBrandLogo(resources: Resources?): Bitmap? {
        if (resources == null) return null
        return BitmapFactory.decodeResource(resources, R.drawable.semper_wordmark)
    }

    private fun recycleLogo(logo: Bitmap?) {
        if (logo != null && !logo.isRecycled) logo.recycle()
    }
}
