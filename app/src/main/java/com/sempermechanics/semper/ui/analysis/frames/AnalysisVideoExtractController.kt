package com.sempermechanics.semper.ui.analysis.frames

import android.graphics.Bitmap
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.imaging.video.ExtractionRequest
import com.sempermechanics.semper.imaging.video.VideoFrameExtractor
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.run.ComputeOverlayController
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.common.dialog.FaqRedirect
import com.sempermechanics.semper.ui.common.dialog.Feedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Video frame extraction orchestration extracted from [StaticAnalysisActivity].
 * Frame 0 of the segment becomes the reference; the rest feed defFilePaths.
 * The extraction runs on [io].
 */
class AnalysisVideoExtractController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val overlayController: ComputeOverlayController,
    private val tvResult: TextView,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    data class AppliedResult(
        val refPreview: Bitmap?,
        val frameCount: Int,
    )

    /**
     * Extracts [request] into the import cache. [onApplied] runs once the view
     * model holds the new reference and frames; [onFinished] runs however the
     * extraction ends.
     */
    @Suppress("TooGenericExceptionCaught") // any failure becomes the import's snackbar
    fun extract(
        request: ExtractionRequest,
        onApplied: (AppliedResult) -> Unit,
        onFinished: () -> Unit,
    ): Job {
        overlayController.processingStartTime = System.currentTimeMillis()
        overlayController.show(title = "Extracting Frames", status = "Reading video…", showRunTiles = false)

        return activity.lifecycleScope.launch(io) {
            try {
                val result = VideoFrameExtractor.extract(
                    context = activity,
                    request = request,
                    cacheDir = activity.cacheDir,
                    onProgress = { percent, status ->
                        overlayController.update(percent = percent.toFloat(), status = status)
                    },
                )

                // Extraction has committed its staged files; keep ViewModel
                // state aligned if cancellation arrives before this dispatch.
                withContext(NonCancellable + Dispatchers.Main) {
                    if (result == null) {
                        FaqRedirect.snackbar(
                            activity,
                            R.string.video_extract_insufficient,
                            R.string.url_faq_video_extract,
                        )
                    } else {
                        apply(result)
                        onApplied(AppliedResult(result.reference.preview, result.batch.frames.size))
                        val count = result.batch.frames.size
                        Feedback.toast(
                            activity,
                            activity.resources.getQuantityString(R.plurals.video_loaded_frames, count, count),
                            long = true,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error extracting video frames")
                withContext(Dispatchers.Main) {
                    FaqRedirect.snackbar(
                        activity,
                        activity.getString(R.string.video_read_error_fmt, e.message),
                        R.string.url_faq_video_extract,
                    )
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    overlayController.hide()
                    tvResult.text = ""
                    onFinished()
                }
            }
        }
    }

    private fun apply(result: VideoFrameExtractor.ExtractionResult) {
        viewModel.applyNewReference(result.reference.png, result.refName, result.reference.size)
        viewModel.deformedFrames = result.batch.frames
        viewModel.defOrderMode = FrameOrderMode.PICKER
        viewModel.defOrderDirection = FrameOrderDirection.ASCENDING
        viewModel.defFromVideo = result.batch.fromVideo
    }
}
