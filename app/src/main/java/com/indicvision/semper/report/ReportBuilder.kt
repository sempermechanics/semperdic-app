// Report rendering: literal page/table coordinates, paint sizes and long draw
// calls are inherent to layout code and read clearest inline, so the structural
// and magic-number rules are suppressed for this whole file.

@file:Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "MagicNumber", "NestedBlockDepth")

package com.indicvision.semper.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.DicResult
import com.indicvision.semper.data.DicUploadWorker
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
    ): FieldExtrema =
        computeFieldExtrema(data, dataIndex, absoluteStrainValues, FloatArray(data.size / DicResult.STRIDE))

    /**
     * As [computeFieldExtrema], but collects accepted values into the caller-supplied
     * [scratch] (must hold at least the accepted-point count) instead of a boxed
     * `List<Float>`, so a report build can reuse a single primitive buffer across
     * fields. Sorting a primitive `FloatArray` uses the same total order as
     * `List<Float>.sort()` (`-0.0 < 0.0`, NaN greatest), so the p02/p98 picks — and
     * therefore the returned indices — are identical to the boxed path.
     */
    fun computeFieldExtrema(
        data: FloatArray,
        dataIndex: Int,
        absoluteStrainValues: Boolean,
        scratch: FloatArray,
    ): FieldExtrema {
        val isStrain = DicResult.isStrainFieldIndex(dataIndex)
        val isCorrelation = dataIndex == DicResult.IDX_ZNSSD

        var count = 0
        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr, isCorrelation)) {
                val rawVal = data[i + dataIndex]
                scratch[count++] = if (isStrain && absoluteStrainValues) abs(rawVal) else rawVal
            }
        }
        return extremaFromScratch(data, dataIndex, absoluteStrainValues, scratch, count)
    }

    /**
     * The sort + percentile + index passes of [computeFieldExtrema], given a [scratch]
     * already filled with the first [count] accepted field values (in the same
     * `absoluteStrainValues` convention). Split out so [buildReport] can fill the buffer
     * once — for both mean/std and extrema — instead of walking `data` twice per field.
     */
    private fun extremaFromScratch(
        data: FloatArray,
        dataIndex: Int,
        absoluteStrainValues: Boolean,
        scratch: FloatArray,
        count: Int,
    ): FieldExtrema {
        if (count == 0) return FieldExtrema(-1, -1)
        val isStrain = DicResult.isStrainFieldIndex(dataIndex)
        val isCorrelation = dataIndex == DicResult.IDX_ZNSSD
        fun fieldValue(rawVal: Float): Float = if (isStrain && absoluteStrainValues) abs(rawVal) else rawVal

        // Only two order statistics are needed out of scratch — quickSelect finds
        // each in expected O(n) instead of paying O(n log n) to fully sort it (same
        // change, same reasoning, as VisualizationEngine.computeSigmaClampedRange).
        val p02Index = (count * 0.02).toInt().coerceIn(0, count - 1)
        val p98Index = (count * 0.98).toInt().coerceIn(0, count - 1)
        val p02 = VisualizationEngine.quickSelect(scratch, p02Index, 0, count)
        val p98 = VisualizationEngine.quickSelect(scratch, p98Index, p02Index, count)

        var maxV = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxIdx = -1
        var minIdx = -1
        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr, isCorrelation)) {
                val valToCheck = fieldValue(data[i + dataIndex])
                if (valToCheck in p02..p98) {
                    if (valToCheck > maxV) {
                        maxV = valToCheck
                        maxIdx = i
                    }
                    if (valToCheck < minV) {
                        minV = valToCheck
                        minIdx = i
                    }
                }
            }
        }

        if (maxIdx == -1 || minIdx == -1) {
            for (i in data.indices step DicResult.STRIDE) {
                val corr = data[i + DicResult.IDX_ZNSSD]
                if (DicResult.isAcceptedPoint(corr, isCorrelation)) {
                    val valToCheck = fieldValue(data[i + dataIndex])
                    if (valToCheck > maxV) {
                        maxV = valToCheck
                        maxIdx = i
                    }
                    if (valToCheck < minV) {
                        minV = valToCheck
                        minIdx = i
                    }
                }
            }
        }

        return FieldExtrema(maxIdx, minIdx)
    }

    fun buildReport(params: ReportBuildParams): ReportData {
        val data = params.data
        val baseImg = params.baseImg
        val fieldResults = mutableListOf<FieldResult>()
        var correlationHeatmap: Bitmap? = null

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
            val extrema = extremaFromScratch(data, dataIndex, absoluteStrainValues = false, scratch, count)

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
            val bakedHeatmap = try {
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
                        tempCanvas, renderW, renderH, actualMin, actualMax,
                        FIELD_KEYS[fieldIndex], unit, extrema.maxIdx, extrema.minIdx, data,
                        dataIndex = dataIndex,
                        drawMinMarker = params.drawMinMarker,
                        coordScale = renderScale,
                        imageName = params.deformedImageName,
                    )
                    composite.compressForPdf()
                } finally {
                    composite.recycle()
                }
            } finally {
                heatmapBmp.recycle()
            }

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
            referenceImage = baseImg.compressForPdf(),
            deformedImage = params.defImgForCover.compressForPdf(),
            referenceImageName = params.referenceImageName,
            deformedImageName = params.deformedImageName,
            fieldResults = fieldResults,
            engineStats = params.engineStats,
            znssdHeatmap = correlationHeatmap ?: createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            solverPathMap = createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            globalAvgZnssd = znssd.mean,
            znssdAcceptedPoints = znssd.points,
            appBuild = appBuildLabel(),
            rigidBody = RigidBodyFit.fit(data),
        )
    }

    fun currentAnalysisDate(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    fun bakeAnnotationsToCanvas(
        canvas: Canvas,
        width: Int,
        height: Int,
        minValRaw: Float,
        maxValRaw: Float,
        typeString: String,
        unit: String,
        maxIdx: Int,
        minIdx: Int,
        dataArray: FloatArray,
        dataIndex: Int? = null,
        drawMinMarker: Boolean = true,
        coordScale: Float = 1f,
        imageName: String? = null,
    ) {
        val multiplier = if (unit == "mε") DicResult.STRAIN_TO_MILLISTRAIN else 1f
        val maxVal = maxValRaw * multiplier
        val minVal = minValRaw * multiplier
        val marked = FieldExtrema(maxIdx, minIdx)

        val textSize = width * 0.025f
        val padding = width * 0.02f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        val bgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }

        val infoText = listOfNotNull(
            "Semper Analysis Report",
            imageName?.takeIf { it.isNotBlank() }?.let { "Image: $it" },
            "Field: $typeString [$unit]",
            // The marked points' values; the colour bar below keeps the scale's ends.
            "Max: ${formatMetric(dataIndex?.let { marked.maxValue(dataArray, it) } ?: maxVal)}",
            "Min: ${formatMetric(dataIndex?.let { marked.minValue(dataArray, it) } ?: minVal)}",
        )
        var maxTextWidth = 0f
        for (line in infoText) {
            val w = textPaint.measureText(line)
            if (w > maxTextWidth) maxTextWidth = w
        }

        canvas.drawRect(
            padding * 0.5f,
            padding * 0.5f,
            padding * 1.5f + maxTextWidth,
            padding + (infoText.size * (textSize * 1.4f)) + padding,
            bgPaint,
        )
        var currentY = padding + textSize
        for (line in infoText) {
            canvas.drawText(line, padding, currentY, textPaint)
            currentY += textSize * 1.4f
        }

        val barWidth = width * 0.03f
        val barHeight = height * 0.5f
        val barLeft = width - padding - barWidth - (textSize * 4.5f)
        val barTop = (height - barHeight) / 2f
        val barRight = barLeft + barWidth
        val barBottom = barTop + barHeight

        // The map's own ramp, lowest value at the bottom. A six-stop jet drawn here
        // before put pure red at 80 % of the scale where the map has it at 87.5 %,
        // off by up to 83 levels in a channel, so values read off the bar were wrong.
        canvas.drawRect(
            barLeft,
            barTop,
            barRight,
            barBottom,
            Paint().apply {
                shader = LinearGradient(
                    0f,
                    barBottom,
                    0f,
                    barTop,
                    VisualizationEngine.rampColors(),
                    null,
                    Shader.TileMode.CLAMP,
                )
            },
        )
        canvas.drawRect(
            barLeft,
            barTop,
            barRight,
            barBottom,
            Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 3f
            },
        )

        val scaleTextPaint = Paint(textPaint).apply {
            textAlign = Paint.Align.LEFT
            clearShadowLayer()
            color = Color.BLACK
        }
        val whiteBgPaint = Paint().apply { color = Color.argb(200, 255, 255, 255) }
        fun drawScaleLabel(text: String, y: Float) {
            val w = scaleTextPaint.measureText(text)
            canvas.drawRect(
                barRight + padding * 0.5f - 5f,
                y - textSize,
                barRight + padding * 0.5f + w + 5f,
                y + (textSize * 0.3f),
                whiteBgPaint,
            )
            canvas.drawText(text, barRight + padding * 0.5f, y, scaleTextPaint)
        }

        drawScaleLabel(formatMetric(maxVal), barTop + (textSize * 0.3f))
        drawScaleLabel(formatMetric((maxVal + minVal) / 2f), barTop + (barHeight / 2f) + (textSize * 0.3f))
        drawScaleLabel(formatMetric(minVal), barBottom)

        if (maxIdx != -1 && minIdx != -1) {
            // Data coords are in full-resolution image space; scale them to the
            // (possibly capped) canvas so markers land correctly.
            val maxX = dataArray[maxIdx] * coordScale
            val maxY = dataArray[maxIdx + 1] * coordScale
            val minX = dataArray[minIdx] * coordScale
            val minY = dataArray[minIdx + 1] * coordScale
            val targetRadius = width * 0.015f
            val crosshairLen = targetRadius * 1.5f
            val whiteOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 6f
            }
            val markerTextPaint = Paint(textPaint).apply { this.textSize = width * 0.018f }

            fun drawTarget(x: Float, y: Float, label: String, coreColor: Int) {
                canvas.drawCircle(x, y, targetRadius, whiteOutline)
                canvas.drawLine(x - crosshairLen, y, x + crosshairLen, y, whiteOutline)
                canvas.drawLine(x, y - crosshairLen, x, y + crosshairLen, whiteOutline)
                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = coreColor
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
                canvas.drawCircle(x, y, targetRadius, corePaint)
                canvas.drawLine(x - crosshairLen, y, x + crosshairLen, y, corePaint)
                canvas.drawLine(x, y - crosshairLen, x, y + crosshairLen, corePaint)
                canvas.drawText(label, x + targetRadius + 5f, y - targetRadius - 5f, markerTextPaint)
            }
            drawTarget(maxX, maxY, "MAX", Color.RED)
            if (drawMinMarker) {
                drawTarget(minX, minY, "MIN", Color.BLUE)
            }
        }
    }
}
