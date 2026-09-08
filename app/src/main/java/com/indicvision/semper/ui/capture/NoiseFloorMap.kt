package com.indicvision.semper.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import com.indicvision.semper.DicResult
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.ui.analysis.NoiseFloorProbe
import timber.log.Timber
import java.io.File

/**
 * Draws the noise burst's per-point scatter over the frame it was measured on.
 *
 * Its own file rather than another method on [NoiseFloorGate], which is already
 * at its limit and has a different job: the gate decides, this renders. The two
 * meet at [NoiseFloorProbe.SigmaField] and nowhere else.
 *
 * Drawn here, at the end of the burst, because [NoiseFloorGate.measure] deletes
 * the burst frames the moment it returns — a map has to be made while the image
 * under it still exists.
 */
/** The heat map composited over the frame it describes. */
class NoiseFloorSigmaMap(
    val image: Bitmap,
    /**
     * Ends of the colour scale, in pixels of displacement — the scale the
     * renderer actually used, which is a percentile clamp and not the field's
     * extremes. The legend has to quote these or it labels colours that are
     * not on the map.
     */
    val minSigmaPx: Double,
    val maxSigmaPx: Double,
)

internal object NoiseFloorMap {

    /**
     * The scatter field drawn as a jet heat map over the frame it was measured
     * on.
     *
     * Rendered through [VisualizationEngine.generateHeatmap] unchanged: the
     * probe already emits its field in [DicResult]'s own layout, so the map is
     * one call with `valIndex = IDX_U` and nothing inside the visualiser has to
     * know this caller exists.
     *
     * Composited over the reference frame rather than shown beside it, because
     * the question the map answers is *where* — a bright corner means nothing
     * without the corner of the specimen under it.
     *
     * **The legend comes back from the visualiser, not from the field.** Asked
     * without explicit bounds, `generateHeatmap` scales its colours to the 2nd
     * and 98th percentiles of the data, not to its extremes — so the field's
     * own min and max are not what the reddest and bluest pixels mean. On a
     * scatter with a couple of glare cells in it the two differ by an order of
     * magnitude, and a legend quoting the extremes would put a number under the
     * map that no colour on the map stands for. The returned pair is the scale
     * that was actually drawn, so that is what is quoted.
     *
     * Null on any failure. A missing map costs the dialog a section it can hide;
     * a crash here would cost a test shot.
     */
    fun of(field: NoiseFloorProbe.SigmaField?, reference: File): NoiseFloorSigmaMap? {
        if (field == null) return null
        return runCatching {
            val (heat, scaleMin, scaleMax) = VisualizationEngine.generateHeatmap(
                data = field.points,
                imgW = field.imgW,
                imgH = field.imgH,
                valIndex = DicResult.IDX_U,
                step = field.step,
                maxLongEdge = MAP_LONG_EDGE_PX,
            )
            val base = decodeAbout(reference, heat.width, heat.height)
            val canvas = createBitmap(heat.width, heat.height)
            Canvas(canvas).apply {
                base?.let { drawBitmap(it, null, Rect(0, 0, heat.width, heat.height), null) }
                drawBitmap(heat, 0f, 0f, Paint().apply { alpha = MAP_ALPHA })
            }
            base?.recycle()
            heat.recycle()
            NoiseFloorSigmaMap(canvas, scaleMin.toDouble(), scaleMax.toDouble())
        }.onFailure { Timber.w(it, "noise floor: sigma map could not be drawn") }.getOrNull()
    }

    /**
     * The frame decoded at roughly [width] x [height].
     *
     * Subsampled on the way in rather than scaled afterwards: the burst frames
     * are full-resolution stills and decoding one whole on top of the heat map,
     * inside a dialog, on a phone that has just been asked for six of them, is
     * how a measurement screen runs out of memory.
     */
    private fun decodeAbout(file: File, width: Int, height: Int): Bitmap? {
        val bounds = boundsOf(file) ?: return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, minOf(bounds.first / width, bounds.second / height))
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun boundsOf(file: File): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }

    /** Long edge of the rendered map. A dialog-sized image, not a report figure. */
    private const val MAP_LONG_EDGE_PX = 720

    /**
     * How much of the frame shows through the colours.
     *
     * The map has to be read against the specimen it describes, so neither
     * layer can win outright: an opaque heat map is a picture of numbers with
     * nowhere to point, and a faint one is a photograph with a tint.
     */
    private const val MAP_ALPHA = 140
}
