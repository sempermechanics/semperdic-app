package com.sempermechanics.semper.ui.home

import android.content.res.Resources
import com.sempermechanics.semper.R

/** The one message Home shows after queueing restores, from what actually happened. */
internal data class RestoreSummary(val text: String, val failed: Boolean) {

    companion object {
        /**
         * [started] were queued now and [running] were already on their way;
         * the rest of [total] could not be started (no cloud link, offline, or
         * the phone already has the frames).
         */
        fun of(res: Resources, total: Int, started: Int, running: Int): RestoreSummary {
            val restoring = started + running
            val failed = total - restoring
            val text = when {
                failed > 0 && total == 1 -> res.getString(R.string.download_analysis_failed)
                failed > 0 && restoring == 0 ->
                    res.getQuantityString(R.plurals.restore_multi_failed, failed, failed)
                failed > 0 -> res.getQuantityString(R.plurals.restore_multi_partial, failed, restoring, failed)
                started == 0 -> res.getString(R.string.download_analysis_already)
                total == 1 -> res.getString(R.string.restore_background_note)
                else -> res.getQuantityString(R.plurals.restore_multi_started, restoring, restoring)
            }
            return RestoreSummary(text, failed > 0)
        }
    }
}
