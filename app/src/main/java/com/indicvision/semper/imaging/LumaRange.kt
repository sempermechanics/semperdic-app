package com.indicvision.semper.imaging

/**
 * Limited-range ("video") luma to full range (TD-134).
 *
 * A video decoder hands back the Y plane as stored, and an ordinary video stores
 * black at 16 and white at 235 (ITU-R BT.601 / BT.709). MJPEG and uncompressed
 * AVI frames, like every photo, span 0–255. Without this, the same scene reached
 * the engine with about 14 % less contrast from an MP4 than from an MJPEG, which
 * moves every contrast-based quality reading. Correlation itself is unaffected.
 */
internal object LumaRange {

    /** Video black in limited range. */
    const val BLACK = 16

    /** Video white in limited range. */
    const val WHITE = 235

    private const val FULL = 255
    private const val SPAN = WHITE - BLACK

    /** Full-range value of each stored byte, rounded; values outside 16–235 clip. */
    private val table = ByteArray(FULL + 1) { y ->
        Math.floorDiv((y - BLACK) * FULL + SPAN / 2, SPAN).coerceIn(0, FULL).toByte()
    }

    /** The full-range value of one limited-range [y] (0–255). */
    fun expand(y: Int): Int = table[y and FULL].toInt() and FULL

    /** Stretches every byte of [bytes] from 16–235 to 0–255, in place. */
    fun expandInPlace(bytes: ByteArray) {
        for (i in bytes.indices) bytes[i] = table[bytes[i].toInt() and FULL]
    }
}
