package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import java.util.Locale

/** One frame's accepted-point ZNSSD: the mean, and how many points it is over. */
internal data class ZnssdFrame(val mean: Float, val points: Int) {
    companion object {
        /** Mean ZNSSD over [data]'s accepted points (0 when none were accepted). */
        fun of(data: FloatArray): ZnssdFrame {
            var total = 0f
            var count = 0
            for (i in data.indices step DicResult.STRIDE) {
                val corr = data[i + DicResult.IDX_ZNSSD]
                if (DicResult.isAcceptedPoint(corr)) {
                    total += corr
                    count++
                }
            }
            return ZnssdFrame(if (count > 0) total / count else 0f, count)
        }
    }
}

/**
 * What the telemetry page's quality rows say, and over what.
 *
 * The engine statistics a session keeps (pipeline counts, convergence, ICGN
 * iterations, timings) are recorded on the run's first frame only, so an
 * all-frames report can aggregate the ZNSSD it decodes per frame but must say
 * whose the rest are, rather than present one frame's numbers as the batch's.
 */
internal data class TelemetrySummary(
    val avgZnssdLabel: String,
    val avgZnssd: Float,
    val convergenceLabel: String,
    /** A note above the tables when their numbers are not all the document's; null when they are. */
    val scopeNote: String?,
) {
    /**
     * The telemetry page's quality table. Formatted in [Locale.US], like every
     * other number in the report: the default locale printed "12,5" on a German
     * phone beside "0.00123" from [ReportBuilder.formatMetric].
     */
    fun qualityRows(stats: EngineStats): List<List<String>> = listOf(
        listOf(avgZnssdLabel, "%.5f".format(Locale.US, avgZnssd)),
        listOf(convergenceLabel, "%.2f %%".format(Locale.US, stats.convergencePercent)),
        listOf("Average ICGN Iterations", "%.2f".format(Locale.US, stats.avgIcgnIterations)),
    )

    companion object {
        /** The telemetry page's wall-time table, in [Locale.US] (see [qualityRows]). */
        fun timingRows(stats: EngineStats): List<List<String>> = listOf(
            listOf("AKAZE + RANSAC Phase", "%.1f ms".format(Locale.US, stats.akazeRansacMs)),
            listOf("Hessian Pre-Pass", "%.1f ms".format(Locale.US, stats.hessianPrepassMs)),
            listOf("Delaunay Mesh Phase", "%.1f ms".format(Locale.US, stats.delaunayMs)),
            listOf("Strain Calculation Phase", "%.1f ms".format(Locale.US, stats.strainMs)),
            listOf("TOTAL WALL TIME", "%.1f ms".format(Locale.US, stats.wallTimeMs)),
            listOf("Average Throughput", "%.2f pts/ms".format(Locale.US, stats.avgThroughputPtsPerMs)),
        )

        /** A single-frame report: every number is that frame's analysis. */
        fun single(data: ReportData) = TelemetrySummary(
            avgZnssdLabel = "Global Average ZNSSD (Correlation)",
            avgZnssd = data.globalAvgZnssd,
            convergenceLabel = "Overall Convergence Rate",
            scopeNote = null,
        )

        /**
         * An all-frames report over the readable [frames]: the ZNSSD is pooled
         * over every accepted point of every frame, so a frame with more points
         * weighs more, exactly as if the batch were one field.
         */
        fun batch(frames: List<ZnssdFrame>): TelemetrySummary {
            val points = frames.sumOf { it.points.toLong() }
            val pooled = if (points > 0) {
                (frames.sumOf { it.mean.toDouble() * it.points } / points).toFloat()
            } else {
                0f
            }
            val n = frames.size
            return TelemetrySummary(
                avgZnssdLabel = if (n == 1) {
                    "Average ZNSSD (Correlation), 1 frame"
                } else {
                    "Average ZNSSD (Correlation), all $n frames"
                },
                avgZnssd = pooled,
                convergenceLabel = "Convergence Rate (first frame)",
                scopeNote = "Average ZNSSD pools every accepted point of the $n " +
                    (if (n == 1) "frame" else "frames") +
                    " in this report. The solver counts, convergence, ICGN iterations, " +
                    "Simplex rescues and timings below were recorded on the run's first frame.",
            )
        }
    }
}
