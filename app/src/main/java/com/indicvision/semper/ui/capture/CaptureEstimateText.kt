package com.indicvision.semper.ui.capture

import android.content.Context
import com.indicvision.semper.R
import java.util.Locale

/**
 * Turns a [CapturePlanOptions.Option] into the sentence under the chips.
 *
 * It states the rate the user picked, what that comes to in frames, and how
 * far apart they land — all three, because a rate alone hides the run's cost
 * and a frame count alone hides its resolution in time.
 */
internal object CaptureEstimateText {

    private const val MILLIS_PER_SECOND = 1_000

    fun line(context: Context, option: CapturePlanOptions.Option, modeLabel: String): String =
        context.resources.getQuantityString(
            R.plurals.capture_estimate_fmt,
            option.frames,
            fps(option.fps),
            option.frames,
            spacing(context, option.intervalMs),
            modeLabel,
        )

    /**
     * Why the list stops where it does. Points at whichever limit is actually
     * binding, so the fix offered is the one that would work.
     */
    fun ceilingNote(context: Context, cappedBySetting: Boolean, maxFramesSetting: Int): String =
        if (cappedBySetting) {
            context.getString(R.string.capture_fps_ceiling_setting_fmt, maxFramesSetting)
        } else {
            context.getString(R.string.capture_fps_ceiling_camera)
        }

    /**
     * Why *nothing* is offered, which is a state the screen can reach now that
     * the ladder stops at [CapturePlanOptions.MIN_FPS].
     *
     * Emptiness on its own is a dead end — the same dead Continue button this
     * replaced — so the sentence names the limit that binds and the number
     * that would lift it. The two limits have opposite fixes and so get two
     * sentences: offering the wrong one sends the user to a setting that was
     * never the problem.
     */
    fun noRateFromSetting(context: Context, durationSec: Int, maxFramesSetting: Int): String {
        val needed = CapturePlanOptions.framesNeededAtFloor(durationSec)
        return context.resources.getQuantityString(
            R.plurals.capture_no_rate_setting_fmt,
            needed,
            durationSec,
            needed,
            maxFramesSetting,
        )
    }

    /** The other half of [noRateFromSetting]: the camera, not the setting. */
    fun noRateFromCamera(context: Context, resolutionLabel: String, perFrameMs: Long): String =
        context.getString(R.string.capture_no_rate_camera_fmt, resolutionLabel, perFrameMs)

    /** "2", "0.5" — never "2.0", which reads like false precision. */
    fun fps(value: Float): String =
        if (value >= 1f && value == value.toInt().toFloat()) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }

    private fun spacing(context: Context, intervalMs: Long): String =
        if (intervalMs >= MILLIS_PER_SECOND) {
            context.getString(
                R.string.capture_interval_sec_fmt,
                intervalMs / MILLIS_PER_SECOND.toFloat(),
            )
        } else {
            context.getString(R.string.capture_interval_ms_fmt, intervalMs)
        }
}
