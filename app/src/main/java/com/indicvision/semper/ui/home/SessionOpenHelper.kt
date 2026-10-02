package com.indicvision.semper.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.indicvision.semper.R
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SkippedNode
import com.indicvision.semper.ui.common.dialog.Dialogs
import com.indicvision.semper.ui.viewer.ViewerArgs
import com.indicvision.semper.ui.viewer.ViewerSweepArgs

/**
 * Packs a [SessionRecord] into the Intent that opens either the result viewer
 * or the VSG lattice (sweeps). The extras themselves are [ViewerArgs], shared
 * with the post-run entry point so the two cannot drift apart.
 */
object SessionOpenHelper {

    /**
     * Opens [session], or says why it cannot be opened when its frames are no
     * longer on this phone — a session deleted locally but kept in the cloud
     * still shows on both the Home list and the settings page.
     *
     * [hasLocalData] is the answer the list read off the main thread; a tap
     * never lists the session directory itself.
     */
    fun openOrExplain(activity: Activity, session: SessionRecord, hasLocalData: Boolean) {
        if (!hasLocalData) {
            Dialogs.info(activity, R.string.session_data_gone_title, R.string.session_data_gone_body)
            return
        }
        activity.startActivity(intentFor(activity, session))
    }

    fun intentFor(context: Context, session: SessionRecord): Intent = argsFor(session).toIntent(context)

    fun argsFor(session: SessionRecord): ViewerArgs =
        ViewerArgs(
            imgW = session.imgW,
            imgH = session.imgH,
            step = session.step,
            refName = session.refName,
            refPath = session.refPath,
            batchDirPath = session.sessionDir,
            // A sweep names its frames after the combination behind them, and
            // needs each frame's own settings to render and describe it.
            frameNames = session.frameNames,
            stopCode = session.stopCode,
            plannedFrames = session.plannedFrameCount,
            sessionId = session.id,
            sessionLocalId = session.id,
            subsetSize = session.subset,
            strainWindow = session.strainWindow,
            engineStats = session.engineStats.ifEmpty { null },
            roiX = session.roiX,
            roiY = session.roiY,
            roiW = session.roiW,
            roiH = session.roiH,
            sweep = if (session.isSweep) {
                ViewerSweepArgs(
                    subsets = session.sweepSubsets,
                    steps = session.sweepSteps,
                    strainWindows = session.sweepStrainWindows,
                    lineCutHorizontal = session.lineCutHorizontal,
                    skippedJson = SkippedNode.encodeJson(session.resolvedSkipNodes()),
                )
            } else {
                null
            },
        )
}
