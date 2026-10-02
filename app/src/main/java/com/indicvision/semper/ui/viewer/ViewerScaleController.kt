package com.indicvision.semper.ui.viewer

import android.graphics.Bitmap
import android.graphics.Matrix
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R
import com.indicvision.semper.databinding.DialogCustomScaleBinding
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.field.ValueRange
import com.indicvision.semper.report.BakedHeatmap
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.ui.common.SerialJob
import com.indicvision.semper.ui.common.dialog.FaqRedirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The heatmap over the viewer's image and its colour scale: renders the field
 * on screen off the main thread (or takes it from the scrub cache), keeps it
 * aligned with the image's zoom, labels the scale's ends, and lets the user fix
 * the scale for a field.
 *
 * Constructed before onCreate; reads [ResultViewerActivity.binding] lazily.
 */
internal class ViewerScaleController(private val host: ResultViewerActivity) {

    private val binding get() = host.binding

    /** The heatmap on screen; null until one is shown. */
    var cachedHeatmap: Bitmap? = null
        private set

    /** The range of the heatmap on screen; null until one is shown. */
    private var shownRange: ValueRange? = null

    private var isGeneratingHeatmap = false

    private val visualizationJob = SerialJob()

    /** Fixed colour scales per field; in the ViewModel so a rotation keeps them. */
    private val customBoundsMap: MutableMap<Int, ValueRange> get() = host.viewerVm.customBounds

    @Suppress("ComplexCondition") // each is a reason the overlay cannot be scaled to the image
    fun applyHeatmapMatrix() {
        val hm = cachedHeatmap
        val imageSize = host.imageSize
        val zoom = binding.imgBaseResult.getZoomMatrix()
        val overlay = binding.imgHeatmapOverlay
        if (hm == null || hm.isRecycled || hm.width <= 0 || imageSize.width <= 0) {
            overlay.imageMatrix = zoom
        } else {
            val m = Matrix(zoom)
            m.preScale(imageSize.width.toFloat() / hm.width, imageSize.height.toFloat() / hm.height)
            overlay.imageMatrix = m
        }
        overlay.invalidate()
    }

    fun showCustomScaleDialog() {
        val currentDataIndex = host.currentDataIndex
        val dialog = DialogCustomScaleBinding.inflate(host.layoutInflater)

        val isStrain = DicResult.isStrainFieldIndex(currentDataIndex)
        val multiplier = DicResult.strainMultiplier(currentDataIndex)
        val unit = host.getString(if (isStrain) R.string.scale_unit_strain else R.string.scale_unit_px)

        dialog.tilScaleMax.hint = host.getString(R.string.scale_max_value, unit)
        dialog.tilScaleMin.hint = host.getString(R.string.scale_min_value, unit)

        val shown = shownRange.takeIf { cachedHeatmap != null && !host.isShowingSummary && !isGeneratingHeatmap }
        CustomScalePrefill.text(
            custom = host.customBoundsFor(currentDataIndex),
            shown = shown,
            multiplier = multiplier,
        )?.let { (min, max) ->
            dialog.etScaleMin.setText(min)
            dialog.etScaleMax.setText(max)
        }

        MaterialAlertDialogBuilder(host)
            .setTitle(host.getString(R.string.scale_dialog_title, host.currentTypeString))
            .setView(dialog.root)
            .setPositiveButton(R.string.apply) { _, _ ->
                val maxVal = dialog.etScaleMax.text?.toString()?.toFloatOrNull()
                val minVal = dialog.etScaleMin.text?.toString()?.toFloatOrNull()

                if (maxVal != null && minVal != null && maxVal > minVal) {
                    customBoundsMap[host.currentDataIndex] = ValueRange(minVal / multiplier, maxVal / multiplier)
                    updateVisualization(host.currentDataIndex)
                    host.summary.onScaleChanged(host.currentDataIndex)
                } else {
                    FaqRedirect.snackbar(
                        host,
                        R.string.invalid_scale_inputs,
                        R.string.url_faq_custom_scale,
                    )
                }
            }
            .setNeutralButton(R.string.auto_scale) { _, _ ->
                customBoundsMap.remove(host.currentDataIndex)
                updateVisualization(host.currentDataIndex)
                host.summary.onScaleChanged(host.currentDataIndex)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun updateVisualization(index: Int) {
        val data = host.rawData ?: return
        isGeneratingHeatmap = true

        val custom = host.customBoundsFor(index)
        val displaced = host.onFramePhoto
        val heatKey = ScrubFrameCache.HeatKey(
            frame = host.currentFrameIndex,
            field = index,
            step = host.step,
            custom = custom,
            displaced = displaced,
        )

        val frameAtStart = host.currentFrameIndex

        host.scrubCache.getHeat(heatKey)?.let { hit ->
            visualizationJob.cancel()
            showHeatmap(hit, index)
            // A heatmap hit means this (frame,field) was visited before, so its
            // metrics are already cached — this read is O(1) on the main thread.
            host.captions.applyFieldMetrics(host.viewerVm.fieldMetricsFor(frameAtStart, index, data), index)
            return
        }

        val size = host.imageSize
        val frameStep = host.step
        // Read on Main: viewModels() is a main-thread lazy.
        val vm = host.viewerVm
        visualizationJob.launch(host.lifecycleScope, Dispatchers.Default) {
            // Warm the stats/extrema off the main thread, next to the heatmap render,
            // so the scrub settle never pays the O(n)+sort on the UI thread.
            val metrics = vm.fieldMetricsFor(frameAtStart, index, data)
            val heatmap = if (displaced) {
                VisualizationEngine.generateDeformedHeatmap(
                    data,
                    size.width,
                    size.height,
                    index,
                    frameStep,
                    custom?.min,
                    custom?.max,
                    maxLongEdge = VisualizationEngine.DISPLAY_MAX_EDGE,
                )
            } else {
                VisualizationEngine.generateHeatmap(
                    data,
                    size.width,
                    size.height,
                    index,
                    frameStep,
                    custom?.min,
                    custom?.max,
                    maxLongEdge = VisualizationEngine.DISPLAY_MAX_EDGE,
                )
            }
            host.scrubCache.putHeat(heatKey, heatmap)

            withContext(Dispatchers.Main) {
                if (host.currentDataIndex != index || host.currentFrameIndex != frameAtStart) return@withContext
                showHeatmap(heatmap, index)
                host.captions.applyFieldMetrics(metrics, index)
            }
        }
    }

    private fun showHeatmap(heatmap: BakedHeatmap, index: Int) {
        cachedHeatmap = heatmap.bitmap
        binding.imgHeatmapOverlay.scaleType = ImageView.ScaleType.MATRIX
        binding.imgHeatmapOverlay.setImageBitmap(heatmap.bitmap)
        applyHeatmapMatrix()

        shownRange = heatmap.range

        // While the summary is up the labels belong to its whole-sequence scale,
        // not to whichever frame happens to be loaded behind it.
        if (!host.isShowingSummary) {
            val isStrain = DicResult.isStrainFieldIndex(index)
            val multiplier = DicResult.strainMultiplier(index)
            val unit = host.getString(if (isStrain) R.string.scale_unit_strain else R.string.scale_unit_px)
            // ≤/≥, not "Min:"/"Max:": these are the 2nd/98th-percentile clamp the
            // colour ramp is built on (VisualizationEngine.computeSigmaClampedRange),
            // not the field's true extrema -- the ⓘ details sheet shows those,
            // via DicResult.fieldStats. Same wording as the summary-mode scale
            // (ViewerSummaryHelper) so the two paths agree.
            val minText = ReportBuilder.formatMetric(heatmap.min * multiplier)
            val maxText = ReportBuilder.formatMetric(heatmap.max * multiplier)
            binding.tvScaleMin.text = host.getString(R.string.scale_min_fmt, minText, unit)
            binding.tvScaleMax.text = host.getString(R.string.scale_max_fmt, maxText, unit)
        }
        isGeneratingHeatmap = false
    }

    /** Stops the render in flight. */
    fun cancel() {
        visualizationJob.cancel()
    }
}
