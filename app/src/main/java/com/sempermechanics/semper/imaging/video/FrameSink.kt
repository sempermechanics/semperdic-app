package com.sempermechanics.semper.imaging.video

import android.content.Context
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.imaging.BitmapDecoder
import com.sempermechanics.semper.imaging.GrayPngEncoder
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.frames.ImportedBatch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Where one extraction's frames go: the staging batch [VideoFrameExtractor]
 * returns, with progress reported as each frame lands.
 *
 * Every import path — the AVI demuxer, the hardware decoder, the retriever
 * fallback — ends here, so the reference frame, the file naming and the commit
 * are decided once and all three produce identical batches. A frame that will
 * not decode, or a batch with nothing deformed in it, aborts the write where it
 * is found rather than part-writing a session.
 *
 * @param startMs where the sampled segment starts; it names the reference.
 */
internal class FrameSink(
    private val context: Context,
    private val cacheDir: File,
    private val stagingDir: File,
    private val startMs: Long,
    private val onProgress: (percent: Int, status: String) -> Unit,
) {

    /** The staged file of the frame at sample [index] (index 0 is the reference). */
    fun deformedFile(index: Int): File = File(stagingDir, String.format(Locale.US, "%04d_frame.png", index))

    /** Reports that the frame at sample [index] of [count] is done. */
    fun progress(index: Int, count: Int) {
        val status = context.getString(R.string.video_extracting_progress_fmt, index + 1, count)
        onProgress((index + 1) * PERCENT / count, status)
    }

    /**
     * Writes [count] decoded frames as lossless grayscale PNGs: frame 0 is the
     * reference, the rest are the deformed batch. Null when a frame fails to
     * decode or nothing deformed came out.
     */
    suspend fun write(count: Int, lumaAt: (Int) -> GrayPngEncoder.Luma?): VideoFrameExtractor.ExtractionResult? {
        val defPaths = mutableListOf<String>()
        var reference: ReferenceFrame? = null

        for (i in 0 until count) {
            currentCoroutineContext().ensureActive()
            val luma = lumaAt(i) ?: return null
            currentCoroutineContext().ensureActive()

            if (i == 0) {
                val out = ByteArrayOutputStream()
                GrayPngEncoder.encode(out, luma)
                val png = out.toByteArray()
                reference = ReferenceFrame(
                    png = png,
                    size = ImageSize(luma.outWidth, luma.outHeight),
                    preview = BitmapDecoder.decodeByteArrayCapped(png, BitmapDecoder.PREVIEW_MAX_EDGE),
                )
            } else {
                val f = deformedFile(i)
                FileOutputStream(f).use { out -> GrayPngEncoder.encode(out, luma) }
                defPaths.add(f.absolutePath)
            }
            progress(i, count)
        }
        return finish(reference, defPaths)
    }

    /** [assemble], once there is a reference and at least one deformed frame; else null. */
    suspend fun finish(reference: ReferenceFrame?, defPaths: List<String>): VideoFrameExtractor.ExtractionResult? =
        if (reference == null || defPaths.isEmpty()) null else assemble(reference, defPaths)

    /** Commits the staged PNGs as an [ImportedBatch] and names the reference frame. */
    suspend fun assemble(reference: ReferenceFrame, defPaths: List<String>): VideoFrameExtractor.ExtractionResult {
        val stagedBatch = ImportedBatch(
            frames = defPaths.sorted().mapIndexed { idx, path ->
                DeformedFrame(
                    path = path,
                    name = String.format(Locale.US, "frame_%04d.png", idx + 1),
                    size = reference.size,
                )
            },
            fromVideo = true,
        )
        currentCoroutineContext().ensureActive()
        val batch = requireNotNull(
            FrameImportHelper.commitStagedBatch(cacheDir, stagingDir, stagedBatch),
        )

        return VideoFrameExtractor.ExtractionResult(
            reference = reference,
            refName = "video @ ${VideoFrameExtractor.formatClock(startMs)}",
            batch = batch,
        )
    }

    private companion object {
        const val PERCENT = 100
    }
}
