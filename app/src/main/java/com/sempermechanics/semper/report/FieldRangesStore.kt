package com.sempermechanics.semper.report

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sidecar persistence for [com.sempermechanics.semper.ui.viewer.summary.SummaryAnimation.globalRanges]'s
 * per-frame, per-field sigma-clamped (p02, p98) — the same values
 * [VisualizationEngine.valueRanges] already computes, just computed once at
 * analysis time (when the frame's data is already decoded in memory) instead
 * of redundantly at every viewer-open and backup.
 *
 * Purely a cache: a missing, truncated, or field-list-mismatched file must
 * fall back to decoding the frames directly
 * ([com.sempermechanics.semper.ui.viewer.summary.SummaryAnimation.globalRanges] does this),
 * so an old session written before this existed, or one restored onto an
 * older/newer app build, still works — it just re-pays the decode cost this
 * cache exists to avoid.
 *
 * NaN in either slot marks a field with no accepted points in that frame
 * (mirrors the `null` [VisualizationEngine.valueRanges] returns for the same
 * case) — NaN is never a valid p02/p98 otherwise, so it is an unambiguous
 * sentinel that survives the round trip through a plain float file.
 */
internal object FieldRangesStore {

    /** Sidecar filename, alongside a batch's `frame_NNNN.dat` files. */
    const val FILE_NAME = "field_ranges.bin"

    private const val INT_BYTES = 4L

    /** frameCount + fieldCount, each one Int. */
    private const val HEADER_BYTES = 2L * INT_BYTES

    /** p02 and p98 per (frame, field). */
    private const val FLOATS_PER_FIELD = 2L

    /**
     * frameCount + fieldCount headers, the field index list itself, then 2
     * floats per (frame, field). The field indices are stored (not just their
     * count) and checked byte-for-byte on read — a silent reorder between
     * [write] and [read] callers would otherwise misattribute one field's
     * range to another without either side raising an error.
     */
    private fun expectedBytes(frameCount: Int, fieldCount: Int): Long {
        val frames = frameCount.toLong()
        val fields = fieldCount.toLong()
        return HEADER_BYTES + INT_BYTES * fields + INT_BYTES * FLOATS_PER_FIELD * frames * fields
    }

    fun write(file: File, fieldIndices: IntArray, perFrameRanges: List<Map<Int, Pair<Float, Float>?>>) {
        val frameCount = perFrameRanges.size
        val fieldCount = fieldIndices.size
        val buffer = ByteBuffer.allocate(expectedBytes(frameCount, fieldCount).toInt())
            .order(ByteOrder.nativeOrder())
        buffer.putInt(frameCount)
        buffer.putInt(fieldCount)
        for (valIndex in fieldIndices) buffer.putInt(valIndex)
        for (frameRanges in perFrameRanges) {
            for (valIndex in fieldIndices) {
                val range = frameRanges[valIndex]
                buffer.putFloat(range?.first ?: Float.NaN)
                buffer.putFloat(range?.second ?: Float.NaN)
            }
        }
        file.writeBytes(buffer.array())
    }

    /**
     * Returns null (never throws) for anything that isn't a clean, matching
     * read — missing file, truncated/corrupt content, or a stored field list
     * that isn't exactly [fieldIndices] (same values, same order). The
     * caller's fallback path handles all of these identically: recompute
     * from the frames.
     */
    @Suppress("ReturnCount") // one guard clause per distinct "not a clean read" case
    fun read(file: File, fieldIndices: IntArray): List<Map<Int, Pair<Float, Float>?>>? {
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val frameCount = buffer.getInt()
        val fieldCount = buffer.getInt()
        if (frameCount < 0 || fieldCount != fieldIndices.size) return null
        if (bytes.size.toLong() != expectedBytes(frameCount, fieldCount)) return null
        for (valIndex in fieldIndices) {
            if (buffer.getInt() != valIndex) return null
        }

        return List(frameCount) {
            val frameRanges = LinkedHashMap<Int, Pair<Float, Float>?>(fieldCount)
            for (valIndex in fieldIndices) {
                val p02 = buffer.getFloat()
                val p98 = buffer.getFloat()
                frameRanges[valIndex] = if (p02.isNaN() || p98.isNaN()) null else p02 to p98
            }
            frameRanges
        }
    }
}
