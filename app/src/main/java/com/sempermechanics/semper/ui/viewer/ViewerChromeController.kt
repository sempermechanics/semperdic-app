package com.sempermechanics.semper.ui.viewer

import android.view.View
import android.view.ViewTreeObserver

/**
 * The viewer's edge chrome: the top bar, the scrubber, the field button and the
 * colour scale. Any interaction brings them back and schedules their auto-hide;
 * the image is fitted to the space between the bars.
 *
 * Constructed before onCreate; reads [ResultViewerActivity.binding] lazily.
 */
internal class ViewerChromeController(private val host: ResultViewerActivity) {

    private val binding get() = host.binding

    private var chromeVisible = true

    // Not while a frame number is being typed: hiding the scrubber takes the
    // field's focus, which commits it and closes the keyboard mid-number. The
    // commit on focus loss bumps the chrome, so the hide is rescheduled then.
    private val hideChromeRunnable = Runnable {
        if (!binding.etFrameNumber.hasFocus()) fadeChrome(visible = false)
    }

    /**
     * Measures the top bar and scrub bar once they've laid out and feeds those
     * sizes to the image as content insets, so the heatmap's fit-to-screen view
     * fills the space between them (full width). The colour scale is a sibling
     * overlay on the right — it may cover the image; it is not a reserved inset.
     * Inset values only change on a real layout event (initial layout, rotation)
     * — [fadeChrome] toggles VISIBLE/INVISIBLE, never GONE, so a bar keeps its
     * laid-out size while faded and the safe area stays stable through the
     * auto-hide animation.
     */
    fun wireContentInsets() {
        binding.imgBaseResult.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val top = binding.chromeTop.height
                    val bottom = binding.layoutScrubber.height
                    if (top > 0 && bottom > 0) {
                        binding.imgBaseResult.setContentInsets(top = top, bottom = bottom)
                    }
                }
            },
        )
    }

    /** Bring edge chrome back, then schedule auto-hide. */
    fun bumpChrome() {
        if (chromeVisible) {
            binding.chromeTop.removeCallbacks(hideChromeRunnable)
            binding.chromeTop.postDelayed(hideChromeRunnable, CHROME_HIDE_DELAY_MS)
            return
        }
        fadeChrome(visible = true)
        binding.chromeTop.removeCallbacks(hideChromeRunnable)
        binding.chromeTop.postDelayed(hideChromeRunnable, CHROME_HIDE_DELAY_MS)
    }

    /**
     * Centre double-tap while chrome is hidden: show the bars. Returns true when
     * consumed so zoom does not also run.
     */
    fun showChromeIfHidden(): Boolean {
        if (chromeVisible) return false
        bumpChrome()
        return true
    }

    private fun fadeChrome(visible: Boolean) {
        chromeVisible = visible
        val bars = with(binding) { listOf(chromeTop, layoutScrubber, btnFieldFab, layoutColorScale) }
        bars.forEach { bar ->
            bar.animate().cancel()
            if (visible) {
                bar.visibility = View.VISIBLE
                if (bar.alpha < OPAQUE) {
                    bar.animate().alpha(1f).setDuration(FADE_IN_MS).start()
                } else {
                    bar.alpha = 1f
                }
            } else {
                bar.animate()
                    .alpha(0f)
                    .setDuration(FADE_OUT_MS)
                    .withEndAction { bar.visibility = View.INVISIBLE }
                    .start()
            }
        }
    }

    /** Drops the pending auto-hide and stops every bar's fade. */
    fun cancel() {
        binding.chromeTop.removeCallbacks(hideChromeRunnable)
        binding.chromeTop.animate().cancel()
        binding.layoutScrubber.animate().cancel()
        binding.btnFieldFab.animate().cancel()
        binding.layoutColorScale.animate().cancel()
    }

    private companion object {
        const val CHROME_HIDE_DELAY_MS = 2_500L
        const val FADE_IN_MS = 180L
        const val FADE_OUT_MS = 320L

        /** Above this alpha a bar counts as fully shown. */
        const val OPAQUE = 0.99f
    }
}
