package com.sempermechanics.semper.ui.analysis.sweep

import android.content.Context
import androidx.core.content.ContextCompat
import com.sempermechanics.semper.R

/** The [VsgPlotView] series colours, behind [VsgPlotView.paletteColor] and [VsgPlotView.lineCutColor]. */
internal object VsgPlotPalette {
    // Resource-backed, not literal ints: each slot needs an independent night
    // value (see values-night/colors.xml) since this view is shared with the
    // dark-glass viewer peek sheet. Under emphasis (dataviz skill: onDraw draws
    // every muted series in one neutral, see VsgPlotView's ALPHA_MUTED) at most one
    // slot is ever shown in colour at a time, so these are validated per-slot
    // (lightness band, chroma floor, contrast) rather than for pairwise
    // separation -- scripts/validate_palette.js, run against both surfaces.
    private val PALETTE_RES = intArrayOf(
        R.color.viewer_plot_palette_0,
        R.color.viewer_plot_palette_1,
        R.color.viewer_plot_palette_2,
        R.color.viewer_plot_palette_3,
        R.color.viewer_plot_palette_4,
        R.color.viewer_plot_palette_5,
        R.color.viewer_plot_palette_6,
        R.color.viewer_plot_palette_7,
    )

    /** exx/eyy/exy on the line-cut: always 3 concurrent curves, so (unlike
     *  [PALETTE_RES]) this is validated all-pairs, not just per-slot. */
    private val LINE_CUT_RES = intArrayOf(
        R.color.viewer_line_cut_0,
        R.color.viewer_line_cut_1,
        R.color.viewer_line_cut_2,
    )

    /** Colour for the n-th series of a multi-line plot; safe for any index
     *  (a skipped lattice node has frameIndex -1). */
    fun series(context: Context, index: Int): Int =
        ContextCompat.getColor(context, PALETTE_RES[index.mod(PALETTE_RES.size)])

    /** Colour for the n-th of the line-cut's 3 concurrent strain components. */
    fun lineCut(context: Context, slot: Int): Int =
        ContextCompat.getColor(context, LINE_CUT_RES[slot.mod(LINE_CUT_RES.size)])
}
