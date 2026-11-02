package com.sempermechanics.semper.ui.viewer

import android.graphics.Bitmap
import android.graphics.Matrix
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.DialogCustomScaleBinding
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.FieldKind
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.report.BakedHeatmap
import com.sempermechanics.semper.report.HeatmapRequest
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.common.SerialJob
import com.sempermechanics.semper.ui.common.dialog.FaqRedirect
import com.sempermechanics.semper.ui.viewer.summary.ViewerSummaryController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What [ViewerScaleController] needs from the viewer. */
internal interface ScaleHost : ViewerViews {
    /** The field and frame on screen, their fixed scales and cached stats. */
    val viewModel: ResultViewerViewModel
    val imageSize: ImageSize

    /** Grid pitch of the frame on screen. */
    val step: Int

    /** The frame on screen's data; null until one is decoded. */
    val rawData: FloatArray?

    /**
     * The frame [rawData] is; -1 until one is decoded. It lags
     * [ResultViewerViewModel.currentFrameIndex] while a Prev / Next decode is
     * pending, and is what a render of [rawData] is cached under.
     */
    val rawFrameIndex: Int

    /** True while the summary animation, not a frame, is on screen. */
    val isShowingSummary: Boolean
    val scrubCache: ScrubFrameCache
}

/**
 * The heatmap over the viewer's image and its colour scale: renders the field
 * on screen off the main thread (or takes it from the scrub cache), keeps it
 * aligned with the image's zoom, labels the scale's ends, and lets the user fix
 * the scale for a field.
 *
 * [activity] runs the renders and shows the scale dialog; [images] says
 * whether the frame is on its own photo (a displaced map), [captions] takes
 * the field's stats, and [summary] rebuilds its animation for a new fixed scale.
 *
 * Constructed before onCreate; reads [ViewerViews.binding] lazily.
 */
internal class ViewerScaleController(
    private val activity: AppCompatActivity,
    private val host: ScaleHost,
    private val images: ViewerImageLoader,
    private val captions: ViewerCaptions,
    private val summary: ViewerSummaryController,
) {

    private val binding get() = host.binding

    /** The heatmap on screen; null until one is shown. */
    var cachedHeatmap: Bitmap? = null
        private set

    /** The range of the heatmap on screen; null until one is shown. */
    private var shownRange: ValueRange? = null

    private var isGeneratingHeatmap = false

    private val visualizationJob = SerialJob()

    /** The field on screen, as the ViewModel holds it. */
    private val field: FieldKind get() = host.viewModel.field

    /** Fixed colour scales per field; in the ViewModel so a rotation keeps them. */
    private val customBoundsMap: MutableMap<Int, ValueRange> get() = host.viewModel.customBounds

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
        val currentDataIndex = field.dataIndex
        val dialog = DialogCustomScaleBinding.inflate(activity.layoutInflater)

        val isStrain = DicResult.isStrainFieldIndex(currentDataIndex)
        val multiplier = DicResult.strainMultiplier(currentDataIndex)
        val unit = activity.getString(if (isStrain) R.string.scale_unit_strain else R.string.scale_unit_px)

        dialog.tilScaleMax.hint = activity.getString(R.string.scale_max_value_fmt, unit)
        dialog.tilScaleMin.hint = activity.getString(R.string.scale_min_value_fmt, unit)

        val shown = shownRange.takeIf { cachedHeatmap != null && !host.isShowingSummary && !isGeneratingHeatmap }
        CustomScalePrefill.text(
            custom = host.viewModel.customBounds[currentDataIndex],
            shown = shown,
            multiplier = multiplier,
        )?.let { (min, max) ->
            dialog.etScaleMin.setText(min)
            dialog.etScaleMax.setText(max)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.scale_dialog_title_fmt, field.label))
            .setView(dialog.root)
            .setPositiveButton(R.string.apply) { _, _ ->
                val maxVal = dialog.etScaleMax.text?.toString()?.toFloatOrNull()
                val minVal = dialog.etScaleMin.text?.toString()?.toFloatOrNull()

                if (maxVal != null && minVal != null && maxVal > minVal) {
                    customBoundsMap[field.dataIndex] = ValueRange(minVal / multiplier, maxVal / multiplier)
                    updateVisualization(field.dataIndex)
                    summary.onScaleChanged(field.dataIndex)
                } else {
                    FaqRedirect.snackbar(
                        activity,
                        R.string.invalid_scale_inputs,
                        R.string.url_faq_custom_scale,
                    )
                }
            }
            .setNeutralButton(R.string.auto_scale) { _, _ ->
                customBoundsMap.remove(field.dataIndex)
                updateVisualization(field.dataIndex)
                summary.onScaleChanged(field.dataIndex)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun updateVisualization(index: Int) {
        val data = host.rawData ?: return
        isGeneratingHeatmap = true

        val custom = host.viewModel.customBounds[index]
        val displaced = images.onFramePhoto
        // The frame [data] is, not the frame asked for: a Next whose decode is
        // still pending has moved the ViewModel's index on already, and caching
        // this render (or its metrics) under that index would serve this
        // frame's map for the next one.
        val frameAtStart = host.rawFrameIndex
        val heatKey = ScrubFrameCache.HeatKey(
            frame = frameAtStart,
            field = index,
            step = host.step,
            custom = custom,
            displaced = displaced,
        )

        host.scrubCache.getHeat(heatKey)?.let { hit ->
            visualizationJob.cancel()
            showHeatmap(hit, index)
            // A heatmap hit means this (frame,field) was visited before, so its
            // metrics are already cached — this read is O(1) on the main thread.
            captions.applyFieldMetrics(host.viewModel.fieldMetricsFor(frameAtStart, index, data), index)
            return
        }

        val size = host.imageSize
        val frameStep = host.step
        // Read on Main: viewModels() is a main-thread lazy.
        val viewModel = host.viewModel
        visualizationJob.launch(activity.lifecycleScope, Dispatchers.Default) {
            // Warm the stats/extrema off the main thread, next to the heatmap render,
            // so the scrub settle never pays the O(n)+sort on the UI thread.
            val metrics = viewModel.fieldMetricsFor(frameAtStart, index, data)
            val heatmap = VisualizationEngine.render(
                HeatmapRequest(
                    data = data,
                    size = size,
                    valIndex = index,
                    step = frameStep,
                    bounds = custom,
                    maxLongEdge = VisualizationEngine.DISPLAY_MAX_EDGE,
                    displaced = displaced,
                ),
            )
            host.scrubCache.putHeat(heatKey, heatmap)

            withContext(Dispatchers.Main) {
                if (field.dataIndex != index || host.rawFrameIndex != frameAtStart) return@withContext
                showHeatmap(heatmap, index)
                captions.applyFieldMetrics(metrics, index)
            }
        }
    }

    private fun showHeatmap(heatmap: BakedHeatmap, index: Int) {
        cachedHeatmap = heatmap.bitmap
        binding.imgHeatmapOverlay.scaleType = ImageView.ScaleType.MATRIX
        binding.imgHeatmapOverlay.setImageBitmap(heatmap.bitmap)
        applyHeatmapMatrix()
        captions.openingDone()

        shownRange = heatmap.range

        // While the summary is up the labels belong to its whole-sequence scale,
        // not to whichever frame happens to be loaded behind it.
        if (!host.isShowingSummary) {
            val isStrain = DicResult.isStrainFieldIndex(index)
            val multiplier = DicResult.strainMultiplier(index)
            val unit = activity.getString(if (isStrain) R.string.scale_unit_strain else R.string.scale_unit_px)
            // ≤/≥, not "Min:"/"Max:": these are the 2nd/98th-percentile clamp the
            // colour ramp is built on (VisualizationEngine.computeSigmaClampedRange),
            // not the field's true extrema -- the ⓘ details sheet shows those,
            // via DicResult.fieldStats. Same wording as the summary-mode scale
            // (ViewerSummaryController) so the two paths agree.
            val minText = ScaleEndLabel.format(heatmap.min * multiplier)
            val maxText = ScaleEndLabel.format(heatmap.max * multiplier)
            binding.tvScaleMin.text = activity.getString(R.string.scale_min_fmt, minText, unit)
            binding.tvScaleMax.text = activity.getString(R.string.scale_max_fmt, maxText, unit)
        }
        isGeneratingHeatmap = false
    }

    /** Stops the render in flight. */
    fun cancel() {
        visualizationJob.cancel()
    }
}
