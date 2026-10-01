@file:OptIn(ExperimentalSerializationApi::class)

package com.indicvision.semper.data.cloud

import android.content.Context
import android.os.Build
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.data.account.DeviceKeyManager
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.session.SessionHeadline
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SkippedNode
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.util.LenientObjectListSerializer
import com.indicvision.semper.util.OptBooleanSerializer
import com.indicvision.semper.util.OptDoubleSerializer
import com.indicvision.semper.util.OptFloatListSerializer
import com.indicvision.semper.util.OptIntListSerializer
import com.indicvision.semper.util.OptIntSerializer
import com.indicvision.semper.util.OptObjectSerializer
import com.indicvision.semper.util.OptStringListSerializer
import com.indicvision.semper.util.OptStringSerializer
import com.indicvision.semper.util.StrictObjectListSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A backup's `metadata.json`, as a model.
 *
 * Two hands wrote this file's shape: [SessionUploadMetadata] builds it with
 * `JSONObject.put`, and `CloudRestore.recordFrom` reads it back with `opt…`
 * getters. This holds both halves: [fromRecord] / [forUpload] + [encode]
 * write what the uploader writes, and [decode] + [toRecord] read what the
 * restore reads, defaults and leniency included (every field goes through
 * the [com.indicvision.semper.util.OrgJson] rules, so an old file, or one
 * with a value of the wrong kind, reads exactly as `opt…` read it).
 *
 * Every field is nullable and null means absent: the defaults are the
 * reader's, applied in [toRecord], because the same absent field can default
 * differently in two places (`specimen` is "Restored" for the name and
 * "Reference" for the reference). Keys the model does not know (another app's
 * schema, a field from a later build) are ignored on read and not written
 * back; the restore keeps the downloaded bytes as they came.
 *
 * Key order follows the writer's, so an encoded file reads like an uploaded one.
 * [encode] is semantically equal to `SessionUploadMetadata.buildMetadataJson`
 * (the same keys, values and two-space indent) but not byte-identical: Android's
 * org.json writes an integral float as `50` where this writes `50.0`, and it
 * escapes `/` as `\/` (the `schema` string contains one) where this does not.
 * Compare the two as JSON, never as text or by digest.
 *
 * [decode] throws [kotlinx.serialization.SerializationException] (an
 * [IllegalArgumentException]) for text that is not a JSON object or whose
 * `frames` holds a non-object, where org.json threw `JSONException`. An adopter
 * in the restore maps it to `CorruptTransferException("metadata_json_invalid")`
 * as it maps `JSONException` today.
 */
@Serializable
data class SessionMetadataDoc(
    @Serializable(with = OptStringSerializer::class) val schema: String? = null,
    @Serializable(with = OptStringSerializer::class) val localSessionId: String? = null,
    @Serializable(with = OptStringSerializer::class) val name: String? = null,
    @Serializable(with = OptStringSerializer::class) val specimen: String? = null,
    @Serializable(with = OptStringSerializer::class) val capturedAtUtc: String? = null,
    @Serializable(with = OptIntSerializer::class) val frameCount: Int? = null,
    /** `batch` or `vsg_study`; absent before the sweep. */
    @Serializable(with = OptStringSerializer::class) val analysisKind: String? = null,
    /** The one combined CSV's name; absent from `/2` files, whose frames each named a CSV. */
    @Serializable(with = OptStringSerializer::class) val csv: String? = null,
    @Serializable(with = FramesSerializer::class) val frames: List<Frame>? = null,
    @Serializable(with = AppSerializer::class) val app: App? = null,
    @Serializable(with = DeviceSerializer::class) val device: Device? = null,
    @Serializable(with = UserSerializer::class) val user: User? = null,
    @Serializable(with = EngineSerializer::class) val engine: Engine? = null,
    @Serializable(with = MetricsSerializer::class) val metrics: Metrics? = null,
) {

    /** One analysed frame. The settings are written for a sweep only, where each frame has its own. */
    @Serializable
    data class Frame(
        @Serializable(with = OptIntSerializer::class) val index: Int? = null,
        /** `Frame_N`, or a sweep's combination label. */
        @Serializable(with = OptStringSerializer::class) val frame: String? = null,
        /** The deformed image's file name; the restore rebuilds `defNames` from these. */
        @Serializable(with = OptStringSerializer::class) val image: String? = null,
        @Serializable(with = OptStringSerializer::class) val dat: String? = null,
        @Serializable(with = OptIntSerializer::class) val subset: Int? = null,
        @Serializable(with = OptIntSerializer::class) val step: Int? = null,
        @Serializable(with = OptIntSerializer::class) val strainWindow: Int? = null,
        /** Same value as [strainWindow] since the window is stored in px. */
        @Serializable(with = OptIntSerializer::class) val vsg: Int? = null,
    )

    @Serializable
    data class App(
        @Serializable(with = OptStringSerializer::class) val versionName: String? = null,
        @Serializable(with = OptIntSerializer::class) val versionCode: Int? = null,
    )

    @Serializable
    data class Device(
        @Serializable(with = OptStringSerializer::class) val id: String? = null,
        @Serializable(with = OptStringSerializer::class) val manufacturer: String? = null,
        @Serializable(with = OptStringSerializer::class) val model: String? = null,
        @Serializable(with = OptStringSerializer::class) val os: String? = null,
        @Serializable(with = OptIntSerializer::class) val sdkInt: Int? = null,
    )

    /** Signed-out uploads leave both absent (`JSONObject.put` drops a null). */
    @Serializable
    data class User(
        @Serializable(with = OptStringSerializer::class) val uid: String? = null,
        @Serializable(with = OptStringSerializer::class) val email: String? = null,
    )

    @Serializable
    data class Engine(
        @Serializable(with = OptIntSerializer::class) val subset: Int? = null,
        @Serializable(with = OptIntSerializer::class) val step: Int? = null,
        @Serializable(with = OptIntSerializer::class) val strainWindow: Int? = null,
        @Serializable(with = OptStringSerializer::class) val strainMethod: String? = null,
        @Serializable(with = OptBooleanSerializer::class) val use6x6: Boolean? = null,
        @Serializable(with = OptIntSerializer::class) val imageWidth: Int? = null,
        @Serializable(with = OptIntSerializer::class) val imageHeight: Int? = null,
        @Serializable(with = RoiSerializer::class) val roi: Roi? = null,
        /** The engine's telemetry slots ([EngineStats.fromArray]). */
        @Serializable(with = OptFloatListSerializer::class) val stats: List<Float>? = null,
        @Serializable(with = SweepSerializer::class) val sweep: Sweep? = null,
    )

    @Serializable
    data class Roi(
        @Serializable(with = OptIntSerializer::class) val x: Int? = null,
        @Serializable(with = OptIntSerializer::class) val y: Int? = null,
        @Serializable(with = OptIntSerializer::class) val w: Int? = null,
        @Serializable(with = OptIntSerializer::class) val h: Int? = null,
    ) {
        /** Each side as the reader takes it: 0 when absent. */
        data class Resolved(val x: Int, val y: Int, val w: Int, val h: Int)

        fun orZero(): Resolved = Resolved(x ?: 0, y ?: 0, w ?: 0, h ?: 0)
    }

    @Serializable
    data class Sweep(
        @Serializable(with = OptBooleanSerializer::class) val lineCutHorizontal: Boolean? = null,
        @Serializable(with = OptIntListSerializer::class) val subsets: List<Int>? = null,
        @Serializable(with = OptIntListSerializer::class) val steps: List<Int>? = null,
        @Serializable(with = OptIntListSerializer::class) val strainWindows: List<Int>? = null,
        @Serializable(with = OptStringListSerializer::class) val labels: List<String>? = null,
        @Serializable(with = SkippedSerializer::class) val skipped: Skipped? = null,
    )

    /**
     * The combinations that solved nothing: a `nodes` array since FI-3, four
     * parallel lists before it. Non-object `nodes` entries are kept as null so
     * an array of only those still counts as present ([SkippedNode.fromMetadata]).
     */
    @Serializable
    data class Skipped(
        @Serializable(with = NodesSerializer::class) val nodes: List<Node?>? = null,
        @Serializable(with = OptIntListSerializer::class) val subsets: List<Int>? = null,
        @Serializable(with = OptIntListSerializer::class) val steps: List<Int>? = null,
        @Serializable(with = OptIntListSerializer::class) val strainWindows: List<Int>? = null,
        @Serializable(with = OptIntListSerializer::class) val codes: List<Int>? = null,
    ) {
        /**
         * As [SkippedNode.fromMetadata]: nodes when there are any, else the
         * legacy lists (which must agree in length).
         */
        fun toSkippedNodes(): List<SkippedNode> =
            nodes?.takeIf { it.isNotEmpty() }?.filterNotNull()?.map { it.toSkippedNode() }
                ?: SkippedNode.fromLegacyArrays(
                    subsets.orEmpty(),
                    steps.orEmpty(),
                    strainWindows.orEmpty(),
                    codes.orEmpty(),
                )
    }

    @Serializable
    data class Node(
        @Serializable(with = OptIntSerializer::class) val subset: Int? = null,
        @Serializable(with = OptIntSerializer::class) val step: Int? = null,
        @Serializable(with = OptIntSerializer::class) val strainWindow: Int? = null,
        @Serializable(with = OptIntSerializer::class) val code: Int? = null,
    ) {
        fun toSkippedNode(): SkippedNode = SkippedNode(subset ?: 0, step ?: 0, strainWindow ?: 0, code ?: 0)
    }

    @Serializable
    data class Metrics(
        @Serializable(with = OptIntSerializer::class) val pointsConverged: Int? = null,
        @Serializable(with = OptDoubleSerializer::class) val avgIterations: Double? = null,
        @Serializable(with = OptIntSerializer::class) val executionTimeMs: Int? = null,
        /** Absent before runs kept why they stopped: read as a completed run. */
        @Serializable(with = OptIntSerializer::class) val stopCode: Int? = null,
        @Serializable(with = OptIntSerializer::class) val plannedFrameCount: Int? = null,
        @Serializable(with = OptBooleanSerializer::class) val isSweep: Boolean? = null,
        @Serializable(with = OptIntSerializer::class) val sweepSolved: Int? = null,
        @Serializable(with = OptIntSerializer::class) val sweepSkipped: Int? = null,
    )

    /** The file's text: two-space indent, in the writer's key order. */
    fun encode(): String = WRITER.encodeToString(serializer(), this)

    /** The frame image names the restore keeps (`restoredFrameNames`): blank ones dropped. */
    fun frameImages(): List<String> = frames.orEmpty().map { it.image ?: "" }.filter { it.isNotBlank() }

    /** Whether the backup's `Session.zip` holds only the restore payload ([CloudRestore.isSplitLayout]). */
    fun isSplitLayout(): Boolean = CloudRestore.isSplitLayout(schema ?: "")

    /**
     * The index row a restore writes for this file, as `CloudRestore.recordFrom`
     * builds it: [existing] keeps its name, creation time and rename flag;
     * everything else comes from the file, under the reader's defaults.
     * Throws, as the reader does, when legacy skip lists disagree in length.
     */
    @Suppress("LongParameterList") // the restore target's fields, plus the clock
    fun toRecord(
        localId: String,
        cloudSessionId: String,
        sessionDir: File,
        refPath: String,
        existing: SessionRecord?,
        now: Long = System.currentTimeMillis(),
    ): SessionRecord {
        val engine = engine ?: Engine()
        val roi = (engine.roi ?: Roi()).orZero()
        val defNames = frameImages()
        val record = SessionRecord(
            id = localId,
            name = restoredName(existing),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            frameCount = frameCount ?: defNames.size,
            subset = engine.subset ?: DEFAULT_SUBSET,
            step = engine.step ?: DEFAULT_STEP,
            strainWindow = engine.strainWindow ?: DEFAULT_STRAIN_WINDOW,
            imgW = engine.imageWidth ?: 0,
            imgH = engine.imageHeight ?: 0,
            roiX = roi.x,
            roiY = roi.y,
            roiW = roi.w,
            roiH = roi.h,
            refPath = refPath,
            refName = specimen ?: "Reference",
            sessionDir = sessionDir.absolutePath,
            defNames = defNames,
            cloudSessionId = cloudSessionId,
            // It came from the cloud, so it is by definition backed up.
            syncState = SessionRecord.SyncState.SYNCED,
            renamedByUser = existing?.renamedByUser ?: false,
        )
        return withRun(record, engine, defNames)
    }

    /** The row's name: the existing row's, else the file's, else the specimen's, else "Restored". */
    private fun restoredName(existing: SessionRecord?): String =
        existing?.name?.takeIf { it.isNotBlank() } ?: (name ?: "").ifBlank { specimen ?: "Restored" }

    /** [record] with the run's engine settings, telemetry, metrics and sweep from this file. */
    private fun withRun(record: SessionRecord, engine: Engine, defNames: List<String>): SessionRecord {
        val metrics = metrics ?: Metrics()
        val stats = engine.stats.orEmpty()
        val sweep = engine.sweep
        val skipNodes = sweep?.skipped?.toSkippedNodes().orEmpty()
        val legacySkip = SkippedNode.toLegacyLists(skipNodes)
        return record.copy(
            use6x6 = engine.use6x6 ?: false,
            headline = headline(engine, defNames, stats),
            engineStats = stats,
            strainMethod = engine.strainMethod ?: "VSG",
            pointsConverged = metrics.pointsConverged ?: 0,
            avgIterations = (metrics.avgIterations ?: 0.0).toFloat(),
            executionTimeMs = metrics.executionTimeMs ?: 0,
            stopCode = metrics.stopCode ?: 0,
            plannedFrameCount = metrics.plannedFrameCount ?: 0,
            sweepSubsets = sweep?.subsets.orEmpty(),
            sweepSteps = sweep?.steps.orEmpty(),
            sweepStrainWindows = sweep?.strainWindows.orEmpty(),
            sweepLabels = sweep?.labels.orEmpty(),
            lineCutHorizontal = sweep?.lineCutHorizontal ?: true,
            sweepSkipSubsets = legacySkip.subsets,
            sweepSkipSteps = legacySkip.steps,
            sweepSkipStrainWindows = legacySkip.strainWindows,
            sweepSkipCodes = legacySkip.codes,
            sweepSkippedNodes = skipNodes,
        )
    }

    /** `CloudRestore.restoredHeadline`: a sweep's span and solved count, else first-frame convergence. */
    private fun headline(engine: Engine, defNames: List<String>, stats: List<Float>): String {
        val sweep = engine.sweep
            ?: return SessionHeadline.firstFrameConvergence(
                stats.getOrElse(EngineStats.SLOT_CONVERGENCE) { 0f },
                defNames.size,
            )
        val solved = frameCount ?: defNames.size
        val skipCount = sweep.skipped?.toSkippedNodes().orEmpty().size
        val image = defNames.firstOrNull().orEmpty().ifBlank { specimen ?: "frame" }
        val subsets = sweep.subsets.orEmpty()
        val lo = subsets.minOrNull() ?: engine.subset ?: 0
        val hi = subsets.maxOrNull() ?: lo
        return String.format(
            Locale.US,
            "%s · %d of %d solved · subset %d–%d",
            image,
            solved,
            solved + skipCount,
            lo,
            hi,
        )
    }

    companion object {
        private const val DEFAULT_SUBSET = 41
        private const val DEFAULT_STEP = 5
        private const val DEFAULT_STRAIN_WINDOW = 15

        /** Lenient like org.json's reader; keys it does not know are skipped. */
        private val READER = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** Absent (null) fields are left out, as `JSONObject.put` leaves out a null. */
        private val WRITER = Json {
            encodeDefaults = false
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        /**
         * Parses a `metadata.json`. Throws [kotlinx.serialization.SerializationException]
         * (an [IllegalArgumentException]) for text that is not a JSON object,
         * or whose `frames` holds a non-object.
         */
        fun decode(text: String): SessionMetadataDoc = READER.decodeFromString(serializer(), text)

        /** `capturedAtUtc` as the uploader stamps it: second precision, `Z`. */
        fun utcStamp(millis: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(millis))

        /**
         * What [SessionUploadMetadata.buildMetadataJson] writes for [record],
         * with this phone's build, device and signed-in user.
         */
        fun forUpload(record: SessionRecord, context: Context): SessionMetadataDoc = fromRecord(
            record = record,
            capturedAtUtc = utcStamp(),
            app = App(versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE),
            device = Device(
                id = DeviceKeyManager.deviceId(context),
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                os = "Android ${Build.VERSION.RELEASE}",
                sdkInt = Build.VERSION.SDK_INT,
            ),
            user = User(uid = TokenStore.cachedUid(context), email = TokenStore.cachedEmail(context)),
        )

        /** The uploader's document for [record]; the environment parts are passed in. */
        fun fromRecord(
            record: SessionRecord,
            capturedAtUtc: String,
            app: App,
            device: Device,
            user: User,
        ): SessionMetadataDoc {
            val metrics = Metrics(
                pointsConverged = record.pointsConverged,
                avgIterations = record.avgIterations.toDouble(),
                executionTimeMs = record.executionTimeMs,
                stopCode = record.stopCode,
                plannedFrameCount = record.plannedFrameCount,
                isSweep = true.takeIf { record.isSweep },
                sweepSolved = record.frameCount.takeIf { record.isSweep },
                sweepSkipped = record.sweepSkipCount.takeIf { record.isSweep },
            )
            return SessionMetadataDoc(
                schema = SessionUploadMetadata.SCHEMA,
                localSessionId = record.id,
                name = record.name,
                specimen = record.refName,
                capturedAtUtc = capturedAtUtc,
                frameCount = record.frameCount,
                analysisKind = if (record.isSweep) "vsg_study" else "batch",
                csv = "analysis_data.csv",
                frames = record.defNames.mapIndexed { index, image -> frameOf(record, index, image) },
                app = app,
                device = device,
                user = user,
                engine = engineOf(record),
                metrics = metrics,
            )
        }

        private fun frameOf(record: SessionRecord, index: Int, image: String): Frame {
            val label = if (record.isSweep) {
                record.sweepLabels.getOrElse(index) { "Combination_${index + 1}" }
            } else {
                "Frame_${index + 1}"
            }
            val frame = Frame(index = index, frame = label, image = image, dat = SessionPaths.frameDatName(index))
            if (!record.isSweep) return frame
            val window = record.sweepStrainWindows.getOrElse(index) { record.strainWindow }
            return frame.copy(
                subset = record.sweepSubsets.getOrElse(index) { record.subset },
                step = record.sweepSteps.getOrElse(index) { record.step },
                strainWindow = window,
                vsg = window,
            )
        }

        private fun engineOf(record: SessionRecord): Engine = Engine(
            subset = record.subset,
            step = record.step,
            strainWindow = record.strainWindow,
            strainMethod = record.strainMethod,
            use6x6 = record.use6x6,
            imageWidth = record.imgW,
            imageHeight = record.imgH,
            roi = Roi(record.roiX, record.roiY, record.roiW, record.roiH),
            stats = record.engineStats,
            sweep = if (record.isSweep) {
                Sweep(
                    lineCutHorizontal = record.lineCutHorizontal,
                    subsets = record.sweepSubsets,
                    steps = record.sweepSteps,
                    strainWindows = record.sweepStrainWindows,
                    labels = record.sweepLabels,
                    skipped = Skipped(
                        nodes = record.resolvedSkipNodes().map { Node(it.subset, it.step, it.strainWindow, it.code) },
                    ),
                )
            } else {
                null
            },
        )
    }

    internal object FramesSerializer : StrictObjectListSerializer<Frame>(Frame.serializer())

    internal object AppSerializer : OptObjectSerializer<App>(App.serializer())

    internal object DeviceSerializer : OptObjectSerializer<Device>(Device.serializer())

    internal object UserSerializer : OptObjectSerializer<User>(User.serializer())

    internal object EngineSerializer : OptObjectSerializer<Engine>(Engine.serializer())

    internal object RoiSerializer : OptObjectSerializer<Roi>(Roi.serializer())

    internal object SweepSerializer : OptObjectSerializer<Sweep>(Sweep.serializer())

    internal object SkippedSerializer : OptObjectSerializer<Skipped>(Skipped.serializer())

    internal object MetricsSerializer : OptObjectSerializer<Metrics>(Metrics.serializer())

    internal object NodesSerializer : LenientObjectListSerializer<Node>(Node.serializer().nullable)
}
