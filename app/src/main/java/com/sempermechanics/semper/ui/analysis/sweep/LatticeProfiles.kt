package com.sempermechanics.semper.ui.analysis.sweep

import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.ui.viewer.ViewerArgs
import com.sempermechanics.semper.ui.viewer.ViewerSweepArgs
import timber.log.Timber
import java.io.File

/** One frame's line-cut profile per strain component: (distance along the line, millistrain) pairs. */
internal typealias StrainProfiles = Map<Int, List<Pair<Float, Float>>>

/** One node per solved combination of [sweep], in frame order. */
internal fun solvedLatticeNodes(sweep: ViewerSweepArgs?): List<VsgLatticeView.Node> {
    if (sweep == null) return emptyList()
    val count = minOf(sweep.subsets.size, sweep.steps.size, sweep.strainWindows.size)
    return (0 until count).map { i ->
        VsgLatticeView.Node(
            subset = sweep.subsets[i],
            step = sweep.steps[i],
            window = VsgStudy.windowPointsFor(sweep.strainWindows[i], sweep.steps[i]),
            vsg = sweep.strainWindows[i],
            solved = true,
            frameIndex = i,
            failureReason = "",
            failureCode = null,
        )
    }
}

/**
 * The combinations [sweep] could not solve, each with the short [reason] for
 * its engine code; [ViewerArgs.from] has already folded any legacy keys in.
 */
internal fun skippedLatticeNodes(sweep: ViewerSweepArgs?, reason: (code: Int) -> String): List<VsgLatticeView.Node> {
    val nodes = sweep?.let { SkippedNode.decodeJson(it.skippedJson) }.orEmpty()
    return nodes.map { node ->
        VsgLatticeView.Node(
            subset = node.subset,
            step = node.step,
            window = VsgStudy.windowPointsFor(node.strainWindow, node.step),
            vsg = node.strainWindow,
            solved = false,
            frameIndex = -1,
            failureReason = reason(node.code),
            failureCode = node.code,
        )
    }
}

/**
 * [sweepFrameProfiles] of every `.dat` in [batchDir], in name order; empty
 * when the directory is gone or cannot be listed. Blocking file IO.
 */
@WorkerThread
internal fun readSweepFrameProfiles(
    batchDir: File,
    steps: List<Int>,
    baseStep: Int,
    components: IntArray,
    line: VsgStudy.StudyLine,
): Map<Int, StrainProfiles> {
    val files = batchDir.takeIf { it.isDirectory }
        ?.listFiles { file -> file.extension == "dat" }
        ?.sortedBy { it.name }
        ?: return emptyMap()
    return sweepFrameProfiles(files, steps, baseStep, components, line)
}

/**
 * Line-cut profiles of a sweep's `.dat` [files] (sorted by name), keyed by the frame
 * each was written for: [SessionPaths.frameIndexOf], else its listing position. That
 * frame index is what the lattice's nodes and the sweep's per-frame [steps] are keyed
 * by. A frame that cannot be read is left out, rather than closing the gap and moving
 * every later profile onto the node before it.
 *
 * Decode → profile → discard, one frame at a time: only the profiles survive the
 * loop, so peak is one frame, not all of them.
 */
internal fun sweepFrameProfiles(
    files: List<File>,
    steps: List<Int>,
    baseStep: Int,
    components: IntArray,
    line: VsgStudy.StudyLine,
): Map<Int, StrainProfiles> {
    val out = sortedMapOf<Int, StrainProfiles>()
    files.forEachIndexed { position, file ->
        val frame = SessionPaths.frameIndexOf(file.name) ?: position
        try {
            val data = DicResult.decodeDatFile(file)
            if (data == null) {
                Timber.w("Invalid .dat size for %s", file.name)
            } else {
                val step = steps.getOrNull(frame)?.coerceAtLeast(1) ?: baseStep
                out[frame] = VsgStudy.profileAlong(data, components, line, step / 2f)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Failed to read %s", file.name)
        }
    }
    return out
}
