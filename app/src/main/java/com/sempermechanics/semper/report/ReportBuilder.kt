// Report assembly: the fusion pass (buildReport) stays one function, whole,
// so the structural rules are suppressed for this file, as are its literal
// render and composite sizes.

@file:Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "MagicNumber", "NestedBlockDepth")

package com.sempermechanics.semper.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.data.DicUploadWorker
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.ValueRange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Headless report assembly shared by the result viewer and [DicUploadWorker].
 * No Activity or UI dependencies.
 */
object ReportBuilder {

    private val FIELD_NAMES = listOf(
        "U Displacement",
        "V Displacement",
        "Exx Strain",
        "Eyy Strain",
        "Exy Shear",
        "ZNSSD (Correlation Quality)",
    )
    private val FIELD_KEYS = listOf("U", "V", "Exx", "Eyy", "Exy", "ZNSSD")

    data class FieldExtrema(val maxIdx: Int, val minIdx: Int) {
        /**
         * The field's value at the max / min marker, in display units, or null
         * when nothing was marked. This is what "Max" / "Min" report: the
         * points the markers sit on. The colour scale's ends are percentiles,
         * widened on a flat field, and need not be any point's value.
         */
        fun maxValue(data: FloatArray, dataIndex: Int): Float? = valueAt(data, maxIdx, dataIndex)
        fun minValue(data: FloatArray, dataIndex: Int): Float? = valueAt(data, minIdx, dataIndex)

        private fun valueAt(data: FloatArray, pointIdx: Int, dataIndex: Int): Float? =
            if (pointIdx < 0) null else data[pointIdx + dataIndex] * DicResult.strainMultiplier(dataIndex)
    }

    data class ReportBuildParams(
        val data: FloatArray,
        val baseImg: Bitmap,
        val defImgForCover: Bitmap,
        val imgW: Int,
        val imgH: Int,
        val step: Int,
        val sessionId: String,
        val specimenName: String,
        val analysisDate: String,
        val subsetSize: Int,
        val strainWindow: Int,
        val strainMethod: String,
        val roiData: RoiData,
        val engineStats: EngineStats,
        val referenceImageName: String,
        val deformedImageName: String,
        /** At 940e57d cloud worker PDFs drew MAX only; viewer PDFs drew MAX+MIN. */
        val drawMinMarker: Boolean = true,
    )

    fun appBuildLabel(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        return "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • $abi"
    }

    fun formatMetric(value: Float): String {
        val absVal = abs(value)
        return if (absVal > 0f && (absVal < 0.001f || absVal >= 10000f)) {
            String.format(Locale.US, "%.2e", value)
        } else {
            String.format(Locale.US, "%.5f", value)
        }
    }

    fun Bitmap.compressForPdf(maxWidth: Int = 600): Bitmap {
        // Only ever downscale — a ratio > 1 applied to the height alone would
        // vertically stretch images narrower than maxWidth.
        val ratio = if (width > maxWidth) maxWidth.toFloat() / width else 1f
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        val scaled = this.scale(newWidth, newHeight)
        try {
            val strippedBmp = createBitmap(newWidth, newHeight, Bitmap.Config.RGB_565)
            Canvas(strippedBmp).drawBitmap(scaled, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            return strippedBmp
        } finally {
            if (scaled != this) scaled.recycle()
        }
    }

    /** Percentile-clamped global max/min indices for one field column. */
    fun computeFieldExtrema(
        data: FloatArray,
        dataIndex: Int,
        absoluteStrainValues: Boolean = true,
    ): FieldExtrema = ReportFieldExtrema.computeFieldExtrema(data, dataIndex, absoluteStrainValues)

    /** [computeFieldExtrema] collecting into the caller's [scratch]; see [ReportFieldExtrema]. */
    fun computeFieldExtrema(
        data: FloatArray,
        dataIndex: Int,
        absoluteStrainValues: Boolean,
        scratch: FloatArray,
    ): FieldExtrema = ReportFieldExtrema.computeFieldExtrema(data, dataIndex, absoluteStrainValues, scratch)

    fun buildReport(params: ReportBuildParams): ReportData = buildReport(params) {}

    /**
     * [buildReport], showing [onBitmap] each bitmap the report will own as it
     * is made. Those bitmaps belong to the caller only once the report is
     * returned: a build that throws part-way (a later field's render, the
     * cover) recycles the ones made so far.
     */
    @VisibleForTesting
    internal fun buildReport(params: ReportBuildParams, onBitmap: (Bitmap) -> Unit): ReportData {
        val data = params.data
        val baseImg = params.baseImg
        val fieldResults = mutableListOf<FieldResult>()
        var correlationHeatmap: Bitmap? = null

        val owned = mutableListOf<Bitmap>()
        fun own(bitmap: Bitmap): Bitmap {
            owned += bitmap
            onBitmap(bitmap)
            return bitmap
        }
        var complete = false
        try {
            // One primitive buffer reused across all fields: filled once per field with
            // this field's signed accepted values (for both mean/std and the extrema sort),
            // so the build walks `data` once per field to collect, not twice. Sized to the
            // point count, it is the only per-point allocation alive during the build.
            val scratch = FloatArray(data.size / DicResult.STRIDE)

            for (fieldIndex in FIELD_NAMES.indices) {
                val dataIndex = fieldIndex + DicResult.IDX_U
                val isStrain = DicResult.isStrainFieldIndex(dataIndex)
                val isCorrelation = dataIndex == DicResult.IDX_ZNSSD
                val multiplier = DicResult.strainMultiplier(dataIndex)
                val unit = when {
                    isStrain -> "mε"
                    isCorrelation -> ""
                    else -> "px"
                }
                val meanTypeString = if (isStrain) "Mean Absolute" else "Simple Mean"

                // One collect pass: store signed values (what computeFieldExtrema's
                // absoluteStrainValues=false path needs) and accumulate the abs-mean sum in
                // the same walk. mean/std keep the exact same Double accumulation and
                // iteration order — abs is applied to the accumulator, not the stored value,
                // so the numbers match the previous two-pass code bit-for-bit.
                var count = 0
                var sum = 0.0
                for (i in data.indices step DicResult.STRIDE) {
                    val corr = data[i + DicResult.IDX_ZNSSD]
                    if (DicResult.isAcceptedPoint(corr, isCorrelation)) {
                        val rawVal = data[i + dataIndex]
                        scratch[count++] = rawVal
                        sum += if (isStrain) abs(rawVal) else rawVal
                    }
                }
                if (count == 0) continue

                val mean = (sum / count).toFloat()
                var sumSq = 0.0
                for (j in 0 until count) {
                    val v = if (isStrain) abs(scratch[j]) else scratch[j]
                    val d = v - mean
                    sumSq += d * d
                }
                val stdDev = sqrt(sumSq / count).toFloat()

                // scratch already holds this field's signed accepted values in order, so the
                // extrema step sorts them in place — no second collect walk of `data`.
                val extrema =
                    ReportFieldExtrema.extremaFromScratch(data, dataIndex, absoluteStrainValues = false, scratch, count)

                // Bound the intermediate render to REPORT_MAX_EDGE. Every output here is
                // downscaled to 600 px by compressForPdf(), so this is invisible — but it
                // stops the full-resolution ARGB_8888 bitmaps (heatmap + composite, up to
                // ~100 MB each on a 26 MP reference) from OOMing.
                val longest = maxOf(params.imgW, params.imgH).coerceAtLeast(1)
                val renderScale = if (longest > VisualizationEngine.REPORT_MAX_EDGE) {
                    VisualizationEngine.REPORT_MAX_EDGE.toFloat() / longest
                } else {
                    1f
                }
                val renderW = (params.imgW * renderScale).toInt().coerceAtLeast(1)
                val renderH = (params.imgH * renderScale).toInt().coerceAtLeast(1)

                val (heatmapBmp, actualMin, actualMax) = VisualizationEngine.generateHeatmap(
                    data,
                    params.imgW,
                    params.imgH,
                    dataIndex,
                    params.step,
                    null,
                    null,
                    maxLongEdge = VisualizationEngine.REPORT_MAX_EDGE,
                )

                // Compose at the capped size, then downscale for the PDF. The composite is
                // a throwaway — compressForPdf() returns a *new* small bitmap, so the
                // original must be recycled here or we leak one ARGB_8888 bitmap per field.
                // Both full-size bitmaps are freed in finally: a draw that throws must not
                // strand them (up to ~100 MB each before REPORT_MAX_EDGE capped them).
                val bakedHeatmap = own(
                    try {
                        val composite = createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                        try {
                            val tempCanvas = Canvas(composite)
                            // baseImg may be larger than the capped composite; scale it in.
                            tempCanvas.drawBitmap(
                                baseImg,
                                null,
                                Rect(0, 0, renderW, renderH),
                                Paint(Paint.FILTER_BITMAP_FLAG),
                            )
                            tempCanvas.drawBitmap(heatmapBmp, 0f, 0f, Paint().apply { alpha = 180 })
                            bakeAnnotationsToCanvas(
                                tempCanvas,
                                renderW,
                                renderH,
                                ValueRange(actualMin, actualMax),
                                extrema,
                                data,
                                FieldAnnotation(
                                    typeString = FIELD_KEYS[fieldIndex],
                                    unit = unit,
                                    dataIndex = dataIndex,
                                    imageName = params.deformedImageName,
                                    drawMinMarker = params.drawMinMarker,
                                ),
                                coordScale = renderScale,
                            )
                            composite.compressForPdf()
                        } finally {
                            composite.recycle()
                        }
                    } finally {
                        heatmapBmp.recycle()
                    },
                )

                if (isCorrelation) {
                    correlationHeatmap = bakedHeatmap
                } else {
                    fieldResults.add(
                        FieldResult(
                            fieldName = FIELD_NAMES[fieldIndex],
                            fieldKey = FIELD_KEYS[fieldIndex],
                            unit = unit,
                            minValue = extrema.minValue(data, dataIndex) ?: (actualMin * multiplier),
                            maxValue = extrema.maxValue(data, dataIndex) ?: (actualMax * multiplier),
                            meanValue = mean * multiplier,
                            stdDevValue = stdDev * multiplier,
                            meanType = meanTypeString,
                            minCoordX = data[extrema.minIdx].toInt(),
                            minCoordY = data[extrema.minIdx + 1].toInt(),
                            maxCoordX = data[extrema.maxIdx].toInt(),
                            maxCoordY = data[extrema.maxIdx + 1].toInt(),
                            bakedHeatmap = bakedHeatmap,
                        ),
                    )
                }
            }

            val znssd = ZnssdFrame.of(data)
            return ReportData(
                sessionId = params.sessionId,
                specimenName = params.specimenName,
                analysisDate = params.analysisDate,
                subsetSize = params.subsetSize,
                stepSize = params.step,
                strainWindow = params.strainWindow,
                strainMethod = params.strainMethod,
                roiData = params.roiData,
                referenceImage = own(baseImg.compressForPdf()),
                deformedImage = own(params.defImgForCover.compressForPdf()),
                referenceImageName = params.referenceImageName,
                deformedImageName = params.deformedImageName,
                fieldResults = fieldResults,
                engineStats = params.engineStats,
                znssdHeatmap = correlationHeatmap ?: own(createBitmap(1, 1, Bitmap.Config.ARGB_8888)),
                solverPathMap = own(createBitmap(1, 1, Bitmap.Config.ARGB_8888)),
                globalAvgZnssd = znssd.mean,
                znssdAcceptedPoints = znssd.points,
                appBuild = appBuildLabel(),
                rigidBody = RigidBodyFit.fit(data),
            ).also { complete = true }
        } finally {
            if (!complete) owned.forEach { it.recycle() }
        }
    }

    fun currentAnalysisDate(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    /**
     * What a baked field image says about itself: the field's name and unit,
     * the data column the MAX / MIN values are read from (null shows the
     * scale's ends instead), the image it was drawn on, and whether MIN is
     * marked as well as MAX.
     */
    data class FieldAnnotation(
        val typeString: String,
        val unit: String,
        val dataIndex: Int? = null,
        val imageName: String? = null,
        val drawMinMarker: Boolean = true,
    )

    /**
     * Draws the info box, the colour bar for [range] (stored units) and the
     * MAX / MIN markers at [extrema] onto a field image [width] × [height].
     * [data] coordinates are scaled by [coordScale] to land on a capped canvas.
     * See [ReportAnnotations].
     */
    fun bakeAnnotationsToCanvas(
        canvas: Canvas,
        width: Int,
        height: Int,
        range: ValueRange,
        extrema: FieldExtrema,
        data: FloatArray,
        annotation: FieldAnnotation,
        coordScale: Float = 1f,
    ) = ReportAnnotations.bakeAnnotationsToCanvas(canvas, width, height, range, extrema, data, annotation, coordScale)
}
