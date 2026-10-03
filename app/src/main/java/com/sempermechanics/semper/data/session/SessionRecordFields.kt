package com.sempermechanics.semper.data.session

import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.FrameParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.RunStop

// Typed views over a [SessionRecord]'s flat fields. Read-only: nothing here
// changes what index.json or metadata.json hold.

/** `Roi(roiX, roiY, roiW, roiH)`. */
val SessionRecord.roi: Roi get() = Roi(roiX, roiY, roiW, roiH)

/** `ImageSize(imgW, imgH)`; zero for a row a restore has not filled yet. */
val SessionRecord.imageSize: ImageSize get() = ImageSize(imgW, imgH)

/** The latest run's scalar parameters; for a sweep, its first frame's. */
val SessionRecord.dicParams: DicParams get() = DicParams(subset, step, strainWindow)

/** Every frame's parameters: [dicParams] plus the sweep's per-frame lists. */
val SessionRecord.frameParams: FrameParams
    get() = FrameParams(dicParams, sweepSubsets, sweepSteps, sweepStrainWindows)

/**
 * Frame [index]'s parameters: `sweepSubsets.getOrElse(index) { subset }`, and
 * likewise for step and strain window, as the upload bundler and
 * `metadata.json` writer look them up.
 */
fun SessionRecord.paramsAt(index: Int): DicParams = frameParams.at(index)

/** [SessionRecord.stopCode] as a [RunStop]. */
val SessionRecord.runStop: RunStop get() = RunStop.fromWireCode(stopCode)

/**
 * True when the backend is known to hold this analysis: it reported the
 * upload [SessionRecord.SyncState.SYNCED], or the row carries the cloud id.
 * Home's rule: `SessionSelectionController` reads it for its cloud actions
 * and counts, and [isRestorable] for restore.
 *
 * Not [SessionRecord.hasCloudCopy], which also counts a PENDING or FAILED row
 * because a rename must still reach a copy on its way; nor the "Only in
 * cloud" badge, which reads SYNCED alone.
 */
val SessionRecord.isKnownInCloud: Boolean
    get() = syncState == SessionRecord.SyncState.SYNCED || cloudSessionId.isNotBlank()

/**
 * True when Home offers to restore this row: its frames are not on the phone
 * and the cloud holds it ([isKnownInCloud]). Home asks it when a row is opened
 * (`HomeActivity.openSession`) and before it offers Restore on a selection.
 *
 * The caller says whether the frames are on the phone: Home passes the list's
 * cached answer; [SessionRecord.hasLocalData] is a disk read, so it is never
 * taken implicitly. Whether the account may restore at all (demo accounts may
 * not) is the caller's gate, as it is today.
 */
fun SessionRecord.isRestorable(hasLocalData: Boolean): Boolean = !hasLocalData && isKnownInCloud

/** `Roi(roiX, roiY, roiW, roiH)`. */
val SessionRecordSettings.roi: Roi get() = Roi(roiX, roiY, roiW, roiH)

/** `DicParams(subset, step, strainWin)`. */
val SessionRecordSettings.dicParams: DicParams get() = DicParams(subset, step, strainWin)

/** The skipped combination's parameters, its `strainWindow` being the VSG in px. */
val SkippedNode.dicParams: DicParams get() = DicParams(subset, step, strainWindow)
