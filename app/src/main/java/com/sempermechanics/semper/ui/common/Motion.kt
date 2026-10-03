package com.sempermechanics.semper.ui.common

import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.transition.AutoTransition
import androidx.transition.Transition
import androidx.transition.TransitionManager
import com.sempermechanics.semper.R

/**
 * Subtle, professional motion primitives shared across the app.
 *
 * Keeping these in one place guarantees every screen animates with the
 * same timing and easing, so motion reads as a single design language
 * rather than ad-hoc per-screen tweaks. All durations are short
 * (< 300 ms) to stay out of the way of an engineering workflow.
 */
object Motion {

    private const val EXPAND_DURATION = 240L

    /**
     * Animate a collapsible section open/closed. Call BEFORE flipping the
     * child's visibility; the layout change is then tweened automatically.
     *
     * @param container the parent whose bounds should animate (usually the
     *                  card or the root scroll child).
     */
    fun animateExpandCollapse(container: ViewGroup) {
        val transition: Transition = AutoTransition().apply {
            duration = EXPAND_DURATION
            // Fade content, then resize bounds — reads as a smooth reveal.
            ordering = AutoTransition.ORDERING_TOGETHER
        }
        TransitionManager.beginDelayedTransition(container, transition)
    }

    /** One-shot entrance: rise + fade. Use on a root/card when a screen appears. */
    fun enter(view: View, startDelayMs: Long = 0L) {
        val anim = AnimationUtils.loadAnimation(view.context, R.anim.fade_up)
        anim.startOffset = startDelayMs
        view.startAnimation(anim)
    }

    /**
     * Stagger children of a container so cards cascade in on first show.
     * @param stepMs delay added per child (keep small so it feels crisp).
     */
    fun enterStaggered(container: ViewGroup, stepMs: Long = 55L) {
        for (i in 0 until container.childCount) {
            enter(container.getChildAt(i), startDelayMs = i * stepMs)
        }
    }
}
