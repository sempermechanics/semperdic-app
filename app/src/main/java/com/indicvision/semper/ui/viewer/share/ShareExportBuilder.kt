package com.indicvision.semper.ui.viewer.share

import android.content.res.Resources
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.util.Mime
import com.indicvision.semper.util.Zips
import java.io.File
import java.util.UUID
import java.util.zip.ZipOutputStream

/**
 * Builds one share/export job's file off the main thread, from data only: the
 * viewer's [ShareCenter.Snapshot] (captured on the main thread), application
 * [resources] and the job's own [outDir]. It holds no Activity, so a job that
 * outlives a rotation in [ShareExportJobs] does not keep the old viewer alive.
 *
 * The kinds are built by [FieldImageExport] (annotated PNGs), [DataExport]
 * (CSV, PDF, GIFs) and [BundleExport] (the everything ZIP).
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
    private val images = FieldImageExport(s, outDir)
    private val dataFiles = DataExport(s, resources, outDir)
    private val bundle = BundleExport(s, outDir, images, dataFiles)

    /**
     * The file for job [kind] and its MIME type. Throws when the generator
     * produced nothing usable, which the job reports as "share failed".
     */
    suspend fun produce(kind: ShareKind, report: (Int, String) -> Unit): Pair<File, String> {
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
            zipInto(files, "${s.baseName}_export.zip") to Mime.ZIP
        }
        check(handoff.first.exists() && handoff.first.length() > 0L) { "Bundled export was empty" }
        return handoff
    }

    private suspend fun buildKind(
        kind: ShareKind,
        report: (Int, String) -> Unit,
    ): Pair<List<File>, String> = when (kind) {
        ShareKind.PHOTO -> listOf(images.currentPhoto()) to Mime.PNG
        ShareKind.PDF -> listOf(dataFiles.allFramesPdf(report)) to Mime.PDF
        ShareKind.ZIP -> listOf(bundle.everythingZip(report)) to Mime.ZIP
        ShareKind.CSV -> listOf(dataFiles.batchCsv()) to Mime.CSV
        ShareKind.PHOTOS -> images.allFieldPhotos() to Mime.PNG
        ShareKind.GIFS -> {
            check(!s.isSweep) { "Animations are not offered for sweeps" }
            dataFiles.fieldAnimations() to Mime.GIF
        }
    }

    /** Bundle several files into a single zip — the SAF picker saves one document. */
    private fun zipInto(files: List<File>, zipName: String): File {
        val out = File(outDir, zipName)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (file in files) Zips.putFile(zip, file.name, file)
        }
        return out
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
