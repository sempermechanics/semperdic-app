// A frame that will not decode, or a batch with nothing deformed in it,
// aborts the write where it is found rather than part-writing a session.
@file:Suppress("LongParameterList", "ReturnCount")

package com.indicvision.semper.imaging.video

import android.content.Context
import android.graphics.Bitmap
import com.indicvision.semper.R
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.GrayPngEncoder
import com.indicvision.semper.ui.analysis.frames.FrameImportHelper
import com.indicvision.semper.ui.analysis.frames.ImportedBatch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Writes decoded frames into the staging batch [VideoFrameExtractor] returns.
 *
 * Every import path — the AVI demuxer, the hardware decoder, the retriever
 * fallback — ends here, so the reference frame, the file naming and the commit
 * are decided once and all three produce identical batches.
 */
internal object VideoFrameBatchWriter {

    private const val PERCENT = 100

    /**
     * Writes [count] decoded frames as lossless grayscale PNGs: frame 0 is the
     * reference, the rest are the deformed batch. Null when a frame fails to
     * decode or nothing deformed came out.
     */
    suspend fun write(
        context: Context,
        count: Int,
        lumaAt: (Int) -> GrayPngEncoder.Luma?,
        startMs: Long,
        cacheDir: File,
        stagingDir: File,
        onProgress: (percent: Int, status: String) -> Unit,
    ): VideoFrameExtractor.ExtractionResult? {
        val defPaths = mutableListOf<String>()
        var refPng: ByteArray? = null
        var refWidth = 0
        var refHeight = 0
        var refPreview: Bitmap? = null

        for (i in 0 until count) {
            currentCoroutineContext().ensureActive()
            val luma = lumaAt(i) ?: return null
            currentCoroutineContext().ensureActive()

            if (i == 0) {
                refWidth = luma.outWidth
                refHeight = luma.outHeight
                val out = ByteArrayOutputStream()
                GrayPngEncoder.encode(out, luma)
                refPng = out.toByteArray()
                refPreview = BitmapDecode.decodeByteArrayCapped(refPng, BitmapDecode.PREVIEW_MAX_EDGE)
            } else {
                val f = File(stagingDir, String.format(Locale.US, "%04d_frame.png", i))
                FileOutputStream(f).use { out -> GrayPngEncoder.encode(out, luma) }
                defPaths.add(f.absolutePath)
            }

            val status = context.getString(R.string.video_extracting_progress_fmt, i + 1, count)
            onProgress((i + 1) * PERCENT / count, status)
        }

        val pngBytes = refPng ?: return null
        if (defPaths.isEmpty()) return null

        return assemble(
            cacheDir = cacheDir,
            stagingDir = stagingDir,
            refPng = pngBytes,
            refWidth = refWidth,
            refHeight = refHeight,
            refPreview = refPreview,
            startMs = startMs,
            defPaths = defPaths,
        )
    }

    /** Commits the staged PNGs as an [ImportedBatch] and names the reference frame. */
    suspend fun assemble(
        cacheDir: File,
        stagingDir: File,
        refPng: ByteArray,
        refWidth: Int,
        refHeight: Int,
        refPreview: Bitmap?,
        startMs: Long,
        defPaths: List<String>,
    ): VideoFrameExtractor.ExtractionResult {
        val sortedDefPaths = defPaths.sorted()
        val videoFrameSize = refWidth to refHeight
        val stagedBatch = ImportedBatch(
            filePaths = sortedDefPaths,
            originalNames = sortedDefPaths.mapIndexed { idx, _ ->
                String.format(Locale.US, "frame_%04d.png", idx + 1)
            },
            frameSizes = sortedDefPaths.associateWith { videoFrameSize },
            fromVideo = true,
        )
        currentCoroutineContext().ensureActive()
        val batch = requireNotNull(
            FrameImportHelper.commitStagedBatch(cacheDir, stagingDir, stagedBatch),
        )

        return VideoFrameExtractor.ExtractionResult(
            refPng = refPng,
            refWidth = refWidth,
            refHeight = refHeight,
            refName = "video @ ${VideoFrameExtractor.formatClock(startMs)}",
            refPreview = refPreview,
            batch = batch,
        )
    }
}
