package com.indicvision.semper.data.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** One unsolved sweep combination: subset, step, strain window, and engine code. */
@Serializable
data class SkippedNode(
    val subset: Int,
    val step: Int,
    val strainWindow: Int,
    val code: Int,
) {
    companion object {
        /**
         * The code of a legacy node saved before codes were kept. The FI-3 types
         * have no "no code" value, so such a node reads as the engine's 0, the
         * strain-window (VSG) skip.
         */
        const val UNRECORDED_CODE = 0

        private val json = Json { ignoreUnknownKeys = true }

        fun encodeJson(nodes: List<SkippedNode>): String = json.encodeToString(nodes)

        /**
         * The nodes of a sweep that solved nothing. The run's own per-node codes
         * win; [planned] (every node under one code) covers only a run that
         * ended before it recorded any.
         */
        fun forFailedSweep(recorded: List<SkippedNode>, planned: () -> List<SkippedNode>): List<SkippedNode> =
            recorded.ifEmpty(planned)

        fun decodeJson(raw: String?): List<SkippedNode> {
            if (raw.isNullOrBlank()) return emptyList()
            return json.decodeFromString(raw)
        }

        /** Prefer SWEEP_SKIPPED JSON; else legacy int arrays on the intent. */
        fun decodeFromExtras(
            sweepSkippedJson: String?,
            subsets: IntArray?,
            steps: IntArray?,
            strainWindows: IntArray?,
            codes: IntArray?,
        ): List<SkippedNode> = if (!sweepSkippedJson.isNullOrBlank()) {
            decodeJson(sweepSkippedJson)
        } else {
            fromLegacyArrays(subsets, steps, strainWindows, codes)
        }

        /**
         * The four parallel lists from before FI-3. The combinations must agree
         * in length. [codes] may be shorter or empty: sweeps saved or backed up
         * before codes were kept (5a1e5fcb..1eb3fa2d) have none, and those nodes
         * read as [UNRECORDED_CODE].
         */
        fun fromLegacyArrays(
            subsets: List<Int>,
            steps: List<Int>,
            strainWindows: List<Int>,
            codes: List<Int>,
        ): List<SkippedNode> {
            val count = subsets.size
            require(steps.size == count && strainWindows.size == count && codes.size <= count) {
                "Legacy skip arrays length mismatch: subsets=${subsets.size} steps=${steps.size} " +
                    "windows=${strainWindows.size} codes=${codes.size}"
            }
            return subsets.indices.map { i ->
                SkippedNode(subsets[i], steps[i], strainWindows[i], codes.getOrElse(i) { UNRECORDED_CODE })
            }
        }

        fun fromLegacyArrays(
            subsets: IntArray?,
            steps: IntArray?,
            strainWindows: IntArray?,
            codes: IntArray?,
        ): List<SkippedNode> = fromLegacyArrays(
            subsets = subsets?.toList().orEmpty(),
            steps = steps?.toList().orEmpty(),
            strainWindows = strainWindows?.toList().orEmpty(),
            codes = codes?.toList().orEmpty(),
        )

        fun toLegacyLists(nodes: List<SkippedNode>): LegacyLists = LegacyLists(
            subsets = nodes.map { it.subset },
            steps = nodes.map { it.step },
            strainWindows = nodes.map { it.strainWindow },
            codes = nodes.map { it.code },
        )

        fun toMetadataJsonArray(nodes: List<SkippedNode>): JSONArray {
            val arr = JSONArray()
            for (node in nodes) {
                arr.put(
                    JSONObject()
                        .put("subset", node.subset)
                        .put("step", node.step)
                        .put("strainWindow", node.strainWindow)
                        .put("code", node.code),
                )
            }
            return arr
        }

        data class LegacyLists(
            val subsets: List<Int>,
            val steps: List<Int>,
            val strainWindows: List<Int>,
            val codes: List<Int>,
        )
    }
}
