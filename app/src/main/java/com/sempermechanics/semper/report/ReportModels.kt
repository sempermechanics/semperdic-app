package com.sempermechanics.semper.report

import android.graphics.Bitmap

data class ReportData(
    val sessionId: String,
    val specimenName: String,
    val analysisDate: String,
    val subsetSize: Int,
    val stepSize: Int,
    val strainWindow: Int,
    val strainMethod: String,

    // Region of Interest Details
    val roiData: RoiData,

    // DOWN-SCALED IMAGES (300 DPI max) for the page-1 preview card
    val referenceImage: Bitmap,
    val deformedImage: Bitmap,
    val referenceImageName: String,
    val deformedImageName: String,

    val fieldResults: List<FieldResult>,
    val engineStats: EngineStats,

    // Correlation-quality diagnostics
    val znssdHeatmap: Bitmap,
    val solverPathMap: Bitmap,
    val globalAvgZnssd: Float,
    /** Accepted points [globalAvgZnssd] is the mean over, so frames can be pooled. */
    val znssdAcceptedPoints: Int = 0,

    /** Traceability, e.g. "v1.4 (12) • arm64-v8a". Null when unavailable. */
    val appBuild: String? = null,

    /**
     * How much of this frame's displacement was the whole scene moving. Null
     * when too few points converged to fit it — see [RigidBodyFit.MIN_POINTS].
     */
    val rigidBody: RigidBodyFit.Fit? = null,
)

// Dedicated Data Class for ROI
data class RoiData(
    val startX: Int,
    val startY: Int,
    val width: Int,
    val height: Int,
)

data class FieldResult(
    val fieldName: String,
    val fieldKey: String,
    val unit: String,
    val minValue: Float,
    val maxValue: Float,
    val meanValue: Float,
    val meanType: String, // "Simple Mean" or "Mean Absolute"
    val stdDevValue: Float,
    val minCoordX: Int,
    val minCoordY: Int,
    val maxCoordX: Int,
    val maxCoordY: Int,
    // Down-scaled to 300 DPI
    val bakedHeatmap: Bitmap,
)

data class EngineStats(
    val totalPointsAttempted: Int,
    val totalPointsSolved: Int,
    val totalPointsRejected: Int,
    val pathAPoints: Int,
    val pathBPoints: Int,

    // ONLY SIMPLEX (No RGDIC Ghost Fields)
    val simplexCalls: Int,
    val simplexSaved: Int,
    val finalDeadPoints: Int,

    val avgIcgnIterations: Float,
    val wallTimeMs: Float,
    val akazeRansacMs: Float,
    val hessianPrepassMs: Float,
    val delaunayMs: Float,
    val strainMs: Float,
    val avgThroughputPtsPerMs: Float,
    val convergencePercent: Float,

    /** 2 = full AKAZE mesh, 1 = sparse mesh, 0 = Path C fallback (RGDIC-only), -1 = unknown */
    val meshSeedingQuality: Int = MESH_SEEDING_UNKNOWN,

    /**
     * Total wall time (ms) spent in the simplex rescue path across every point
     * that needed one, and in the main ICGN solve, for the whole frame. Answers
     * the "is the simplex rescue plausibly comparable to the main solve" question
     * from the perf plan — 0f on data from an app build older than this field
     * (see [SLOT_SIMPLEX_MS]'s doc).
     */
    val simplexMs: Float = 0f,
    val icgnMs: Float = 0f,
) {
    fun meshSeedingLabel(): String = when (meshSeedingQuality) {
        2 -> "Full AKAZE Mesh"
        1 -> "Sparse AKAZE Mesh"
        0 -> "Fallback (RGDIC-only, no mesh)"
        else -> "Unknown (legacy data)"
    }

    companion object {
        const val MESH_SEEDING_UNKNOWN = -1

        // The engine returns telemetry as a flat FloatArray whose slots have
        // fixed meanings (see SemperJNI.cpp). Most are read here in
        // [fromArray]; these few are also read live during a run, so their
        // indices live here as the single source of truth rather than as
        // literals scattered across the analysis code.
        /** Number of core metric slots (indices 0..15) every engine writes. */
        const val CORE_SLOT_COUNT = 16

        /** Points ICGN accepted via the mesh (path A) and the flood fill (path B). */
        const val SLOT_PATH_A_POINTS = 3
        const val SLOT_PATH_B_POINTS = 4

        /** Mean ICGN iteration count. */
        const val SLOT_AVG_ITERS = 8

        /** Percentage of points that converged. */
        const val SLOT_CONVERGENCE = 15

        /** Mesh-seeding quality; optional, so a 16-slot array omits it. */
        const val SLOT_MESH_SEEDING = 16

        /**
         * Total simplex-rescue time (ms) for the frame — optional, only present
         * from the engine commit that added it; a native .so built before that
         * (or a 16/17-slot legacy array) simply leaves this and [SLOT_ICGN_MS]
         * unwritten, read below as 0f rather than out-of-bounds.
         */
        const val SLOT_SIMPLEX_MS = 17

        /** Total main-ICGN-solve time (ms) for the frame — same optionality as above. */
        const val SLOT_ICGN_MS = 18

        /** Full slot count including the optional mesh-seeding and simplex/ICGN-time slots. */
        const val SLOT_COUNT = 19

        /** Matches the float[] written by SemperJNI.cpp (16 slots + optional slot 16) */
        fun fromArray(a: FloatArray): EngineStats = if (a.size >= CORE_SLOT_COUNT) {
            EngineStats(
                totalPointsAttempted = a[0].toInt(),
                totalPointsSolved = a[1].toInt(),
                totalPointsRejected = a[2].toInt(),
                pathAPoints = a[SLOT_PATH_A_POINTS].toInt(),
                pathBPoints = a[SLOT_PATH_B_POINTS].toInt(),
                simplexCalls = a[5].toInt(),
                simplexSaved = a[6].toInt(),
                finalDeadPoints = a[7].toInt(),
                avgIcgnIterations = a[SLOT_AVG_ITERS],
                wallTimeMs = a[9],
                akazeRansacMs = a[10],
                hessianPrepassMs = a[11],
                delaunayMs = a[12],
                strainMs = a[13],
                avgThroughputPtsPerMs = a[14],
                convergencePercent = a[SLOT_CONVERGENCE],
                meshSeedingQuality = if (a.size > SLOT_MESH_SEEDING) {
                    a[SLOT_MESH_SEEDING].toInt()
                } else {
                    MESH_SEEDING_UNKNOWN
                },
                simplexMs = if (a.size > SLOT_SIMPLEX_MS) a[SLOT_SIMPLEX_MS] else 0f,
                icgnMs = if (a.size > SLOT_ICGN_MS) a[SLOT_ICGN_MS] else 0f,
            )
        } else {
            EngineStats(0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }
}
