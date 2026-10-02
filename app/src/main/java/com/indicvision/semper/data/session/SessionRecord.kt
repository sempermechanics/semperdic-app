package com.indicvision.semper.data.session

import com.indicvision.semper.data.cloud.SessionMetadataSync
import kotlinx.serialization.Serializable
import java.io.File

/**
 * One completed (or re-run) analysis as shown on the Home list. Metadata only —
 * the heavy artifacts (.dat frames, reference copy) live in [SessionStore.dirFor],
 * and full result files live in the cloud once synced.
 */
@Serializable
data class SessionRecord(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val frameCount: Int,
    // Engine parameters of the latest run
    val subset: Int,
    val step: Int,
    val strainWindow: Int,
    val use6x6: Boolean = false,
    // Geometry
    val imgW: Int,
    val imgH: Int,
    val roiX: Int,
    val roiY: Int,
    val roiW: Int,
    val roiH: Int,
    // Files
    val refPath: String,
    val refName: String,
    val sessionDir: String,
    val defNames: List<String> = emptyList(),
    // Headline shown on the row, e.g. "97.5% converged"
    val headline: String = "",
    val engineStats: List<Float> = emptyList(),
    // Run metrics — kept here (not just in the worker's input Data) so a
    // re-upload triggered by cloud reconciliation is still complete.
    val strainMethod: String = "",
    val pointsConverged: Int = 0,
    val avgIterations: Float = 0f,
    val executionTimeMs: Int = 0,
    /** Backend session id of the cloud copy — needed to erase it. Blank if never synced. */
    val cloudSessionId: String = "",
    val syncState: SyncState = SyncState.LOCAL_ONLY,

    /**
     * The cloud copy's metadata.json predates a change made here after the
     * backup (a rename), so [SessionMetadataSync] still has to send it.
     * Cleared once the backend holds the current metadata.
     */
    val metadataStale: Boolean = false,

    // ── Parameter sweep (VsgStudy)
    // A sweep varies the settings instead of the image, so [subset], [step] and
    // [strainWindow] above only describe its first frame. These carry the rest,
    // and their emptiness is what marks an ordinary analysis.

    /** Per-frame subset sizes; empty unless this session is a sweep. */
    val sweepSubsets: List<Int> = emptyList(),

    /** Per-frame step sizes. Rendering a frame depends on its own pitch. */
    val sweepSteps: List<Int> = emptyList(),

    /** Per-frame strain windows. */
    val sweepStrainWindows: List<Int> = emptyList(),

    /** Labels naming each combination, shown in the viewer and the report. */
    val sweepLabels: List<String> = emptyList(),

    /** True when the sweep's line cut runs along x. */
    val lineCutHorizontal: Boolean = true,

    // Combinations the engine could not solve — kept so the lattice still
    // shows hollow nodes after a Home reopen (and after a cloud restore).
    val sweepSkipSubsets: List<Int> = emptyList(),
    val sweepSkipSteps: List<Int> = emptyList(),
    val sweepSkipStrainWindows: List<Int> = emptyList(),

    /**
     * Engine code per skipped combination, index-aligned with the lists above.
     * Stored rather than resolved so the lattice can still say *why* each node
     * is hollow after a reopen — without it the reasons only survive until the
     * screen is left.
     */
    val sweepSkipCodes: List<Int> = emptyList(),

    /** Typed skips; legacy parallel lists remain for old on-disk JSON. */
    val sweepSkippedNodes: List<SkippedNode> = emptyList(),

    /**
     * Why a run ended before it finished, as an engine/run code, or 0 when it
     * ran to completion. Kept with the analysis because a short run otherwise
     * looks exactly like a shorter test that ran cleanly.
     */
    val stopCode: Int = 0,

    /** Frames the run set out to solve; 0 for records predating this field. */
    val plannedFrameCount: Int = 0,

    /**
     * True once the user has renamed this session, so a re-run keeps their name
     * instead of regenerating the auto-name. Auto-names ARE regenerated per run
     * so a sweep re-run as a single (or vice-versa) stops carrying the old kind.
     */
    val renamedByUser: Boolean = false,

) {

    /**
     * True when this analysis has a cloud copy, or one on its way: a change to
     * what its metadata.json carries then has to reach it ([metadataStale]).
     */
    val hasCloudCopy: Boolean
        get() = syncState != SyncState.LOCAL_ONLY || cloudSessionId.isNotBlank()

    /** True when the run stopped itself before working through every frame. */
    val stoppedEarly: Boolean get() = runStop.stoppedEarly

    /** True when the frames are parameter combinations rather than images. */
    val isSweep: Boolean get() = sweepSteps.isNotEmpty()

    /**
     * What each frame is called in the viewer and its reports: a sweep's
     * combination labels, else the deformed images' own names.
     */
    val frameNames: List<String> get() = if (isSweep) sweepLabels else defNames

    /** Planned combinations that never produced a frame. */
    val sweepSkipCount: Int
        get() {
            if (sweepSkippedNodes.isNotEmpty()) return sweepSkippedNodes.size
            return minOf(
                sweepSkipSubsets.size,
                sweepSkipSteps.size,
                sweepSkipStrainWindows.size,
            )
        }

    /** Typed [sweepSkippedNodes] first; else legacy parallel lists on disk. */
    fun resolvedSkipNodes(): List<SkippedNode> {
        if (sweepSkippedNodes.isNotEmpty()) return sweepSkippedNodes
        return SkippedNode.fromLegacyArrays(
            sweepSkipSubsets,
            sweepSkipSteps,
            sweepSkipStrainWindows,
            sweepSkipCodes,
        )
    }

    @Serializable
    enum class SyncState {
        LOCAL_ONLY,
        PENDING,
        SYNCED,

        /**
         * Backup was refused for a reason retrying can't fix — the cloud
         * analysis quota is full, the session is too large, or the device
         * isn't authorised. Surfaced on the Home row so it isn't silent.
         */
        FAILED,
    }

    /** True when the frame data is still on this phone (Results can reopen). */
    fun hasLocalData(): Boolean {
        val dir = File(sessionDir)
        return dir.isDirectory && (dir.listFiles { f -> f.extension == "dat" }?.isNotEmpty() == true)
    }
}
