package com.sempermechanics.semper.ui.analysis.wizard

import android.os.Bundle
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.getRoi
import com.sempermechanics.semper.field.putRoi
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderMode
import com.sempermechanics.semper.ui.analysis.sweep.SweepFrame
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * The wizard's editing state across a process death (ADR-005).
 *
 * Split by size. The scalars go in the view model's `SavedStateHandle` as one
 * Bundle ([save] / [restoreScalars]) and are back before the Activity's first
 * frame. The frame list (up to 500 paths, too big for a Bundle), the
 * reference bytes and the mask go in the [WizardDraft] and come back through
 * [readInputs], off the main thread.
 *
 * The settings sliders are not here: they are views with ids, and the
 * Activity's own saved view state restores them.
 */
internal object WizardState {

    /** The `SavedStateHandle` key the whole Bundle lives under. */
    const val KEY = "wizard_state"

    private const val STEP = "step"
    private const val SETTINGS_REVIEWED = "settingsReviewed"
    private const val SUBSET_USER_MODIFIED = "subsetUserModified"
    private const val REF_W = "refW"
    private const val REF_H = "refH"
    private const val REF_NAME = "refName"
    private const val HAS_REFERENCE = "hasReference"
    private const val HAS_MASK = "hasMask"
    private const val HAS_CUSTOM_ROI = "hasCustomRoi"
    private const val ROI = "roi"
    private const val FRAME_COUNT = "frameCount"

    /**
     * SHA-256 of the frame list [save] was handed, as the draft should hold
     * it. Absent in a Bundle an older app saved; [readInputs] then falls back
     * to [FRAME_COUNT] alone.
     */
    private const val FRAMES_FINGERPRINT = "framesFingerprint"

    /**
     * [draftDigest] of the reference and of the mask [save] was handed, null when
     * the wizard held none. Absent in a Bundle an older app saved;
     * [readInputs] then checks only that each part is there.
     */
    private const val REFERENCE_DIGEST = "referenceDigest"
    private const val MASK_DIGEST = "maskDigest"
    private const val ORDER_MODE = "orderMode"
    private const val ORDER_DIRECTION = "orderDirection"
    private const val FRAME_SIZE_ERROR = "frameSizeError"
    private const val FROM_VIDEO = "fromVideo"
    private const val VIDEO_NAME = "videoName"
    private const val SWEEP_MODE = "sweepMode"
    private const val SWEEP_RANGES = "sweepRanges"
    private const val SUBSET_OVERLAP = "subsetOverlap"
    private const val LINE_CUT_HORIZONTAL = "lineCutHorizontal"
    private const val SWEEP_FRAME_INDEX = "vsgFrameIndex"
    private const val WORKING_LOCAL_ID = "workingLocalId"
    private const val WORKING_IS_SWEEP = "workingIsSweep"

    /**
     * Index-aligned lists behind the deformed-frames card; `-1` = size not
     * measured. [timesMs] is each video frame's time in its clip: empty for
     * picked images, and for a draft an older app saved.
     */
    @Serializable
    data class Frames(
        val paths: List<String> = emptyList(),
        val names: List<String> = emptyList(),
        val dates: List<Long> = emptyList(),
        val widths: List<Int> = emptyList(),
        val heights: List<Int> = emptyList(),
        val timesMs: List<Long> = emptyList(),
    )

    /** What [readInputs] read back from the draft. */
    class Inputs(val reference: ByteArray?, val mask: ByteArray?, val frames: Frames)

    private val json = Json { ignoreUnknownKeys = true }

    /** The scalars of [viewModel]'s inputs, with the fingerprint of [framesJson], the list queued for the draft. */
    fun save(viewModel: AnalysisViewModel, framesJson: String): Bundle = save(viewModel.inputs.value, framesJson)

    /** The scalars of [inputs], read from the one value, with the fingerprint of [framesJson]. */
    fun save(inputs: WizardInputs, framesJson: String): Bundle = Bundle().apply {
        putInt(STEP, inputs.step.number)
        putBoolean(SETTINGS_REVIEWED, inputs.settingsReviewed)
        putBoolean(SUBSET_USER_MODIFIED, inputs.subsetUserModified)
        putInt(REF_W, inputs.refSize.width)
        putInt(REF_H, inputs.refSize.height)
        putString(REF_NAME, inputs.refName)
        putBoolean(HAS_REFERENCE, inputs.refBytes != null)
        putBoolean(HAS_MASK, inputs.roiMaskBytes != null)
        putBoolean(HAS_CUSTOM_ROI, inputs.hasCustomRoi)
        putRoi(ROI, inputs.roi)
        putInt(FRAME_COUNT, inputs.deformedFrames.size)
        putString(ORDER_MODE, inputs.defOrderMode.name)
        putString(ORDER_DIRECTION, inputs.defOrderDirection.name)
        putString(FRAME_SIZE_ERROR, inputs.frameSizeError)
        putBoolean(FROM_VIDEO, inputs.defFromVideo)
        putString(VIDEO_NAME, inputs.defVideoName)
        putBoolean(SWEEP_MODE, inputs.sweepMode)
        putIntArray(SWEEP_RANGES, inputs.sweepRanges.toIntArray())
        putDouble(SUBSET_OVERLAP, inputs.subsetOverlap)
        putBoolean(LINE_CUT_HORIZONTAL, inputs.lineCutHorizontal)
        putInt(SWEEP_FRAME_INDEX, inputs.sweepFrame.savedIndex)
        putString(WORKING_LOCAL_ID, inputs.working?.id)
        putBoolean(WORKING_IS_SWEEP, inputs.working?.kind == RunKind.SWEEP)
        putString(FRAMES_FINGERPRINT, fingerprint(framesJson))
        putString(REFERENCE_DIGEST, referenceDigest.of(inputs.refBytes))
        putString(MASK_DIGEST, maskDigest.of(inputs.roiMaskBytes))
    }

    /**
     * The scalars of a [save]d Bundle, into [viewModel]'s inputs in one edit;
     * the draft's parts follow through [readInputs].
     */
    fun restoreScalars(viewModel: AnalysisViewModel, b: Bundle) = viewModel.edit { restoreScalars(it, b) }

    /** [inputs] with the scalars of a [save]d Bundle [b]; a key [b] lacks or garbles keeps its value. */
    fun restoreScalars(inputs: WizardInputs, b: Bundle): WizardInputs = inputs.copy(
        step = WizardStep.of(b.getInt(STEP, 1)),
        settingsReviewed = b.getBoolean(SETTINGS_REVIEWED),
        subsetUserModified = b.getBoolean(SUBSET_USER_MODIFIED),
        refSize = ImageSize(b.getInt(REF_W), b.getInt(REF_H)),
        refName = b.getString(REF_NAME) ?: inputs.refName,
        hasCustomRoi = b.getBoolean(HAS_CUSTOM_ROI),
        roi = b.getRoi(ROI) ?: inputs.roi,
        defOrderMode = b.getString(ORDER_MODE)?.let { name -> FrameOrderMode.entries.find { it.name == name } }
            ?: inputs.defOrderMode,
        defOrderDirection = b.getString(ORDER_DIRECTION)
            ?.let { name -> FrameOrderDirection.entries.find { it.name == name } }
            ?: inputs.defOrderDirection,
        frameSizeError = b.getString(FRAME_SIZE_ERROR),
        defFromVideo = b.getBoolean(FROM_VIDEO),
        defVideoName = b.getString(VIDEO_NAME),
        sweepMode = b.getBoolean(SWEEP_MODE),
        sweepRanges = SweepRanges.fromIntArray(b.getIntArray(SWEEP_RANGES)) ?: inputs.sweepRanges,
        subsetOverlap = b.getDouble(SUBSET_OVERLAP, inputs.subsetOverlap),
        lineCutHorizontal = b.getBoolean(LINE_CUT_HORIZONTAL, true),
        sweepFrame = SweepFrame.fromSaved(b.getInt(SWEEP_FRAME_INDEX, -1)),
        // A Bundle from before the kind was saved cannot say whether its
        // session is a single run or a sweep, and a run of the other kind
        // must not write over it: the next run starts a new record instead.
        working = b.getString(WORKING_LOCAL_ID)
            ?.takeIf { b.containsKey(WORKING_IS_SWEEP) }
            ?.let { WorkingSession(it, if (b.getBoolean(WORKING_IS_SWEEP)) RunKind.SWEEP else RunKind.BATCH) },
    )

    fun frames(viewModel: AnalysisViewModel): Frames = wizardFramesOf(viewModel.inputs.value.deformedFrames)

    fun encodeFrames(frames: Frames): String = json.encodeToString(Frames.serializer(), frames)

    /**
     * The draft parts [b] says the wizard held, or null when any is missing:
     * the reference or mask file, one of the staged frames (the OS may evict
     * `cacheDir` under storage pressure), or the frame list itself. The list
     * must be the very one [save] fingerprinted, not merely as long: a kill
     * before its write landed leaves the previous list, which may hold the
     * same number of frames in another order. The reference and mask must be
     * the very bytes [save] digested too, for the same reason: a kill before
     * a new reference's write landed leaves the old one, which the restored
     * size, ROI and mask would be applied to. A partial restore would be a
     * wizard that looks ready but solves something else, so it is all or none.
     */
    @WorkerThread
    fun readInputs(b: Bundle, draft: WizardDraft): Inputs? {
        val wantReference = b.getBoolean(HAS_REFERENCE)
        val wantMask = b.getBoolean(HAS_MASK)
        val reference = if (wantReference) draft.readReference() else null
        val mask = if (wantMask) draft.readMask() else null
        val framesText = draft.readFrames()
        val frames = framesText?.let(::decodeFrames) ?: Frames()
        val expected = b.getString(FRAMES_FINGERPRINT)
        val sameList = expected == null || expected == fingerprint(framesText ?: encodeFrames(Frames()))
        val complete = (reference != null) == wantReference &&
            (mask != null) == wantMask &&
            draftDigestMatches(b.getString(REFERENCE_DIGEST), reference) &&
            draftDigestMatches(b.getString(MASK_DIGEST), mask) &&
            sameList &&
            frames.paths.size == b.getInt(FRAME_COUNT) &&
            frames.paths.all { File(it).isFile }
        return if (complete) Inputs(reference, mask, frames) else null
    }

    private val referenceDigest = LastDigest()
    private val maskDigest = LastDigest()

    /** Hex SHA-256 of [framesJson]'s UTF-8 bytes. */
    fun fingerprint(framesJson: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(framesJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun decodeFrames(text: String): Frames? = try {
        json.decodeFromString(Frames.serializer(), text)
    } catch (e: IllegalArgumentException) { // SerializationException included
        Timber.w(e, "Unreadable wizard draft frame list")
        null
    }
}

/** True unless [expected] is a [draftDigest] that [bytes] does not have; no digest saved checks nothing. */
private fun draftDigestMatches(expected: String?, bytes: ByteArray?): Boolean =
    expected == null || bytes == null || expected == draftDigest(bytes)

/**
 * "size:crc32" of [bytes], as `WizardState` saves it for the draft's reference
 * and mask: enough to tell one image from another, and cheap enough for the
 * main thread's state save on tens of megabytes.
 */
internal fun draftDigest(bytes: ByteArray): String =
    "${bytes.size}:" + java.lang.Long.toHexString(CRC32().apply { update(bytes) }.value)

/**
 * [draftDigest] of the last array it was handed, kept: the reference is the
 * same array save after save. Held weakly, so a closed wizard's reference is
 * not kept alive here.
 */
private class LastDigest {
    private var bytes = WeakReference<ByteArray>(null)
    private var value: String? = null

    @Synchronized
    fun of(current: ByteArray?): String? {
        if (current == null) return null
        if (current !== bytes.get()) {
            value = draftDigest(current)
            bytes = WeakReference(current)
        }
        return value
    }
}
