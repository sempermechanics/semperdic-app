package com.indicvision.semper.ui.analysis.wizard

import android.widget.EditText
import com.google.android.material.slider.Slider
import com.indicvision.semper.ui.common.commitOnDone
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
