package com.indicvision.semper.ui.capture

import timber.log.Timber

/**
 * How many millimetres of specimen one image pixel covers, when the device
 * will say enough to work it out.
 *
 * A speckle measured in pixels is a fact about the recording; the same speckle
 * in millimetres is a fact about the specimen, and only the second one can be
 * acted on at the bench. When a pattern is too fine for every resolution the
 * camera offers, the fix is a coarser pattern, and "make the dots about 0.4 mm"
 * is advice a user can follow while "make them 5 px" is not.
 *
 * ### The arithmetic
 *
 * Thin lens, magnification `m = f / (s - f)` for a subject at distance `s`
 * through a lens of focal length `f`. One image pixel spans
 * `sensorLongEdgeMm / imageLongEdgePx` on the sensor, and that divided by `m`
 * is what it spans on the specimen:
 *
 * ```
 * mmPerPx = (sensorLongEdgeMm / imageLongEdgePx) * (subjectDistanceMm - focalLengthMm) / focalLengthMm
 * ```
 *
 * The image is taken to span the full sensor because [CaptureIspApply] pins
 * `SCALER_CROP_REGION` to the whole active array, so there is no digital zoom
 * to correct for. Long edge against long edge, never width against width: the
 * test shot is frequently landscape while the run records portrait.
 *
 * ### Unavailable is the normal answer, not an error
 *
 * Subject distance is the weak link. Most phones report focus distance as
 * `UNCALIBRATED` and omit the EXIF tag entirely, so [Unavailable] is what this
 * returns on a great many devices — and everything that consumes it must read
 * as "not available" rather than as a failed measurement. A fabricated scale
 * would be far worse than none: it would put a millimetre figure in front of
 * someone who is about to re-make a specimen from it.
 */
object ImageScale {

    /** Millimetres per image pixel, or the honest absence of a figure. */
    sealed interface Result {
        data class Known(val mmPerPx: Double) : Result
        data object Unavailable : Result
    }

    /**
     * Sanity bounds. Each rejects a number that is arithmetically fine and
     * physically impossible for a phone photographing a specimen, which is what
     * a mis-parsed or mis-scaled EXIF tag looks like.
     */
    private const val MIN_SUBJECT_DISTANCE_MM = 50.0
    private const val MAX_SUBJECT_DISTANCE_MM = 5_000.0
    private const val MIN_FOCAL_LENGTH_MM = 1.0
    private const val MAX_FOCAL_LENGTH_MM = 50.0
    private const val MIN_SENSOR_EDGE_MM = 1.0
    private const val MAX_SENSOR_EDGE_MM = 50.0
    private const val MIN_MM_PER_PX = 0.0005
    private const val MAX_MM_PER_PX = 5.0

    private const val MM_PER_METRE = 1_000.0

    /**
     * Scale for a frame [imageLongEdgePx] across, from the focus lock's EXIF
     * and the camera's reported sensor size.
     *
     * @param focalLengthMm from EXIF, falling back to the camera's own list.
     * @param subjectDistanceM from EXIF. Usually absent; see the class KDoc.
     */
    fun of(
        sensorLongEdgeMm: Float?,
        focalLengthMm: Float?,
        subjectDistanceM: Float?,
        imageLongEdgePx: Int,
    ): Result {
        // Computed before it is known to be valid, so that every rejection can
        // be one branch of one decision rather than a ladder of early returns.
        // Nothing here throws on bad input: a missing figure becomes NaN and a
        // zero frame becomes an infinity, and both fall out of the range checks
        // below, because NaN compares false against every bound.
        val sensor = sensorLongEdgeMm?.toDouble() ?: Double.NaN
        val focal = focalLengthMm?.toDouble() ?: Double.NaN
        val distance = (subjectDistanceM?.toDouble() ?: Double.NaN) * MM_PER_METRE
        val mmPerPx = (sensor / imageLongEdgePx) * (distance - focal) / focal
        return when {
            sensorLongEdgeMm == null -> unavailable("no sensor size")
            focalLengthMm == null -> unavailable("no focal length")
            subjectDistanceM == null -> unavailable("no subject distance")
            imageLongEdgePx <= 0 -> unavailable("no image size")
            sensor !in MIN_SENSOR_EDGE_MM..MAX_SENSOR_EDGE_MM -> unavailable("sensor edge $sensor mm")
            focal !in MIN_FOCAL_LENGTH_MM..MAX_FOCAL_LENGTH_MM -> unavailable("focal length $focal mm")
            distance !in MIN_SUBJECT_DISTANCE_MM..MAX_SUBJECT_DISTANCE_MM ->
                unavailable("subject distance $distance mm")
            // Focus at or inside the focal length is not a photograph of anything.
            distance <= focal -> unavailable("subject inside the focal length")
            mmPerPx !in MIN_MM_PER_PX..MAX_MM_PER_PX -> unavailable("derived $mmPerPx mm/px")
            else -> Result.Known(mmPerPx)
        }
    }

    /**
     * The same scale on a differently sized frame: pixels get larger as the
     * frame gets smaller, in exact proportion.
     */
    fun scaledTo(mmPerPx: Double, fromLongEdge: Int, toLongEdge: Int): Double? {
        if (fromLongEdge <= 0 || toLongEdge <= 0 || !mmPerPx.isFinite()) return null
        return mmPerPx * fromLongEdge / toLongEdge
    }

    private fun unavailable(why: String): Result {
        Timber.i("image scale: not available (%s)", why)
        return Result.Unavailable
    }
}
