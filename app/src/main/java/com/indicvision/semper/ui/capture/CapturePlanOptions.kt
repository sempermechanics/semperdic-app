package com.indicvision.semper.ui.capture

/**
 * The capture rates this device can actually sustain, offered as a short list.
 *
 * Rate is the unit an experiment is designed in — "I need 10 frames a second
 * through the yield point" — so it is the unit the user picks in. The frame
 * count and the spacing follow from it rather than the other way round.
 *
 * Every rate offered is one the run will hold for its whole duration. The
 * ceiling comes from [CaptureFrameCost], which takes the larger of the
 * sensor's own per-resolution read-out floor (from Camera2's stream
 * configuration map) and the measured software PNG encode cost — so a rate
 * that the sensor cannot read out is never on the list, and neither is one the
 * CPU cannot encode.
 *
 * Nothing offered sits *at* that ceiling. Two margins hold it down:
 *  * [CaptureCalibration.SAFETY_FACTOR] already pads the measured encode cost,
 *  * [ASSURANCE_MARGIN] pads the combined figure again,
 *
 * and then the result is snapped *down* to a standard rate from [FPS_LADDER],
 * which usually costs a little more headroom still. A device running slower
 * than it measured — thermal throttling, a busy CPU, a larger-than-usual PNG —
 * therefore still finishes the run it promised.
 *
 * Nothing here is device-specific: both halves of the cost are read or measured
 * from whatever hardware is running, so a slow phone is offered lower rates
 * rather than the same rates with worse odds.
 */
internal object CapturePlanOptions {

    /**
     * Headroom over the already-padded per-frame cost. A promise that holds
     * only when everything goes right is not a promise, and the cost of being
     * wrong (a short run the user has to repeat on a loaded specimen) is far
     * higher than the cost of offering a slightly lower rate.
     */
    const val ASSURANCE_MARGIN = 1.30f

    /** More than a handful stops being a choice and becomes a slider again. */
    const val MAX_OPTIONS = 4

    /**
     * Standard capture rates, descending. Snapping to these keeps the choice
     * readable — "2 fps", not "2.64 fps" — and the snap is always downward, so
     * it can only add headroom.
     *
     * The ladder stops at [MIN_FPS]. The iDICs good-practice guidance this app
     * follows elsewhere asks for frames close enough together that a subset
     * moves less than its own size between them, and a rate below one frame a
     * second cannot hold that for anything but a creep test the app does not
     * claim to support. Rates of a frame every few seconds used to sit on this
     * ladder; what they produced was a sequence the solver could not track.
     */
    @Suppress("MagicNumber") // the ladder *is* the data; naming each rung adds nothing
    private val FPS_LADDER = floatArrayOf(
        60f, 30f, 24f, 20f, 15f, 12f, 10f, 8f, 6f, 5f, 4f, 3f, 2f, 1f,
    )

    /** The slowest rate this app will offer. See [FPS_LADDER]. */
    const val MIN_FPS = 1f

    /** Spacing between successive offers, so four chips span a useful range. */
    private const val OPTION_STEP_DOWN = 2f

    private const val MILLIS_PER_SECOND = 1_000f

    data class Option(
        val fps: Float,
        val frames: Int,
        val intervalMs: Long,
    )

    /**
     * Descending list of sustainable rates for [durationSec] at a resolution
     * costing [perFrameMs] per still, with the run's frame count kept within
     * [maxFramesSetting].
     *
     * **Empty is a real answer.** This used to fall back to a single
     * one-frame-per-run option when nothing on the ladder fit, which offered
     * the user a rate below [MIN_FPS] dressed up as a plan — a recording that
     * cannot be correlated afterwards. A combination this device cannot shoot
     * at [MIN_FPS] is not offerable, and the caller must say which limit binds
     * rather than showing a chip that leads nowhere. See
     * [cappedByFrameSetting] for which of the two it is.
     */
    fun of(durationSec: Int, perFrameMs: Long, maxFramesSetting: Int): List<Option> {
        val seconds = durationSec.coerceAtLeast(1)
        val cap = maxFramesSetting.coerceAtLeast(1)
        val ceiling = assuredMaxFps(perFrameMs, seconds, cap)
        return ladderFrom(ceiling).map { fps -> option(fps, seconds, cap) }
    }

    /**
     * Frames a run of [durationSec] needs to hold [MIN_FPS] — which at the
     * floor is one per second. The message that names *Max frames* as the
     * binding limit quotes this, so the user is told the number to raise it to.
     */
    fun framesNeededAtFloor(durationSec: Int): Int =
        (MIN_FPS * durationSec.coerceAtLeast(1)).toInt().coerceAtLeast(1)

    /**
     * Highest rate this device can hold, after both margins and the frame cap.
     * Returned unsnapped — [ladderFrom] does the rounding down.
     */
    fun assuredMaxFps(perFrameMs: Long, durationSec: Int, maxFramesSetting: Int): Float =
        minOf(hardwareFps(perFrameMs), frameCapFps(durationSec, maxFramesSetting))

    /**
     * Whether this device can hold [MIN_FPS] at a frame costing [perFrameMs],
     * ignoring the run's own length and the user's frame cap.
     *
     * Deliberately a *device* question and not a plan question. The frame cap
     * and the duration apply equally to every resolution, so they can never
     * make one resolution offerable and another not — only the camera can. That
     * is what makes this safe to ask once, when the screen is built, rather
     * than on every keystroke in the duration field.
     */
    fun sustainableAtFloor(perFrameMs: Long): Boolean = hardwareFps(perFrameMs) >= MIN_FPS

    /** True when [maxFramesSetting] — not the camera — is holding the list down. */
    fun cappedByFrameSetting(perFrameMs: Long, durationSec: Int, maxFramesSetting: Int): Boolean =
        frameCapFps(durationSec, maxFramesSetting) < hardwareFps(perFrameMs)

    /** Rate the device can hold once [ASSURANCE_MARGIN] is paid. */
    private fun hardwareFps(perFrameMs: Long): Float =
        MILLIS_PER_SECOND / (perFrameMs.coerceAtLeast(1L) * ASSURANCE_MARGIN)

    /** Rate above which the run would blow the user's own frame ceiling. */
    private fun frameCapFps(durationSec: Int, maxFramesSetting: Int): Float =
        maxFramesSetting.coerceAtLeast(1).toFloat() / durationSec.coerceAtLeast(1)

    private fun option(fps: Float, durationSec: Int, maxFramesSetting: Int): Option {
        val frames = (fps * durationSec).toInt().coerceIn(1, maxFramesSetting)
        val intervalMs = (MILLIS_PER_SECOND / fps).toLong().coerceAtLeast(1L)
        return Option(fps = fps, frames = frames, intervalMs = intervalMs)
    }

    /**
     * Standard rates at or below [ceiling], each at most half the previous one
     * so four chips cover a decade rather than crowding the top of the range.
     */
    private fun ladderFrom(ceiling: Float): List<Float> {
        val out = mutableListOf<Float>()
        var limit = ceiling
        while (out.size < MAX_OPTIONS) {
            val next = FPS_LADDER.firstOrNull { it <= limit } ?: break
            out.add(next)
            limit = next / OPTION_STEP_DOWN
        }
        return out
    }
}
