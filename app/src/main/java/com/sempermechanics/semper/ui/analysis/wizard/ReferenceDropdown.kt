package com.sempermechanics.semper.ui.analysis.wizard

import android.content.Context
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.sempermechanics.semper.R

/**
 * The settings page's reference dropdown: seconds of the clip for frames
 * sampled from a video, frame counts for picked images ([ReferenceIntervals]).
 * [frameTimesMs] reads the frames' clip times, null for picked images.
 */
class ReferenceDropdown(
    private val context: Context,
    private val dropdown: MaterialAutoCompleteTextView,
    private val frameTimesMs: () -> List<Long>?,
) {
    /**
     * The mean time between the frames in ms when they were sampled from a
     * video, which makes the choices seconds; null for picked images.
     */
    private var frameSpacingMs: Double? = null

    /** The choices for these frames: seconds of video, or frame counts. */
    private val choices: List<Int>
        get() = if (frameSpacingMs != null) ReferenceIntervals.VIDEO_SECONDS else ReferenceIntervals.IMAGE_FRAMES

    /** The dropdown's labels, index-aligned with [choices]. */
    private val labels: List<String>
        get() = choices.map { n ->
            when {
                n == 0 -> context.getString(R.string.reference_fixed)
                frameSpacingMs != null -> context.getString(R.string.reference_every_n_seconds, n)
                n == 1 -> context.getString(R.string.reference_every_frame)
                else -> context.resources.getQuantityString(R.plurals.reference_every_n_frames, n, n)
            }
        }

    /** The choice the dropdown shows, in [choices]' unit; 0, fixed, when it shows none. */
    private val choice: Int get() = choices.getOrElse(labels.indexOf(dropdown.text.toString())) { 0 }

    /** Offers the choices for the frames held now, fixed when none is shown; [onPicked] on a pick. */
    fun bind(onPicked: () -> Unit) {
        frameSpacingMs = ReferenceIntervals.frameSpacingMs(frameTimesMs())
        dropdown.setSimpleItems(labels.toTypedArray())
        if (dropdown.text.isNullOrEmpty()) interval = 0
        dropdown.setOnItemClickListener { _, _, _, _ -> onPicked() }
    }

    /**
     * New frames: a video offers seconds of the clip, picked images frame
     * counts. A choice the new frames still offer stays as shown (every 10 s
     * of one clip is every 10 s of the next, and a restored draft's choice
     * lands back on its frames); otherwise the interval converts and snaps.
     */
    fun onFramesChanged() {
        val shown = dropdown.text.toString()
        val before = interval
        frameSpacingMs = ReferenceIntervals.frameSpacingMs(frameTimesMs())
        dropdown.setSimpleItems(labels.toTypedArray())
        if (shown in labels) dropdown.setText(shown, false) else interval = before
    }

    /**
     * How often the reference moves, in frames
     * ([com.sempermechanics.semper.ui.analysis.run.RunSpec.refUpdateInterval]);
     * 0 is the fixed reference. A video's seconds convert at its frame
     * spacing. Setting it snaps to a choice ([ReferenceIntervals]).
     */
    var interval: Int
        get() {
            val spacing = frameSpacingMs ?: return choice
            return ReferenceIntervals.framesFor(choice, spacing)
        }
        set(frames) {
            val spacing = frameSpacingMs
            val snapped = if (spacing != null) {
                ReferenceIntervals.snapSeconds(frames, spacing)
            } else {
                ReferenceIntervals.snapFrames(frames)
            }
            dropdown.setText(labels[choices.indexOf(snapped)], false)
        }
}
