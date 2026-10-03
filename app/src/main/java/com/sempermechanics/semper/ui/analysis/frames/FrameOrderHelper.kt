package com.sempermechanics.semper.ui.analysis.frames

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** How multi-picked deformed frames are ordered for analysis. */
enum class FrameOrderMode {
    /** System picker / import order. */
    PICKER,

    /** Alphabetical by display name. */
    NAME,

    /** Capture / creation / modified time. */
    DATE,

    /** User drag order. */
    MANUAL,
}

/** Sort direction for [FrameOrderMode.NAME] and [FrameOrderMode.DATE]. */
enum class FrameOrderDirection {
    ASCENDING,
    DESCENDING,
}

/**
 * Best-effort creation times for imported frame files, and reordering of the
 * deformed frames for the analysis wizard.
 */
object FrameOrderHelper {

    /**
     * [frames] in [mode]'s order. For MANUAL, [manualOrder] is the desired
     * permutation of indices into [frames], used only when it is as long;
     * otherwise the order stays. [direction] applies to NAME and DATE only.
     */
    fun reorder(
        frames: List<DeformedFrame>,
        mode: FrameOrderMode,
        direction: FrameOrderDirection = FrameOrderDirection.ASCENDING,
        manualOrder: List<Int>? = null,
    ): List<DeformedFrame> {
        val byName = compareBy<DeformedFrame> { it.name.lowercase(Locale.US) }
        // Stable sorts: frames that compare equal keep their current order.
        return when (mode) {
            FrameOrderMode.PICKER -> frames
            FrameOrderMode.MANUAL -> manualOrder?.takeIf { it.size == frames.size }?.map { frames[it] } ?: frames
            FrameOrderMode.NAME -> frames.sortedWith(byName).facing(direction)
            FrameOrderMode.DATE ->
                frames.sortedWith(compareBy<DeformedFrame> { it.date }.then(byName)).facing(direction)
        }
    }

    private fun <T> List<T>.facing(direction: FrameOrderDirection): List<T> =
        if (direction == FrameOrderDirection.ASCENDING) this else asReversed()

    /**
     * Renames each frame's temp file to `%04d_…` in [frames]' order, so a
     * lexicographic path sort matches the analysis order. Returns the frames
     * at their new paths; names, dates and sizes stay with them.
     */
    fun reprefixTempFiles(frames: List<DeformedFrame>): List<DeformedFrame> {
        val parent = frames.firstOrNull()?.let { File(it.path).parentFile } ?: return frames
        // Through a "_ord_" name first, so no rename lands on a file that a
        // later frame has yet to move out of the way.
        val staged = frames.mapIndexed { index, frame ->
            val old = File(frame.path)
            val base = frame.name.ifEmpty { old.name }
                .replace(Regex("^\\d{4}_"), "")
                .replace(Regex("[^a-zA-Z0-9.-]"), "_")
            moveTo(old, File(parent, String.format(Locale.US, "_ord_%04d_%s", index, base)))
        }
        return staged.mapIndexed { index, file ->
            val base = file.name.removePrefix(String.format(Locale.US, "_ord_%04d_", index))
            val final = moveTo(file, File(parent, String.format(Locale.US, "%04d_%s", index, base)))
            frames[index].copy(path = final.absolutePath)
        }
    }

    /** Renames [from] to [to], replacing a file already there. Returns [to], renamed or not. */
    private fun moveTo(from: File, to: File): File {
        if (from.absolutePath != to.absolutePath) {
            if (to.exists()) to.delete()
            from.renameTo(to)
        }
        return to
    }

    /**
     * Date for an already-imported frame file. Used when sort-by-date is chosen
     * after a fast import that skipped URI EXIF probes.
     */
    fun resolveDateMs(file: File): Long = runCatching {
        val exif = ExifInterface(file.absolutePath)
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return@runCatching null
        val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        fmt.parse(raw)?.time
    }.getOrNull()?.takeIf { it > 0L }
        ?: file.lastModified().takeIf { it > 0L }
        ?: DeformedFrame.UNKNOWN_DATE
}
