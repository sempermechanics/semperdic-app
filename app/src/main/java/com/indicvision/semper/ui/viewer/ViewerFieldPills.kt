package com.indicvision.semper.ui.viewer

import com.indicvision.semper.R
import com.indicvision.semper.field.DicResult

/**
 * The five field pills, in screen order, as one id ↔ field mapping.
 *
 * The layout has to check something, and it checks U. The active field survives
 * rotation in the ViewModel, so the pills must be re-derived from it on setup —
 * otherwise an Exx heatmap comes back with the U pill lit.
 */
object ViewerFieldPills {

    /** Pill id → (label, `DicResult` data index). */
    val BY_ID: Map<Int, Pair<String, Int>> = mapOf(
        R.id.rbFieldU to ("U" to DicResult.IDX_U),
        R.id.rbFieldV to ("V" to DicResult.IDX_V),
        R.id.rbFieldExx to ("Exx" to DicResult.IDX_EXX),
        R.id.rbFieldEyy to ("Eyy" to DicResult.IDX_EYY),
        R.id.rbFieldExy to ("Exy" to DicResult.IDX_EXY),
    )

    /** The pill showing [dataIndex]; falls back to U for an index off the map. */
    fun idFor(dataIndex: Int): Int =
        BY_ID.entries.firstOrNull { it.value.second == dataIndex }?.key ?: R.id.rbFieldU
}
