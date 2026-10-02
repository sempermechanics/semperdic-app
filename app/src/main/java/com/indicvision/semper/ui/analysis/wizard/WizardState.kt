package com.indicvision.semper.ui.analysis.wizard

import android.os.Bundle
import androidx.annotation.WorkerThread
import com.indicvision.semper.data.prefs.WizardDraft
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.getRoi
import com.indicvision.semper.field.putRoi
import com.indicvision.semper.ui.analysis.frames.FrameOrderDirection
import com.indicvision.semper.ui.analysis.frames.FrameOrderMode
import com.indicvision.semper.ui.analysis.sweep.SweepRanges
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

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
    private const val ORDER_MODE = "orderMode"
    private const val ORDER_DIRECTION = "orderDirection"
    private const val FRAME_SIZE_ERROR = "frameSizeError"
    private const val FROM_VIDEO = "fromVideo"
    private const val SWEEP_MODE = "sweepMode"
    private const val SWEEP_RANGES = "sweepRanges"
    private const val SUBSET_OVERLAP = "subsetOverlap"
    private const val LINE_CUT_HORIZONTAL = "lineCutHorizontal"
    private const val VSG_FRAME_INDEX = "vsgFrameIndex"
    private const val WORKING_LOCAL_ID = "workingLocalId"

    /** Index-aligned lists behind the deformed-frames card; `-1` = size not measured. */
    @Serializable
    data class Frames(
        val paths: List<String> = emptyList(),
        val names: List<String> = emptyList(),
        val dates: List<Long> = emptyList(),
        val widths: List<Int> = emptyList(),
        val heights: List<Int> = emptyList(),
    )

    /** What [readInputs] read back from the draft. */
    class Inputs(val reference: ByteArray?, val mask: ByteArray?, val frames: Frames)

    private val json = Json { ignoreUnknownKeys = true }

    /** The scalars of [vm], with the fingerprint of [framesJson], the list queued for the draft. */
    fun save(vm: AnalysisViewModel, framesJson: String): Bundle = Bundle().apply {
        putInt(STEP, vm.wizardStep)
        putBoolean(SETTINGS_REVIEWED, vm.settingsReviewed)
        putBoolean(SUBSET_USER_MODIFIED, vm.subsetUserModified)
        putInt(REF_W, vm.refSize.width)
        putInt(REF_H, vm.refSize.height)
        putString(REF_NAME, vm.refName)
        putBoolean(HAS_REFERENCE, vm.refBytes != null)
        putBoolean(HAS_MASK, vm.roiMaskBytes != null)
        putBoolean(HAS_CUSTOM_ROI, vm.hasCustomRoi)
        putRoi(ROI, vm.roi)
        putInt(FRAME_COUNT, vm.deformedFrames.size)
        putString(ORDER_MODE, vm.defOrderMode.name)
        putString(ORDER_DIRECTION, vm.defOrderDirection.name)
        putString(FRAME_SIZE_ERROR, vm.frameSizeError)
        putBoolean(FROM_VIDEO, vm.defFromVideo)
        putBoolean(SWEEP_MODE, vm.sweepMode)
        putIntArray(SWEEP_RANGES, vm.sweepRanges.toIntArray())
        putDouble(SUBSET_OVERLAP, vm.subsetOverlap)
        putBoolean(LINE_CUT_HORIZONTAL, vm.lineCutHorizontal)
        putInt(VSG_FRAME_INDEX, vm.vsgFrameIndex)
        putString(WORKING_LOCAL_ID, vm.workingLocalId)
        putString(FRAMES_FINGERPRINT, fingerprint(framesJson))
    }

    /** The scalars of a [save]d Bundle; the draft's parts follow through [readInputs]. */
    fun restoreScalars(vm: AnalysisViewModel, b: Bundle) {
        vm.wizardStep = b.getInt(STEP, 1)
        vm.settingsReviewed = b.getBoolean(SETTINGS_REVIEWED)
        vm.subsetUserModified = b.getBoolean(SUBSET_USER_MODIFIED)
        vm.refSize = ImageSize(b.getInt(REF_W), b.getInt(REF_H))
        b.getString(REF_NAME)?.let { vm.refName = it }
        vm.hasCustomRoi = b.getBoolean(HAS_CUSTOM_ROI)
        b.getRoi(ROI)?.let { vm.roi = it }
        b.getString(ORDER_MODE)?.let { name -> FrameOrderMode.entries.find { it.name == name } }
            ?.let { vm.defOrderMode = it }
        b.getString(ORDER_DIRECTION)?.let { name -> FrameOrderDirection.entries.find { it.name == name } }
            ?.let { vm.defOrderDirection = it }
        vm.frameSizeError = b.getString(FRAME_SIZE_ERROR)
        vm.defFromVideo = b.getBoolean(FROM_VIDEO)
        vm.sweepMode = b.getBoolean(SWEEP_MODE)
        SweepRanges.fromIntArray(b.getIntArray(SWEEP_RANGES))?.let { vm.sweepRanges = it }
        vm.subsetOverlap = b.getDouble(SUBSET_OVERLAP, vm.subsetOverlap)
        vm.lineCutHorizontal = b.getBoolean(LINE_CUT_HORIZONTAL, true)
        vm.vsgFrameIndex = b.getInt(VSG_FRAME_INDEX, -1)
        vm.workingLocalId = b.getString(WORKING_LOCAL_ID)
    }

    fun frames(vm: AnalysisViewModel): Frames = wizardFramesOf(vm.deformedFrames)

    fun encodeFrames(frames: Frames): String = json.encodeToString(Frames.serializer(), frames)

    /**
     * The draft parts [b] says the wizard held, or null when any is missing:
     * the reference or mask file, one of the staged frames (the OS may evict
     * `cacheDir` under storage pressure), or the frame list itself. The list
     * must be the very one [save] fingerprinted, not merely as long: a kill
     * before its write landed leaves the previous list, which may hold the
     * same number of frames in another order. A partial restore would be a
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
            sameList &&
            frames.paths.size == b.getInt(FRAME_COUNT) &&
            frames.paths.all { File(it).isFile }
        return if (complete) Inputs(reference, mask, frames) else null
    }

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
