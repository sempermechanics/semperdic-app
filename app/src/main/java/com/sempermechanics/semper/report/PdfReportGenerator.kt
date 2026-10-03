// PDF assembly: literal DPI/page dimensions are inherent to layout, and the
// broad catches guard a whole document render (any failure aborts that page),
// so MagicNumber / TooGenericExceptionCaught are suppressed for this file.
@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.sempermechanics.semper.report

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
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

    /** Raster width for the vector wordmark; PDF draws it at [PdfLayoutEngine.BRAND_LOGO_WIDTH]. */
    private const val BRAND_LOGO_RASTER_WIDTH = 1040

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
    ): Flow<Progress> = renderPdf(outputStream, resources) { layout ->
        // The session's engine stats are its first frame's, so one page
        // closes the document; its ZNSSD is pooled over every frame drawn.
        // Holds no bitmaps, so it survives the recycling below.
        var telemetrySource: ReportData? = null
        val znssdFrames = mutableListOf<ZnssdFrame>()

        for (index in 0 until frameCount) {
            currentCoroutineContext().ensureActive()
            val percent = FRAMES_PROGRESS_START + (index * FRAMES_PROGRESS_SPAN / frameCount)
            emit(Progress.Status("Frame ${index + 1} of $frameCount…", percent))
            val data = dataAt(index) ?: continue
            if (telemetrySource == null) telemetrySource = data
            znssdFrames += ZnssdFrame(data.globalAvgZnssd, data.znssdAcceptedPoints)

            try {
                drawCoverPage(layout, data, frameTitle(index), frameCount)
                drawFieldPages(layout, data)
            } finally {
                // A page that fails to draw still frees this frame's images.
                recycleImages(data)
            }
        }

        telemetrySource?.let {
            emit(Progress.Status("Compiling Engine Telemetry...", TELEMETRY_PROGRESS))
            drawTelemetryPage(layout, it, TelemetrySummary.batch(znssdFrames))
        }
    }.flowOn(Dispatchers.Default)

    fun generate(
        data: ReportData,
        outputStream: OutputStream,
        resources: Resources? = null,
    ): Flow<Progress> = renderPdf(outputStream, resources) { layout ->
        currentCoroutineContext().ensureActive()
        emit(Progress.Status("Building Cover Page...", 10))
        drawCoverPage(layout, data, "Master DIC Analysis Report", frameCount = null)

        currentCoroutineContext().ensureActive()
        emit(Progress.Status("Rendering Visualization Maps...", 30))
        drawFieldPages(layout, data)

        currentCoroutineContext().ensureActive()
        emit(Progress.Status("Compiling Engine Telemetry...", 90))
        drawTelemetryPage(layout, data, TelemetrySummary.single(data))

        emit(Progress.Status("Finalizing PDF...", 98))
    }.flowOn(Dispatchers.IO)

    /**
     * One PDF document into [outputStream]: [draw] lays its pages out on a
     * [PdfLayoutEngine] (the wordmark from [resources] heads each page), then
     * the document is written and [Progress.Complete] follows. A failure is
     * emitted as [Progress.Error] rather than thrown; cancellation is not caught.
     */
    private fun renderPdf(
        outputStream: OutputStream,
        resources: Resources?,
        draw: suspend FlowCollector<Progress>.(PdfLayoutEngine) -> Unit,
    ): Flow<Progress> = flow {
        val pdfDocument = PdfDocument()
        val brandLogo = decodeBrandLogo(resources)
        val layout = PdfLayoutEngine(pdfDocument, brandLogo)
        try {
            draw(layout)
            // Finish the still-open page before writing — PdfDocument rejects
            // writeTo()/close() while any page is unfinished.
            layout.finishCurrentPage()
            writeChecked(pdfDocument, outputStream)
            emit(Progress.Complete)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Progress.Error(e))
        } finally {
            // PdfDocument.close() throws IllegalStateException while a page is
            // unfinished, so a draw that failed mid-page would otherwise replace
            // the Error (or a cancellation) with that throw and skip the close.
            layout.finishCurrentPage()
            pdfDocument.close()
            recycleLogo(brandLogo)
        }
    }

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
        // strainWindow is the VSG in px; sessions since the window was entered in points also get the count.
        val windowPoints = VsgStudy.windowPointsFor(data.strainWindow, data.stepSize)
        layout.drawKeyValue(
            "Strain Window:",
            if (windowPoints != null) {
                "$windowPoints-point window (VSG ${data.strainWindow} px)"
            } else {
                "VSG ${data.strainWindow} px"
            },
        )
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

    /**
     * Final page: engine telemetry (shared by single and batch reports).
     * [summary] says what the quality rows are over — one frame, or pooled.
     */
    private fun drawTelemetryPage(layout: PdfLayoutEngine, data: ReportData, summary: TelemetrySummary) {
        layout.newPage()
        layout.drawTitle("Engine Performance Log")
        summary.scopeNote?.let { layout.drawNotice(it) }

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
            rows = summary.qualityRows(stats),
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
            rows = TelemetrySummary.timingRows(stats),
            colWeights = listOf(0.6f, 0.4f),
        )
    }

    /**
     * [PdfDocument.writeTo] drops an IOException its stream throws: the native
     * writer stops, and the call returns normally. A full disk would then
     * report Complete over a truncated file, so the stream's first failure is
     * kept and rethrown here.
     */
    private fun writeChecked(pdfDocument: PdfDocument, out: OutputStream) {
        val checked = CheckedStream(out)
        pdfDocument.writeTo(checked)
        checked.failure?.let { throw it }
    }

    private class CheckedStream(private val out: OutputStream) : OutputStream() {
        var failure: IOException? = null
            private set

        override fun write(b: Int) = guard { out.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) = guard { out.write(b, off, len) }

        override fun flush() = guard { out.flush() }

        private inline fun guard(block: () -> Unit) {
            failure?.let { throw it }
            try {
                block()
            } catch (e: IOException) {
                failure = e
                throw e
            }
        }
    }

    private fun decodeBrandLogo(resources: Resources?): Bitmap? {
        val src = resources ?: return null
        // Reports are always printed on a light page, so ignore night fills.
        val lightConfig = Configuration(src.configuration)
        lightConfig.uiMode = (lightConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            Configuration.UI_MODE_NIGHT_NO
        @Suppress("DEPRECATION")
        val lightResources = Resources(src.assets, src.displayMetrics, lightConfig)
        val drawable = ResourcesCompat.getDrawable(lightResources, R.drawable.semper_wordmark, null)
        return if (drawable == null) {
            null
        } else {
            val intrinsicW = drawable.intrinsicWidth.coerceAtLeast(1)
            val intrinsicH = drawable.intrinsicHeight.coerceAtLeast(1)
            val width = BRAND_LOGO_RASTER_WIDTH
            val height = (width.toLong() * intrinsicH / intrinsicW).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(width, height)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            bitmap
        }
    }

    private fun recycleLogo(logo: Bitmap?) {
        if (logo != null && !logo.isRecycled) logo.recycle()
    }
}
