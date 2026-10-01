// Share sheet UI: one row per export kind, each started through [runJob]; the
// generators live in ShareExportBuilder.

@file:SuppressLint("InflateParams")

package com.indicvision.semper.ui.viewer.share

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.indicvision.semper.R
import com.indicvision.semper.data.account.LicenseEntitlements
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.report.ReportImageNames
import com.indicvision.semper.ui.common.CrispToast
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import com.indicvision.semper.ui.viewer.summary.SummaryAnimation
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Results share sheet (wireframe 08). One scope rule: photos share the
 * current frame; the PDF and CSV cover the whole analysis; the ZIP bundles
 * everything. Fast single-photo export still generates into `cacheDir/share`
 * then offers Save/Share. Slow exports (PDF, ZIP, all-fields, field GIFs, CSV)
 * pick a Save-to-Files destination first, then write there. Field GIFs are
 * single-setting only; a parameter sweep hides that row.
 */
class ShareCenter(private val host: ResultViewerActivity) {

    private val snap by lazy { host.buildShareSnapshot() }

    private fun ensureShareAllowed(): Boolean {
        if (LicenseEntitlements.shareEnabled(host)) return true
        CrispToast.show(host, host.getString(R.string.share_licensed_only), long = true)
        return false
    }

    fun show() {
        if (!ensureShareAllowed()) return
        val s = snap ?: return
        val sheet = BottomSheetDialog(host)
        val v = host.layoutInflater.inflate(R.layout.sheet_share, null)
        sheet.setContentView(v)

        val frameName = s.nameAt(s.frameIndex) ?: "Frame ${s.plannedAt(s.frameIndex) + 1}"
        v.findViewById<TextView>(R.id.tvShareCaption).text =
            host.resources.getQuantityString(
                R.plurals.share_caption_fmt,
                s.batchFiles.size,
                s.frameIndex + 1,
                s.batchFiles.size,
            )
        v.findViewById<TextView>(R.id.tvSharePhotoSub).text =
            host.getString(R.string.share_photo_sub_fmt, s.typeString, frameName)
        val allName = s.sourceImageName(s.frameIndex) ?: frameName
        v.findViewById<TextView>(R.id.tvShareAllPhotosSub).text =
            host.resources.getQuantityString(
                R.plurals.share_all_photos_sub_fmt,
                FIELDS.size,
                FIELDS.size,
                allName,
            )
        v.findViewById<TextView>(R.id.tvSharePdfSub).text =
            host.resources.getQuantityString(R.plurals.share_pdf_sub_fmt, s.batchFiles.size, s.batchFiles.size)
        v.findViewById<TextView>(R.id.tvShareCsvSub).text =
            host.resources.getQuantityString(R.plurals.share_csv_sub_fmt, s.batchFiles.size, s.batchFiles.size)

        v.findViewById<View>(R.id.rowSharePhoto).setOnClickListener {
            sheet.dismiss()
            runJob(KIND_PHOTO, R.string.share_generating)
        }
        v.findViewById<View>(R.id.rowShareAllPhotos).setOnClickListener {
            sheet.dismiss()
            offerSlowExport(KIND_PHOTOS, "application/zip", s, R.string.share_generating)
        }
        // Parameter sweeps are not a time series — no summary GIF and no Animations row.
        val animationsRow = v.findViewById<View>(R.id.rowShareAnimations)
        if (s.stepPerFrame != null) {
            animationsRow.visibility = View.GONE
        } else {
            animationsRow.setOnClickListener {
                sheet.dismiss()
                offerSlowExport(KIND_GIFS, "application/zip", s, R.string.share_generating_gif)
            }
        }
        v.findViewById<View>(R.id.rowSharePdf).setOnClickListener {
            sheet.dismiss()
            offerSlowExport(KIND_PDF, "application/pdf", s, R.string.share_generating_pdf)
        }
        v.findViewById<View>(R.id.rowShareCsv).setOnClickListener {
            sheet.dismiss()
            offerSlowExport(KIND_CSV, "text/csv", s, R.string.share_generating)
        }
        v.findViewById<View>(R.id.rowShareZip).setOnClickListener {
            sheet.dismiss()
            offerSlowExport(KIND_ZIP, "application/zip", s, R.string.share_generating_pdf)
        }
        sheet.show()
    }

    // ── Job runner: ShareExportJobs (survives rotation) → share sheet / file ──

    /**
     * Save vs Share before any generation. Save opens SAF immediately; Share
     * still has to wait on the job, then hands the file to the system sheet.
     */
    private fun offerSlowExport(
        kind: String,
        mime: String,
        s: Snapshot,
        progressText: Int,
    ) {
        SendToSheet.showChooser(
            host,
            onSave = { host.pickShareDocument(kind, mime, suggestedName(kind, s)) },
            onShare = { runJob(kind, progressText, direct = true) },
        )
    }

    internal fun writeKindToUri(kind: String, uri: Uri) {
        val progressText = when (kind) {
            KIND_PDF, KIND_ZIP -> R.string.share_generating_pdf
            KIND_GIFS -> R.string.share_generating_gif
            else -> R.string.share_generating
        }
        runJob(kind, progressText, destUri = uri)
    }

    /** The name the save-as picker suggests for an export of [kind] from [s]. */
    private fun suggestedName(kind: String, s: Snapshot): String = when (kind) {
        KIND_PDF -> "${s.baseName}_report.pdf"
        KIND_CSV -> "${s.baseName}_data.csv"
        KIND_PHOTOS -> "${s.baseName}_fields_frame${s.frameIndex + 1}.zip"
        KIND_GIFS -> "${s.baseName}_animations.zip"
        else -> "${s.baseName}_everything_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip"
    }

    /**
     * Starts an export of [kind] in the viewer's [ShareExportJobs], which outlive a
     * rotation; [ShareExportUi] shows its progress and delivers the result.
     *
     * The job is given data only — the snapshot taken here on the main thread,
     * the application's resources and the cache dir — so it never holds this
     * viewer ([ShareExportBuilder]).
     */
    private fun runJob(
        kind: String,
        progressText: Int,
        destUri: Uri? = null,
        direct: Boolean = false,
    ) {
        val s = snap
        if (s == null) {
            Timber.e("Share snapshot unavailable for %s", kind)
            CrispToast.show(host, host.getString(R.string.share_failed), long = true)
            return
        }
        val app = host.applicationContext
        val resources = app.resources
        val cacheDir = app.cacheDir
        host.shareExports.start(kind, host.getString(progressText), destUri, direct) { report ->
            ShareExportBuilder(s, resources, ShareExportBuilder.newJobDir(cacheDir)).produce(kind, report)
        }
    }

    /** Everything the generators need, captured once from the viewer. */
    data class Snapshot(
        /**
         * The field of the frame on screen, or null while it is still loading —
         * a save-as answer can reach a recreated viewer first. Only the photo
         * kinds need it; read it through [frameData].
         */
        val data: FloatArray?,
        val batchFiles: List<File>,
        /** Frame names in planned-frame order; look one up with [nameAt]. */
        val defNames: List<String>,
        /** Filename-safe base for exports, e.g. the specimen/reference name. */
        val baseName: String,
        val frameIndex: Int,
        val imgW: Int,
        val imgH: Int,
        val step: Int,
        /**
         * Per-frame step sizes for a parameter sweep, where each frame is a
         * different settings combination. Null for an ordinary analysis, whose
         * frames all share [step]. Its non-null-ness marks a sweep, which the
         * CSV export splits into subset/step/window/VSG columns.
         */
        val stepPerFrame: IntArray?,
        /** Per-frame subset sizes for a sweep; index-aligned with the frames. */
        val subsetPerFrame: IntArray?,
        /** Per-frame strain windows for a sweep; index-aligned with the frames. */
        val strainWindowPerFrame: IntArray?,
        val dataIndex: Int,
        val typeString: String,
        /** Display-scale bitmap (may be null while decode is in flight); exports prefer [refImagePath]. */
        val baseImage: Bitmap?,
        val refImagePath: String?,
        val defImagePaths: List<String>,
        /** Single-setting overview builder; null on a parameter sweep. */
        val summary: SummaryAnimation?,
        /**
         * Each field's GIF colour bounds as the viewer knew them when the job
         * started (a fixed scale, else the sequence range); a field the range
         * pass had not reached yet is absent.
         */
        val summaryBounds: Map<Int, Pair<Float, Float>>,
        /**
         * What a frame's report page needs from the viewer. Each frame of an
         * all-frames report builds its own cover — its parameters and its
         * deformed image — from this, rather than reusing the one on screen.
         */
        val reportSource: ViewerReportFactory.Source,
        val referenceName: String = "",
        val strainMethod: String = "VSG",
        val subset: Int = 41,
        val strainWindow: Int = 15,
        val roiX: Int = 0,
        val roiY: Int = 0,
        val roiW: Int = 0,
        val roiH: Int = 0,
        /**
         * The planned frame behind each of [batchFiles], by position
         * (`SessionPaths.plannedFrameIndices`). Past a frame the batch skipped
         * the position and the planned frame part ways.
         */
        val plannedFrames: List<Int> = emptyList(),
    ) {
        /** Grid pitch of frame [index] — what rendering that frame depends on. */
        fun stepAt(index: Int): Int = stepPerFrame?.getOrNull(index) ?: step

        /** The planned frame behind the frame at position [index]. */
        fun plannedAt(index: Int): Int = plannedFrames.getOrElse(index) { index }

        /** The name of the frame at position [index], or null when it has none. */
        fun nameAt(index: Int): String? = ReportImageNames.frameName(defNames, plannedAt(index))

        /** The field of frame [frameIndex]: the viewer's copy, else read from disk. Off the main thread. */
        fun frameData(): FloatArray = data
            ?: batchFiles.getOrNull(frameIndex)?.let { DicResult.decodeDatFile(it) }
            ?: error("Frame ${frameIndex + 1} is unreadable")

        /** Filename stamped on share photos: the real image, not a sweep settings label. */
        fun sourceImageName(frameIndex: Int): String? =
            if (stepPerFrame != null) defImagePaths.firstOrNull()?.let { File(it).name } else nameAt(frameIndex)
    }
    private companion object {
        val FIELDS = ShareExportBuilder.FIELDS
        const val KIND_PDF = ShareExportBuilder.KIND_PDF
        const val KIND_ZIP = ShareExportBuilder.KIND_ZIP
        const val KIND_CSV = ShareExportBuilder.KIND_CSV
        const val KIND_PHOTOS = ShareExportBuilder.KIND_PHOTOS
        const val KIND_GIFS = ShareExportBuilder.KIND_GIFS
        const val KIND_PHOTO = ShareExportBuilder.KIND_PHOTO
    }
}
