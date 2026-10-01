package com.indicvision.semper.ui.common

import java.util.Locale

/**
 * A byte count as Settings shows it: binary units (1 KB = 1024 bytes), always
 * in `Locale.US` digits — "1.5 GB", "120 MB", "64 KB".
 *
 * Ported from `SettingsActivity.humanSize`, the app's one hand-written byte
 * formatter. Home's backups card uses the platform's
 * `Formatter.formatShortFileSize` instead, which counts in powers of 1000,
 * follows the device locale and rounds differently, so the two disagree on
 * the same count; this does not replace that one.
 */
object ByteSize {

    private const val BYTES_PER_KB = 1024.0
    private const val BYTES_PER_MB = 1_048_576L
    private const val BYTES_PER_GB = 1_073_741_824L

    fun format(bytes: Long): String = when {
        bytes >= BYTES_PER_GB -> String.format(Locale.US, "%.1f GB", bytes / BYTES_PER_GB.toDouble())
        bytes >= BYTES_PER_MB -> String.format(Locale.US, "%.0f MB", bytes / BYTES_PER_MB.toDouble())
        else -> String.format(Locale.US, "%.0f KB", bytes / BYTES_PER_KB)
    }
}
