// Overlay helper binds a fixed set of named views; the constructor list and the
// small progress/animation constants read clearest passed and inlined directly.
@file:Suppress("LongParameterList", "MagicNumber")

@file:SuppressLint("SetTextI18n")

package com.indicvision.semper.ui.analysis

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.indicvision.semper.util.OverlayFormats
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Owns the compute / video-extraction progress overlay: visibility, progress
 * ring, status line, and the one-second elapsed ticker.
 *
 * Progress ticks are coalesced (~100 ms) into a single main-thread post that
 * updates all wired widgets together.
 */
class ComputeOverlayHelper(
    private val overlay: View,
    private val title: TextView,
    private val progress: ProgressBar,
    private val percent: TextView,
    private val status: TextView,
    private val elapsed: TextView,
    private val runPoints: TextView? = null,
    private val runConvergence: TextView? = null,
    private val runTilesRow: View? = null,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val elapsedTicker = object : Runnable {
        override fun run() {
            val ms = System.currentTimeMillis() - processingStartTime
            elapsed.text = "Elapsed ${OverlayFormats.elapsed(ms)}"
            mainHandler.postDelayed(this, 1000)
        }
    }

    var processingStartTime: Long = 0

    // Coalesced pending state (any thread may write; flushed on main).
    @Volatile private var pendingPercent: Float? = null

    @Volatile private var pendingStatus: String? = null

    @Volatile private var pendingTitle: String? = null

    @Volatile private var pendingPoints: Int? = null

    @Volatile private var pendingConvergence: Float? = null

    private var lastFlushUptimeMs = 0L
    private var flushScheduled = false

    private val flushRunnable = Runnable {
        flushScheduled = false
        applyPending()
    }

    fun show(
        title: String = "Computing Strain Field",
        status: String = "Initializing engine…",
        showRunTiles: Boolean = true,
    ) {
        mainHandler.removeCallbacks(flushRunnable)
        flushScheduled = false
        clearPending()
        this.title.text = title
        progress.max = RING_MAX
        progress.progress = 0
        percent.text = "0.0%"
        this.status.text = status
        elapsed.text = "Elapsed ${OverlayFormats.elapsed(0)}"
        // Reset the run tiles too, so a re-run doesn't flash the PREVIOUS run's
        // points/convergence until its first frame completes.
        runPoints?.text = OverlayFormats.compactCount(0)
        runConvergence?.text = "0.0%"
        runTilesRow?.visibility = if (showRunTiles) View.VISIBLE else View.GONE
        overlay.visibility = View.VISIBLE
        mainHandler.removeCallbacks(elapsedTicker)
        mainHandler.post(elapsedTicker)
    }

    fun hide() {
        mainHandler.removeCallbacks(flushRunnable)
        flushScheduled = false
        clearPending()
        overlay.visibility = View.GONE
        mainHandler.removeCallbacks(elapsedTicker)
    }

    /**
     * Drop every pending main-thread callback. Call from the host's onDestroy so
     * the self-reposting elapsed ticker cannot keep firing against a dead view
     * hierarchy after the Activity is gone.
     */
    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        flushScheduled = false
        clearPending()
    }

    /** Update the overlay's ring + percentage. Safe to call from any thread. */
    fun setProgress(percent: Float) {
        pendingPercent = percent.coerceIn(0f, 100f)
        scheduleFlush()
    }

    /** Update the overlay's status line (e.g. "Processing frame 2/5"). */
    fun setStatus(text: String) {
        pendingStatus = text
        scheduleFlush()
    }

    /** Update the overlay title. Safe to call from any thread. */
    fun setTitle(text: String) {
        pendingTitle = text
        scheduleFlush()
    }

    /**
     * Single coalesced update for progress + optional run tiles.
     * Prefer this over separate [setProgress]/[setStatus]/[setTitle] calls.
     */
    fun update(
        percent: Float? = null,
        status: String? = null,
        title: String? = null,
        pointsSolved: Int = -1,
        convergencePercent: Float = -1f,
    ) {
        if (percent != null) pendingPercent = percent.coerceIn(0f, 100f)
        if (status != null) pendingStatus = status
        if (title != null) pendingTitle = title
        if (pointsSolved >= 0) pendingPoints = pointsSolved
        if (convergencePercent >= 0f) pendingConvergence = convergencePercent
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        val now = SystemClock.uptimeMillis()
        val elapsedSince = now - lastFlushUptimeMs
        // Post through mainHandler, not overlay.post: show()/hide()/release() cancel
        // the flush via mainHandler.removeCallbacks, and a View's post() enqueues on
        // a different Handler instance, so those cancels would silently miss it and
        // a queued flush could still fire into a torn-down view hierarchy.
        if (elapsedSince >= THROTTLE_MS) {
            mainHandler.post(flushRunnable)
        } else {
            mainHandler.postDelayed(flushRunnable, THROTTLE_MS - elapsedSince)
        }
    }

    private fun applyPending() {
        lastFlushUptimeMs = SystemClock.uptimeMillis()
        pendingPercent?.let {
            progress.max = RING_MAX
            progress.progress = (it * RING_SCALE).roundToInt().coerceIn(0, RING_MAX)
            percent.text = String.format(Locale.US, "%.1f%%", it)
            pendingPercent = null
        }
        pendingStatus?.let {
            status.text = it
            pendingStatus = null
        }
        pendingTitle?.let {
            title.text = it
            pendingTitle = null
        }
        pendingPoints?.let { pts ->
            runPoints?.text = OverlayFormats.compactCount(pts)
            pendingPoints = null
        }
        pendingConvergence?.let { conv ->
            runConvergence?.text = String.format(Locale.US, "%.1f%%", conv)
            pendingConvergence = null
        }
    }

    private fun clearPending() {
        pendingPercent = null
        pendingStatus = null
        pendingTitle = null
        pendingPoints = null
        pendingConvergence = null
    }

    companion object {
        private const val THROTTLE_MS = 100L
        private const val RING_MAX = 1000
        private const val RING_SCALE = 10f
    }
}
