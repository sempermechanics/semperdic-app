package com.indicvision.semper.ui.analysis.sweep

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.Resources
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import com.indicvision.semper.R
import kotlin.math.roundToInt

// Copy-confirmation "pop + highlight" animation (readout and param chip).
private const val COPY_POP_SCALE = 1.06f
private const val COPY_POP_MS = 120L
private const val COPY_FLASH_MS = 500L
private const val COPY_FLASH_ALPHA = 120

/** Double-tapping or long-pressing [target] runs [copy]: the lattice's copy-the-params gesture. */
internal fun bindCopyGestures(target: View, copy: () -> Unit) {
    val detector = GestureDetector(
        target.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                copy()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                copy()
            }
        },
    )
    target.setOnTouchListener { v, event ->
        detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
        true
    }
}

/**
 * A quick "pop + highlight" on [view] to confirm the params were copied.
 * TalkBack hears the confirmation from the CrispToast pill, which is a
 * polite live region, so no explicit announcement is made here.
 */
internal fun animateCopyConfirmation(view: View) {
    view.animate()
        .scaleX(COPY_POP_SCALE).scaleY(COPY_POP_SCALE)
        .setDuration(COPY_POP_MS)
        .withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(COPY_POP_MS).start()
        }
        .start()
    // A foreground scrim flashes over both the transparent readout and the filled chip.
    val scrim = ContextCompat.getColor(view.context, R.color.sky_primary).toDrawable()
    view.foreground = scrim
    ObjectAnimator.ofInt(scrim, "alpha", COPY_FLASH_ALPHA, 0)
        .apply { duration = COPY_FLASH_MS }
        .apply { doOnEnd { view.foreground = null } }
        .start()
}

/**
 * Scrub readout: x plus the unmuted series' y values (one series → `y=…`,
 * several → each `label=value`).
 */
internal fun scrubReadout(context: Context, x: Float, samples: List<VsgPlotView.Sample>): CharSequence {
    if (x.isNaN() || samples.isEmpty()) return ""
    return if (samples.size == 1) {
        context.getString(R.string.vsg_lattice_scrub_xy_fmt, x, samples[0].value)
    } else {
        val ys = samples.joinToString("  ") { "${it.label}=${"%.4g".format(it.value)}" }
        context.getString(R.string.vsg_lattice_scrub_x_multi_fmt, x, ys)
    }
}

/**
 * The lattice's count line for a sweep with at least one solved node: planned,
 * solved and skipped combinations (with the subset/step ratio when known), and
 * how many a cancelled sweep never reached.
 */
internal fun latticeSummary(
    resources: Resources,
    nodes: List<VsgLatticeView.Node>,
    solvedCount: Int,
    skippedCount: Int,
    plannedFrames: Int,
): String {
    val stepDenom = nodes.firstOrNull()?.takeIf { it.step > 0 }
        ?.let { (it.subset.toDouble() / it.step).roundToInt() } ?: 0
    // A cancelled sweep never reached some combinations; they are neither
    // solved nor skipped, and counting only the two read as a finished plan.
    val total = maxOf(plannedFrames, nodes.size)
    val unreached = total - nodes.size
    val counts = if (stepDenom > 0) {
        resources.getQuantityString(
            R.plurals.vsg_lattice_summary_fmt,
            total,
            total,
            solvedCount,
            skippedCount,
            stepDenom,
        )
    } else {
        resources.getQuantityString(
            R.plurals.vsg_lattice_summary_short_fmt,
            total,
            total,
            solvedCount,
            skippedCount,
        )
    }
    return if (unreached > 0) {
        counts + resources.getQuantityString(R.plurals.vsg_lattice_unreached_fmt, unreached, unreached)
    } else {
        counts
    }
}
