package com.indicvision.semper.ui.capture

import android.content.Context
import kotlin.math.max

/**
 * What one still really costs at a given resolution, from the two independent
 * limits that apply to it.
 *
 * A frame cannot arrive faster than the sensor will read that size out, and it
 * cannot be written faster than the CPU encodes the PNG. Neither figure alone
 * is the answer: the sensor floor comes from Camera2's stream configuration
 * map and knows nothing about this app's software encode, while the measured
 * encode cost knows nothing about read-out. Planning from whichever is larger
 * is the only bound that holds on both counts.
 *
 * Taking the larger of the two rather than their sum is deliberately
 * optimistic: today's loop is serial, since LockedCameraSession finishes the
 * encode inside the ImageReader callback before the next capture is issued.
 * The gap between max and sum is part of what
 * [CapturePlanOptions.ASSURANCE_MARGIN] is paid to cover. Planning from the
 * sum would price in a no-overlap worst case on every device and cut the
 * offered rates well below what phones actually sustain.
 */
internal object CaptureFrameCost {

    /** Per-frame budget in ms for [res] on the camera described by [caps]. */
    fun perFrameMs(
        context: Context,
        caps: CameraCapabilities.Info,
        res: CameraCapabilities.Resolution,
    ): Long {
        val encodeMs = CaptureCalibration.estimateFrameMs(context, res.width, res.height)
        val sensorMs = caps.sensorFloorMs(res)
        return max(encodeMs, sensorMs).coerceAtLeast(1L)
    }

    /**
     * The sizes worth offering on this camera: the ones it can hold
     * [CapturePlanOptions.MIN_FPS] at.
     *
     * One implementation on purpose, shared by the setup screen's picker and by
     * the speckle verdict's recommendation. They used to filter separately —
     * the picker did, the verdict did not — so the verdict could name a size
     * the picker had already dropped, and taking the recommendation landed the
     * user on a different resolution from the one they had just been promised.
     * Two answers to "which sizes can this run use" is one answer too many.
     *
     * The filter is only ever the **camera's** limit: the run length and the
     * frame cap bind every size alike, so they can never make one offerable and
     * another not. If *nothing* clears the floor the whole list comes back —
     * an empty drawer says less than a populated one beside a message naming
     * the camera.
     */
    fun offerable(
        context: Context,
        caps: CameraCapabilities.Info,
    ): List<CameraCapabilities.Resolution> {
        val all = caps.yuvSizes
        val sustainable = all.filter { CapturePlanOptions.sustainableAtFloor(perFrameMs(context, caps, it)) }
        return sustainable.ifEmpty { all }
    }
}
