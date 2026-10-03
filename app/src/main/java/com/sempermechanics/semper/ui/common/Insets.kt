package com.sempermechanics.semper.ui.common

import android.view.View
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Edge-to-edge window-insets helpers.
 *
 * On targetSdk 35+ (Android 15/16) apps are ALWAYS edge-to-edge — content
 * draws under the status bar and navigation bar, and the strips behind them
 * are system gesture zones. Without handling this, top toolbars sit under
 * the clock and in the swipe-down-for-notifications area (so taps get eaten
 * by the system), and bottom controls hide under the nav bar / gesture pill.
 *
 * These helpers add the relevant system-bar inset as PADDING (preserving any
 * padding already set in XML), so backgrounds still extend edge-to-edge while
 * the interactive content is pushed into the safe, reachable area.
 */
object Insets {

    /** Pad the top by the status-bar inset (e.g. an app bar). */
    fun padTop(view: View) = applyInsets(view, top = true)

    /** Pad the bottom by the navigation-bar inset (e.g. a bottom action row). */
    fun padBottom(view: View) = applyInsets(view, bottom = true)

    /** Pad both top and bottom (e.g. a full-screen root with its own bars). */
    fun padVertical(view: View) = applyInsets(view, top = true, bottom = true)

    /**
     * For a scroll container that hosts text fields: pad the top by the
     * status-bar inset and the bottom by whichever is larger — the navigation
     * bar or the IME (keyboard). When the keyboard opens, the extra bottom
     * padding shrinks the scroll viewport so the focused field can scroll clear
     * of the keyboard instead of being covered by it. Apply this to the
     * scrolling view itself (e.g. the ScrollView), not its inner content.
     */
    fun padTopAndImeBottom(view: View) {
        val startTop = view.paddingTop
        val startBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.updatePadding(
                top = startTop + bars.top,
                bottom = startBottom + barsOrImeBottom(windowInsets),
            )
            windowInsets
        }
        if (view.isAttachedToWindow) ViewCompat.requestApplyInsets(view)
    }

    /**
     * Pad the bottom of a scroll view that sits above its own bottom chrome (e.g.
     * a wizard nav bar that already takes the navigation-bar inset) by the part
     * of the keyboard that covers it, on top of any padding declared in XML. The
     * IME inset is measured from the window's bottom edge, so padding by all of
     * it would count the chrome under the view too, leaving a blank band above
     * the keyboard and hiding that much more of the page.
     *
     * Edge-to-edge (Android 15+) the window no longer resizes for the keyboard,
     * so ScrollView's own resize handling never brings the focused field back
     * into view, and its focus scrolling ignores padding. Once the keyboard is
     * up, the focused field (on opening, or after the IME's Next) is scrolled
     * clear of it here.
     */
    fun padImeBottom(view: ScrollView) {
        val startBottom = view.paddingBottom
        val location = IntArray(2)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.getLocationInWindow(location)
            val overlap = ImeReveal.overlap(ime, v.rootView.height - (location[1] + v.height))
            if (v.paddingBottom != startBottom + overlap) {
                v.updatePadding(bottom = startBottom + overlap)
                if (overlap > 0) v.post { ImeReveal.revealFocused(view) }
            }
            windowInsets
        }
        view.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (view.paddingBottom > startBottom && newFocus != null && ImeReveal.isInside(newFocus, view)) {
                view.post { ImeReveal.revealFocused(view) }
            }
        }
        if (view.isAttachedToWindow) ViewCompat.requestApplyInsets(view)
    }

    /**
     * For a bottom-anchored dock that holds text fields and sits under a
     * resizable canvas: pad the bottom by the navigation bar or, while the
     * keyboard is open, by the IME. The dock grows by the keyboard height so
     * its fields and buttons stay above it, and whatever is constrained to the
     * dock's top shrinks to fit.
     */
    fun padBottomAboveIme(view: View) {
        val startBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            v.updatePadding(bottom = startBottom + barsOrImeBottom(windowInsets))
            windowInsets
        }
        if (view.isAttachedToWindow) ViewCompat.requestApplyInsets(view)
    }

    /**
     * For a bottom bar floating over content whose layout must not change while
     * typing (e.g. a zoomable image fitted to the bars' heights): pad the bottom
     * by the navigation bar as [padBottom] does, and translate the bar up by the
     * part of the keyboard that reaches above the navigation bar. Its height
     * stays put, so nothing measured from it refits.
     */
    fun padBottomLiftAboveIme(view: View) {
        val startBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            ).bottom
            v.updatePadding(bottom = startBottom + bars)
            v.translationY = -(barsOrImeBottom(windowInsets) - bars).toFloat()
            windowInsets
        }
        if (view.isAttachedToWindow) ViewCompat.requestApplyInsets(view)
    }

    private fun barsOrImeBottom(windowInsets: WindowInsetsCompat): Int {
        val bars = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        ).bottom
        return maxOf(bars, windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
    }

    private fun applyInsets(view: View, top: Boolean = false, bottom: Boolean = false) {
        // Capture the padding declared in XML so we ADD the inset to it
        // rather than overwrite it (idempotent across re-dispatches).
        val startTop = view.paddingTop
        val startBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.updatePadding(
                top = if (top) startTop + bars.top else v.paddingTop,
                bottom = if (bottom) startBottom + bars.bottom else v.paddingBottom,
            )
            windowInsets
        }
        // Ensure the listener runs even if the view is already attached.
        if (view.isAttachedToWindow) ViewCompat.requestApplyInsets(view)
    }
}
