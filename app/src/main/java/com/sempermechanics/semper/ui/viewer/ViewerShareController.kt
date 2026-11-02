package com.sempermechanics.semper.ui.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.data.integrity.LocalRecordKey
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.field.FieldKind
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.viewer.share.ExportReport
import com.sempermechanics.semper.ui.viewer.share.ShareCenter
import com.sempermechanics.semper.ui.viewer.share.ShareExportController
import com.sempermechanics.semper.ui.viewer.share.ShareKind
import com.sempermechanics.semper.ui.viewer.share.ShareSource
import com.sempermechanics.semper.ui.viewer.share.ViewerReportFactory
import com.sempermechanics.semper.ui.viewer.summary.ViewerSummaryController
import java.io.File

/** What [ViewerShareController] needs from the viewer to snapshot it for an export. */
internal interface ShareControllerHost {
    /** The frame and field on screen, and the save-as picked but not yet started. */
    val viewModel: ResultViewerViewModel
    val args: ViewerArgs
    val imageSize: ImageSize

    /** True once the frame set is opened: the lists below are final. */
    val frameSetLoaded: Boolean
    val batchFiles: List<File>
    val plannedFrames: List<Int>
    val defImagePaths: List<String>
    val isSweep: Boolean

    /** The frame on screen's data; null while it is still loading. */
    val rawData: FloatArray?

    /** The frame [rawData] is; it lags the ViewModel's frame while a Prev / Next decode is pending. */
    val rawFrameIndex: Int

    /** The stored record behind this viewer, or null when it was opened without one. */
    val sessionRecord: SessionRecord?
}

/**
 * What the viewer hands its exports ([ShareSource]): the snapshot [ShareCenter]
 * builds every export from, the save-as document picker for the slow ones, and
 * [exports], which runs them. Made while the Activity is constructed, since it
 * registers the picker's result with [activity]. The snapshot takes the
 * reference from [images] and the sequence ranges from [summary].
 */
internal class ViewerShareController(
    private val activity: AppCompatActivity,
    private val host: ShareControllerHost,
    private val images: ViewerImageLoader,
    private val summary: ViewerSummaryController,
    private val exports: () -> ShareExportController,
) : ShareSource {

    /** Stashed while the SAF save-as picker is open for a slow share export. */
    private var pendingShareKind: ShareKind? = null

    private val createShareDocument = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val kind = pendingShareKind
        pendingShareKind = null
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null || kind == null) return@registerForActivityResult
        host.viewModel.setPendingSave(kind.wire, uri)
        startPendingSave()
    }

    /**
     * Starts the ViewModel's pending save-as export, once this viewer has its
     * frames: before then it waits, and the viewer calls this again when they
     * are open. Taking it clears it, so a viewer recreated after the export
     * started does not start it again.
     */
    fun startPendingSave() {
        if (!host.frameSetLoaded) return
        val (kind, uri) = host.viewModel.takePendingSave() ?: return
        ShareCenter(activity, this).writeKindToUri(kind, uri)
    }

    /** Puts back the save-as kind a recreated viewer was waiting on. */
    fun restoreState(savedInstanceState: Bundle) {
        pendingShareKind = ShareKind.fromWire(savedInstanceState.getString(STATE_SHARE_KIND))
    }

    fun saveState(outState: Bundle) {
        pendingShareKind?.let { outState.putString(STATE_SHARE_KIND, it.wire) }
    }

    /** What a report page reads from this viewer, as plain data an export can keep. */
    private fun reportSource(): ViewerReportFactory.Source {
        val integrity = host.viewModel.state.value.integrity
        return ViewerReportFactory.Source(
            args = host.args,
            imageSize = host.imageSize,
            plannedFrames = host.plannedFrames,
            defImagePaths = host.defImagePaths,
            displayBase = images.cachedBaseImage,
            integrity = integrity,
            // Only when the viewer's check has not finished; that check read (and
            // cached) the key first, so this is rarely a Keystore call.
            localThumbprint = if (integrity == null) LocalRecordKey.thumbprint(activity.applicationContext) else null,
        )
    }

    /**
     * A filename-safe base for exports, drawn from the specimen/reference name so
     * shared files read like "IMG_0768_report.pdf" instead of a generic prefix.
     * Falls back to the session name, then the first deformed frame, then "analysis".
     */
    private fun shareBaseName(): String {
        val record = host.sessionRecord
        val raw = record?.refName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: record?.name?.takeIf { it.isNotBlank() }
            ?: host.args.frameNames.firstOrNull()?.substringBeforeLast('.')
            ?: "analysis"
        return raw.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(BASE_NAME_MAX).ifBlank { "analysis" }
    }

    /**
     * Everything an export needs, or null before the frame set is read or when
     * there are no frames. The frame on screen may still be loading ([ShareControllerHost.rawData]
     * null, or still the previous frame's after a Prev / Next): then the data
     * is left out, and the photo kinds read the frame asked for from disk, so
     * its data, photo and name are all the one frame.
     */
    override fun buildShareSnapshot(): ShareCenter.Snapshot? {
        if (!host.frameSetLoaded || host.batchFiles.isEmpty()) return null
        val isSweep = host.isSweep
        val frameIndex = host.viewModel.currentFrameIndex
        return ShareCenter.Snapshot(
            data = host.rawData.takeIf { host.rawFrameIndex == frameIndex },
            batchFiles = host.batchFiles,
            baseName = shareBaseName(),
            frameIndex = frameIndex,
            dataIndex = host.viewModel.field.dataIndex,
            typeString = host.viewModel.field.label,
            summary = if (isSweep) null else summary.animation,
            summaryBounds = if (isSweep) {
                emptyMap()
            } else {
                FieldKind.entries.mapNotNull { field ->
                    summary.boundsFor(field.dataIndex)?.let { field.dataIndex to it }
                }.toMap()
            },
            reportSource = reportSource(),
        )
    }

    /** SAF CreateDocument for a slow export; generation starts only after a URI returns. */
    override fun pickShareDocument(kind: ShareKind, filename: String) {
        pendingShareKind = kind
        createShareDocument.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = kind.saveMime
                putExtra(Intent.EXTRA_TITLE, filename)
            },
        )
    }

    override fun startExport(
        kind: ShareKind,
        title: String,
        destUri: Uri?,
        direct: Boolean,
        produce: suspend (report: ExportReport) -> Pair<File, String>,
    ) = exports().start(kind, title, destUri, direct, produce)

    private companion object {
        // The viewer's own cap, not SessionNaming's 40: export names have
        // always been cut at 60, and a shorter cap would rename them.
        const val BASE_NAME_MAX = 60

        // Saved-state key; its string is what a restored viewer reads back.
        const val STATE_SHARE_KIND = "PENDING_SHARE_KIND"
    }
}
