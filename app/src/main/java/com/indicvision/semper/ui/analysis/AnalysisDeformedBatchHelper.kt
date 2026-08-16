@file:Suppress("TooGenericExceptionCaught", "LongMethod", "LongParameterList", "MagicNumber")

package com.indicvision.semper.ui.analysis

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
 * Deformed-frame batch import extracted from [StaticAnalysisActivity].
 * Caps to [DicSettings.maxFrames], caches via [FrameImportHelper], then
 * applies ViewModel state on the main thread.
 */
object AnalysisDeformedBatchHelper {

    fun handle(
        activity: AppCompatActivity,
        viewModel: AnalysisViewModel,
        rawUris: List<Uri>,
        cacheDir: File,
        displayName: (Uri) -> String,
        tvResult: TextView,
        overlayHelper: ComputeOverlayHelper,
        onApplied: () -> Unit,
        onFinished: () -> Unit,
    ): Job {
        val cap = DicSettings.maxFrames(activity, AppRemoteConfig.maxFrames(activity))
        val capped = if (rawUris.size > cap) {
            Toast.makeText(
                activity,
                activity.resources.getQuantityString(R.plurals.frames_capped_fmt, cap, cap),
                Toast.LENGTH_LONG,
            ).show()
            FrameImportHelper.cappedUris(rawUris, cap)
        } else {
            rawUris
        }
        return activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    tvResult.setText(R.string.analysis_caching_images)
                    overlayHelper.processingStartTime = System.currentTimeMillis()
                    overlayHelper.show(
                        title = activity.getString(R.string.analysis_importing_title),
                        status = activity.getString(R.string.analysis_caching_images),
                        showRunTiles = false,
                    )
                }

                // Import starts in PICKER order — skip EXIF/MediaStore date probes
                // here (they opened every URI before any copy and left the overlay
                // stuck at 0% on large PLC picks). Dates resolve when the user
                // sorts by date.
                val uris = capped
                val datesByIndex = List(uris.size) { Long.MAX_VALUE }
                withContext(Dispatchers.Main) {
                    overlayHelper.update(
                        percent = 0,
                        status = activity.resources.getQuantityString(
                            R.plurals.analysis_importing_fmt,
                            uris.size,
                            0,
                            uris.size,
                        ),
                    )
                }

                val batch = FrameImportHelper.importDeformedUris(
                    context = activity,
                    uris = uris,
                    cacheDir = cacheDir,
                    displayName = displayName,
                    onProgress = { done, total ->
                        overlayHelper.update(
                            percent = if (total > 0) done * 100 / total else 0,
                            status = activity.resources.getQuantityString(
                                R.plurals.analysis_importing_fmt,
                                total,
                                done,
                                total,
                            ),
                        )
                    },
                )
                val frameDates = batch?.filePaths?.map { path ->
                    val name = File(path).name
                    val idx = name.take(4).toIntOrNull()
                    if (idx != null && idx in datesByIndex.indices) datesByIndex[idx] else Long.MAX_VALUE
                }

                // The staged directory is already committed. Apply matching
                // ViewModel paths even if lifecycle cancellation lands now.
                withContext(NonCancellable + Dispatchers.Main) {
                    viewModel.clearPreviousResults()
                    viewModel.defOrderMode = FrameOrderMode.PICKER
                    viewModel.defOrderDirection = FrameOrderDirection.ASCENDING
                    if (batch != null) {
                        viewModel.defFilePaths = batch.filePaths
                        viewModel.defOriginalNames = batch.originalNames
                        viewModel.defFrameSizes = batch.frameSizes
                        viewModel.defFrameDates = frameDates.orEmpty()
                        viewModel.defFromVideo = batch.fromVideo
                    } else {
                        viewModel.defFilePaths = emptyList()
                        viewModel.defOriginalNames = emptyList()
                        viewModel.defFrameSizes = emptyMap()
                        viewModel.defFrameDates = emptyList()
                        viewModel.defFromVideo = false
                    }
                    tvResult.text = ""
                    onApplied()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error handling batch")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.error_loading_images, e.message),
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
