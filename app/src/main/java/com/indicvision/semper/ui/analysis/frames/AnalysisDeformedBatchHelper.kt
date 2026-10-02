package com.indicvision.semper.ui.analysis.frames

import android.net.Uri
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import com.indicvision.semper.ui.analysis.run.ComputeOverlayHelper
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.common.FaqRedirect
import com.indicvision.semper.ui.common.Feedback
import com.indicvision.semper.util.ProgressCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Deformed-frame batch import extracted from [StaticAnalysisActivity].
 * Caps to [DicSettings.maxFrames], caches via [FrameImportHelper] on [io],
 * then applies ViewModel state on the main thread.
 */
class AnalysisDeformedBatchHelper(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val overlayHelper: ComputeOverlayHelper,
    private val tvResult: TextView,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Imports [rawUris], named by [displayName] (a content query, so it runs
     * on [io]). [onApplied] runs once the view model holds the new frames;
     * [onFinished] runs however the import ends.
     */
    @Suppress("TooGenericExceptionCaught") // any failure becomes the import's snackbar
    fun handle(
        rawUris: List<Uri>,
        displayName: (Uri) -> String,
        onApplied: () -> Unit,
        onFinished: () -> Unit,
    ): Job {
        val uris = capped(rawUris)
        return activity.lifecycleScope.launch(io) {
            try {
                withContext(Dispatchers.Main) { showImporting(uris.size) }

                // Import starts in name order — skip EXIF/MediaStore date probes
                // here (they opened every URI before any copy and left the overlay
                // stuck at 0% on large PLC picks). Dates resolve when the user
                // sorts by date.
                val batch = FrameImportHelper.importDeformedUris(
                    context = activity,
                    uris = uris,
                    cacheDir = activity.cacheDir,
                    displayName = displayName,
                    // done counts finished images: the bar follows it, the
                    // label names the image being copied now.
                    onProgress = { done, total -> showProgress(done, total) },
                )

                // The staged directory is already committed. Apply matching
                // ViewModel paths even if lifecycle cancellation lands now.
                withContext(NonCancellable + Dispatchers.Main) {
                    apply(batch)
                    tvResult.text = ""
                    onApplied()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error handling batch")
                withContext(Dispatchers.Main) {
                    FaqRedirect.snackbar(
                        activity,
                        activity.getString(R.string.error_loading_images, e.message),
                        R.string.url_faq_import_deformed,
                    )
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

    /** [rawUris] cut to the frame cap, saying so when it cut. */
    private fun capped(rawUris: List<Uri>): List<Uri> {
        val cap = DicSettings.maxFrames(activity, AppRemoteConfig.maxFrames(activity))
        if (rawUris.size <= cap) return rawUris
        Feedback.toast(
            activity,
            activity.resources.getQuantityString(R.plurals.frames_capped_fmt, cap, cap),
            long = true,
        )
        return FrameImportHelper.cappedUris(rawUris, cap)
    }

    private fun showImporting(count: Int) {
        tvResult.setText(R.string.analysis_caching_images)
        overlayHelper.processingStartTime = System.currentTimeMillis()
        overlayHelper.show(
            title = activity.getString(R.string.analysis_importing_title),
            status = activity.getString(R.string.analysis_caching_images),
            showRunTiles = false,
        )
        showProgress(0, count)
    }

    private fun showProgress(done: Int, total: Int) {
        overlayHelper.update(
            percent = if (total > 0) (done * PERCENT / total) else 0f,
            status = activity.resources.getQuantityString(
                R.plurals.analysis_importing_fmt,
                total,
                ProgressCount.current(done, total),
                total,
            ),
        )
    }

    private suspend fun apply(batch: ImportedBatch?) {
        viewModel.clearPreviousResults()
        viewModel.defOrderDirection = FrameOrderDirection.ASCENDING
        viewModel.defOrderMode = FrameOrderMode.NAME
        if (batch != null) {
            val ordered = FrameOrderHelper.reorder(batch.frames, FrameOrderMode.NAME)
            viewModel.deformedFrames = withContext(io) { FrameOrderHelper.reprefixTempFiles(ordered) }
            viewModel.defFromVideo = batch.fromVideo
        } else {
            viewModel.deformedFrames = emptyList()
            viewModel.defFromVideo = false
        }
    }

    private companion object {
        const val PERCENT = 100f
    }
}
