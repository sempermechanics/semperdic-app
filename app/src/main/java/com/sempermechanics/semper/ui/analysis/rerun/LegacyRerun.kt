package com.sempermechanics.semper.ui.analysis.rerun

import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderMode
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import java.io.File

/**
 * The reference Home had the user pick again for an older analysis's Re-run
 * ([RerunSource.NEEDS_REFERENCE]): its bytes as the engine is handed them,
 * in [file] in the cache, and the name it was picked as.
 */
internal class PickedReference(val file: File, val name: String)

/** What Home's Re-run can start a new analysis from, for one saved analysis. */
internal enum class RerunSource {
    /** Its Re-run inputs are all on this phone ([RerunInputs.readComplete]). */
    KEPT,

    /**
     * Saved before Re-run kept its inputs, or restored from a backup, which
     * does not carry them: its frames are here, and the user picks its
     * reference again ([LegacyRerun]).
     */
    NEEDS_REFERENCE,

    /** Its photos are not on this phone: only in the cloud, or freed up. */
    MISSING,
    ;

    companion object {
        /** Where [record]'s Re-run would come from; reads the disk. */
        @WorkerThread
        fun of(record: SessionRecord): RerunSource = when {
            RerunInputs.readComplete(File(record.sessionDir)) != null -> KEPT
            LegacyRerun.framesOnPhone(record) -> NEEDS_REFERENCE
            else -> MISSING
        }
    }
}

/**
 * Re-run inputs for an analysis that has none kept (owner decision,
 * 2026-10-15): one saved before Re-run existed, or restored. Its record and
 * its `raw_deformed/` frames say everything but the reference, which the
 * session holds only at display size, and the mask, which no record holds.
 * The user picks the reference again; its size must be the record's.
 */
internal object LegacyRerun {

    /**
     * The frames' names in `raw_deformed/`, in run order: every frame of a
     * single-setting run, and a sweep's one frame (its record names it once a
     * combination).
     */
    fun frameNames(record: SessionRecord): List<String> =
        if (record.isSweep) listOfNotNull(record.defNames.firstOrNull()) else record.defNames

    /**
     * True when [record] has results here and each of its frames is in its
     * `raw_deformed/` folder, so a Re-run needs only its reference picked.
     */
    @WorkerThread
    fun framesOnPhone(record: SessionRecord): Boolean {
        val names = frameNames(record)
        val sized = record.imgW > 0 && record.imgH > 0
        if (names.isEmpty() || !sized || !record.hasLocalData()) return false
        val raw = SessionLayout(File(record.sessionDir)).rawDeformedDir
        val root = raw.canonicalFile
        return names.all { name ->
            val file = File(raw, name)
            name.isNotBlank() && file.isFile && file.length() > 0 && file.canonicalFile.parentFile == root
        }
    }

    /**
     * True when [record]'s region was drawn rather than the whole image: it
     * may have been a freehand shape, whose mask no record kept, so the
     * Re-run's region is its bounding rectangle. A region the size of the
     * inset full frame for any subset the run used was not drawn.
     */
    fun regionWasDrawn(record: SessionRecord): Boolean {
        val size = ImageSize(record.imgW, record.imgH)
        val roi = Roi(record.roiX, record.roiY, record.roiW, record.roiH)
        val subsets = listOf(record.subset) + record.sweepSubsets + record.resolvedSkipNodes().map { it.subset }
        return subsets.distinct().none { Roi.insetFullFrame(size, it) == roi }
    }

    /**
     * [record]'s inputs with [referenceName] as the reference: its frames in
     * `raw_deformed/` in run order with their names and clip times, its
     * region as a rectangle and no mask, its settings, and for a sweep the
     * axes its combinations were sampled from ([sweepRanges]). Frames keep run
     * order: the order mode is the import's own.
     */
    fun inputs(record: SessionRecord, referenceName: String): RerunInputs {
        val drawn = regionWasDrawn(record)
        val size = ImageSize(record.imgW, record.imgH)
        val roi = if (drawn) Roi(record.roiX, record.roiY, record.roiW, record.roiH) else Roi.full(size)
        val frames = frameNames(record).mapIndexed { index, name ->
            RerunInputs.Frame(
                path = "${SessionPaths.RAW_DEFORMED_SUBDIR}/$name",
                name = name,
                width = record.imgW,
                height = record.imgH,
                timeMs = record.frameTimesMs?.getOrNull(index).takeUnless { record.isSweep },
            )
        }
        val sweep = if (record.isSweep) {
            val (ranges, denominator) = sweepRanges(record)
            RerunInputs.Sweep(
                ranges = ranges.toIntArray().toList(),
                overlap = SweepStudy.overlapForDenominator(denominator),
                lineCutHorizontal = record.lineCutHorizontal,
                frameIndex = 0,
            )
        } else {
            null
        }
        return RerunInputs(
            reference = RerunInputs.Reference(referenceName, record.imgW, record.imgH),
            hasMask = false,
            hasCustomRoi = drawn,
            roi = roi.toXywh().toList(),
            frames = frames,
            fromVideo = record.isVideo,
            videoName = record.videoName,
            orderMode = FrameOrderMode.PICKER.name,
            orderDirection = FrameOrderDirection.ASCENDING.name,
            params = RerunInputs.Params(
                record.subset,
                record.step,
                record.strainWindow,
                record.use6x6,
                record.refUpdateInterval,
            ),
            sweep = sweep,
        )
    }

    /**
     * A sweep's axes, read back from the combinations it planned (solved and
     * skipped): the subset range and count, the window range and count in
     * points, and the step denominator every combination's step agrees with
     * (the default when none does). `SweepStudy.plan` of them gives those
     * combinations again.
     */
    fun sweepRanges(record: SessionRecord): Pair<SweepRanges, Int> {
        val solved = record.sweepSubsets.indices.mapNotNull { i ->
            val step = record.sweepSteps.getOrNull(i) ?: return@mapNotNull null
            val vsg = record.sweepStrainWindows.getOrNull(i) ?: return@mapNotNull null
            SweepStudy.Point(record.sweepSubsets[i], step, windowPoints(vsg, step))
        }
        val skipped = record.resolvedSkipNodes().map {
            SweepStudy.Point(it.subset, it.step, windowPoints(it.strainWindow, it.step))
        }
        val points = (solved + skipped).ifEmpty {
            listOf(SweepStudy.Point(record.subset, record.step, windowPoints(record.strainWindow, record.step)))
        }
        val denominator = (SweepStudy.STEP_DENOM_MIN..SweepStudy.STEP_DENOM_MAX).firstOrNull { d ->
            points.all { SweepStudy.stepSizeFor(it.subset, d) == it.step }
        } ?: SweepStudy.DEFAULT_STEP_DENOM
        val subsets = points.map { it.subset }.distinct().sorted()
        val windows = points.map { it.window }.distinct().sorted()
        val ranges = SweepRanges(
            subsetMin = subsets.first(),
            subsetMax = subsets.last(),
            strainWinMin = windows.first(),
            strainWinMax = windows.last(),
            subsetSamples = subsets.size,
            strainWinSamples = windows.size,
            stepDenominator = denominator,
        )
        return ranges to denominator
    }

    /** A VSG of [vsg] px at [step] in window points; the nearest odd count for a session from before points. */
    private fun windowPoints(vsg: Int, step: Int): Int =
        SweepStudy.windowPointsFor(vsg, step) ?: SweepStudy.nearestWindowPoints(vsg, step)
}
