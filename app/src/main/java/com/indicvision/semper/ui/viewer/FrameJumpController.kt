package com.indicvision.semper.ui.viewer

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.ui.common.SerialJob
import com.indicvision.semper.ui.common.commitOnDone
import com.indicvision.semper.ui.common.hideKeyboard
import kotlinx.coroutines.delay

/**
 * Moving between the viewer's frames: Prev / Next (debounced, so a burst
 * decodes only the frame it settles on), the summary slot before frame 1, and
 * the typed frame number.
 *
 * Constructed before onCreate; reads [ResultViewerActivity.binding] lazily.
 */
internal class FrameJumpController(private val host: ResultViewerActivity) {

    private val binding get() = host.binding

    private val scrubDebounceJob = SerialJob()

    /**
     * True while the summary animation is up instead of a frame. It sits before
     * frame 1: Prev from frame 1 reaches it, Next leaves it.
     */
    var showingSummary = false

    /** Advance or retreat one frame (or leave/enter the summary). Used by buttons and fling. */
    fun stepFrame(delta: Int) {
        host.bumpChrome()
        if (delta == 0) return
        if (delta > 0) {
            if (showingSummary) {
                leaveSummary()
            } else if (host.currentFrameIndex < host.batchFiles.size - 1) {
                host.currentFrameIndex++
                updateNavButtons()
                requestFrameLoad(debounced = true)
            }
            return
        }
        when {
            showingSummary -> Unit
            host.currentFrameIndex == 0 -> if (!host.isSweep) enterSummary()
            else -> {
                host.currentFrameIndex--
                updateNavButtons()
                requestFrameLoad(debounced = true)
            }
        }
    }

    /** Debounce rapid Next/Prev so only the settled frame is decoded. */
    private fun requestFrameLoad(debounced: Boolean) {
        scrubDebounceJob.cancel()
        if (!debounced) {
            host.frames.loadFrameData(host.currentFrameIndex)
            return
        }
        scrubDebounceJob.launch(host.lifecycleScope) {
            delay(SCRUB_DEBOUNCE_MS)
            host.frames.loadFrameData(host.currentFrameIndex)
        }
    }

    fun updateNavButtons() {
        val prev = binding.btnPrevFrame
        val next = binding.btnNextFrame
        val batchFiles = host.batchFiles
        // Sweep: no summary slot, so Prev is inert on the first combination.
        prev.isEnabled =
            !showingSummary &&
            batchFiles.isNotEmpty() &&
            (host.currentFrameIndex > 0 || !host.isSweep)
        next.isEnabled = showingSummary || host.currentFrameIndex < batchFiles.size - 1

        prev.alpha = if (prev.isEnabled) 1.0f else DISABLED_ALPHA
        next.alpha = if (next.isEnabled) 1.0f else DISABLED_ALPHA
        // The number tracks the buttons, not the decode: a debounced scrub would
        // otherwise leave it a frame behind for as long as the load takes.
        syncFrameNumber()
    }

    // ── Summary slot ─────────────────────────────────────────────────────

    fun wireSummaryGestures() {
        val gif = binding.imgSummary
        gif.onScrubListener = { stepFrame(it) }
        gif.onCenterDoubleTapShowChrome = { host.showChromeIfHidden() }
        gif.onChromeSwipeListener = { show -> if (show) host.bumpChrome() }
        gif.onTapListener = { _, _ -> host.bumpChrome() }
    }

    fun enterSummary() {
        if (host.isSweep) return
        showingSummary = true
        host.inspect.dismissProbe()
        host.summary.show()
        binding.tvFrameCounter.text = host.summary.counterText()
        binding.layoutFrameJump.visibility = View.GONE
        binding.tvFinding.text = host.getString(
            R.string.viewer_edge_title_fmt,
            host.currentTypeString,
            host.getString(R.string.summary_title),
        )
        updateNavButtons()
        host.bumpChrome()
    }

    private fun leaveSummary() {
        showingSummary = false
        host.summary.hide()
        binding.layoutFrameJump.visibility = View.VISIBLE
        updateNavButtons()
        // Re-apply the frame's own labels and heatmap after the summary's.
        requestFrameLoad(debounced = false)
        host.bumpChrome()
    }

    // ── Typed frame jump ─────────────────────────────────────────────────

    fun wireFrameJump() {
        val field = binding.etFrameNumber
        field.commitOnDone(EditorInfo.IME_ACTION_GO) { commitFrameJump() }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitFrameJump()
        }
    }

    /**
     * Applies what is typed in the frame field. Anything unparseable or outside
     * the batch restores the current number rather than jumping somewhere the
     * user did not ask for.
     */
    private fun commitFrameJump() {
        host.bumpChrome()
        val field = binding.etFrameNumber
        val typed = field.text?.toString()?.trim()?.toIntOrNull()
        val target = typed?.minus(1)?.takeIf { it in host.batchFiles.indices }
        if (target == null) {
            syncFrameNumber()
        } else if (target != host.currentFrameIndex || showingSummary) {
            if (showingSummary) leaveSummary()
            host.currentFrameIndex = target
            updateNavButtons()
            // A typed number is a settled destination, unlike a Next/Prev burst.
            requestFrameLoad(debounced = false)
        }
        field.clearFocus()
        field.hideKeyboard()
    }

    fun syncFrameNumber() {
        val shown = if (showingSummary) "" else (host.currentFrameIndex + 1).toString()
        val field = binding.etFrameNumber
        if (field.text?.toString() != shown) field.setText(shown)
    }

    /** Drops a debounced load still waiting. */
    fun cancel() {
        scrubDebounceJob.cancel()
    }

    private companion object {
        const val SCRUB_DEBOUNCE_MS = 70L
        const val DISABLED_ALPHA = 0.5f
    }
}
