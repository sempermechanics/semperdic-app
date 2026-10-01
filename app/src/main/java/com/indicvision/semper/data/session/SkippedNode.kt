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

        fun fromLegacyArrays(
            subsets: List<Int>,
            steps: List<Int>,
            strainWindows: List<Int>,
            codes: List<Int>,
        ): List<SkippedNode> {
            val n = listOf(subsets.size, steps.size, strainWindows.size, codes.size).distinct()
            require(n.size == 1) {
                "Legacy skip arrays length mismatch: subsets=${subsets.size} steps=${steps.size} " +
                    "windows=${strainWindows.size} codes=${codes.size}"
            }
            return subsets.indices.map { i ->
                SkippedNode(subsets[i], steps[i], strainWindows[i], codes[i])
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

        /** Cloud metadata `engine.sweep.skipped` — `nodes` array or legacy lists. */
        fun fromMetadata(skipped: JSONObject?): List<SkippedNode> {
            val fromNodes = skipped?.optJSONArray("nodes")
                ?.takeIf { it.length() > 0 }
                ?.let { arr ->
                    buildList {
                        for (i in 0 until arr.length()) {
                            val entry = arr.optJSONObject(i) ?: continue
                            add(
                                SkippedNode(
                                    subset = entry.optInt("subset"),
                                    step = entry.optInt("step"),
                                    strainWindow = entry.optInt("strainWindow"),
                                    code = entry.optInt("code"),
                                ),
                            )
                        }
                    }
                }
            return fromNodes
                ?: skipped?.let {
                    fromLegacyArrays(
                        jsonIntList(it.optJSONArray("subsets")),
                        jsonIntList(it.optJSONArray("steps")),
                        jsonIntList(it.optJSONArray("strainWindows")),
                        jsonIntList(it.optJSONArray("codes")),
                    )
                }
                    .orEmpty()
        }

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

        private fun jsonIntList(arr: JSONArray?): List<Int> = buildList {
            if (arr == null) return@buildList
            for (i in 0 until arr.length()) add(arr.optInt(i))
        }

        data class LegacyLists(
            val subsets: List<Int>,
            val steps: List<Int>,
            val strainWindows: List<Int>,
            val codes: List<Int>,
        )
    }
}
