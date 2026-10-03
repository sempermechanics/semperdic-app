// The reference lookup (cache, file, display bitmap) reads best as early returns.
@file:Suppress("ReturnCount")

package com.sempermechanics.semper.ui.viewer.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.imaging.BitmapDecoder
import com.sempermechanics.semper.imaging.ImageEncoder
import com.sempermechanics.semper.report.ReportBuilder
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.viewer.share.ShareExportBuilder.Companion.FIELDS
import com.sempermechanics.semper.ui.viewer.share.ShareExportBuilder.Companion.HEATMAP_ALPHA
import java.io.File

/**
 * The annotated field PNGs of a [ShareExportBuilder] job: the current field
 * ([ShareKind.PHOTO]), every field of the frame ([ShareKind.PHOTOS]), and the
 * render the everything ZIP's result images use.
 */
internal class FieldImageExport(
    private val s: ShareCenter.Snapshot,
    private val outDir: File,
) {

    /**
     * Annotated PNG of one field for one frame's data. Composited at the
     * [VisualizationEngine.REPORT_MAX_EDGE]-capped size rather than full sensor
     * resolution: a 26 MP reference otherwise held four ~100 MB ARGB bitmaps at once
     * (heatmap + base + out + decode) per field. Marker coordinates are scaled by the
     * same factor, mirroring [ReportBuilder.buildReport].
     */
    fun renderAnnotated(
        data: FloatArray,
        dataIndex: Int,
        typeString: String,
        frameIndex: Int,
        baseCache: MutableMap<Pair<Int, Int>, Bitmap>? = null,
    ): Bitmap {
        // Optional cache: multi-field export reuses one decoded reference bitmap.
        val size = s.imageSize
        val renderScale =
            VisualizationEngine.cappedRenderScale(size.width, size.height, VisualizationEngine.REPORT_MAX_EDGE)
        val renderW = (size.width * renderScale).toInt().coerceAtLeast(1)
        val renderH = (size.height * renderScale).toInt().coerceAtLeast(1)

        val heatmap = VisualizationEngine.generateHeatmap(
            data,
            size.width,
            size.height,
            dataIndex,
            s.stepAt(frameIndex),
            null,
            null,
            maxLongEdge = VisualizationEngine.REPORT_MAX_EDGE,
        )
        // Each full-size bitmap is freed in finally, so a throw part-way (no
        // reference to draw on, an OOM) does not strand the others.
        var base: Bitmap? = null
        var out: Bitmap? = null
        var done = false
        try {
            base = loadCappedBase(renderW, renderH, baseCache)
            out = createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(base, null, Rect(0, 0, renderW, renderH), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawBitmap(heatmap.bitmap, 0f, 0f, Paint().apply { alpha = HEATMAP_ALPHA })
            // Signed, as the PDF does: with absolute values "MIN" marked the strain
            // nearest zero under a label giving the most negative.
            val extrema = ReportBuilder.computeFieldExtrema(data, dataIndex, absoluteStrainValues = false)
            val unit = if (DicResult.isStrainFieldIndex(dataIndex)) "mε" else "px"
            ReportBuilder.bakeAnnotationsToCanvas(
                canvas,
                renderW,
                renderH,
                heatmap.range,
                extrema,
                data,
                ReportBuilder.FieldAnnotation(
                    typeString = typeString,
                    unit = unit,
                    dataIndex = dataIndex,
                    imageName = s.sourceImageName(frameIndex),
                ),
                coordScale = renderScale,
            )
            done = true
            return out
        } finally {
            heatmap.bitmap.recycle()
            // A cached base belongs to the cache; the display bitmap to the viewer.
            if (baseCache == null && base != null && base !== s.baseImage) base.recycle()
            if (!done) out?.recycle()
        }
    }

    /**
     * Reference image for compositing, decoded no larger than the capped composite it
     * draws into — prefer the on-disk reference (inSampleSize-decoded) over the
     * viewer's display bitmap so export quality doesn't depend on viewer scale.
     */
    private fun loadCappedBase(
        renderW: Int,
        renderH: Int,
        cache: MutableMap<Pair<Int, Int>, Bitmap>? = null,
    ): Bitmap {
        val key = renderW to renderH
        cache?.get(key)?.let { return it }
        s.refImagePath?.let { path ->
            BitmapDecoder.decodeFileForView(
                path,
                renderW,
                renderH,
                VisualizationEngine.REPORT_MAX_EDGE,
                rawWidth = s.imageSize.width,
                rawHeight = s.imageSize.height,
            )?.let { decoded ->
                cache?.put(key, decoded)
                return decoded
            }
        }
        val display = s.baseImage ?: error("No reference image for export")
        val scaled = if (display.width == renderW && display.height == renderH) {
            display
        } else {
            display.scale(renderW, renderH)
        }
        if (scaled !== display) cache?.put(key, scaled)
        return scaled
    }

    fun recycleBaseCache(cache: MutableMap<Pair<Int, Int>, Bitmap>) {
        cache.values.forEach { bmp ->
            if (bmp !== s.baseImage) bmp.recycle()
        }
        cache.clear()
    }

    /** Writes [bmp] as a PNG and frees it, whether or not the write succeeds. */
    private fun writePng(bmp: Bitmap, name: String): File {
        try {
            val f = File(outDir, name)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, ImageEncoder.PNG_QUALITY_MAX, it) }
            return f
        } finally {
            bmp.recycle()
        }
    }

    fun currentPhoto(): File {
        return writePng(
            renderAnnotated(s.frameData(), s.dataIndex, s.typeString, s.frameIndex),
            "${s.baseName}_${s.typeString}_frame${s.frameIndex + 1}.png",
        )
    }

    fun allFieldPhotos(): List<File> {
        val data = s.frameData()
        val baseCache = mutableMapOf<Pair<Int, Int>, Bitmap>()
        return try {
            FIELDS.map { (label, idx) ->
                writePng(
                    renderAnnotated(data, idx, label, s.frameIndex, baseCache),
                    "${s.baseName}_${label}_frame${s.frameIndex + 1}.png",
                )
            }
        } finally {
            recycleBaseCache(baseCache)
        }
    }
}
