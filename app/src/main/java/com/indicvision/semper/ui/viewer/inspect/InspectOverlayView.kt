// Custom overlay view: literal marker sizes, stroke widths and colours are
// clearest inline, so MagicNumber is suppressed for this whole file.
@file:Suppress("MagicNumber")

package com.indicvision.semper.ui.viewer.inspect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt

class InspectOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var drawX = -1f
    private var drawY = -1f
    private var showCrosshair = false

    private val paintProbe = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val paintShadow = Paint().apply {
        color = "#88000000".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    fun updatePosition(x: Float, y: Float) {
        drawX = x
        drawY = y
        showCrosshair = true
        invalidate()
    }

    fun hide() {
        showCrosshair = false
        invalidate()
    }

    private fun drawReticle(canvas: Canvas, x: Float, y: Float, paint: Paint) {
        val radius = 15f
        val lineLen = 35f
        val gap = 5f

        canvas.drawCircle(x, y, radius, paintShadow)
        canvas.drawLine(x - lineLen, y, x - gap, y, paintShadow)
        canvas.drawLine(x + gap, y, x + lineLen, y, paintShadow)
        canvas.drawLine(x, y - lineLen, x, y - gap, paintShadow)
        canvas.drawLine(x, y + gap, x, y + lineLen, paintShadow)

        canvas.drawCircle(x, y, radius, paint)
        canvas.drawLine(x - lineLen, y, x - gap, y, paint)
        canvas.drawLine(x + gap, y, x + lineLen, y, paint)
        canvas.drawLine(x, y - lineLen, x, y - gap, paint)
        canvas.drawLine(x, y + gap, x, y + lineLen, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showCrosshair) drawReticle(canvas, drawX, drawY, paintProbe)
    }
}
