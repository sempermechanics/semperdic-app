package com.indicvision.semper.ui.analysis.run

import android.content.res.Resources
import com.indicvision.semper.R

/** The wizard's one-line status after a run that finished. */
internal object RunSummaryText {

    /**
     * "Computed N frames", or "Computed N of M frames — K kept no valid points"
     * when frames were dropped. A frame whose field kept no points gets no
     * `.dat`, so [kept] can be below [planned] on a run that otherwise finished.
     */
    fun computed(res: Resources, kept: Int, planned: Int): String {
        val dropped = planned - kept
        return if (dropped > 0) {
            res.getQuantityString(R.plurals.analysis_computed_partial_fmt, dropped, kept, planned, dropped)
        } else {
            res.getQuantityString(R.plurals.analysis_computed_fmt, kept, kept)
        }
    }
}
