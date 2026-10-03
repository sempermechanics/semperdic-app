package com.sempermechanics.semper.ui.common

import android.graphics.Rect
import android.view.View
import android.widget.ScrollView

/**
 * The keyboard arithmetic behind [Insets.padImeBottom]: how much of the IME
 * covers a scroll view, and the scroll that brings its focused field clear.
 */
internal object ImeReveal {

    private const val REVEAL_MARGIN_DP = 8

    /** How much of a keyboard [imeBottom] px tall covers a view with [spaceBelow] px of window under it. */
    fun overlap(imeBottom: Int, spaceBelow: Int): Int = (imeBottom - spaceBelow).coerceAtLeast(0)

    /**
     * The scroll that brings [top]..[bottom] inside [visibleTop]..[visibleBottom]:
     * 0 when it already fits, and the top wins when it is taller than the window.
     */
    fun revealDelta(top: Int, bottom: Int, visibleTop: Int, visibleBottom: Int): Int = when {
        bottom > visibleBottom -> minOf(bottom - visibleBottom, top - visibleTop)
        top < visibleTop -> top - visibleTop
        else -> 0
    }

    fun revealFocused(scroll: ScrollView) {
        val focused = scroll.findFocus() ?: return
        val rect = Rect()
        focused.getDrawingRect(rect)
        scroll.offsetDescendantRectToMyCoords(focused, rect)
        val margin = (REVEAL_MARGIN_DP * scroll.resources.displayMetrics.density).toInt()
        val dy = revealDelta(
            rect.top - margin,
            rect.bottom + margin,
            scroll.scrollY + scroll.paddingTop,
            scroll.scrollY + scroll.height - scroll.paddingBottom,
        )
        if (dy != 0) scroll.smoothScrollBy(0, dy)
    }

    fun isInside(view: View, ancestor: View): Boolean {
        var p = view.parent
        while (p is View) {
            if (p === ancestor) return true
            p = p.parent
        }
        return false
    }
}
