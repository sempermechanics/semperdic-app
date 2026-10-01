package com.indicvision.semper.ui.analysis.roi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.core.graphics.createBitmap

/**
 * ALPHA_8 ROI mask encoding extracted from [StudioOverlayView.generateMaskBytes].
 * White = correlate, clear = void.
 *
 * Only the holes are void. The background outside the crop stays opaque on
 * purpose: the ROI rect (passed to the engine separately) already bounds the
 * grid, and the engine drops any point whose subset touches a void pixel
 * (`native/src/pipeline/full_field_solver.cpp`, "PURE SUBSETS ONLY"). A void
 * background would cost a subset-radius band of points along every crop edge.
 * That only holds because the crop is always a rectangle (TD-74).
 */
object StudioOverlayMaskEncoder {

    data class Input(
        val realImageWidth: Int,
        val realImageHeight: Int,
        val imageBounds: RectF,
        val holes: List<StudioOverlayView.Hole>,
    )

    fun encode(input: Input): ByteArray {
        if (input.realImageWidth <= 0 || input.realImageHeight <= 0) return ByteArray(0)

        val maskBitmap = createBitmap(input.realImageWidth, input.realImageHeight, Bitmap.Config.ALPHA_8)
        val maskCanvas = Canvas(maskBitmap)

        maskCanvas.drawColor(Color.BLACK)

        val scaleX = input.realImageWidth.toFloat() / input.imageBounds.width()
        val scaleY = input.realImageHeight.toFloat() / input.imageBounds.height()

        val paintSub = Paint().apply {
            color = Color.TRANSPARENT
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        for (hole in input.holes) {
            val mappedHole = RectF(
                (hole.rect.left - input.imageBounds.left) * scaleX,
                (hole.rect.top - input.imageBounds.top) * scaleY,
                (hole.rect.right - input.imageBounds.left) * scaleX,
                (hole.rect.bottom - input.imageBounds.top) * scaleY,
            )
            maskCanvas.drawRect(mappedHole, paintSub)
        }

        val size = maskBitmap.rowBytes * maskBitmap.height
        val byteBuffer = java.nio.ByteBuffer.allocate(size)
        maskBitmap.copyPixelsToBuffer(byteBuffer)
        maskBitmap.recycle()

        return byteBuffer.array()
    }
}
