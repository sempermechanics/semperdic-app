package com.sempermechanics.semper.ui.viewer

import android.graphics.Color
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.SheetSettingsUsedBinding
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.FieldHistogram
import com.sempermechanics.semper.report.ReportBuilder
import com.sempermechanics.semper.ui.analysis.recommend.StrainWindowText
import com.sempermechanics.semper.ui.analysis.run.EngineFailure
import com.sempermechanics.semper.ui.analysis.sweep.VsgPlotView
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudy
import com.sempermechanics.semper.ui.common.dialog.inflateSheet
import kotlin.math.roundToInt

/**
 * Peek sheet for a result. On a still frame: true max / min (with coordinates) /
 * mean, then a histogram of accepted values. On the summary GIF: the sequence
 * colour-bar ends only — no mean, no histogram. Then the parameter rows that
 * produced the result (and the sweep line-cut).
 */
object ViewerSettingsSheet {

    private const val SETTINGS_ROW_SP = 13f
    private const val SETTINGS_ROW_PAD_V = 11

    /**
     * Why the run stopped and how far it got, or nothing when it finished.
     *
     * This sheet is the provenance record, so it is where "why is this analysis
     * short?" has to be answerable months later without remembering the run.
     */
    private fun stopRows(host: ResultViewerActivity): List<Pair<String, String>> {
        val stopCode = host.args.stopCode
        if (stopCode == 0) return emptyList()
        val planned = host.args.plannedFrames
        return buildList {
            add(
                host.getString(R.string.settings_used_stopped_early) to
                    EngineFailure.shortReason(host, stopCode),
            )
            if (planned > 0) {
                add(
                    host.getString(R.string.settings_used_frames_solved) to
                        host.resources.getQuantityString(
                            R.plurals.session_frames_of_fmt,
                            planned,
                            host.frameCount(),
                            planned,
                        ),
                )
            }
        }
    }

    /**
     * Every row the sheet shows for the frame on screen, in order. Internal so
     * `ViewerEntryParityDeviceTest` can compare them across entry points.
     */
    internal fun entriesFor(host: ResultViewerActivity): List<Pair<String, String>> {
        val args = host.args
        val roi = args.roi
        // A sweep varies the settings frame by frame, so the sheet must describe
        // the combination on screen rather than the one the run started with.
        val params = host.frameParams.at(host.currentFrameIndex)
        val subset = params.subset
        val strainWin = params.strainWindow
        return buildList {
            add(host.getString(R.string.settings_used_subset) to host.getString(R.string.settings_used_px_fmt, subset))
            add(host.getString(R.string.settings_used_step) to host.getString(R.string.settings_used_px_fmt, host.step))
            // Stored as the VSG in px; shown with its window in points when it has one.
            add(host.getString(R.string.settings_used_strain_window) to StrainWindowText.of(host, strainWin, host.step))
            add(
                host.getString(R.string.settings_used_strain_method) to
                    args.strainMethod,
            )
            addAll(stopRows(host))
            // ROI is only meaningful when one was actually recorded.
            if (roi.w > 0 && roi.h > 0) {
                val roiText = host.getString(R.string.settings_used_roi_fmt, roi.w, roi.h, roi.x, roi.y)
                add(host.getString(R.string.settings_used_roi) to roiText)
            }
            add(
                host.getString(R.string.settings_used_image_size) to host.getString(
                    R.string.settings_used_size_fmt,
                    args.imgW,
                    args.imgH,
                ),
            )
        }
    }

    fun show(host: ResultViewerActivity) {
        // Themed so Material's own sheet background is transparent and the content
        // layout's bg_viewer_peek_sheet supplies the 22dp top radius — otherwise the
        // two stack and you get a hard card edge inside a rounded one.
        val sheet = inflateSheet(host, R.layout.sheet_settings_used, R.style.ThemeOverlay_Semper_ViewerPeekSheet)
        val view = SheetSettingsUsedBinding.bind(sheet.view)
        // The overlay makes the *dialog* window transparent; this clears the sheet
        // container Material inflates around the content, which the theme cannot reach.
        sheet.dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)

        view.tvSettingsUsedSpecimen.text = host.args.refName
        view.tvSheetStats.text = host.detailStatsText()
        populateHistogram(host, view)

        entriesFor(host).forEachIndexed { index, (label, value) ->
            if (index > 0) view.settingsUsedRows.addView(settingsDivider(host))
            view.settingsUsedRows.addView(settingsRow(host, label, value))
        }
        if (host.isSweep) populateLineCut(host, view)

        sheet.show()
    }

    /**
     * Accepted-value histogram for the frame on screen. Hidden on the summary
     * GIF — that sheet quotes the sequence colour-bar ends, not this frame's
     * population.
     */
    private fun populateHistogram(host: ResultViewerActivity, sheetView: SheetSettingsUsedBinding) {
        val section = sheetView.distributionSection
        if (host.isShowingSummary) {
            section.visibility = View.GONE
            return
        }
        val data = host.rawData
        val hist = data?.let { FieldHistogram.from(it, host.currentDataIndex) }
        if (hist == null) {
            section.visibility = View.GONE
            return
        }
        section.visibility = View.VISIBLE
        val unit = host.getString(
            if (DicResult.isStrainFieldIndex(host.currentDataIndex)) {
                R.string.scale_unit_strain
            } else {
                R.string.scale_unit_px
            },
        )
        sheetView.tvHistogramTitle.text = host.getString(R.string.viewer_histogram_title_fmt, host.currentTypeString)
        val caption = sheetView.tvHistogramCaption
        val plot = sheetView.plotFieldHistogram
        plot.setHistogram(hist, unit)
        plot.onBinSelected = { index ->
            val count = hist.counts[index]
            val text = host.resources.getQuantityString(
                R.plurals.viewer_histogram_bin_fmt,
                count,
                count,
                ReportBuilder.formatMetric(hist.binStart(index)),
                ReportBuilder.formatMetric(hist.binEnd(index)),
                unit,
            )
            caption.text = text
        }
    }

    /**
     * Strain along the cut through the centre of the ROI, all three components
     * at once (guide step 4). The cut is the same physical line for every
     * combination of the sweep, so scrubbing frames compares like with like.
     */
    private fun populateLineCut(host: ResultViewerActivity, sheetView: SheetSettingsUsedBinding) {
        val data = host.rawData ?: return
        val section = sheetView.lineCutSection
        val plot = sheetView.plotLineCut
        val roi = host.roi
        val line = VsgStudy.centreLine(roi.x, roi.y, roi.w, roi.h, host.lineCutHorizontal)
        val tolerance = host.step / 2f

        val labels = listOf(R.string.field_exx, R.string.field_eyy, R.string.field_exy)
        val profiles = VsgStudy.profileAlong(data, VsgStudy.STRAIN_COMPONENTS.toIntArray(), line, tolerance)
        val series = VsgStudy.STRAIN_COMPONENTS.mapIndexed { slot, component ->
            VsgPlotView.Series(
                label = host.getString(labels[slot]),
                color = VsgPlotView.lineCutColor(host, slot),
                points = profiles[component].orEmpty(),
                markers = false,
            )
        }
        if (series.all { it.points.isEmpty() }) {
            section.visibility = View.GONE
            return
        }

        section.visibility = View.VISIBLE
        sheetView.tvLineCutTitle.setText(R.string.line_cut_title)
        sheetView.tvLineCutLegend.text = lineCutLegend(
            host,
            host.getString(
                if (host.lineCutHorizontal) R.string.axis_x else R.string.axis_y,
            ),
            line.position,
        )
        plot.compactAxes = true
        plot.setData(
            series,
            host.getString(
                if (host.lineCutHorizontal) R.string.line_cut_axis_x else R.string.line_cut_axis_y,
            ),
            host.getString(R.string.line_cut_axis_strain),
            xUnit = host.getString(R.string.scale_unit_px),
            yUnit = host.getString(R.string.scale_unit_strain),
        )
    }

    private const val LEGEND_SWATCH_DP = 10f

    /**
     * Prefix plus a colour swatch beside each of Exx / Eyy / Exy for the line-cut
     * plot -- identity comes from the swatch, not from colouring the label text
     * itself (a light categorical hue is illegible as text; see the dataviz
     * skill's marks-and-anatomy.md). Labels stay in the row's own ink colour.
     */
    fun lineCutLegend(
        host: ResultViewerActivity,
        axis: String,
        position: Float,
    ): CharSequence {
        val prefix = host.getString(R.string.line_cut_legend_prefix_fmt, axis, position)
        val parts = listOf(
            host.getString(R.string.line_cut_legend_exx) to VsgPlotView.lineCutColor(host, 0),
            host.getString(R.string.line_cut_legend_eyy) to VsgPlotView.lineCutColor(host, 1),
            host.getString(R.string.line_cut_legend_exy) to VsgPlotView.lineCutColor(host, 2),
        )
        val spanned = SpannableStringBuilder(prefix).append(' ')
        parts.forEachIndexed { index, (label, color) ->
            if (index > 0) spanned.append("  ")
            val swatchStart = spanned.length
            spanned.append('●') // placeholder glyph the ImageSpan replaces
            spanned.setSpan(
                legendSwatchSpan(host, color),
                swatchStart,
                spanned.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spanned.append(' ').append(label)
        }
        return spanned
    }

    /** A small filled circle, [color], sized to sit on one text line. */
    private fun legendSwatchSpan(host: ResultViewerActivity, color: Int): ImageSpan {
        val sizePx = (LEGEND_SWATCH_DP * host.resources.displayMetrics.density).roundToInt()
        val dot = ShapeDrawable(OvalShape()).apply {
            paint.color = color
            setBounds(0, 0, sizePx, sizePx)
        }
        return ImageSpan(dot, ImageSpan.ALIGN_BASELINE)
    }

    private fun settingsRow(host: ResultViewerActivity, label: String, value: String): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = SETTINGS_ROW_PAD_V
                bottomMargin = SETTINGS_ROW_PAD_V
            }
        }
        row.addView(
            TextView(host).apply {
                text = label
                setTextColor(host.getColor(R.color.viewer_chrome_muted))
                textSize = SETTINGS_ROW_SP
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(host).apply {
                text = value
                setTextColor(host.getColor(R.color.viewer_chrome_text))
                textSize = SETTINGS_ROW_SP
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        return row
    }

    private fun settingsDivider(host: ResultViewerActivity): View = View(host).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            1,
        )
        setBackgroundColor(host.getColor(R.color.surface_outline))
    }
}
