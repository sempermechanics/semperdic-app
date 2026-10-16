package com.sempermechanics.semper.ui.analysis.rerun

import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderMode
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * What Home's Re-run starts a new analysis from: a saved run's inputs, as the
 * wizard held them when the user tapped Compute (owner decision, 2026-10-15:
 * a finished analysis is never re-run in place, only copied).
 *
 * Kept in the session's `rerun/` folder ([SessionLayout.rerunInputs]) beside
 * the original reference and mask, which the session keeps nowhere else (its
 * `reference.png` is a display-size copy). Frame paths are relative to the
 * session folder: `raw_deformed/…` for a frame the run reached, `rerun/frames/…`
 * for one it stopped before. On this phone only: the backup does not carry
 * it, so a restored analysis has none, and nor does one saved before it
 * existed.
 *
 * @property roi the ROI as drawn, `[x, y, w, h]`; the run solves `Roi.forSolve` of it
 * @property sweep set for a parameter sweep, whose one frame is [frames]
 */
@Serializable
internal data class RerunInputs(
    val version: Int = VERSION,
    val reference: Reference,
    val hasMask: Boolean,
    val hasCustomRoi: Boolean,
    val roi: List<Int>,
    val frames: List<Frame>,
    val fromVideo: Boolean = false,
    val videoName: String? = null,
    val orderMode: String = FrameOrderMode.NAME.name,
    val orderDirection: String = FrameOrderDirection.ASCENDING.name,
    val params: Params,
    val sweep: Sweep? = null,
) {
    /** The reference's file name and true pixel size. */
    @Serializable
    data class Reference(val name: String, val width: Int, val height: Int)

    /**
     * One deformed frame, in run order: where it is ([path], under the
     * session folder), the name it was picked as, its date, its size (`-1`
     * unmeasured) and its time in its clip.
     */
    @Serializable
    data class Frame(
        val path: String,
        val name: String,
        val date: Long = DeformedFrame.UNKNOWN_DATE,
        val width: Int = -1,
        val height: Int = -1,
        val timeMs: Long? = null,
    )

    /**
     * The solver's settings: subset, step and strain window (VSG, px), the Keys
     * interpolator, and how often the reference moved (0: never; absent in
     * files written before incremental runs, which all kept it fixed).
     */
    @Serializable
    data class Params(
        val subset: Int,
        val step: Int,
        val strainWindow: Int,
        val use6x6: Boolean,
        val refUpdateInterval: Int = 0,
    )

    /**
     * A sweep's settings: its axes ([SweepRanges.toIntArray]'s layout), the
     * overlap, the line-cut axis, and the frame it solved against, an index
     * into [frames].
     */
    @Serializable
    data class Sweep(
        val ranges: List<Int>,
        val overlap: Double,
        val lineCutHorizontal: Boolean,
        val frameIndex: Int,
    )

    /** [roi] as a [Roi]; the full frame when it is not four values. */
    fun drawnRoi(): Roi = Roi.fromXywh(roi.toIntArray()) ?: Roi(0, 0, reference.width, reference.height)

    /** [sweep]'s axes; null for a single run or an unreadable array. */
    fun sweepRanges(): SweepRanges? = sweep?.let { SweepRanges.fromIntArray(it.ranges.toIntArray()) }

    /** Each of [frames] in the session folder [dir]. */
    fun frameFiles(dir: File): List<File> = frames.map { File(dir, it.path) }

    companion object {
        /** Bumped when a field changes meaning; a reader skips any other. */
        const val VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(inputs: RerunInputs): String = json.encodeToString(serializer(), inputs)

        /** [text] as inputs; null when it is not this [VERSION]'s. */
        fun decode(text: String): RerunInputs? = try {
            json.decodeFromString(serializer(), text).takeIf { it.version == VERSION }
        } catch (e: IllegalArgumentException) { // SerializationException included
            Timber.w(e, "Unreadable re-run inputs")
            null
        }

        /**
         * The inputs saved in session folder [dir], or null when they are not
         * all on this phone: the inputs themselves, the reference, the mask
         * when the run had one, and every frame. A Re-run of only some of
         * them would be a different analysis, so it is all or none.
         */
        @WorkerThread
        fun readComplete(dir: File): RerunInputs? {
            val layout = SessionLayout(dir)
            val inputs = layout.rerunInputs.takeIf { it.isFile }
                ?.let { runCatching { it.readText() }.getOrNull() }
                ?.let(::decode)
                ?: return null
            val root = dir.canonicalFile
            val complete = layout.rerunReference.isFile &&
                (!inputs.hasMask || layout.rerunMask.isFile) &&
                inputs.frames.isNotEmpty() &&
                inputs.frameFiles(dir).all { it.isFile && it.length() > 0 && it.canonicalFile.startsWith(root) }
            return inputs.takeIf { complete }
        }
    }
}
