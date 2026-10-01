// The share sheet's generators, one method per export target (PNG, CSV, PDF,
// GIFs, bundle), with broad IO catches around file writes; literal quality
// constants read clearest inline, and guard clauses read best as early returns,
// so these rules are suppressed for this file.
@file:Suppress("MagicNumber", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

package com.indicvision.semper.ui.viewer

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.indicvision.semper.DicResult
import com.indicvision.semper.data.CacheJanitor
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.ImageEncode
import com.indicvision.semper.report.AnalysisCsvWriter
import com.indicvision.semper.report.FieldRangesStore
import com.indicvision.semper.report.PdfReportGenerator
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.ReportImageNames
import com.indicvision.semper.report.VisualizationEngine
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds one share/export job's file off the main thread, from data only: the
 * viewer's [ShareCenter.Snapshot] (captured on the main thread), application
 * [resources] and the job's own [outDir]. It holds no Activity, so a job that
 * outlives a rotation in [ShareExportJobs] does not keep the old viewer alive.
 *
 * Every job writes into a directory of its own ([newJobDir]): two jobs running
 * at once (one in the banner, one in the dialog) produce files of the same
 * name — `<base>_report.pdf`, `<base>_data.csv` — and must not truncate each
 * other's. The user-visible names are unchanged.
 */
internal class ShareExportBuilder(
    private val s: ShareCenter.Snapshot,
    private val resources: Resources,
    private val outDir: File,
) {

    /**
     * The file for job [kind] and its MIME type. Throws when the generator
     * produced nothing usable, which the job reports as "share failed".
     */
    suspend fun produce(kind: String, report: (Int, String) -> Unit): Pair<File, String> {
        val (files, mime) = buildKind(kind, report)
        // Safety: never hand an empty or missing file to the share sheet —
        // a generator that silently produced nothing would otherwise share
        // a 0-byte document.
        check(files.isNotEmpty() && files.all { it.exists() && it.length() > 0L }) {
            "Share produced no usable files"
        }
        // SAF saves one document; bundle multi-file exports into a zip first.
        val handoff = if (files.size == 1) {
            files[0] to mime
        } else {
            zipInto(files, "${s.baseName}_export.zip") to "application/zip"
        }
        check(handoff.first.exists() && handoff.first.length() > 0L) { "Bundled export was empty" }
        return handoff
    }

    private suspend fun buildKind(
        kind: String,
        report: (Int, String) -> Unit,
    ): Pair<List<File>, String> = when (kind) {
        KIND_PHOTO -> listOf(currentPhoto()) to "image/png"
        KIND_PDF -> listOf(allFramesPdf(report)) to "application/pdf"
        KIND_ZIP -> listOf(everythingZip(report)) to "application/zip"
        KIND_CSV -> listOf(batchCsv()) to "text/csv"
        KIND_PHOTOS -> allFieldPhotos() to "image/png"
        KIND_GIFS -> {
            check(s.stepPerFrame == null) { "Animations are not offered for sweeps" }
            fieldAnimations() to "image/gif"
        }
        else -> error("Unknown share kind $kind")
    }

    /** Bundle several files into a single zip — the SAF picker saves one document. */
    private fun zipInto(files: List<File>, zipName: String): File {
        val out = File(outDir, zipName)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    // ── Generators ───────────────────────────────────────────────────────

    /**
     * Annotated PNG of one field for one frame's data. Composited at the
     * [VisualizationEngine.REPORT_MAX_EDGE]-capped size rather than full sensor
     * resolution: a 26 MP reference otherwise held four ~100 MB ARGB bitmaps at once
     * (heatmap + base + out + decode) per field. Marker coordinates are scaled by the
     * same factor, mirroring [ReportBuilder.buildReport].
     */
    private fun renderAnnotated(
        data: FloatArray,
        dataIndex: Int,
        typeString: String,
        frameIndex: Int,
        baseCache: MutableMap<Pair<Int, Int>, Bitmap>? = null,
    ): Bitmap {
        // Optional cache: multi-field export reuses one decoded reference bitmap.
        val renderScale = VisualizationEngine.cappedRenderScale(s.imgW, s.imgH, VisualizationEngine.REPORT_MAX_EDGE)
        val renderW = (s.imgW * renderScale).toInt().coerceAtLeast(1)
        val renderH = (s.imgH * renderScale).toInt().coerceAtLeast(1)

        val (heatmap, actualMin, actualMax) = VisualizationEngine.generateHeatmap(
            data,
            s.imgW,
            s.imgH,
            dataIndex,
            s.stepAt(frameIndex),
            null,
            null,
            maxLongEdge = VisualizationEngine.REPORT_MAX_EDGE,
        )
        // Each full-size bitmap is freed in finally, so a throw part-way (no
        // reference to draw on, an OOM) does not strand the others.
        var base: Bitmap? = null
        var out: Bitmap? = null
        var done = false
        try {
            base = loadCappedBase(s, renderW, renderH, baseCache)
            out = createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(base, null, Rect(0, 0, renderW, renderH), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawBitmap(heatmap, 0f, 0f, Paint().apply { alpha = HEATMAP_ALPHA })
            // Signed, as the PDF does: with absolute values "MIN" marked the strain
            // nearest zero under a label giving the most negative.
            val extrema = ReportBuilder.computeFieldExtrema(data, dataIndex, absoluteStrainValues = false)
            val unit = if (DicResult.isStrainFieldIndex(dataIndex)) "mε" else "px"
            ReportBuilder.bakeAnnotationsToCanvas(
                canvas, renderW, renderH, actualMin, actualMax,
                typeString, unit, extrema.maxIdx, extrema.minIdx, data,
                dataIndex = dataIndex,
                coordScale = renderScale,
                imageName = s.sourceImageName(frameIndex),
            )
            done = true
            return out
        } finally {
            heatmap.recycle()
            // A cached base belongs to the cache; the display bitmap to the viewer.
            if (baseCache == null && base != null && base !== s.baseImage) base.recycle()
            if (!done) out?.recycle()
        }
    }

    /**
     * Reference image for compositing, decoded no larger than the capped composite it
     * draws into — prefer the on-disk reference (inSampleSize-decoded) over the
     * viewer's display bitmap so export quality doesn't depend on viewer scale.
     */
    private fun loadCappedBase(
        s: ShareCenter.Snapshot,
        renderW: Int,
        renderH: Int,
        cache: MutableMap<Pair<Int, Int>, Bitmap>? = null,
    ): Bitmap {
        val key = renderW to renderH
        cache?.get(key)?.let { return it }
        s.refImagePath?.let { path ->
            BitmapDecode.decodeFileForView(
                path,
                renderW,
                renderH,
                VisualizationEngine.REPORT_MAX_EDGE,
                rawWidth = s.imgW,
                rawHeight = s.imgH,
            )?.let { decoded ->
                cache?.put(key, decoded)
                return decoded
            }
        }
        val display = s.baseImage ?: error("No reference image for export")
        val scaled = if (display.width == renderW && display.height == renderH) {
            display
        } else {
            display.scale(renderW, renderH)
        }
        if (scaled !== display) cache?.put(key, scaled)
        return scaled
    }

    private fun recycleBaseCache(cache: MutableMap<Pair<Int, Int>, Bitmap>, s: ShareCenter.Snapshot) {
        cache.values.forEach { bmp ->
            if (bmp !== s.baseImage) bmp.recycle()
        }
        cache.clear()
    }

    /** Writes [bmp] as a PNG and frees it, whether or not the write succeeds. */
    private fun writePng(bmp: Bitmap, name: String): File {
        try {
            val f = File(outDir, name)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, it) }
            return f
        } finally {
            bmp.recycle()
        }
    }

    private fun currentPhoto(): File {
        return writePng(
            renderAnnotated(s.frameData(), s.dataIndex, s.typeString, s.frameIndex),
            "${s.baseName}_${s.typeString}_frame${s.frameIndex + 1}.png",
        )
    }

    private fun allFieldPhotos(): List<File> {
        val data = s.frameData()
        val baseCache = mutableMapOf<Pair<Int, Int>, Bitmap>()
        return try {
            FIELDS.map { (label, idx) ->
                writePng(
                    renderAnnotated(data, idx, label, s.frameIndex, baseCache),
                    "${s.baseName}_${label}_frame${s.frameIndex + 1}.png",
                )
            }
        } finally {
            recycleBaseCache(baseCache, s)
        }
    }

    /**
     * All five fields as looping GIFs, each covering every frame on that field's
     * whole-sequence colour scale.
     *
     * Shared as a set rather than one at a time: the point of the animations is
     * that they are directly comparable, which only holds if you have them all.
     * Fields the viewer has not rendered yet are built here, so sharing works
     * the moment the screen opens.
     */
    private suspend fun fieldAnimations(): List<File> {
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
    private fun batchCsv(): File {
        val sweep = s.stepPerFrame != null
        // A sweep ran every combination against the one image; a batch has one
        // image per frame.
        val sweepImage = s.defImagePaths.firstOrNull()?.let { File(it).name } ?: "image"
        val frames = s.batchFiles.mapIndexed { index, file ->
            AnalysisCsvWriter.Frame(
                // Named as the cloud bundle's CSV names it, by the planned frame.
                image = if (sweep) sweepImage else ReportImageNames.deformed(s.defNames, s.plannedAt(index)),
                subset = s.subsetPerFrame?.getOrNull(index) ?: s.subset,
                step = s.stepPerFrame?.getOrNull(index) ?: s.step,
                strainWindow = s.strainWindowPerFrame?.getOrNull(index) ?: s.strainWindow,
                data = { DicResult.decodeDatFile(file) },
            )
        }
        val metadata = AnalysisCsvWriter.Metadata(
            referenceName = s.referenceName.ifBlank { s.baseName },
            strainMethod = s.strainMethod,
            imgW = s.imgW,
            imgH = s.imgH,
            roiX = s.roiX,
            roiY = s.roiY,
            roiW = s.roiW,
            roiH = s.roiH,
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
    private suspend fun allFramesPdf(report: (Int, String) -> Unit = { _, _ -> }): File {
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

    /**
     * The complete-bundle ZIP (`{ts}` = capture time, `yyyyMMdd_HHmmss`):
     * ```
     * ├── {base}_data.csv                      (root)
     * ├── {base}_report.pdf                    (root)
     * └── photos_{ts}/
     *     ├── raw photos/                      reference + deformed originals
     *     ├── animations/                      U..Exy GIFs (single-setting only)
     *     └── results/<NNN_frame>/             U, V, Exx, Eyy, Exy per frame
     * ```
     */
    private suspend fun everythingZip(report: (Int, String) -> Unit = { _, _ -> }): File {
        // The PDF is the long pole; give it the first 60% of the bar, then the
        // per-frame result images the last 40%.
        val pdf = allFramesPdf { pct, label -> report(pct * 60 / 100, label) }
        val csv = batchCsv()
        val animations = if (s.stepPerFrame != null) emptyList() else fieldAnimations()
        report(62, "Bundling files…")
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(outDir, "${s.baseName}_everything_$ts.zip")
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            addRawPhotos(zip, s, ts)
            for (gif in animations) {
                zip.putNextEntry(ZipEntry("photos_$ts/animations/${gif.name}"))
                gif.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            addResultImages(zip, s, ts) { done, total ->
                report(70 + (if (total > 0) done * 30 / total else 0), "Adding result images…")
            }
            // Home of the archive: the data table and the full report.
            zip.putNextEntry(ZipEntry("${s.baseName}_data.csv"))
            csv.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("${s.baseName}_report.pdf"))
            pdf.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        report(100, "Bundling files…")
        return f
    }

    /** `photos_{ts}/raw photos/` — the reference and (best-effort) deformed originals. */
    private fun addRawPhotos(zip: ZipOutputStream, s: ShareCenter.Snapshot, ts: String) {
        val dir = "photos_$ts/raw photos"

        val refFile = s.refImagePath?.let { File(it) }?.takeIf { it.exists() }
        if (refFile != null) {
            zip.putNextEntry(ZipEntry("$dir/reference_${refFile.name}"))
            refFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        } else {
            // No persisted reference file (shouldn't happen) — fall back to the
            // in-memory base image so the folder is never empty.
            // No persisted reference path here, so the display base is the only image
            // available — write it as-is (this is the raw-photos folder, not a capped
            // composite).
            s.baseImage?.let { base ->
                zip.putNextEntry(ZipEntry("$dir/reference.png"))
                base.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, zip)
                zip.closeEntry()
            }
        }

        // Deformed originals persisted in the session dir; names already carry a
        // sortable NNNN_ prefix. Guarded so a missing file can't abort the export.
        for (path in s.defImagePaths) {
            val df = File(path)
            if (!df.exists()) continue
            zip.putNextEntry(ZipEntry("$dir/${df.name}"))
            df.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** `photos_{ts}/results/<NNN_frame>/` — every field's annotated heatmap per frame. */
    private fun addResultImages(
        zip: ZipOutputStream,
        s: ShareCenter.Snapshot,
        ts: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        for ((index, file) in s.batchFiles.withIndex()) {
            onProgress(index + 1, s.batchFiles.size)
            val data = DicResult.decodeDatFile(file) ?: continue
            // Numbered by the planned frame, like the cloud bundle's Frame_N, so
            // a frame after a skipped one keeps its own number and name.
            val frameNumber = s.plannedAt(index) + 1
            val prefix = frameNumber.toString().padStart(3, '0')
            val frameName = s.nameAt(index)?.substringBeforeLast('.') ?: "Frame_$frameNumber"
            val folder = "photos_$ts/results/${prefix}_$frameName"
            val baseCache = mutableMapOf<Pair<Int, Int>, Bitmap>()
            try {
                addFrameResultImages(zip, data, index, folder, baseCache)
            } finally {
                recycleBaseCache(baseCache, s)
            }
        }
    }

    private fun addFrameResultImages(
        zip: ZipOutputStream,
        data: FloatArray,
        index: Int,
        folder: String,
        baseCache: MutableMap<Pair<Int, Int>, Bitmap>,
    ) {
        for ((label, idx) in FIELDS) {
            var bmp: Bitmap? = null
            try {
                bmp = renderAnnotated(data, idx, label, index, baseCache)
                zip.putNextEntry(ZipEntry("$folder/$label.png"))
                bmp.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, zip)
                zip.closeEntry()
            } catch (e: Exception) {
                // One unrenderable field shouldn't abort the whole export.
                Timber.w(e, "Skipping %s of frame %d in ZIP export", label, index + 1)
            } finally {
                bmp?.recycle()
            }
        }
    }

    internal companion object {
        const val HEATMAP_ALPHA = 180

        val FIELDS = listOf(
            "U" to DicResult.IDX_U,
            "V" to DicResult.IDX_V,
            "Exx" to DicResult.IDX_EXX,
            "Eyy" to DicResult.IDX_EYY,
            "Exy" to DicResult.IDX_EXY,
        )

        const val KIND_PDF = "pdf"
        const val KIND_ZIP = "zip"
        const val KIND_CSV = "csv"
        const val KIND_PHOTOS = "photos"
        const val KIND_GIFS = "gifs"

        /** The current frame's photo; shared straight away, never saved-as. */
        const val KIND_PHOTO = "photo"

        /**
         * A fresh directory for one job's files under the share dir. The FileProvider
         * `cache-path` covers `share/` and everything under it, and the share-dir
         * sweep in [CacheJanitor] removes whole entries, directories included, once
         * they are a day old. Disk I/O: call it off the main thread.
         *
         * `File.mkdir` rather than `Files.createTempDirectory`, which needs API 26
         * (minSdk is 24); a directory another job made first is skipped, as
         * `mkdir` fails on one that exists.
         */
        fun newJobDir(cacheDir: File): File {
            val shareDir = CacheJanitor.shareDir(cacheDir)
            repeat(JOB_DIR_ATTEMPTS) {
                val dir = File(shareDir, "job-${UUID.randomUUID()}")
                if (dir.mkdir()) return dir
            }
            error("Could not create a share job directory in $shareDir")
        }

        private const val JOB_DIR_ATTEMPTS = 8
    }
}
