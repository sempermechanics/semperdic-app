package com.sempermechanics.semper.ui.analysis.wizard

import android.animation.ValueAnimator
import android.widget.EditText
import androidx.core.animation.doOnEnd
import com.google.android.material.slider.Slider
import com.sempermechanics.semper.ui.common.Motion
import com.sempermechanics.semper.ui.common.commitOnDone
import java.util.Locale

/**
 * Snaps [raw] into [slider]'s range and onto its step grid. Subset size and
 * strain window use stepSize 2 from an odd valueFrom, so a typed even value
 * lands on the nearest odd one.
 */
internal fun snapToSlider(slider: Slider, raw: Int): Int {
    val from = slider.valueFrom.toInt()
    val to = slider.valueTo.toInt()
    val step = slider.stepSize.toInt().coerceAtLeast(1)
    val offset = raw.coerceIn(from, to) - from
    return (from + (offset + step / 2) / step * step).coerceIn(from, to)
}

/**
 * Two-way binds this numeric field to [slider]; commits on Done or focus
 * loss. [onUserChange] fires only when the commit actually moves the
 * slider, so tabbing through a field is not mistaken for an edit.
 */
internal fun EditText.bindToSlider(slider: Slider, onUserChange: () -> Unit) {
    val commit = {
        val previous = slider.value.toInt()
        val typed = text.toString().trim().toIntOrNull()
        val value = if (typed == null) previous else snapToSlider(slider, typed)
        slider.value = value.toFloat()
        setText(String.format(Locale.ROOT, "%d", value))
        setSelection(text.length)
        if (value != previous) onUserChange()
    }
    commitOnDone(onDone = commit)
    setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
}

/** How long [glideTo] takes to move a slider. */
private const val GLIDE_MS = 250L

/**
 * Moves this slider to [target] (on its grid already) over 250 ms, every frame
 * snapped onto the grid, then calls [onEnd]. With animations off
 * ([Motion.reduced]) or the slider off screen it jumps there and calls [onEnd]
 * at once. A frame on which [interrupted] holds (the user took the slider)
 * stops the glide where it is; [onEnd] still runs.
 *
 * Returns the running animator, null when it jumped; its `end()` lands the
 * glide at once.
 */
internal fun Slider.glideTo(target: Int, interrupted: () -> Boolean, onEnd: () -> Unit): ValueAnimator? {
    val from = value.toInt()
    if (from == target || !isShown || Motion.reduced(context)) {
        value = target.toFloat()
        onEnd()
        return null
    }
    val slider = this
    return ValueAnimator.ofInt(from, target).apply {
        duration = GLIDE_MS
        addUpdateListener { glide ->
            if (interrupted()) {
                glide.cancel()
            } else {
                slider.value = snapToSlider(slider, glide.animatedValue as Int).toFloat()
            }
        }
        doOnEnd {
            if (!interrupted()) slider.value = target.toFloat()
            onEnd()
        }
        start()
    }
}
