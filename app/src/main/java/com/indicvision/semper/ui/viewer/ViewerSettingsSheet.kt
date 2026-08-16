@file:SuppressLint("InflateParams")

package com.indicvision.semper.ui.viewer

import android.annotation.SuppressLint
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.EngineFailure
import com.indicvision.semper.ui.analysis.VsgPlotView
import com.indicvision.semper.ui.analysis.VsgStudy

/**
 * Peek sheet for a result: max / min (with coordinates) / mean for the frame on
 * screen, then the parameter rows that produced it (and the sweep line-cut).
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
        val stopCode = host.intent.getIntExtra(DicKeys.STOP_CODE, 0)
        if (stopCode == 0) return emptyList()
        val planned = host.intent.getIntExtra(DicKeys.PLANNED_FRAMES, 0)
        return buildList {
            add(
                host.getString(R.string.setting_stopped_early) to
                    host.getString(EngineFailure.shortReasonRes(stopCode)),
            )
            if (planned > 0) {
                add(
                    host.getString(R.string.setting_frames_solved) to
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
     * Every row the sheet shows for the frame on screen, in order.
     */
    private fun entriesFor(host: ResultViewerActivity): List<Pair<String, String>> {
        val roiW = host.intent.getIntExtra(DicKeys.ROI_W, 0)
        val roiH = host.intent.getIntExtra(DicKeys.ROI_H, 0)
        // A sweep varies the settings frame by frame, so the sheet must describe
        // the combination on screen rather than the one the run started with.
        val frame = host.currentFrameIndex
        val subset = host.sweepSubsets?.getOrNull(frame)
            ?: host.intent.getIntExtra(DicKeys.SUBSET_SIZE, 0)
        val strainWin = host.sweepStrainWins?.getOrNull(frame)
            ?: host.intent.getIntExtra(DicKeys.STRAIN_WINDOW, 0)
        return buildList {
            add(host.getString(R.string.setting_subset) to host.getString(R.string.setting_px_fmt, subset))
            add(host.getString(R.string.setting_step) to host.getString(R.string.setting_px_fmt, host.step))
            add(
                host.getString(R.string.setting_strain_window) to
                    host.resources.getQuantityString(R.plurals.setting_subsets_fmt, strainWin, strainWin),
            )
            if (host.isSweep) {
                add(
                    host.getString(R.string.setting_vsg) to host.getString(
                        R.string.setting_px_fmt,
                        VsgStudy.vsgFor(host.step, strainWin),
                    ),
                )
            }
            add(
                host.getString(R.string.setting_strain_method) to
                    (host.intent.getStringExtra(DicKeys.STRAIN_METHOD) ?: "VSG"),
            )
            addAll(stopRows(host))
            // ROI is only meaningful when one was actually recorded.
            if (roiW > 0 && roiH > 0) {
                add(
                    host.getString(R.string.setting_roi) to host.getString(
                        R.string.setting_roi_fmt,
                        roiW,
                        roiH,
                        host.intent.getIntExtra(DicKeys.ROI_X, 0),
                        host.intent.getIntExtra(DicKeys.ROI_Y, 0),
                    ),
                )
            }
            add(
                host.getString(R.string.setting_image_size) to host.getString(
                    R.string.setting_size_fmt,
                    host.intent.getIntExtra(DicKeys.IMG_W, 0),
                    host.intent.getIntExtra(DicKeys.IMG_H, 0),
                ),
            )
        }
    }

    fun show(host: ResultViewerActivity) {
        val sheet = BottomSheetDialog(host)
        val view = host.layoutInflater.inflate(R.layout.sheet_settings_used, null)
        sheet.setContentView(view)

        view.findViewById<TextView>(R.id.tvSettingsUsedSpecimen).text =
            host.intent.getStringExtra(DicKeys.REF_NAME).orEmpty()

        view.findViewById<TextView>(R.id.tvSheetStats).text = host.detailStatsText()

        val rows = view.findViewById<LinearLayout>(R.id.settingsUsedRows)

        val entries = entriesFor(host)

        entries.forEachIndexed { index, (label, value) ->
            if (index > 0) rows.addView(settingsDivider(host))
            rows.addView(settingsRow(host, label, value))
        }
        if (host.isSweep) populateLineCut(host, view)

        sheet.show()
    }

    /**
     * Strain along the cut through the centre of the ROI, all three components
     * at once (guide step 4). The cut is the same physical line for every
     * combination of the sweep, so scrubbing frames compares like with like.
     */
    fun populateLineCut(host: ResultViewerActivity, sheetView: View) {
        val data = host.rawData ?: return
        val section = sheetView.findViewById<View>(R.id.lineCutSection)
        val plot = sheetView.findViewById<VsgPlotView>(R.id.plotLineCut)
        val line = VsgStudy.centreLine(
            host.roiX,
            host.roiY,
            host.roiW,
            host.roiH,
            host.lineCutHorizontal,
        )
        val tolerance = host.step / 2f

        val labels = listOf(R.string.field_exx, R.string.field_eyy, R.string.field_exy)
        val profiles = VsgStudy.profileAlong(data, VsgStudy.STRAIN_COMPONENTS.toIntArray(), line, tolerance)
        val series = VsgStudy.STRAIN_COMPONENTS.mapIndexed { slot, component ->
            VsgPlotView.Series(
                label = host.getString(labels[slot]),
                color = VsgPlotView.paletteColor(slot),
                points = profiles[component].orEmpty(),
                markers = false,
            )
        }
        if (series.all { it.points.isEmpty() }) {
            section.visibility = View.GONE
            return
        }

        section.visibility = View.VISIBLE
        sheetView.findViewById<TextView>(R.id.tvLineCutTitle).setText(R.string.line_cut_title)
        sheetView.findViewById<TextView>(R.id.tvLineCutLegend).text = lineCutLegend(
            host,
            host.getString(
                if (host.lineCutHorizontal) R.string.axis_x else R.string.axis_y,
            ),
            line.position,
        )
        plot.setData(
            series,
            host.getString(
                if (host.lineCutHorizontal) R.string.line_cut_axis_x else R.string.line_cut_axis_y,
            ),
            host.getString(R.string.line_cut_axis_strain),
        )
    }

    /** Prefix plus colour-matched Exx / Eyy / Exy labels for the line-cut plot. */
    fun lineCutLegend(
        host: ResultViewerActivity,
        axis: String,
        position: Float,
    ): CharSequence {
        val prefix = host.getString(R.string.line_cut_legend_prefix_fmt, axis, position)
        val parts = listOf(
            host.getString(R.string.line_cut_legend_exx) to VsgPlotView.paletteColor(0),
            host.getString(R.string.line_cut_legend_eyy) to VsgPlotView.paletteColor(1),
            host.getString(R.string.line_cut_legend_exy) to VsgPlotView.paletteColor(2),
        )
        val spanned = SpannableStringBuilder(prefix).append(' ')
        parts.forEachIndexed { index, (label, color) ->
            if (index > 0) spanned.append(" · ")
            val start = spanned.length
            spanned.append(label)
            spanned.setSpan(
                ForegroundColorSpan(color),
                start,
                spanned.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return spanned
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
                setTextColor(host.getColor(R.color.text_secondary))
                textSize = SETTINGS_ROW_SP
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(host).apply {
                text = value
                setTextColor(host.getColor(R.color.text_primary))
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
