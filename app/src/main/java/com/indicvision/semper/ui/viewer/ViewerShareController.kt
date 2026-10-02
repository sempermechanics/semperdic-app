package com.indicvision.semper.ui.viewer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.indicvision.semper.ui.viewer.share.ShareCenter
import com.indicvision.semper.ui.viewer.share.ShareKind
import com.indicvision.semper.ui.viewer.share.ViewerReportFactory
import com.indicvision.semper.ui.viewer.summary.SummaryAnimation

/**
 * What the viewer hands its exports: the snapshot [ShareCenter] builds every
 * export from, and the save-as document picker for the slow ones. Made while
 * the Activity is constructed, since it registers the picker's result.
 *
 * Constructed before onCreate; reads [ResultViewerActivity.binding] lazily.
 */
internal class ViewerShareController(private val host: ResultViewerActivity) {

    /** Stashed while the SAF save-as picker is open for a slow share export. */
    private var pendingShareKind: ShareKind? = null

    private val createShareDocument = host.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val kind = pendingShareKind
        pendingShareKind = null
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null || kind == null) return@registerForActivityResult
        host.viewerVm.setPendingSave(kind.wire, uri)
        startPendingSave()
    }

    /**
     * Starts the ViewModel's pending save-as export once the frames it covers
     * are known. Taking it clears it, so a viewer recreated after the export
     * started does not start it again.
     */
    fun startPendingSave() = host.whenFrameSetLoaded {
        val (kind, uri) = host.viewerVm.takePendingSave() ?: return@whenFrameSetLoaded
        ShareCenter(host).writeKindToUri(kind, uri)
    }

    /** Puts back the save-as kind a recreated viewer was waiting on. */
    fun restoreState(savedInstanceState: Bundle) {
        pendingShareKind = ShareKind.fromWire(savedInstanceState.getString(STATE_SHARE_KIND))
    }

    fun saveState(outState: Bundle) {
        pendingShareKind?.let { outState.putString(STATE_SHARE_KIND, it.wire) }
    }

    /** What a report page reads from this viewer, as plain data an export can keep. */
    private fun reportSource(): ViewerReportFactory.Source = ViewerReportFactory.Source(
        args = host.args,
        imageSize = host.imageSize,
        plannedFrames = host.plannedFrames,
        defImagePaths = host.defImagePaths,
        displayBase = host.cachedBaseImage,
    )

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
     * there are no frames. The frame on screen may still be loading ([ResultViewerActivity.rawData]
     * null): only the photo kinds need it, and they read it from disk then.
     */
    fun buildShareSnapshot(): ShareCenter.Snapshot? {
        if (!host.frameSetLoaded || host.batchFiles.isEmpty()) return null
        val isSweep = host.isSweep
        return ShareCenter.Snapshot(
            data = host.rawData,
            batchFiles = host.batchFiles,
            baseName = shareBaseName(),
            frameIndex = host.currentFrameIndex,
            dataIndex = host.currentDataIndex,
            typeString = host.currentTypeString,
            summary = if (isSweep) null else host.summary.animation,
            summaryBounds = if (isSweep) {
                emptyMap()
            } else {
                SummaryAnimation.FIELDS.mapNotNull { (_, index) -> host.summary.boundsFor(index)?.let { index to it } }
                    .toMap()
            },
            reportSource = reportSource(),
        )
    }

    /** SAF CreateDocument for a slow export; generation starts only after a URI returns. */
    fun pickShareDocument(kind: ShareKind, filename: String) {
        pendingShareKind = kind
        createShareDocument.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = kind.saveMime
                putExtra(Intent.EXTRA_TITLE, filename)
            },
        )
    }

    private companion object {
        // The viewer's own cap, not SessionNaming's 40: export names have
        // always been cut at 60, and a shorter cap would rename them.
        const val BASE_NAME_MAX = 60

        // Saved-state key; its string is what a restored viewer reads back.
        const val STATE_SHARE_KIND = "PENDING_SHARE_KIND"
    }
}
