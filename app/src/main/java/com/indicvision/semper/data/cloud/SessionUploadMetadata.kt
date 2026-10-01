package com.indicvision.semper.data.cloud

import android.content.Context
import android.os.Build
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.data.account.DeviceKeyManager
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SkippedNode
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Session-level metadata JSON for cloud upload: device, time, engine params,
 * and the frame list (`metadata.json`).
 */
object SessionUploadMetadata {

    /**
     * Backup layout version, and the one field a restore is allowed to branch on.
     *
     * - `/2` and earlier: a single `Session.zip` holding raw/, dat/, csv/, reports/
     *   and processed/ together.
     * - `/3`: the payload is split — `Session.zip` carries only raw/ + dat/, and the
     *   derived deliverables live in a separate `Extras.zip` that a restore skips.
     *
     * Restore reads this to know whether the bundle it is about to fetch is the small
     * split one or a legacy everything-archive (see `CloudRestore.isSplitLayout`).
     * Bump it only when that distinction changes, and keep the parse tolerant:
     * pre-`/3` backups predate the field being read at all.
     */
    const val SCHEMA = "indic.session.metadata/3"

    /** Layout version at which the restore payload was split out of the bundle. */
    const val SCHEMA_SPLIT_BUNDLE = 3

    /** One JSON object per frame: its label, files, and (for a sweep) its settings. */
    fun framesJson(record: SessionRecord): JSONArray {
        val frames = JSONArray()
        record.defNames.forEachIndexed { index, name ->
            val frameObj = JSONObject()
                .put("index", index)
                .put(
                    "frame",
                    if (record.isSweep) {
                        record.sweepLabels.getOrElse(index) { "Combination_${index + 1}" }
                    } else {
                        "Frame_${index + 1}"
                    },
                )
                .put("image", name)
                .put("dat", SessionPaths.frameDatName(index))
            if (record.isSweep) {
                val subset = record.sweepSubsets.getOrElse(index) { record.subset }
                val step = record.sweepSteps.getOrElse(index) { record.step }
                val window = record.sweepStrainWindows.getOrElse(index) { record.strainWindow }
                frameObj
                    .put("subset", subset)
                    .put("step", step)
                    // The stored window is already the VSG in px (the engine's diameter).
                    .put("strainWindow", window)
                    .put("vsg", window)
            }
            frames.put(frameObj)
        }
        return frames
    }

    fun buildMetadataJson(record: SessionRecord, context: Context): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val frames = framesJson(record)
        val metrics = JSONObject()
            .put("pointsConverged", record.pointsConverged)
            .put("avgIterations", record.avgIterations.toDouble())
            .put("executionTimeMs", record.executionTimeMs)
            // Why a run ended short, and what it set out to do. Without them a
            // restored run that stopped at 39 of 50 reads as a clean 39-frame test.
            .put("stopCode", record.stopCode)
            .put("plannedFrameCount", record.plannedFrameCount)
        if (record.isSweep) {
            metrics
                .put("isSweep", true)
                .put("sweepSolved", record.frameCount)
                .put("sweepSkipped", record.sweepSkipCount)
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("localSessionId", record.id)
            .put("name", record.name)
            .put("specimen", record.refName)
            .put("capturedAtUtc", iso)
            .put("frameCount", record.frameCount)
            .put("analysisKind", if (record.isSweep) "vsg_study" else "batch")
            // One combined CSV for the whole analysis (every frame's points, keyed
            // by the leading columns) rather than a file per frame.
            .put("csv", "analysis_data.csv")
            .put("frames", frames)
            .put(
                "app",
                JSONObject()
                    .put("versionName", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE),
            )
            .put("device", deviceJson(context))
            .put(
                "user",
                JSONObject()
                    .put("uid", TokenStore.cachedUid(context))
                    .put("email", TokenStore.cachedEmail(context)),
            )
            .put("engine", engineJson(record))
            .put("metrics", metrics)
            .toString(2)
    }

    fun deviceJson(context: Context): JSONObject = JSONObject()
        .put("id", DeviceKeyManager.deviceId(context))
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("os", "Android ${Build.VERSION.RELEASE}")
        .put("sdkInt", Build.VERSION.SDK_INT)

    fun engineJson(record: SessionRecord): JSONObject {
        val engine = JSONObject()
            .put("subset", record.subset)
            .put("step", record.step)
            .put("strainWindow", record.strainWindow)
            .put("strainMethod", record.strainMethod)
            .put("use6x6", record.use6x6)
            .put("imageWidth", record.imgW)
            .put("imageHeight", record.imgH)
            .put(
                "roi",
                JSONObject()
                    .put("x", record.roiX).put("y", record.roiY)
                    .put("w", record.roiW).put("h", record.roiH),
            )
            .put("stats", JSONArray(record.engineStats))
        if (record.isSweep) {
            engine.put(
                "sweep",
                JSONObject()
                    .put("lineCutHorizontal", record.lineCutHorizontal)
                    .put("subsets", JSONArray(record.sweepSubsets))
                    .put("steps", JSONArray(record.sweepSteps))
                    .put("strainWindows", JSONArray(record.sweepStrainWindows))
                    .put("labels", JSONArray(record.sweepLabels))
                    .put(
                        "skipped",
                        JSONObject()
                            .put("nodes", SkippedNode.toMetadataJsonArray(record.resolvedSkipNodes())),
                    ),
            )
        }
        return engine
    }
}
