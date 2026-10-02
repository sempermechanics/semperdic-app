package com.indicvision.semper.ui.analysis

import android.content.res.Resources
import android.net.Uri
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import com.indicvision.semper.imaging.video.ExtractionRequest
import com.indicvision.semper.ui.analysis.frames.AnalysisDeformedBatchHelper
import com.indicvision.semper.ui.analysis.frames.AnalysisVideoExtractHelper
import com.indicvision.semper.ui.analysis.run.RunChrome
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.common.Feedback
import kotlinx.coroutines.Job
import java.io.File

/**
 * Imports deformed frames — picked images, or a video's sampled frames — with
 * the progress overlay up and its Cancel armed. One import at a time, and none
 * while a run is busy. [checkReady] re-checks the wizard's buttons as the
 * import starts and ends.
 */
class FrameImportController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val chrome: RunChrome,
    private val tvResult: TextView,
    private val checkReady: () -> Unit,
) {
    private var importJob: Job? = null

    /** Shared result path for the deformed-frame pickers (Photos and Files). [onApplied] once they land. */
    fun importDeformed(uris: List<Uri>, onApplied: () -> Unit) {
        if (uris.isEmpty()) {
            Feedback.toast(activity, R.string.no_images_selected)
            return
        }
        if (chrome.isBusy) return
        val job = AnalysisDeformedBatchHelper(activity, viewModel, chrome.overlay, tvResult).handle(
            rawUris = uris,
            displayName = { displayNameOf(activity.contentResolver, it) },
            onApplied = onApplied,
            onFinished = ::finish,
        )
        begin(job)
    }

    /** Extracts the frames [request] samples. [onApplied] once the view model holds them. */
    fun extractVideo(request: ExtractionRequest, onApplied: (AnalysisVideoExtractHelper.AppliedResult) -> Unit) {
        if (chrome.isBusy) return
        val job = AnalysisVideoExtractHelper(activity, viewModel, chrome.overlay, tvResult).extract(
            request = request,
            onApplied = onApplied,
            onFinished = ::finish,
        )
        begin(job)
    }

    /** Stops the import in flight, if any: the screen is going. */
    fun cancel() {
        importJob?.cancel()
        importJob = null
    }

    private fun begin(job: Job) {
        importJob = job
        chrome.beginImport { job.cancel() }
        checkReady()
    }

    private fun finish() {
        importJob = null
        chrome.end()
        checkReady()
    }
}

/**
 * Every deformed frame must match the reference pixel for pixel. The engine
 * clamps its AKAZE search window to the reference size and then indexes the
 * deformed image with it, so a mismatch throws inside OpenCV — and the JNI
 * layer swallows that exception, leaving a silently under-seeded solve.
 * Catching it here turns a bad result into a clear, fixable message.
 *
 * Costs nothing: the sizes were measured during import.
 */
internal fun AnalysisViewModel.checkFrameSizes(resources: Resources) {
    val ref = refSize
    val badNames = deformedFrames
        .filter { it.size != null && it.size != ref }
        .map { frame -> frame.name.ifEmpty { File(frame.path).name } }
    frameSizeError = if (!ref.isKnown || badNames.isEmpty()) {
        null
    } else {
        resources.getQuantityString(
            R.plurals.frames_size_mismatch_fmt,
            badNames.size,
            ref.width,
            ref.height,
            mismatchNames(resources, badNames),
        )
    }
}

/** First few mismatched filenames, then "and N more" when the list is long. */
private fun mismatchNames(resources: Resources, names: List<String>): String {
    if (names.size <= MISMATCH_NAMES_SHOWN) return names.joinToString(", ")
    val head = names.take(MISMATCH_NAMES_SHOWN).joinToString(", ")
    val more = names.size - MISMATCH_NAMES_SHOWN
    return resources.getQuantityString(R.plurals.frames_size_mismatch_and_more_fmt, more, head, more)
}

private const val MISMATCH_NAMES_SHOWN = 3
