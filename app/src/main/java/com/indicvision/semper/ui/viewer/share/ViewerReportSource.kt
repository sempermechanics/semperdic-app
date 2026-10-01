package com.indicvision.semper.ui.viewer.share

import com.indicvision.semper.report.EMPTY
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.ReportSource

/**
 * The viewer's [ReportSource], with `ViewerReportFactory`'s values: the
 * offline session id when there is none, the args' strain method as given,
 * MIN and MAX markers, and stats only when the args carry at least the core
 * slots (fewer read as [EngineStats.EMPTY]).
 */
fun ViewerReportFactory.Source.toReportSource(): ReportSource = ReportSource(
    sessionId = args.sessionId ?: ReportSource.OFFLINE_SESSION_ID,
    refName = args.refName,
    frameNames = frameNames,
    imgW = imgW,
    imgH = imgH,
    roi = roi,
    strainMethod = args.strainMethod,
    subset = args.subsetSize,
    step = baseStep,
    strainWindow = args.strainWindow,
    subsetPerFrame = sweepSubsets?.toList().orEmpty(),
    stepPerFrame = sweepSteps?.toList().orEmpty(),
    strainWindowPerFrame = sweepStrainWins?.toList().orEmpty(),
    engineStats = args.engineStatsArray()
        ?.takeIf { it.size >= EngineStats.CORE_SLOT_COUNT }
        ?.let(EngineStats::fromArray)
        ?: EngineStats.EMPTY,
    drawMinMarker = true,
)

/** The name index the viewer prints for frame [frameIndex]: the frame itself in a sweep, else its planned frame. */
fun ViewerReportFactory.Source.nameIndexAt(frameIndex: Int): Int = if (isSweep) frameIndex else plannedAt(frameIndex)
