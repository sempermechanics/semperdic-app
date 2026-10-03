package com.sempermechanics.semper.ui.viewer.inspect

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
        strokeWidth = PROBE_STROKE
        isAntiAlias = true
    }
    private val paintShadow = Paint().apply {
        color = "#88000000".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = SHADOW_STROKE
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
        val radius = RETICLE_RADIUS
        val lineLen = RETICLE_ARM
        val gap = RETICLE_GAP

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

    /** The probe reticle, in px: a ring and four arms that stop short of it. */
    private companion object {
        const val PROBE_STROKE = 3f
        const val SHADOW_STROKE = 6f
        const val RETICLE_RADIUS = 15f
        const val RETICLE_ARM = 35f
        const val RETICLE_GAP = 5f
    }
}
