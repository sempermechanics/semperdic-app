// A UI controller: one small method per thing the viewer can do to the summary
// (show, hide, field change, scale change, cancel) plus the playback and status
// helpers they share, so TooManyFunctions is suppressed for this file.
@file:Suppress("TooManyFunctions")

package com.indicvision.semper.ui.viewer.summary

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.indicvision.semper.R
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.report.FieldRangesStore
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Drives the viewer's summary slot — the looping animation that sits before
 * frame 1 and shows the whole sequence in the selected field.
 *
 * The heavy lifting is [SummaryAnimation]'s; this owns the screen: which field
 * is showing, the progress line while a field is still rendering, and playback.
 */
class ViewerSummaryHelper(private val host: ResultViewerActivity) {

    private val layer: View = host.findViewById(R.id.summaryLayer)
    private val image: ImageView = host.findViewById(R.id.imgSummary)
    private val statusPanel: View = host.findViewById(R.id.summaryStatus)
    private val progress: ProgressBar = host.findViewById(R.id.progressSummary)
    private val status: TextView = host.findViewById(R.id.tvSummaryStatus)
    private val cancelButton: MaterialButton = host.findViewById(R.id.btnSummaryCancel)

    /** Value range per field over the whole sequence; empty until the pass finishes. */
    private var ranges: Map<Int, Pair<Float, Float>> = emptyMap()
    private var rangesJob: Job? = null
    private var buildJob: Job? = null

    /** Which field the on-screen animation belongs to, so a re-show can skip work. */
    private var shownField: Int? = null

    var isShowing: Boolean = false
        private set

    /**
     * Shared with the share sheet, so both build into the same cached files.
     * Holds plain values only: an export job keeps it past a rotation, so it
     * must not reach back into this viewer.
     */
    internal val animation: SummaryAnimation by lazy {
        val sweepSteps = host.sweepSteps
        val baseStep = host.baseStep
        SummaryAnimation(
            SummaryAnimation.Spec(
                batchFiles = host.summaryBatchFiles(),
                imgW = host.imgW,
                imgH = host.imgH,
                stepAt = { index -> sweepSteps?.getOrNull(index) ?: baseStep },
                outputDir = CacheJanitor.shareDir(host.cacheDir),
                backgroundColor = ContextCompat.getColor(host, R.color.viewer_canvas),
                fitBounds = host.summaryFitBounds(),
            ),
        )
    }

    init {
        cancelButton.setOnClickListener { cancel() }
    }

    /**
     * Kicks off the one pass that fixes every field's colour scale.
     *
     * `ResultViewerActivity` starts it on every open of a multi-frame, non-sweep session,
     * since single frames also take their colour scale from the sequence range
     * ([sequenceRange]); [show] calls it too. It is idempotent, so repeated calls do
     * not re-scan. A batch run writes the [FieldRangesStore] sidecar, and then the pass
     * decodes nothing. Without it (older or restored sessions) the pass decodes and
     * range-scans **every frame**, about 1 MB of garbage per frame at 19 200 points,
     * which set the 150-frame heap (TD-87).
     */
    fun start() {
        if (host.summaryBatchFiles().isEmpty()) return
        if (rangesJob?.isActive == true || ranges.isNotEmpty()) return
        rangesJob = host.lifecycleScope.launch {
            val files = host.summaryBatchFiles()
            val computed = try {
                withContext(Dispatchers.Default) {
                    val rangesFile = files.firstOrNull()?.parentFile?.let {
                        File(it, FieldRangesStore.FILE_NAME)
                    }
                    SummaryAnimation.globalRanges(files, rangesFile) { done, total ->
                        host.lifecycleScope.launch(Dispatchers.Main.immediate) {
                            if (!isShowing || rangesJob?.isActive != true) return@launch
                            progress.progress = done * PERCENT / total.coerceAtLeast(1)
                            showStatus(host.getString(R.string.summary_scanning_fmt, done, total))
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                Timber.e(e, "OOM computing summary colour ranges")
                emptyMap()
            }
            ranges = computed
            host.onSequenceRangesReady()
            if (isShowing) render(host.currentDataIndex)
        }
    }

    fun show() {
        isShowing = true
        layer.isVisible = true
        // The colour-scale scan is only needed once the summary is actually on screen.
        start()
        render(host.currentDataIndex)
    }

    fun hide() {
        isShowing = false
        stopPlayback()
        layer.isVisible = false
    }

    /** The field toggle moved; re-render if the summary is the thing on screen. */
    fun onFieldChanged() {
        if (isShowing) render(host.currentDataIndex)
    }

    /** A changed fixed scale invalidates the baked-in colours for that field. */
    fun onScaleChanged(dataIndex: Int) {
        if (shownField == dataIndex) shownField = null
        if (isShowing) render(host.currentDataIndex)
    }

    fun cancel() {
        rangesJob?.cancel()
        buildJob?.cancel()
        stopPlayback()
    }

    /** Bounds for [dataIndex]: a user-set fixed scale wins, else the global range. */
    fun boundsFor(dataIndex: Int): Pair<Float, Float>? =
        host.customBoundsFor(dataIndex) ?: ranges[dataIndex]

    /** Whole-sequence range for [dataIndex], ignoring any custom override. */
    fun sequenceRange(dataIndex: Int): Pair<Float, Float>? = ranges[dataIndex]

    /** The label the frame counter shows while the summary is up. */
    fun counterText(): String = host.getString(R.string.summary_gif)

    private fun render(dataIndex: Int) {
        if (shownField == dataIndex && image.drawable != null) {
            startPlayback()
            return
        }
        val bounds = boundsFor(dataIndex)
        if (bounds == null) {
            // Either the range pass is still running or nothing correlated at all.
            // Do not steal the determinate "Reading frames…" status that [start]
            // already pushes — a static "Preparing files…" looked hung on PLC.
            if (rangesJob?.isActive == true) {
                if (!statusPanel.isVisible) {
                    showStatus(host.getString(R.string.summary_scanning_fmt, 0, host.summaryBatchFiles().size))
                }
            } else {
                showStatus(host.getString(R.string.summary_no_data))
            }
            return
        }

        val label = host.currentTypeString
        val total = host.summaryBatchFiles().size
        buildJob?.cancel()
        stopPlayback()
        showStatus(host.getString(R.string.summary_building_fmt, label, 0, total))
        buildJob = host.lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.Default) {
                    animation.build(dataIndex, label, bounds) { done, count ->
                        host.lifecycleScope.launch {
                            if (host.currentDataIndex != dataIndex) return@launch
                            progress.progress = done * PERCENT / count.coerceAtLeast(1)
                            status.text = host.getString(R.string.summary_building_fmt, label, done, count)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                Timber.e(e, "Summary animation for %s failed", label)
                null
            }
            if (host.currentDataIndex != dataIndex || !isShowing) return@launch
            if (file == null) {
                showStatus(host.getString(R.string.summary_failed))
                return@launch
            }
            val decoded = decodeForDisplay(file)
            // The decode suspended: the field or the slot may have moved on.
            if (host.currentDataIndex != dataIndex || !isShowing) return@launch
            shownField = dataIndex
            display(decoded)
            host.refreshSummaryScaleLabels(dataIndex, bounds)
        }
    }

    /**
     * The GIF as something the slot can show, decoded off the main thread: an
     * animated drawable from API 28, else its first frame. Null when it does not
     * decode.
     */
    private suspend fun decodeForDisplay(file: File): Drawable? = withContext(Dispatchers.IO) {
        // AnimatedImageDrawable arrived in API 28; below that the same GIF still
        // builds and shares, it just does not move on screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) }
                .onFailure { Timber.w(it, "Could not decode the summary animation") }
                .getOrNull()
        } else {
            runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()?.toDrawable(host.resources)
        }
    }

    private fun display(drawable: Drawable?) {
        if (drawable == null) {
            showStatus(host.getString(R.string.summary_failed))
            return
        }
        image.setImageDrawable(drawable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            statusPanel.isVisible = false
            (drawable as? Animatable)?.start()
        } else {
            showStatus(host.getString(R.string.summary_needs_android_9))
        }
    }

    private fun startPlayback() {
        statusPanel.isVisible = false
        (image.drawable as? Animatable)?.start()
    }

    private fun stopPlayback() {
        (image.drawable as? Animatable)?.stop()
    }

    private fun showStatus(message: String?) {
        statusPanel.isVisible = message != null
        status.text = message.orEmpty()
        val building = message != null &&
            message != host.getString(R.string.summary_failed) &&
            message != host.getString(R.string.summary_no_data) &&
            message != host.getString(R.string.summary_needs_android_9)
        progress.isVisible = building
        cancelButton.isVisible = building && (rangesJob?.isActive == true || buildJob?.isActive == true)
    }

    private companion object {
        const val PERCENT = 100
    }
}

/** Formats a field's animation bounds for the viewer's colour-scale labels. */
internal fun ResultViewerActivity.refreshSummaryScaleLabels(dataIndex: Int, bounds: Pair<Float, Float>) {
    val multiplier = DicResult.strainMultiplier(dataIndex)
    val unit = getString(
        if (DicResult.isStrainFieldIndex(dataIndex)) R.string.scale_unit_strain else R.string.scale_unit_px,
    )
    findViewById<TextView>(R.id.tvScaleMax).text =
        getString(R.string.scale_max_fmt, ReportBuilder.formatMetric(bounds.second * multiplier), unit)
    findViewById<TextView>(R.id.tvScaleMin).text =
        getString(R.string.scale_min_fmt, ReportBuilder.formatMetric(bounds.first * multiplier), unit)
}
