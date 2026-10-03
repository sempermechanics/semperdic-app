package com.sempermechanics.semper.ui.analysis.roi

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * The ROI editor's zoom and pan gestures, fed every touch before the
 * one-finger editing sees it. Two fingers pinch and pan [viewport]; a
 * double-tap toggles 2x and fit. Taking over a touch first runs [cancelEdit],
 * so an edit begun by the first finger of a pinch is dropped; every move of
 * the viewport runs [onMoved].
 */
internal class StudioOverlayViewport(
    context: Context,
    private val viewport: RoiViewport,
    private val cancelEdit: () -> Unit,
    private val onMoved: () -> Unit,
) {
    /** True from a second finger (or a double-tap) until every finger lifts. */
    private var active = false
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinchSpan = 0f

    private val doubleTapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                cancelEdit()
                viewport.beginPan()
                viewport.toggle(e.x, e.y)
                onMoved()
                active = true
                trackPinch(e)
                return true
            }
        },
    ).apply { setIsLongpressEnabled(false) }

    /** Returns true when [event] belongs to a zoom/pan gesture, not to editing. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) active = false
        doubleTapDetector.onTouchEvent(event)
        // The last finger lifting still belongs to the gesture it ends.
        val action = event.actionMasked
        val ending = active && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!active) {
                    cancelEdit()
                    viewport.beginPan()
                    active = true
                }
                trackPinch(event)
            }
            MotionEvent.ACTION_POINTER_UP -> if (active) settleThenTrack(event)
            MotionEvent.ACTION_MOVE -> if (active) pinchMove(event)
            MotionEvent.ACTION_UP -> if (active) {
                pinchMove(event, lifting = NO_POINTER, settle = true)
                active = false
            }
            MotionEvent.ACTION_CANCEL -> active = false
        }
        return active || ending
    }

    /**
     * A finger lifting: first follow every finger to where it really is, then
     * re-anchor on the ones still down. The last MOVE can be resampled a few px
     * past the fingers (Choreographer input resampling, worst on a slow
     * device); the lift carries the true positions, so the photo stops where
     * the fingers left it.
     */
    private fun settleThenTrack(event: MotionEvent) {
        pinchMove(event, lifting = NO_POINTER, settle = true)
        trackPinch(event)
    }

    private fun pinchMove(event: MotionEvent, lifting: Int = liftingIndex(event), settle: Boolean = false) {
        val prevX = pinchFocusX
        val prevY = pinchFocusY
        val prevSpan = pinchSpan
        trackPinch(event, lifting)
        if (settle) {
            viewport.settleBy(pinchFocusX - prevX, pinchFocusY - prevY)
        } else {
            viewport.panBy(pinchFocusX - prevX, pinchFocusY - prevY)
        }
        if (prevSpan > MIN_PINCH_SPAN && pinchSpan > MIN_PINCH_SPAN) {
            viewport.zoomBy(pinchSpan / prevSpan, pinchFocusX, pinchFocusY)
        }
        onMoved()
    }

    private fun liftingIndex(event: MotionEvent): Int =
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else NO_POINTER

    /** Focus and mean spread of the fingers still down (a lifting one is left out). */
    private fun trackPinch(event: MotionEvent, lifting: Int = liftingIndex(event)) {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == lifting) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        if (count == 0) return
        pinchFocusX = sumX / count
        pinchFocusY = sumY / count
        var spread = 0f
        for (i in 0 until event.pointerCount) {
            if (i == lifting) continue
            spread += hypot(event.getX(i) - pinchFocusX, event.getY(i) - pinchFocusY)
        }
        pinchSpan = spread / count
    }

    private companion object {
        /** Below this finger spread (view px) a pinch only pans. */
        const val MIN_PINCH_SPAN = 10f
        const val NO_POINTER = -1
    }
}
