package com.sempermechanics.semper.ui.analysis.roi

import android.graphics.RectF
import com.sempermechanics.semper.ui.common.ViewportMath
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * Zoom and pan for the ROI editor's canvas. Zoom 1 is the fit-center rest
 * view; the state is the zoom and the image point (0..1 of each side) shown
 * at the view centre, so a canvas resize (keyboard, toolbar) keeps both.
 *
 * A zoomed side always covers the view and a side smaller than the view stays
 * centred, so the photo can never be panned off screen.
 */
internal class RoiViewport {

    var zoom = 1f
        private set

    val isZoomed: Boolean get() = zoom > 1f + ZOOM_EPS

    private var centerX = HALF
    private var centerY = HALF
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** Image size in view px at zoom 1. */
    private var fitWidth = 0f
    private var fitHeight = 0f

    /** Finger travel an edge stopped during this pan, per axis, in view px (TD-146). */
    private var slackX = 0f
    private var slackY = 0f

    /** Sets the drawable's size and the view's; keeps zoom and centre. */
    fun layout(imageWidth: Float, imageHeight: Float, viewWidth: Float, viewHeight: Float) {
        val fit = min(viewWidth / imageWidth, viewHeight / imageHeight)
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        fitWidth = imageWidth * fit
        fitHeight = imageHeight * fit
        clampCenter()
    }

    /** Where the image sits, in view px. */
    fun bounds(out: RectF): RectF {
        val w = fitWidth * zoom
        val h = fitHeight * zoom
        val left = viewWidth / 2f - centerX * w
        val top = viewHeight / 2f - centerY * h
        out.set(left, top, left + w, top + h)
        return out
    }

    /** Scales by [factor] about view point ([focusX], [focusY]), within 1..[MAX_ZOOM]. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (fitWidth <= 0f || fitHeight <= 0f) return
        val w = fitWidth * zoom
        val h = fitHeight * zoom
        // The image point under the focus stays under it.
        val u = centerX + (focusX - viewWidth / 2f) / w
        val v = centerY + (focusY - viewHeight / 2f) / h
        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        centerX = u - (focusX - viewWidth / 2f) / (fitWidth * zoom)
        centerY = v - (focusY - viewHeight / 2f) / (fitHeight * zoom)
        clampCenter()
    }

    /** A new two-finger gesture: no edge has stopped any travel yet. */
    fun beginPan() {
        slackX = 0f
        slackY = 0f
    }

    /**
     * Moves the image by ([dx], [dy]) view px. Travel an edge stops is kept as
     * slack for [settleBy]; a move the photo follows in full clears it.
     */
    fun panBy(dx: Float, dy: Float) {
        if (fitWidth <= 0f || fitHeight <= 0f) return
        val w = fitWidth * zoom
        val h = fitHeight * zoom
        val fromX = centerX
        val fromY = centerY
        centerX -= dx / w
        centerY -= dy / h
        clampCenter()
        slackX = slackAfter(slackX, dx, (fromX - centerX) * w)
        slackY = slackAfter(slackY, dy, (fromY - centerY) * h)
    }

    /**
     * A lift's correction back to where the fingers really are (TD-142). The
     * last move can run a few px past the fingers; if an edge stopped that
     * overshoot, the photo never made it, so the correction first gives back
     * the stopped travel and does not pull the photo off the edge.
     */
    fun settleBy(dx: Float, dy: Float) {
        val backX = givenBack(slackX, dx)
        val backY = givenBack(slackY, dy)
        slackX += backX
        slackY += backY
        panBy(dx - backX, dy - backY)
    }

    /** Double-tap: back to fit when zoomed, else [DOUBLE_TAP_ZOOM] about the tap. */
    fun toggle(focusX: Float, focusY: Float) {
        if (isZoomed) reset() else zoomBy(DOUBLE_TAP_ZOOM, focusX, focusY)
    }

    fun reset() {
        zoom = 1f
        centerX = HALF
        centerY = HALF
        beginPan()
    }

    private fun clampCenter() {
        centerX = ViewportMath.centerFraction(centerX, viewWidth, fitWidth * zoom)
        centerY = ViewportMath.centerFraction(centerY, viewHeight, fitHeight * zoom)
    }

    companion object {
        /** Matches the viewer's pinch ceiling (`TouchImageView`). */
        const val MAX_ZOOM = 10f
        const val DOUBLE_TAP_ZOOM = 2f
        private const val HALF = 0.5f
        private const val ZOOM_EPS = 1e-3f
    }
}

/** View px: float noise when comparing a pan with what the photo moved. */
private const val SLACK_EPS = 1e-3f

/** Slack after a pan that wanted [wanted] px and moved the photo [moved]. */
private fun slackAfter(slack: Float, wanted: Float, moved: Float): Float = when {
    abs(wanted - moved) >= SLACK_EPS -> slack + wanted - moved
    abs(wanted) >= SLACK_EPS -> 0f
    else -> slack
}

/** The part of [delta] that runs back over [slack], at most all of it. */
private fun givenBack(slack: Float, delta: Float): Float =
    if (slack * delta < 0f) sign(delta) * min(abs(delta), abs(slack)) else 0f
