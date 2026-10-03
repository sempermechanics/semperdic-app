package com.sempermechanics.semper.report

import com.sempermechanics.semper.field.DicResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * How much of a frame's displacement field is the whole scene moving, rather
 * than the specimen deforming.
 *
 * Two device runs on a tripod, with the specimen flat on a table under a fixed
 * lamp, still walked 2.3 px over sixty seconds — monotonically, which is
 * thermal, not vibration. That number has to reach the user, because it is the
 * one error in this whole chain that no camera setting touches and that only
 * they can fix.
 *
 * **It is reported and never subtracted.** A uniform translation already
 * cancels in strain, which is a spatial derivative of the displacement, so
 * removing it would change how the field *looks* without changing a single
 * strain value — the definition of a cosmetic fix. Worse, subtracting a fit
 * taken over the whole ROI would quietly remove real deformation along with
 * it, since under load the field is not rigid and the least-squares fit does
 * not know the difference. Where a genuinely undeforming region is available
 * to fit over, subtracting becomes defensible; until one is, reporting is the
 * honest half of the job and the only half worth doing.
 *
 * Pure JVM, no Android types, so the arithmetic is unit-testable.
 */
object RigidBodyFit {

    /**
     * @param uPx mean horizontal shift of the whole field, in pixels.
     * @param vPx mean vertical shift, in pixels.
     * @param rotationDeg small-angle rotation about the field's centroid.
     * @param residualPx RMS of what the rigid fit could not explain — the
     *   deformation plus the noise, which is what the measurement is actually
     *   for. A residual far below the shift means the frame moved and the
     *   specimen did not.
     * @param points accepted points the fit was taken over.
     */
    data class Fit(
        val uPx: Double,
        val vPx: Double,
        val rotationDeg: Double,
        val residualPx: Double,
        val points: Int,
    ) {
        /** Straight-line distance the scene moved, in pixels. */
        fun shiftPx(): Double = hypot(uPx, vPx)

        /**
         * True when the shift is large enough to be worth a sentence.
         *
         * A pixel is the threshold because it is the scale the rest of this
         * work operates at: the whole point of the ISP lockdown is to hold
         * frame-to-frame motion at hundredths of a pixel, so a whole pixel of
         * bulk movement is the rig, not the camera, and no further capture
         * setting will improve it.
         */
        fun notable(): Boolean = shiftPx() >= NOTABLE_SHIFT_PX
    }

    /** Shift, in pixels, at or above which the fit is worth telling the user about. */
    const val NOTABLE_SHIFT_PX = 1.0

    /**
     * Fewest accepted points worth fitting three parameters to.
     *
     * Three unknowns need three points to be determined at all; below a few
     * dozen the rotation term is dominated by whichever handful of points
     * happened to converge, and a rotation read off eight points is a number
     * with no error bar being shown to someone who will believe it.
     */
    const val MIN_POINTS = 32

    /**
     * Fit translation and a small rotation about the centroid to the accepted
     * points of one frame's `.dat`.
     *
     * The model is `u = tx - θ(y - ȳ)`, `v = ty + θ(x - x̄)`. Centring on the
     * centroid is what makes it separable: the rotation terms are then
     * zero-mean, so the translation is just the mean displacement and θ falls
     * out of a single ratio rather than a 3x3 solve. The small-angle form is
     * not an approximation being got away with — a frame that rotated enough
     * for it to matter has a rig failure the number would only understate.
     *
     * @return null when too few points converged to fit anything, which is a
     *   frame with its own problems and not a place to report a drift number.
     */
    fun fit(data: FloatArray?): Fit? {
        val sums = accumulate(data) ?: return null
        val n = sums.count.toDouble()
        val meanX = sums.x / n
        val meanY = sums.y / n
        val meanU = sums.u / n
        val meanV = sums.v / n
        // Cross term over the second moment: the least-squares rotation once
        // both the coordinates and the displacements are centred.
        val cross = sums.xv - meanX * sums.v - meanV * sums.x + n * meanX * meanV -
            (sums.yu - meanY * sums.u - meanU * sums.y + n * meanY * meanU)
        val moment = sums.xx - n * meanX * meanX + sums.yy - n * meanY * meanY
        val theta = if (moment > 0.0) cross / moment else 0.0
        return Fit(
            uPx = meanU,
            vPx = meanV,
            rotationDeg = Math.toDegrees(atan2(theta, 1.0)),
            residualPx = residualOf(requireNotNull(data), meanX, meanY, meanU, meanV, theta),
            points = sums.count,
        )
    }

    /** The sums the fit needs, gathered in one pass over the accepted points. */
    private class Sums {
        var count = 0
        var x = 0.0
        var y = 0.0
        var u = 0.0
        var v = 0.0
        var xx = 0.0
        var yy = 0.0
        var xv = 0.0
        var yu = 0.0
    }

    private fun accumulate(data: FloatArray?): Sums? {
        if (data == null) return null
        val sums = Sums()
        var i = 0
        while (i < data.size) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                val x = data[i + DicResult.IDX_X].toDouble()
                val y = data[i + DicResult.IDX_Y].toDouble()
                val u = data[i + DicResult.IDX_U].toDouble()
                val v = data[i + DicResult.IDX_V].toDouble()
                sums.count++
                sums.x += x
                sums.y += y
                sums.u += u
                sums.v += v
                sums.xx += x * x
                sums.yy += y * y
                sums.xv += x * v
                sums.yu += y * u
            }
            i += DicResult.STRIDE
        }
        return if (sums.count >= MIN_POINTS) sums else null
    }

    @Suppress("LongParameterList") // the fitted parameters, which travel together
    private fun residualOf(
        data: FloatArray,
        meanX: Double,
        meanY: Double,
        meanU: Double,
        meanV: Double,
        theta: Double,
    ): Double {
        var sumSq = 0.0
        var count = 0
        var i = 0
        while (i < data.size) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                val du = data[i + DicResult.IDX_U] -
                    (meanU - theta * (data[i + DicResult.IDX_Y] - meanY))
                val dv = data[i + DicResult.IDX_V] -
                    (meanV + theta * (data[i + DicResult.IDX_X] - meanX))
                sumSq += du * du + dv * dv
                count++
            }
            i += DicResult.STRIDE
        }
        return if (count == 0) 0.0 else sqrt(sumSq / count)
    }

    /**
     * One line for the report, leading with the number and saying what it is.
     *
     * Deliberately does not say "drift": the field cannot tell a camera that
     * moved from a specimen that did, and naming the wrong one sends the user
     * to the wrong fixture.
     */
    fun label(fit: Fit): String = String.format(
        java.util.Locale.US,
        "%.2f px shift, %.3f° rotation (%.2f px left unexplained)",
        fit.shiftPx(),
        abs(fit.rotationDeg),
        fit.residualPx,
    )
}
