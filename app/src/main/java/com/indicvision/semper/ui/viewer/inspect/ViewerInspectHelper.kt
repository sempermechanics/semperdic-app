// Probe gestures: literal touch thresholds read clearest inline.
@file:Suppress("MagicNumber")

@file:SuppressLint("ClickableViewAccessibility", "SetTextI18n")

package com.indicvision.semper.ui.viewer.inspect

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.view.View
import android.widget.TextView
import com.indicvision.semper.R
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.ui.viewer.ResultViewerActivity

/**
 * Tap-to-probe overlay: nearest correlated point, one crosshair, one readout.
 * Frame data and field index stay on [ResultViewerActivity].
 */
class ViewerInspectHelper(private val host: ResultViewerActivity) {

    private val imgMain: TouchImageView get() = host.imgMain
    private val glassShield: InspectOverlayView get() = host.glassShield
    private val tvProbeReadout: TextView get() = host.tvProbeReadout

    var lastClosestIdx = -1
    private var probeVisible = false

    /** Restore a probe after rotation when [idx] was saved. */
    fun restoreProbe(idx: Int) {
        if (idx < 0) return
        lastClosestIdx = idx
        probeVisible = true
        glassShield.visibility = View.VISIBLE
    }

    /**
     * Spatial buckets for the current frame's accepted points. Cleared on frame load
     * and built lazily on the first tap ([findNearestDataPoint]), so scrubbing frames
     * that are never probed pays nothing for it.
     */
    private var spatialIndex: PointSpatialIndex? = null

    fun clearSpatialIndex() {
        spatialIndex = null
    }

    fun wireTapHandling() {
        imgMain.onTapListener = { x, y ->
            host.bumpChrome()
            onScreenTap(x, y)
        }
        imgMain.onScrubListener = { delta -> host.stepFrame(delta) }
        imgMain.onCenterDoubleTapShowChrome = { host.showChromeIfHidden() }
        imgMain.onChromeSwipeListener = { show ->
            if (show) host.bumpChrome()
        }
        tvProbeReadout.setOnClickListener { dismissProbe() }
    }

    fun onScreenTap(screenX: Float, screenY: Float) {
        val pts = floatArrayOf(screenX, screenY)
        val inverse = Matrix()
        imgMain.getZoomMatrix().invert(inverse)
        inverse.mapPoints(pts)
        findNearestDataPoint(pts[0], pts[1])
    }

    fun dismissProbe() {
        probeVisible = false
        lastClosestIdx = -1
        glassShield.hide()
        glassShield.visibility = View.GONE
        tvProbeReadout.visibility = View.GONE
    }

    /**
     * Nearest point to a tap at image pixel ([physX], [physY]). On the frame's
     * own photo the points are where they moved to, so the lookup is over
     * (x + u, y + v); the returned index reads the same frame data either way.
     */
    fun findNearestDataPoint(physX: Float, physY: Float) {
        val data = host.rawData ?: return
        val searchRadius = host.step * 1.5f
        val index = spatialIndex ?: PointSpatialIndex.build(
            if (host.onFramePhoto) displacedPositions(data) else data,
            host.step,
        ).also { spatialIndex = it }
        lastClosestIdx = index.nearest(physX, physY, searchRadius)
        probeVisible = true
        glassShield.visibility = View.VISIBLE
        refreshCrosshairs()
    }

    fun refreshCrosshairs() {
        if (!probeVisible) {
            glassShield.hide()
            tvProbeReadout.visibility = View.GONE
            return
        }
        val data = host.rawData ?: return

        val dataIndex = host.currentDataIndex
        val typeString = host.currentTypeString
        val isStrain = DicResult.isStrainFieldIndex(dataIndex)
        val multiplier = DicResult.strainMultiplier(dataIndex)
        val unit = if (isStrain) "mε" else "px"

        glassShield.visibility = View.VISIBLE
        tvProbeReadout.visibility = View.VISIBLE

        if (lastClosestIdx != -1 && lastClosestIdx < data.size) {
            val actualX = data[lastClosestIdx].toInt()
            val actualY = data[lastClosestIdx + 1].toInt()
            val value = data[lastClosestIdx + dataIndex] * multiplier

            tvProbeReadout.text = host.getString(
                R.string.probe_reading_fmt,
                typeString,
                ReportBuilder.formatMetric(value),
                unit,
                actualX,
                actualY,
            )

            val pts = if (host.onFramePhoto) {
                floatArrayOf(
                    data[lastClosestIdx] + data[lastClosestIdx + DicResult.IDX_U],
                    data[lastClosestIdx + 1] + data[lastClosestIdx + DicResult.IDX_V],
                )
            } else {
                floatArrayOf(actualX.toFloat(), actualY.toFloat())
            }
            imgMain.getZoomMatrix().mapPoints(pts)
            glassShield.updatePosition(pts[0], pts[1])
        } else {
            glassShield.hide()
            tvProbeReadout.text = host.getString(R.string.probe_no_data)
        }
    }

    internal companion object {
        /**
         * A copy of [data] with each point's x, y moved by its u, v — the
         * layout [PointSpatialIndex] reads, so the same index finds points on
         * the deformed frame. Built only on the first tap of a frame.
         */
        fun displacedPositions(data: FloatArray): FloatArray {
            val out = data.copyOf()
            for (i in out.indices step DicResult.STRIDE) {
                out[i + DicResult.IDX_X] += out[i + DicResult.IDX_U]
                out[i + DicResult.IDX_Y] += out[i + DicResult.IDX_V]
            }
            return out
        }
    }
}
