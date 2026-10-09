package com.sempermechanics.semper.ui.viewer

import android.content.res.Resources
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.ReportBuilder
import com.sempermechanics.semper.ui.common.InlineBusy
import com.sempermechanics.semper.ui.viewer.ResultViewerViewModel.FieldMetrics
import com.sempermechanics.semper.ui.viewer.summary.SummaryCaption

/**
 * The viewer's edge title and the field's stats: max and min (with where they
 * are) and mean, in the caption and in the info peek sheet. Also the
 * "Opening steel_00 · 40 frames" pill while the first frame loads.
 *
 * Constructed before onCreate; reads [ResultViewerActivity.binding] lazily.
 */
internal class ViewerCaptions(private val host: ResultViewerActivity) {

    /** The stats text last shown, kept for the info peek sheet. */
    private var detailStats: String = ""

    /** The opening pill's wait, from [showOpening] until [openingDone]. */
    private var opening: InlineBusy? = null
    private var openingToken = 0

    /**
     * Starts the opening wait: past 300 ms, a spinner and "Opening <name> · N
     * frames" float over the canvas until [openingDone]. Call once, in onCreate.
     */
    fun showOpening() {
        val binding = host.binding
        val busy = InlineBusy(host, binding.viewerOpening, spinner = binding.viewerOpeningSpinner)
        opening = busy
        openingToken = busy.start {
            val frames = if (host.frameSetLoaded) host.frameCount() else host.args.frameNames.size
            binding.tvViewerOpening.text = openingText(host.resources, host.args.refName, frames)
        }
    }

    /** The first frame drew, or none will: the opening pill goes. */
    fun openingDone() {
        opening?.stop(openingToken)
        opening = null
    }

    /** Max / min (with coordinates) / mean for the info peek sheet. */
    fun detailStatsText(): String = detailStats.ifBlank { host.getString(R.string.stat_empty) }

    /** Edge title + peek-sheet stats for [index], from pre-computed [metrics]. Main thread only. */
    fun applyFieldMetrics(metrics: FieldMetrics, index: Int) {
        val binding = host.binding
        val unit = if (DicResult.isStrainFieldIndex(index)) "mε" else "px"
        val frameBit = if (host.isShowingSummary) {
            host.getString(R.string.summary_title)
        } else {
            "${host.currentFrameIndex + 1} / ${host.batchFiles.size.coerceAtLeast(1)}"
        }
        binding.tvFinding.text = host.getString(R.string.viewer_edge_title_fmt, host.currentTypeString, frameBit)

        if (host.isShowingSummary) {
            detailStats = SummaryCaption.text(host.resources, host.summary.boundsFor(index), index, unit)
            binding.tvStatsCaption.text = detailStats
            return
        }

        val stats = metrics.stats
        if (stats == null) {
            detailStats = host.getString(R.string.stat_empty)
            binding.tvStatsCaption.text = detailStats
            return
        }
        val maxText = ReportBuilder.formatMetric(stats.max)
        val minText = ReportBuilder.formatMetric(stats.min)
        val meanText = ReportBuilder.formatMetric(stats.mean)
        val data = host.rawData
        detailStats = if (
            data != null &&
            metrics.maxIdx in data.indices &&
            metrics.minIdx in data.indices
        ) {
            host.getString(
                R.string.viewer_stats_fmt,
                maxText,
                data[metrics.maxIdx].toInt(),
                data[metrics.maxIdx + 1].toInt(),
                minText,
                data[metrics.minIdx].toInt(),
                data[metrics.minIdx + 1].toInt(),
                meanText,
                unit,
            )
        } else {
            host.getString(R.string.viewer_stats_plain_fmt, maxText, minText, meanText, unit)
        }
        binding.tvStatsCaption.text = detailStats
    }

    companion object {
        /**
         * "Opening steel_00 · 40 frames": the reference's name without its
         * extension (else "analysis"), and the frame count when known.
         */
        fun openingText(resources: Resources, refName: String, frames: Int): String {
            val name = refName.substringBeforeLast('.').ifBlank { resources.getString(R.string.viewer_opening_unnamed) }
            return if (frames > 0) {
                resources.getQuantityString(R.plurals.viewer_opening_frames_fmt, frames, name, frames)
            } else {
                resources.getString(R.string.viewer_opening_fmt, name)
            }
        }
    }
}
