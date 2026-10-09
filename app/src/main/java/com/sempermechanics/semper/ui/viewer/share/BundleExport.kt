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
        // The PDF is the long pole: the first 55% of the bar. Each later stage
        // reports its own frames inside its share, so the bar never stands
        // still between stages (a sweep has no animations; its CSV takes theirs).
        val pdf = dataFiles.allFramesPdf(report.within(0.0, 55.0))
        val csvEnd = if (s.isSweep) 72.0 else 62.0
        val csv = dataFiles.batchCsv(report.within(55.0, csvEnd))
        val animations = if (s.isSweep) emptyList() else dataFiles.fieldAnimations(report.within(csvEnd, 72.0))
        report(72.0, resources.getString(R.string.share_progress_zip_photos))
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(outDir, "${s.baseName}_everything_$ts.zip")
        val heatmaps = report.within(75.0, 98.0)
        val writing = resources.getString(R.string.share_progress_zip_writing)
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            addRawPhotos(zip, ts)
            for (gif in animations) Zips.putFile(zip, "photos_$ts/animations/${gif.name}", gif)
            addResultImages(zip, ts) { done, total ->
                heatmaps.step(resources, done, total, R.string.share_progress_zip_heatmaps_fmt)
            }
            report(98.0, writing)
            // Home of the archive: the data table and the full report.
            Zips.putFile(zip, "${s.baseName}_data.csv", csv)
            Zips.putFile(zip, "${s.baseName}_report.pdf", pdf)
        }
        report(100.0, writing)
        return f
    }

    /** `photos_{ts}/raw photos/` — the reference and (best-effort) deformed originals. */
    private fun addRawPhotos(zip: ZipOutputStream, ts: String) {
        val dir = "photos_$ts/raw photos"

        val refFile = s.refImagePath?.let { File(it) }?.takeIf { it.exists() }
        if (refFile != null) {
            Zips.putFile(zip, "$dir/reference_${refFile.name}", refFile)
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
            if (df.exists()) Zips.putFile(zip, "$dir/${df.name}", df)
        }
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
