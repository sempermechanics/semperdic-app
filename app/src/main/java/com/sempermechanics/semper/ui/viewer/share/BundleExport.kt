// Progress shares and per-field catches: one unrenderable field must not
// abort the archive, and the progress split reads clearest as literals.
@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.sempermechanics.semper.ui.viewer.share

import android.content.res.Resources
import android.graphics.Bitmap
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.imaging.ImageEncoder
import com.sempermechanics.semper.ui.viewer.share.ShareExportBuilder.Companion.FIELDS
import com.sempermechanics.semper.util.Zips
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The everything ZIP of a [ShareExportBuilder] job ([ShareKind.ZIP]): the
 * [DataExport] files and the [FieldImageExport] renders, with the raw photos.
 */
internal class BundleExport(
    private val s: ShareCenter.Snapshot,
    private val resources: Resources,
    private val outDir: File,
    private val images: FieldImageExport,
    private val dataFiles: DataExport,
) {

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
    suspend fun everythingZip(report: ExportReport = NO_REPORT): File {
        // Each stage reports inside its share of the bar ([ZipBudget]): frame by
        // frame, the CSV also within a frame, and the copies into the archive by
        // bytes, so the bar never stands still between stages.
        val budget = ZipBudget(s.isSweep)
        val pdf = dataFiles.allFramesPdf(report.within(budget.pdf))
        val csv = dataFiles.batchCsv(report.within(budget.csv))
        val animations = if (s.isSweep) emptyList() else dataFiles.fieldAnimations(report.within(budget.animations))
        val photos = report.within(budget.photos)
        val adding = resources.getString(R.string.share_progress_zip_photos)
        photos(0.0, adding)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(outDir, "${s.baseName}_everything_$ts.zip")
        val heatmaps = report.within(budget.heatmaps)
        val archive = report.within(budget.archive)
        val writing = resources.getString(R.string.share_progress_zip_writing)
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            val copies = rawPhotos(zip, ts) + animations.map { "photos_$ts/animations/${it.name}" to it }
            putAll(zip, copies, photos.byteCounter(copies.sumOf { it.second.length() }, adding))
            addResultImages(zip, ts) { done, total ->
                heatmaps.step(resources, done, total, R.string.share_progress_zip_heatmaps_fmt)
            }
            archive(0.0, writing)
            // Home of the archive: the data table and the full report.
            val home = listOf("${s.baseName}_data.csv" to csv, "${s.baseName}_report.pdf" to pdf)
            putAll(zip, home, archive.byteCounter(home.sumOf { it.second.length() }, writing))
        }
        report(100.0, writing)
        return f
    }

    private fun putAll(zip: ZipOutputStream, entries: List<Pair<String, File>>, onBytes: (Long) -> Unit) {
        for ((name, file) in entries) Zips.putFile(zip, name, file, onBytes)
    }

    /**
     * `photos_{ts}/raw photos/` — the reference and (best-effort) deformed
     * originals, as entries to copy, reference first. With no reference file on
     * disk the in-memory base image is written to [zip] at once instead.
     */
    private fun rawPhotos(zip: ZipOutputStream, ts: String): List<Pair<String, File>> {
        val dir = "photos_$ts/raw photos"
        val entries = mutableListOf<Pair<String, File>>()

        val refFile = s.refImagePath?.let { File(it) }?.takeIf { it.exists() }
        if (refFile != null) {
            entries += "$dir/reference_${refFile.name}" to refFile
        } else {
            // No persisted reference file (shouldn't happen) — fall back to the
            // in-memory base image so the folder is never empty.
            // No persisted reference path here, so the display base is the only image
            // available — write it as-is (this is the raw-photos folder, not a capped
            // composite).
            s.baseImage?.let { base ->
                zip.putNextEntry(ZipEntry("$dir/reference.png"))
                base.compress(Bitmap.CompressFormat.PNG, ImageEncoder.PNG_QUALITY_MAX, zip)
                zip.closeEntry()
            }
        }

        // Deformed originals persisted in the session dir; names already carry a
        // sortable NNNN_ prefix. Guarded so a missing file can't abort the export.
        for (path in s.defImagePaths) {
            val df = File(path)
            if (df.exists()) entries += "$dir/${df.name}" to df
        }
        return entries
    }

    /**
     * `photos_{ts}/results/<NNN_frame>/` — every field's annotated heatmap per
     * frame. [onProgress] hears (frames done, frame count) before each frame
     * and once when the last is in.
     */
    private fun addResultImages(
        zip: ZipOutputStream,
        ts: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        for ((index, file) in s.batchFiles.withIndex()) {
            onProgress(index, s.batchFiles.size)
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
                images.recycleBaseCache(baseCache)
            }
        }
        onProgress(s.batchFiles.size, s.batchFiles.size)
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
                bmp = images.renderAnnotated(data, idx, label, index, baseCache)
                zip.putNextEntry(ZipEntry("$folder/$label.png"))
                bmp.compress(Bitmap.CompressFormat.PNG, ImageEncoder.PNG_QUALITY_MAX, zip)
                zip.closeEntry()
            } catch (e: Exception) {
                // One unrenderable field shouldn't abort the whole export.
                Timber.w(e, "Skipping %s of frame %d in ZIP export", label, index + 1)
            } finally {
                bmp?.recycle()
            }
        }
    }
}

/**
 * Where each stage of the everything ZIP sits on its progress bar, percent of
 * the whole, in the order the stages run; each starts where the last ended.
 *
 * Shares follow each stage's work. The PDF builds every frame's report images
 * and draws its pages, then writes the document in one `PdfDocument.writeTo`
 * ("Finishing the report"), its last few percent. The CSV's
 * point rows run six `DecimalFormat` calls per solved point, seconds a frame on
 * a dense grid. The heatmaps render five fields a frame and compress each as a
 * PNG. The GIFs are 640 px at most, the raw photos are copied, and the CSV and
 * PDF are deflated into the archive last, which a large CSV makes slow.
 */
internal class ZipBudget(sweep: Boolean) {
    val pdf = 0.0..45.0

    /** A sweep has no animations: its CSV takes their share. */
    val csv = 45.0..(if (sweep) 72.0 else 62.0)
    val animations = csv.endInclusive..72.0
    val photos = 72.0..75.0
    val heatmaps = 75.0..96.0
    val archive = 96.0..100.0

    /** Every stage, in the order it runs. */
    val stages: List<ClosedFloatingPointRange<Double>>
        get() = listOf(pdf, csv, animations, photos, heatmaps, archive)
}
