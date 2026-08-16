@file:Suppress("TooGenericExceptionCaught", "LongMethod", "LongParameterList")

package com.indicvision.semper.ui.analysis

import android.graphics.Bitmap
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.net.AppRemoteConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Video frame extraction orchestration extracted from [StaticAnalysisActivity].
 * Frame 0 of the segment becomes the reference; the rest feed defFilePaths.
 */
object AnalysisVideoExtractHelper {

    data class AppliedResult(
        val refPreview: Bitmap?,
        val frameCount: Int,
    )

    fun extract(
        activity: AppCompatActivity,
        viewModel: AnalysisViewModel,
        uri: Uri,
        fpsExtract: Double,
        startMs: Long,
        endMs: Long,
        cacheDir: File,
        tvResult: TextView,
        overlayHelper: ComputeOverlayHelper,
        onApplied: (AppliedResult) -> Unit,
        onFinished: () -> Unit,
    ): Job {
        overlayHelper.processingStartTime = System.currentTimeMillis()
        overlayHelper.show(title = "Extracting Frames", status = "Reading video…", showRunTiles = false)

        return activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = VideoFrameExtractor.extract(
                    context = activity,
                    uri = uri,
                    fpsExtract = fpsExtract,
                    startMs = startMs,
                    endMs = endMs,
                    maxFrames = DicSettings.maxFrames(activity, AppRemoteConfig.maxFrames(activity)),
                    cacheDir = cacheDir,
                    onProgress = { percent, status ->
                        overlayHelper.update(percent = percent.toFloat(), status = status)
                    },
                )

                // Extraction has committed its staged files; keep ViewModel
                // state aligned if cancellation arrives before this dispatch.
                withContext(NonCancellable + Dispatchers.Main) {
                    if (result == null) {
                        Toast.makeText(
                            activity,
                            R.string.video_extract_insufficient,
                            Toast.LENGTH_LONG,
                        ).show()
                        return@withContext
                    }

                    viewModel.clearPreviousResults()
                    viewModel.realRefWidth = result.refWidth
                    viewModel.realRefHeight = result.refHeight
                    viewModel.refBytes = result.refPng
                    viewModel.refName = result.refName
                    if (!viewModel.hasCustomRoi) {
                        viewModel.roiX = 0
                        viewModel.roiY = 0
                        viewModel.roiW = result.refWidth
                        viewModel.roiH = result.refHeight
                    }
                    viewModel.defFilePaths = result.batch.filePaths
                    viewModel.defOriginalNames = result.batch.originalNames
                    viewModel.defFrameSizes = result.batch.frameSizes
                    viewModel.defFrameDates = emptyList()
                    viewModel.defOrderMode = FrameOrderMode.PICKER
                    viewModel.defOrderDirection = FrameOrderDirection.ASCENDING
                    viewModel.defFromVideo = result.batch.fromVideo

                    onApplied(
                        AppliedResult(
                            refPreview = result.refPreview,
                            frameCount = result.batch.filePaths.size,
                        ),
                    )
                    Toast.makeText(
                        activity,
                        activity.resources.getQuantityString(
                            R.plurals.video_loaded_frames,
                            result.batch.filePaths.size,
                            result.batch.filePaths.size,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error extracting video frames")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.video_read_error, e.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    overlayHelper.hide()
                    tvResult.text = ""
                    onFinished()
                }
            }
        }
    }
}
