package com.sempermechanics.semper.ui.common

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay

/**
 * A busy state for one view [slot] during a wait the user would otherwise
 * watch in silence: a reference decoding, the speckle being measured, a
 * video's metadata, the viewer's first frame.
 *
 * The slot shows only once a wait has lasted [showAfterMs] (300 ms), so fast
 * work never flashes it, and goes when the wait ends however it ends.
 * While it shows, [pulse] (a skeleton) breathes and [spinner] (an
 * indeterminate indicator) turns; with animations off ([Motion.reduced]) the
 * pulse stays still and the spinner stays hidden, leaving the static state.
 *
 * Latest wins: [start] cancels a show still pending and returns a token, and
 * only the latest token's [stop] ends the wait, so an older wait finishing
 * late never hides a newer one. Bound to [owner]: the pending show is in its
 * lifecycleScope, and on destroy nothing more is shown or animated.
 *
 * Main thread only, like [SerialJob], which holds the pending show.
 */
class InlineBusy(
    private val owner: LifecycleOwner,
    private val slot: View,
    private val pulse: View? = null,
    private val spinner: View? = null,
    private val showAfterMs: Long = SHOW_AFTER_MS,
) {
    private val pending = SerialJob()
    private var token = 0
    private var pulsing: ValueAnimator? = null
    private var released = false

    /** True while the slot shows the busy state. */
    var isShown: Boolean = false
        private set

    /** True while [pulse] is animating. */
    @get:VisibleForTesting
    internal val isPulsing: Boolean get() = pulsing != null

    init {
        owner.lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) release() },
        )
    }

    /**
     * Starts a wait. [onShow] fills the slot (its text, which parts show) just
     * before it appears: after [showAfterMs], or at once when the slot is
     * already up for an earlier wait. Returns the token [stop] takes.
     */
    fun start(onShow: () -> Unit = {}): Int {
        val mine = ++token
        when {
            released -> Unit
            isShown -> onShow()
            else -> pending.launch(owner.lifecycleScope) {
                delay(showAfterMs)
                show(onShow)
            }
        }
        return mine
    }

    /** Ends the wait [started] began, unless a newer one has started since. */
    fun stop(started: Int) {
        if (started != token) return
        pending.cancel()
        if (!isShown) return
        isShown = false
        pulsing?.cancel()
        pulsing = null
        pulse?.alpha = 1f
        spinner?.isVisible = false
        slot.isVisible = false
    }

    /** Runs [block] as one wait: [start] before it, [stop] after, whether it returns, throws or is cancelled. */
    suspend fun <T> around(onShow: () -> Unit = {}, block: suspend () -> T): T {
        val started = start(onShow)
        try {
            return block()
        } finally {
            stop(started)
        }
    }

    private fun show(onShow: () -> Unit) {
        isShown = true
        onShow()
        val still = Motion.reduced(slot.context)
        spinner?.isVisible = !still
        slot.isVisible = true
        val target = pulse ?: return
        if (still) return
        pulsing = ObjectAnimator.ofFloat(target, View.ALPHA, 1f, PULSE_LOW_ALPHA).apply {
            duration = PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    /** The owner is gone: nothing pending lands, and the pulse lets go of its view. */
    private fun release() {
        released = true
        pending.cancel()
        pulsing?.cancel()
        pulsing = null
    }

    companion object {
        /** Waits shorter than this show nothing. */
        const val SHOW_AFTER_MS = 300L

        private const val PULSE_MS = 900L
        private const val PULSE_LOW_ALPHA = 0.45f
    }
}
