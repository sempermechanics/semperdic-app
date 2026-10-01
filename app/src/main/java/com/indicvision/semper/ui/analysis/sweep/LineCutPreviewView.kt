// Custom preview view: literal geometry, stroke widths and the dense mask/line
// rendering logic are clearest inline, so the structural and magic-number rules
// are suppressed for this whole file.
@file:Suppress("MagicNumber", "CyclomaticComplexMethod", "LongParameterList", "ReturnCount")

package com.indicvision.semper.ui.analysis.sweep

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.indicvision.semper.R
import com.indicvision.semper.ui.common.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reference-image preview of the virtual strain gauge centre line cut: the
 * image, the ROI (green) and holes (red) from the mask, and the cut through
 * the ROI centre along the chosen axis.
 */
class LineCutPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val PAD_DP = 8f
        const val ROI_STROKE_DP = 2f
        const val CUT_STROKE_DP = 2.5f

        /** Semi-transparent green for the selected ROI fill. */
        const val ROI_FILL_ALPHA = 0x55

        /** Semi-transparent red for hole pixels in the overlay. */
        const val HOLE_FILL_ALPHA = 0x88

        /** Cap overlay resolution so large masks stay cheap to rebuild. */
        const val OVERLAY_MAX_EDGE = 512
    }

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val roiStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(ROI_STROKE_DP)
        color = ContextCompat.getColor(context, R.color.semantic_success)
    }
    private val roiFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.semantic_success)
        alpha = ROI_FILL_ALPHA
    }
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(CUT_STROKE_DP)
        color = ContextCompat.getColor(context, R.color.sky_primary)
    }
    private val dimPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.surface_muted)
    }

    private val drawMatrix = Matrix()

    /** Reused every draw: onDraw runs on each scrub frame. */
    private val bitmapSrcRect = RectF()
    private val imageRect = RectF()
    private val roiRect = RectF()

    private var bitmap: Bitmap? = null
    private var maskBytes: ByteArray? = null
    private var holeOverlay: Bitmap? = null
    private var imageW = 0
    private var imageH = 0
    private var roiX = 0
    private var roiY = 0
    private var roiW = 0
    private var roiH = 0
    private var horizontal = true

    /**
     * @param bitmap preview of the reference image (may be scaled down)
     * @param imageW full reference width in engine pixels
     * @param imageH full reference height in engine pixels
     * @param maskBytes ALPHA_8 mask (`255` = include, `0` = exclude/hole), or null
     */
    fun setPreview(
        bitmap: Bitmap?,
        imageW: Int,
        imageH: Int,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        horizontal: Boolean,
        maskBytes: ByteArray? = null,
    ) {
        this.bitmap = bitmap
        this.imageW = imageW.coerceAtLeast(1)
        this.imageH = imageH.coerceAtLeast(1)
        this.roiX = roiX
        this.roiY = roiY
        this.roiW = roiW.coerceAtLeast(1)
        this.roiH = roiH.coerceAtLeast(1)
        this.horizontal = horizontal
        this.maskBytes = maskBytes
        rebuildHoleOverlay()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) rebuildHoleOverlay()
    }

    /**
     * Downscaled ARGB overlay: red only for mask==0 pixels inside the ROI
     * bounds. Outside the ROI is left transparent so the image shows through.
     */
    private fun rebuildHoleOverlay() {
        holeOverlay?.recycle()
        holeOverlay = null
        val mask = maskBytes ?: return
        if (imageW <= 0 || imageH <= 0 || mask.isEmpty()) return
        // ALPHA_8 bitmaps may pad each row; prefer packed width×height, else stride.
        val stride = when {
            mask.size >= imageW * imageH && mask.size % imageH == 0 -> mask.size / imageH
            mask.size >= imageW * imageH -> imageW
            else -> return
        }

        val maxEdge = OVERLAY_MAX_EDGE
        val scale = minOf(1f, maxEdge.toFloat() / max(imageW, imageH))
        val ow = max(1, (imageW * scale).roundToInt())
        val oh = max(1, (imageH * scale).roundToInt())
        val pixels = IntArray(ow * oh)
        val green = ContextCompat.getColor(context, R.color.semantic_success)
        val red = ContextCompat.getColor(context, R.color.semantic_danger)
        val greenPx = Color.argb(
            ROI_FILL_ALPHA,
            Color.red(green),
            Color.green(green),
            Color.blue(green),
        )
        val redPx = Color.argb(
            HOLE_FILL_ALPHA,
            Color.red(red),
            Color.green(red),
            Color.blue(red),
        )
        val roiRight = roiX + roiW
        val roiBottom = roiY + roiH

        for (oy in 0 until oh) {
            val iy = ((oy + 0.5f) / oh * imageH).toInt().coerceIn(0, imageH - 1)
            for (ox in 0 until ow) {
                val ix = ((ox + 0.5f) / ow * imageW).toInt().coerceIn(0, imageW - 1)
                val insideRoi = ix >= roiX && ix < roiRight && iy >= roiY && iy < roiBottom
                if (!insideRoi) {
                    pixels[oy * ow + ox] = Color.TRANSPARENT
                    continue
                }
                val included = mask[iy * stride + ix].toInt() and 0xFF != 0
                pixels[oy * ow + ox] = if (included) greenPx else redPx
            }
        }
        holeOverlay = createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, ow, 0, 0, ow, oh)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val pad = dp(PAD_DP)
        val availW = width - 2 * pad
        val availH = height - 2 * pad
        if (availW <= 0f || availH <= 0f) return

        val scale = minOf(availW / imageW, availH / imageH)
        val drawnW = imageW * scale
        val drawnH = imageH * scale
        val left = pad + (availW - drawnW) / 2f
        val top = pad + (availH - drawnH) / 2f
        imageRect.set(left, top, left + drawnW, top + drawnH)

        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            drawMatrix.reset()
            bitmapSrcRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            drawMatrix.setRectToRect(bitmapSrcRect, imageRect, Matrix.ScaleToFit.FILL)
            canvas.drawBitmap(bmp, drawMatrix, imagePaint)
        }

        fun mapX(x: Float) = left + x / imageW * drawnW
        fun mapY(y: Float) = top + y / imageH * drawnH

        roiRect.set(
            mapX(roiX.toFloat()),
            mapY(roiY.toFloat()),
            mapX((roiX + roiW).toFloat()),
            mapY((roiY + roiH).toFloat()),
        )

        val overlay = holeOverlay
        if (overlay != null && !overlay.isRecycled) {
            canvas.drawBitmap(overlay, null, imageRect, overlayPaint)
        } else {
            // No mask: still show the selected region as a green fill.
            canvas.drawRect(roiRect, roiFillPaint)
        }
        canvas.drawRect(roiRect, roiStrokePaint)

        val line = VsgStudy.centreLine(roiX, roiY, roiW, roiH, horizontal)
        if (line.horizontal) {
            val y = mapY(line.position)
            canvas.drawLine(roiRect.left, y, roiRect.right, y, cutPaint)
        } else {
            val x = mapX(line.position)
            canvas.drawLine(x, roiRect.top, x, roiRect.bottom, cutPaint)
        }
    }
}
