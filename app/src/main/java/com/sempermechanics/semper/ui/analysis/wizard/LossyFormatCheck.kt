package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.ui.analysis.run.baseName

/**
 * Names the image formats in a frame set that are not lossless.
 *
 * Correlation reads intensity per pixel, so any compression that alters
 * intensities — JPEG's blocks and chroma subsampling, lossy WebP, HEIC —
 * shifts the very quantity being measured. The warning used to test only for
 * `.jpg`/`.jpeg` and to say "JPEG detected" regardless, which was wrong in
 * both directions: a lossy WebP passed silently, and a run whose frames are
 * PNG but whose reference is a camera-app JPEG still read as though the PNGs
 * were the problem.
 *
 * Lossless is an allowlist rather than a lossy blocklist. An unrecognised
 * extension is far more likely to be some lossy container than a format worth
 * trusting silently, and the cost of asking is one dismissible chip.
 */
internal object LossyFormatCheck {

    private val LOSSLESS = setOf(
        "png", "tif", "tiff", "bmp", "pgm", "ppm", "pnm", "dat",
        // Sensor RAW: imported as lossless intensity for correlation (see RawRgba).
        "dng", "raw",
    )

    /**
     * Distinct upper-case labels ("JPEG", "WEBP") for every non-lossless file
     * in [names], in first-seen order. Empty when the whole set is lossless.
     * Files with no extension are skipped: there is nothing to name, and
     * guessing would put a blank in the message.
     */
    fun lossyLabels(names: Iterable<String>): List<String> = names
        .map { extensionOf(it) }
        .filter { it.isNotEmpty() && it !in LOSSLESS }
        .map { label(it) }
        .distinct()

    /** Lower-case extension without the dot, or "" when there is none. */
    fun extensionOf(name: String): String {
        val base = name.baseName()
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.lastIndex) return ""
        return base.substring(dot + 1).lowercase()
    }

    /** ".jpg" and ".jpeg" are one format to the reader, so show one name. */
    private fun label(extension: String): String = when (extension) {
        "jpg", "jpe", "jfif" -> "JPEG"
        "heif" -> "HEIC"
        else -> extension.uppercase()
    }
}
