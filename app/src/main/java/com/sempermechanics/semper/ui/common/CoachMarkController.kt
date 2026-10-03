// The overlay is built in code, so its dp paddings, corner radii and scrim
// alpha are literal by nature and read clearest inline — MagicNumber is
// suppressed for this file.
@file:Suppress("MagicNumber")

package com.sempermechanics.semper.ui.common

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.CoachPrefs

/**
 * Lightweight first-visit coach: dim overlay + speech bubble anchored near a
 * target view. No third-party showcase library.
 */
class CoachMarkController(
    private val activity: Activity,
) {
    data class Step(
        val target: View,
        val message: String,
        val onEnter: (() -> Unit)? = null,
        val illustration: Int? = null,
    )

    private var overlayRoot: FrameLayout? = null
    private var holeView: View? = null
    private var bubble: LinearLayout? = null
    private var illustrationView: ImageView? = null
    private var messageView: TextView? = null
    private var nextButton: MaterialButton? = null
    private var skipButton: MaterialButton? = null

    private var steps: List<Step> = emptyList()
    private var index: Int = 0
    private var screen: CoachPrefs.Screen? = null

    /**
     * Shows the coach if [screen] has not been seen. Marks seen on Skip/Done
     * (and when the sequence finishes via Next).
     */
    @Suppress("ReturnCount") // three independent "nothing to show" guards
    fun maybeShow(
        screen: CoachPrefs.Screen,
        steps: List<Step>,
        overlayParent: ViewGroup? = null,
    ) {
        if (steps.isEmpty()) return
        if (CoachPrefs.hasSeen(activity, screen)) return
        if (overlayRoot != null) return
        this.screen = screen
        this.steps = steps
        this.index = 0
        attachOverlay(overlayParent)
        showStep(0)
    }

    fun dismiss(markSeen: Boolean = true) {
        val root = overlayRoot ?: return
        (root.parent as? ViewGroup)?.removeView(root)
        overlayRoot = null
        holeView = null
        bubble = null
        illustrationView = null
        messageView = null
        nextButton = null
        skipButton = null
        if (markSeen) {
            screen?.let { CoachPrefs.markSeen(activity, it) }
        }
        screen = null
        steps = emptyList()
    }

    private fun attachOverlay(overlayParent: ViewGroup?) {
        val content = overlayParent ?: activity.findViewById(android.R.id.content)
        val root = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            isClickable = true
            isFocusable = true
        }

        val hole = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(0, 0)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), ContextCompat.getColor(activity, R.color.sky_primary))
                cornerRadius = dp(8).toFloat()
            }
        }

        val bubbleLayout = buildBubble()

        root.addView(hole)
        root.addView(bubbleLayout)
        content.addView(root)

        overlayRoot = root
        holeView = hole
        bubble = bubbleLayout
    }

    /** The speech bubble and its actions; also binds the views the steps update. */
    private fun buildBubble(): LinearLayout {
        val bubblePad = dp(14)
        val bubbleLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(bubblePad, bubblePad, bubblePad, bubblePad)
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(activity, R.color.surface))
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(20)
                marginEnd = dp(20)
            }
        }

        val illustration = ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isForceDarkAllowed = false
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        val msg = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            textSize = 15f
        }
        bubbleLayout.addView(illustration)
        bubbleLayout.addView(msg)
        bubbleLayout.addView(buildBubbleActions())

        illustrationView = illustration
        messageView = msg
        return bubbleLayout
    }

    /** Skip/Next row appended to the bubble; also binds [skipButton]/[nextButton]. */
    private fun buildBubbleActions(): LinearLayout {
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        val skip = MaterialButton(activity).apply {
            text = activity.getString(R.string.coach_skip)
            setOnClickListener { dismiss(markSeen = true) }
        }
        val next = MaterialButton(activity).apply {
            text = activity.getString(R.string.coach_next)
            setOnClickListener { advance() }
        }
        actions.addView(skip)
        actions.addView(next)
        skipButton = skip
        nextButton = next
        return actions
    }

    private fun advance() {
        if (index >= steps.lastIndex) {
            dismiss(markSeen = true)
        } else {
            showStep(index + 1)
        }
    }

    private fun showStep(stepIndex: Int) {
        index = stepIndex
        val step = steps[stepIndex]
        messageView?.text = step.message
        val ill = illustrationView
        if (ill != null) {
            val res = step.illustration
            if (res != null) {
                ill.setImageResource(res)
                ill.visibility = View.VISIBLE
            } else {
                ill.setImageDrawable(null)
                ill.visibility = View.GONE
            }
        }
        val last = stepIndex == steps.lastIndex
        nextButton?.text = activity.getString(
            if (last) R.string.coach_done else R.string.coach_next,
        )
        skipButton?.visibility = if (last) View.GONE else View.VISIBLE
        step.onEnter?.invoke()

        step.target.post {
            if (overlayRoot == null) return@post
            layoutAround(step.target)
        }
    }

    @Suppress("ReturnCount") // one bail per missing piece, then the retry when the target has no bounds yet
    private fun layoutAround(target: View) {
        val hole = holeView ?: return
        val bubbleLayout = bubble ?: return
        val root = overlayRoot ?: return

        val targetRect = Rect()
        target.getGlobalVisibleRect(targetRect)
        val rootRect = Rect()
        root.getGlobalVisibleRect(rootRect)
        if (targetRect.isEmpty || rootRect.isEmpty) {
            // Target not laid out yet — retry once.
            target.post { if (overlayRoot != null) layoutAround(target) }
            return
        }

        val pad = dp(6)
        val left = (targetRect.left - rootRect.left - pad).coerceAtLeast(0)
        val top = (targetRect.top - rootRect.top - pad).coerceAtLeast(0)
        val width = targetRect.width() + pad * 2
        val height = targetRect.height() + pad * 2

        (hole.layoutParams as FrameLayout.LayoutParams).apply {
            this.leftMargin = left
            this.topMargin = top
            this.width = width
            this.height = height
            gravity = Gravity.TOP or Gravity.START
        }
        hole.requestLayout()

        bubbleLayout.measure(
            View.MeasureSpec.makeMeasureSpec(root.width - dp(40), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val bubbleH = bubbleLayout.measuredHeight
        val spaceBelow = root.height - (top + height)
        val bubbleTop = if (spaceBelow >= bubbleH + dp(16)) {
            top + height + dp(12)
        } else {
            (top - bubbleH - dp(12)).coerceAtLeast(dp(24))
        }
        (bubbleLayout.layoutParams as FrameLayout.LayoutParams).apply {
            this.topMargin = bubbleTop
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        bubbleLayout.requestLayout()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
