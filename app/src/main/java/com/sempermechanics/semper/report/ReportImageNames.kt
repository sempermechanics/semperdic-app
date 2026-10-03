package com.sempermechanics.semper.report

/**
 * The names a report prints for its session and images, shared by the
 * on-device report (viewer) and the cloud-backup bundle so the two PDFs of
 * one frame say the same thing.
 */
object ReportImageNames {

    /** The reference as the user named it. */
    fun reference(refName: String): String = refName.ifBlank { "reference.png" }

    /**
     * Frame [index]'s name from the session's frame names, or null when it has
     * none. [index] is the planned frame, never a position in the `.dat`
     * listing: past a frame the batch skipped the two differ
     * ([com.sempermechanics.semper.data.session.SessionPaths.plannedFrameIndices]).
     */
    fun frameName(frameNames: List<String>, index: Int): String? =
        frameNames.getOrNull(index)?.takeIf { it.isNotBlank() }

    /** Frame [index]'s name from the session's frame names, else "Frame_N". */
    fun deformed(frameNames: List<String>, index: Int): String =
        frameName(frameNames, index) ?: "Frame_${index + 1}"

    /** The specimen: the reference's name without its extension. */
    fun specimen(refName: String): String = refName.substringBeforeLast(".").ifBlank { "Batch Analysis" }
}
