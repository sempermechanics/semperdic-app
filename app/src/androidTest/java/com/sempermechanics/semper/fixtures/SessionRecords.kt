package com.sempermechanics.semper.fixtures

import com.sempermechanics.semper.data.session.SessionRecord

/**
 * One [SessionRecord] with every required field defaulted: a single-frame,
 * 100 × 100 px analysis (subset 41, step 5, strain window 15) whose ROI is the
 * whole image. Tests name only the fields they care about; anything not listed
 * here goes through `.copy(...)`.
 *
 * Same builder as the unit-test source set's; the two source sets do not share
 * code, so keep them in step.
 */
@Suppress("LongParameterList") // one defaulted, named knob per SessionRecord field
fun sessionRecord(
    id: String = "s1",
    name: String = id,
    createdAt: Long = 1L,
    updatedAt: Long = createdAt,
    frameCount: Int = 1,
    subset: Int = 41,
    step: Int = 5,
    strainWindow: Int = 15,
    imgW: Int = 100,
    imgH: Int = 100,
    roiX: Int = 0,
    roiY: Int = 0,
    roiW: Int = imgW,
    roiH: Int = imgH,
    refPath: String = "",
    refName: String = "ref.png",
    sessionDir: String = "",
    defNames: List<String> = emptyList(),
    syncState: SessionRecord.SyncState = SessionRecord.SyncState.LOCAL_ONLY,
    cloudSessionId: String = "",
) = SessionRecord(
    id = id,
    name = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    frameCount = frameCount,
    subset = subset,
    step = step,
    strainWindow = strainWindow,
    imgW = imgW,
    imgH = imgH,
    roiX = roiX,
    roiY = roiY,
    roiW = roiW,
    roiH = roiH,
    refPath = refPath,
    refName = refName,
    sessionDir = sessionDir,
    defNames = defNames,
    syncState = syncState,
    cloudSessionId = cloudSessionId,
)
