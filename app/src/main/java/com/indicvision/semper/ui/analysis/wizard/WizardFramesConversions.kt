package com.indicvision.semper.ui.analysis.wizard

import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.ui.analysis.frames.DeformedFrame

/** `WizardState.Frames`' marker for a frame whose size was not measured. */
private const val UNMEASURED = -1

/**
 * The draft's frame list as [DeformedFrame]s. A size counts only when both
 * sides are positive, as `WizardState.applyFrames` keeps it; anything else
 * (the `-1` marker, a missing entry) is a null size.
 */
internal fun WizardState.Frames.toDeformedFrames(): List<DeformedFrame> = paths.mapIndexed { i, path ->
    val w = widths.getOrElse(i) { UNMEASURED }
    val h = heights.getOrElse(i) { UNMEASURED }
    DeformedFrame(
        path = path,
        name = names.getOrElse(i) { "" },
        date = dates.getOrElse(i) { DeformedFrame.UNKNOWN_DATE },
        size = ImageSize(w, h).takeIf { it.isKnown },
    )
}

/** [frames] in the draft's layout, `-1` for an unmeasured size, as `WizardState.frames` writes it. */
internal fun wizardFramesOf(frames: List<DeformedFrame>): WizardState.Frames = WizardState.Frames(
    paths = frames.map { it.path },
    names = frames.map { it.name },
    dates = frames.map { it.date },
    widths = frames.map { it.size?.width ?: UNMEASURED },
    heights = frames.map { it.size?.height ?: UNMEASURED },
)
