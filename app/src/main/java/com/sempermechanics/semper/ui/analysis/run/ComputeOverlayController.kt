// Overlay helper binds a fixed set of named views; the constructor list and the
// small progress/animation constants read clearest passed and inlined directly.
@file:Suppress("LongParameterList")

package com.sempermechanics.semper.ui.analysis.run

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ActivityStaticAnalysisBinding
import com.sempermechanics.semper.ui.analysis.wizard.BatchProgressUpdate
import com.sempermechanics.semper.ui.common.EtaEstimator
import com.sempermechanics.semper.util.OverlayFormats
import kotlin.math.roundToInt

/**
 * Owns the compute / import progress overlay: visibility, the progress ring,
 * time left and elapsed, the status line and, for a batch run, the frame count,
 * pace and convergence-per-frame graph with its one-strike warning.
 *
 * Progress ticks are coalesced (~100 ms) into a single main-thread post that
 * updates all wired widgets together.
 */
class ComputeOverlayController(
    private val overlay: View,
    private val title: TextView,
    private val progress: ProgressBar,
    private val percent: TextView,
    private val status: TextView,
    private val elapsed: TextView,
    private val eta: TextView,
    private val pace: TextView? = null,
    private val frameCount: TextView? = null,
    private val convergenceGroup: View? = null,
    private val convergenceLatest: TextView? = null,
    private val convergenceLine: ConvergenceLineView? = null,
    private val strikeWarn: TextView? = null,
) {
    /** The wizard's overlay, with its run detail. */
    constructor(wizard: ActivityStaticAnalysisBinding) : this(
        overlay = wizard.computeOverlay,
        title = wizard.overlayTitle,
        progress = wizard.overlayProgress,
        percent = wizard.overlayPercent,
        status = wizard.overlayStatus,
        elapsed = wizard.overlayElapsed,
        eta = wizard.tvRunEta,
        pace = wizard.tvRunPace,
        frameCount = wizard.tvRunFrameCount,
        convergenceGroup = wizard.runConvergenceGroup,
        convergenceLatest = wizard.tvRunConvergenceLatest,
        convergenceLine = wizard.convergenceLine,
        strikeWarn = wizard.tvRunStrikeWarn,
    )

    private val res get() = overlay.resources
    private val mainHandler = Handler(Looper.getMainLooper())
    private val etaEstimator = EtaEstimator()
    private val elapsedTicker = object : Runnable {
        override fun run() {
            elapsed.text = res.getString(R.string.run_elapsed_fmt, OverlayFormats.elapsed(elapsedMs()))
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    /** Wall-clock start of what the overlay shows; the batch run times itself from it too. */
    var processingStartTime: Long = 0

    // Coalesced pending state (any thread may write; flushed on main).
    @Volatile private var pendingPercent: Float? = null

    @Volatile private var pendingStatus: String? = null

    @Volatile private var pendingTitle: String? = null

    @Volatile private var pendingRun: BatchProgressUpdate? = null

    private var lastFlushUptimeMs = 0L
    private var flushScheduled = false

    private val flushRunnable = Runnable {
        flushScheduled = false
        applyPending()
    }

    /**
     * Shows the overlay from zero. [showConvergence] is for a batch run: the
     * frame count, pace and convergence graph. An import, a video extraction
     * and a sweep show the ring, time left and status only.
     */
    fun show(
        title: String = res.getString(R.string.computing_strain_field),
        status: String = res.getString(R.string.initializing_engine),
        showConvergence: Boolean = true,
    ) {
        mainHandler.removeCallbacks(flushRunnable)
        flushScheduled = false
        clearPending()
        etaEstimator.reset()
        this.title.text = title
        progress.max = RING_MAX
        progress.progress = 0
        percent.text = RunOverlayText.percent(0f)
        this.status.text = status
        elapsed.text = res.getString(R.string.run_elapsed_fmt, OverlayFormats.elapsed(0))
        eta.setText(R.string.eta_estimating)
        // Reset the run detail too, so a re-run doesn't flash the PREVIOUS run's
        // graph and counts until its first frame completes.
        pace?.visibility = View.GONE
        frameCount?.visibility = View.GONE
        strikeWarn?.visibility = View.GONE
        convergenceLatest?.text = null
        convergenceLine?.setValues(FloatArray(0))
        convergenceGroup?.visibility = if (showConvergence) View.VISIBLE else View.GONE
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

    /** An import, extraction or sweep tick: ring, status, title. Safe to call from any thread. */
    fun update(percent: Float? = null, status: String? = null, title: String? = null) {
        if (percent != null) pendingPercent = percent.coerceIn(0f, PERCENT)
        if (status != null) pendingStatus = status
        if (title != null) pendingTitle = title
        scheduleFlush()
    }

    /**
     * A batch run's tick. The overlay words its own status from the frame
     * fields; [BatchProgressUpdate.status] is the engine-side log line.
     */
    fun updateRun(tick: BatchProgressUpdate) {
        pendingPercent = tick.percent.coerceIn(0f, PERCENT)
        // A tick without a snapshot keeps the last one: only frame ends carry it.
        val previous = pendingRun
        pendingRun = if (tick.perFrameConvergence == null && previous?.perFrameConvergence != null) {
            tick.copy(perFrameConvergence = previous.perFrameConvergence)
        } else {
            tick
        }
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
            percent.text = RunOverlayText.percent(it)
            val left = etaEstimator.sample(it / PERCENT.toDouble(), SystemClock.elapsedRealtime())
            eta.text = EtaEstimator.label(res, left) ?: res.getString(R.string.eta_estimating)
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
        pendingRun?.let {
            applyRun(it)
            pendingRun = null
        }
    }

    private fun applyRun(tick: BatchProgressUpdate) {
        frameCount.showText(RunOverlayText.frameCount(res, tick))
        status.text = RunOverlayText.status(res, tick)
        pace.showText(RunOverlayText.pace(res, tick, elapsedMs()))
        val values = tick.perFrameConvergence ?: return
        convergenceLine?.setValues(values)
        convergenceLatest?.text = ConvergenceTrace.latest(values)?.let {
            res.getString(R.string.run_convergence_latest_fmt, it)
        }
        strikeWarn.showText(RunOverlayText.strikeWarning(res, values))
    }

    private fun elapsedMs(): Long = System.currentTimeMillis() - processingStartTime

    private fun clearPending() {
        pendingPercent = null
        pendingStatus = null
        pendingTitle = null
        pendingRun = null
    }

    companion object {
        private const val THROTTLE_MS = 100L
        private const val TICK_MS = 1000L
        private const val PERCENT = 100f
        private const val RING_MAX = 1000
        private const val RING_SCALE = 10f
    }
}

/** Shows [text], or hides the view when there is none. */
private fun TextView?.showText(text: String?) {
    this ?: return
    visibility = if (text != null) View.VISIBLE else View.GONE
    if (text != null) this.text = text
}
