package com.sempermechanics.semper.data.session

import java.util.Locale

/**
 * The Home-row headline of a single-setting analysis: the first frame's
 * convergence, which is the only frame whose engine stats a run keeps.
 *
 * It is set when the run is saved (or restored) and not changed by viewing
 * it. With more than one frame it says which frame it is about, so it does
 * not read as the whole run's. Stored text, like a sweep's caption, so it is
 * not localised.
 */
object SessionHeadline {

    /** [convergence] (0–100) of the first of [plannedFrames] frames. */
    fun firstFrameConvergence(convergence: Float, plannedFrames: Int): String {
        val converged = String.format(Locale.US, "%.1f%% converged", convergence)
        return if (plannedFrames > 1) "$converged on frame 1" else converged
    }
}
