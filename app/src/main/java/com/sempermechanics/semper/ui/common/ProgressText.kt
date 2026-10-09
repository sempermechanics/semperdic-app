package com.sempermechanics.semper.ui.common

import android.content.res.Resources
import com.sempermechanics.semper.R
import kotlin.math.floor

/**
 * How every long job words its progress, so the export dialog, the transfer
 * banner and the transfer notifications read alike:
 *
 * - the percent to one decimal, rounded down so a job never reads "100.0%"
 *   before it ends: "34.6%";
 * - bytes moved, both counts in the total's unit: "4.2 of 12.0 MB";
 * - the rate: "1.1 MB/s";
 * - pieces joined with a middle dot: "34.6% · About 35 s left".
 *
 * Byte units are binary (1 KB = 1024 bytes), as [ByteSize] counts them.
 */
object ProgressText {

    private const val PERCENT_MAX = 100.0
    private const val TENTHS = 10.0
    private const val BYTES_PER_KB = 1024.0
    private const val FLOAT_SLACK = 1e-6

    /** "34.6%": [percent] (0–100) rounded down to a tenth. */
    fun percent(res: Resources, percent: Double): String =
        res.getString(R.string.progress_percent_fmt, floorTenth(percent))

    /**
     * [percent] clamped to 0–100 and rounded down to a tenth. A hair of slack
     * keeps float noise (an exact 35% computed as 34.99999…) from reading a
     * tenth low.
     */
    fun floorTenth(percent: Double): Double {
        if (percent.isNaN()) return 0.0
        return floor(percent.coerceIn(0.0, PERCENT_MAX) * TENTHS + FLOAT_SLACK) / TENTHS
    }

    /** "4.2 of 12.0 MB": [done] of [total] bytes, both in the total's unit. */
    fun bytes(res: Resources, done: Long, total: Long): String {
        val unit = ByteUnit.of(total)
        return res.getString(
            R.string.progress_bytes_fmt,
            unit.amount(done.coerceIn(0L, total.coerceAtLeast(0L))),
            unit.amount(total.coerceAtLeast(0L)),
            unit.label,
        )
    }

    /** "1.1 MB/s". */
    fun rate(res: Resources, bytesPerSecond: Double): String {
        val perSecond = bytesPerSecond.coerceAtLeast(0.0)
        val unit = ByteUnit.of(perSecond.toLong())
        return res.getString(R.string.progress_rate_fmt, perSecond / unit.bytes, unit.label)
    }

    /** "4.2 of 12.0 MB · 1.1 MB/s"; just the bytes while the rate is unknown. */
    fun transfer(res: Resources, done: Long, total: Long, bytesPerSecond: Double?): String {
        val bytes = bytes(res, done, total)
        return if (bytesPerSecond == null) bytes else joined(res, bytes, rate(res, bytesPerSecond))
    }

    /** "34.6% · About 35 s left"; just the percent while the time left is unknown. */
    fun percentAndEta(res: Resources, percent: Double, eta: EtaEstimator.Eta): String {
        val pct = percent(res, percent)
        val left = EtaEstimator.label(res, eta) ?: return pct
        return joined(res, pct, left)
    }

    /** "[first] · [second]". */
    fun joined(res: Resources, first: String, second: String): String =
        res.getString(R.string.progress_joined_fmt, first, second)

    /** The unit a byte count is shown in: the largest that keeps it at 1 or more, KB at least. */
    private enum class ByteUnit(val bytes: Double, val label: String) {
        KB(BYTES_PER_KB, "KB"),
        MB(BYTES_PER_KB * BYTES_PER_KB, "MB"),
        GB(BYTES_PER_KB * BYTES_PER_KB * BYTES_PER_KB, "GB"),
        ;

        fun amount(count: Long): Double = count / bytes

        companion object {
            fun of(count: Long): ByteUnit = entries.lastOrNull { count >= it.bytes } ?: KB
        }
    }
}
