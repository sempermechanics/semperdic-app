package com.indicvision.semper.ui.analysis.run

import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionRecordSettings

/**
 * What a re-run that wrote no record of its own left behind.
 *
 * @property settings what the run solved with, which the frames on disk now
 *   reflect
 * @property defNames the frame names on disk, as a saved run records them
 */
internal data class UnsavedRerun(
    val framesOnDisk: Int,
    val stopCode: Int,
    val plannedFrames: Int,
    val settings: SessionRecordSettings,
    val defNames: List<String>,
)

/**
 * What the Home row of a re-run that saved nothing should become. The run
 * deleted the previous frames before it started, so the row can no longer
 * describe them as on this phone.
 *
 * - Nothing on disk and a cloud copy: unchanged. The row reads "Only in
 *   cloud", and the cloud copy is the run it describes.
 * - Nothing on disk and no cloud copy: null, the row goes. There is no
 *   analysis left anywhere for it to open.
 * - Some frames on disk: it describes those. They are this run's, so the row
 *   takes its settings and frame names, and is an ordinary analysis even if
 *   it was a sweep; it keeps no headline or stats from the run that is gone,
 *   and is not backed up.
 */
internal fun afterUnsavedRerun(previous: SessionRecord, run: UnsavedRerun): SessionRecord? = when {
    run.framesOnDisk == 0 && previous.syncState == SessionRecord.SyncState.SYNCED -> previous
    run.framesOnDisk == 0 -> null
    else -> previous.copy(
        updatedAt = System.currentTimeMillis(),
        frameCount = run.framesOnDisk,
        subset = run.settings.subset,
        step = run.settings.step,
        strainWindow = run.settings.strainWin,
        use6x6 = run.settings.use6x6,
        roiX = run.settings.roiX,
        roiY = run.settings.roiY,
        roiW = run.settings.roiW,
        roiH = run.settings.roiH,
        defNames = run.defNames,
        stopCode = run.stopCode,
        plannedFrameCount = run.plannedFrames,
        headline = "",
        engineStats = emptyList(),
        syncState = SessionRecord.SyncState.LOCAL_ONLY,
        sweepSubsets = emptyList(),
        sweepSteps = emptyList(),
        sweepStrainWindows = emptyList(),
        sweepLabels = emptyList(),
        sweepSkipSubsets = emptyList(),
        sweepSkipSteps = emptyList(),
        sweepSkipStrainWindows = emptyList(),
        sweepSkipCodes = emptyList(),
        sweepSkippedNodes = emptyList(),
    )
}
