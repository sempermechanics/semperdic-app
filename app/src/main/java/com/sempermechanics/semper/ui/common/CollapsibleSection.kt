package com.sempermechanics.semper.ui.common

import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible

/**
 * A section a tap on its [header] opens and closes, starting closed: the
 * wizard pages' "Advanced" (`view_advanced_header.xml`). Open, the [chevron]
 * points up; the change animates over [container]
 * ([Motion.animateExpandCollapse]), as Settings' sections do. Whether it is
 * open is not kept: the page shows it closed again after a recreate.
 */
class CollapsibleSection(
    private val header: View,
    private val chevron: View,
    private val body: View,
    private val container: ViewGroup,
) {
    /** True while [body] shows. */
    val isExpanded: Boolean get() = body.isVisible

    init {
        show(expanded = false)
        header.setOnClickListener { set(expanded = !isExpanded) }
    }

    /** Opens the section, unless it is open already. */
    fun expand() {
        if (!isExpanded) set(expanded = true)
    }

    private fun set(expanded: Boolean) {
        Motion.animateExpandCollapse(container)
        show(expanded)
    }

    private fun show(expanded: Boolean) {
        body.isVisible = expanded
        chevron.rotation = if (expanded) EXPANDED_DEG else 0f
    }

    private companion object {
        const val EXPANDED_DEG = 180f
    }
}
