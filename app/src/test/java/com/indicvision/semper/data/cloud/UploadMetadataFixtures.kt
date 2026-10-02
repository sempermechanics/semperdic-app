package com.indicvision.semper.data.cloud

import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SkippedNode
import com.indicvision.semper.fixtures.sessionRecord

/**
 * The records `metadata.json` was captured for before its writer moved onto
 * [SessionMetadataDoc] (`cloud/upload_metadata_golden.json`): an ordinary
 * analysis, a sweep with typed skips, a sweep with only the legacy skip lists,
 * and an empty record.
 */
internal object UploadMetadataFixtures {

    val batch: SessionRecord = sessionRecord(
        id = "b1",
        name = "Tensile A · Sep 30",
        frameCount = 3,
        defNames = listOf("def_001.tif", "def_002.tif", "def_003.tif"),
        imgW = 4000,
        imgH = 3000,
        roiX = 10,
        roiY = 20,
        roiW = 300,
        roiH = 400,
        refName = "ref.tif",
    ).copy(
        engineStats = listOf(
            1200f, 1180f, 20f, 900f, 280f, 3f, 2f, 1f, 2.3f, 812.5f,
            0.1f, 50f, 7.25f, 33f, 1.4777f, 97.5f, 2f, 0.003f, 410f,
        ),
        strainMethod = "VSG",
        use6x6 = true,
        pointsConverged = 1180,
        avgIterations = 50f,
        executionTimeMs = 812,
        stopCode = -7,
        plannedFrameCount = 5,
    )

    val sweep: SessionRecord = sessionRecord(
        id = "s1",
        name = "Sweep",
        frameCount = 2,
        defNames = listOf("def.png", "def.png", "def.png"),
        refName = "ref.png",
    ).copy(
        engineStats = listOf(10f, 9f, 1f),
        sweepSubsets = listOf(21, 31),
        sweepSteps = listOf(5, 7),
        sweepStrainWindows = listOf(41, 85),
        sweepLabels = listOf("S21/st5/w41", "S31/st7/w85"),
        lineCutHorizontal = false,
        sweepSkippedNodes = listOf(SkippedNode(41, 9, 121, -12), SkippedNode(51, 9, 121, -3)),
    )

    /** A sweep stored before typed skips: only the four parallel lists. */
    val legacySkipSweep: SessionRecord = sweep.copy(
        id = "s2",
        sweepSkippedNodes = emptyList(),
        sweepSkipSubsets = listOf(41),
        sweepSkipSteps = listOf(9),
        sweepSkipStrainWindows = listOf(121),
        sweepSkipCodes = listOf(-12),
    )

    val bare: SessionRecord = sessionRecord(id = "e1", name = "", refName = "")

    val all: List<SessionRecord> = listOf(batch, sweep, legacySkipSweep, bare)

    const val UID = "uid-1"
    const val EMAIL = "a@b.c"
    const val GOLDEN = "cloud/upload_metadata_golden.json"
}
