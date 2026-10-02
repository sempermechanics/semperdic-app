// Share sheet UI: one row per export kind, each started through [runJob]; the
// generators live in ShareExportBuilder.

package com.indicvision.semper.ui.viewer.share

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import com.indicvision.semper.R
import com.indicvision.semper.data.account.LicenseEntitlements
import com.indicvision.semper.databinding.SheetShareBinding
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.field.FrameParams
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.report.ReportImageNames
import com.indicvision.semper.ui.common.dialog.CrispToast
import com.indicvision.semper.ui.common.dialog.inflateSheet
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
        val sheet = inflateSheet(host, R.layout.sheet_share)
        val v = SheetShareBinding.bind(sheet.view)

        val frames = s.batchFiles.size
        val frameName = s.nameAt(s.frameIndex) ?: "Frame ${s.plannedAt(s.frameIndex) + 1}"
        val res = host.resources
        v.tvShareCaption.text = res.getQuantityString(R.plurals.share_caption_fmt, frames, s.frameIndex + 1, frames)
        v.tvSharePhotoSub.text = host.getString(R.string.share_photo_sub_fmt, s.typeString, frameName)
        val allName = s.sourceImageName(s.frameIndex) ?: frameName
        v.tvShareAllPhotosSub.text =
            res.getQuantityString(R.plurals.share_all_photos_sub_fmt, FIELDS.size, FIELDS.size, allName)
        v.tvSharePdfSub.text = res.getQuantityString(R.plurals.share_pdf_sub_fmt, frames, frames)
        v.tvShareCsvSub.text = res.getQuantityString(R.plurals.share_csv_sub_fmt, frames, frames)

        sheet.row(R.id.rowSharePhoto) { runJob(ShareKind.PHOTO) }
        sheet.row(R.id.rowShareAllPhotos) { offerSlowExport(ShareKind.PHOTOS, s) }
        // Parameter sweeps are not a time series — no summary GIF and no Animations row.
        if (s.isSweep) {
            v.rowShareAnimations.visibility = View.GONE
        } else {
            sheet.row(R.id.rowShareAnimations) { offerSlowExport(ShareKind.GIFS, s) }
        }
        sheet.row(R.id.rowSharePdf) { offerSlowExport(ShareKind.PDF, s) }
        sheet.row(R.id.rowShareCsv) { offerSlowExport(ShareKind.CSV, s) }
        sheet.row(R.id.rowShareZip) { offerSlowExport(ShareKind.ZIP, s) }
        sheet.show()
    }

    // ── Job runner: ShareExportJobs (survives rotation) → share sheet / file ──

    /**
     * Save vs Share before any generation. Save opens SAF immediately; Share
     * still has to wait on the job, then hands the file to the system sheet.
     */
    private fun offerSlowExport(kind: ShareKind, s: Snapshot) {
        SendToSheet.showChooser(
            host,
            onSave = { host.pickShareDocument(kind, suggestedName(kind, s)) },
            onShare = { runJob(kind, direct = true) },
        )
    }

    /**
     * Writes an export of the kind saved as [kind] (a [ShareKind.wire]) into
     * the picked document [uri]. A kind this build does not know fails the
     * export as any failed export does.
     */
    internal fun writeKindToUri(kind: String, uri: Uri) {
        val known = ShareKind.fromWire(kind)
        if (known == null) {
            Timber.e("Unknown share kind %s", kind)
            CrispToast.show(host, host.getString(R.string.share_failed), long = true)
            return
        }
        runJob(known, destUri = uri)
    }

    /** The name the save-as picker suggests for an export of [kind] from [s]. */
    private fun suggestedName(kind: ShareKind, s: Snapshot): String = when (kind) {
        ShareKind.PDF -> "${s.baseName}_report.pdf"
        ShareKind.CSV -> "${s.baseName}_data.csv"
        ShareKind.PHOTOS -> "${s.baseName}_fields_frame${s.frameIndex + 1}.zip"
        ShareKind.GIFS -> "${s.baseName}_animations.zip"
        ShareKind.PHOTO, ShareKind.ZIP ->
            "${s.baseName}_everything_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip"
    }

    /**
     * Starts an export of [kind] in the viewer's [ShareExportJobs], which outlive a
     * rotation; [ShareExportUi] shows its progress and delivers the result.
     *
     * The job is given data only — the snapshot taken here on the main thread,
     * the application's resources and the cache dir — so it never holds this
     * viewer ([ShareExportBuilder]).
     */
    private fun runJob(kind: ShareKind, destUri: Uri? = null, direct: Boolean = false) {
        val s = snap
        if (s == null) {
            Timber.e("Share snapshot unavailable for %s", kind.wire)
            CrispToast.show(host, host.getString(R.string.share_failed), long = true)
            return
        }
        val app = host.applicationContext
        val resources = app.resources
        val cacheDir = app.cacheDir
        host.shareExports.start(kind, host.getString(kind.progressText), destUri, direct) { report ->
            ShareExportBuilder(s, resources, ShareExportBuilder.newJobDir(cacheDir)).produce(kind, report)
        }
    }

    /**
     * Everything the generators need, captured once from the viewer: the frame
     * and field on screen, and the [reportSource] the rest of the analysis is
     * read from.
     */
    data class Snapshot(
        /**
         * The field of the frame on screen, or null while it is still loading —
         * a save-as answer can reach a recreated viewer first. Only the photo
         * kinds need it; read it through [frameData].
         */
        val data: FloatArray?,
        val batchFiles: List<File>,
        /** Filename-safe base for exports, e.g. the specimen/reference name. */
        val baseName: String,
        val frameIndex: Int,
        val dataIndex: Int,
        val typeString: String,
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
    ) {
        val imageSize: ImageSize get() = reportSource.imageSize

        /** Every frame's solver parameters; a sweep's vary frame by frame. */
        val frameParams: FrameParams get() = reportSource.frameParams

        /** True when the frames are a parameter sweep's combinations, not a time series. */
        val isSweep: Boolean get() = reportSource.isSweep

        /** Display-scale reference (null while its decode is in flight); exports prefer [refImagePath]. */
        val baseImage: Bitmap? get() = reportSource.displayBase

        val refImagePath: String? get() = reportSource.args.refPath.ifBlank { null }

        val defImagePaths: List<String> get() = reportSource.defImagePaths

        /** Grid pitch of frame [index] — what rendering that frame depends on. */
        fun stepAt(index: Int): Int = frameParams.at(index).step

        /** The planned frame behind the frame at position [index]. */
        fun plannedAt(index: Int): Int = reportSource.plannedAt(index)

        /** The name of the frame at position [index], or null when it has none. */
        fun nameAt(index: Int): String? = ReportImageNames.frameName(reportSource.frameNames, plannedAt(index))

        /** The field of frame [frameIndex]: the viewer's copy, else read from disk. Off the main thread. */
        fun frameData(): FloatArray = data
            ?: batchFiles.getOrNull(frameIndex)?.let { DicResult.decodeDatFile(it) }
            ?: error("Frame ${frameIndex + 1} is unreadable")

        /** Filename stamped on share photos: the real image, not a sweep settings label. */
        fun sourceImageName(frameIndex: Int): String? =
            if (isSweep) defImagePaths.firstOrNull()?.let { File(it).name } else nameAt(frameIndex)
    }

    private companion object {
        val FIELDS = ShareExportBuilder.FIELDS
    }
}
