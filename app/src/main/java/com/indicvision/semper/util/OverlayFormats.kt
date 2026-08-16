package com.indicvision.semper.util

import java.util.Locale
import kotlin.math.abs

/**
 * Compact labels for the compute overlay: counts like 1K / 1.5M and
 * elapsed clocks that grow from `m:ss` into `h:mm:ss`.
 */
object OverlayFormats {

    fun compactCount(value: Int): String {
        val n = abs(value)
        val sign = if (value < 0) "-" else ""
        return sign + when {
            n >= 1_000_000_000 -> compactScaled(n / 1_000_000_000.0, "B")
            n >= 1_000_000 -> compactScaled(n / 1_000_000.0, "M")
            n >= 1_000 -> compactScaled(n / 1_000.0, "K")
            else -> n.toString()
        }
    }

    fun elapsed(elapsedMs: Long): String {
        val totalSec = (elapsedMs.coerceAtLeast(0L) / 1000L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun compactScaled(scaled: Double, suffix: String): String {
        val rounded = kotlin.math.round(scaled * 10.0) / 10.0
        return if (abs(rounded - kotlin.math.round(rounded)) < 0.05) {
            "${rounded.toInt()}$suffix"
        } else {
            String.format(Locale.US, "%.1f%s", rounded, suffix)
        }
    }
}
