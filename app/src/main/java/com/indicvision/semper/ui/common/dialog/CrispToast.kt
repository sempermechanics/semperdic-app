package com.indicvision.semper.ui.common.dialog

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.indicvision.semper.R

/**
 * Compact wrap-content pill. Material Snackbars stretch full-width and read as
 * banners; this sits bottom-center and removes itself.
 */
object CrispToast {

    fun show(
        context: Context,
        message: CharSequence,
        long: Boolean = false,
        overlayRoot: ViewGroup? = null,
        fromTop: Boolean = false,
    ) {
        showInternal(
            context,
            message,
            overlayRoot,
            if (fromTop) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            if (long) LONG_MS else SHORT_MS,
            layout = R.layout.toast_crisp,
            edgeAware = true,
            fromTop = fromTop,
        )
    }

    /** Same pill with an explicit hold time (e.g. short instructional tips). */
    fun show(
        context: Context,
        message: CharSequence,
        overlayRoot: ViewGroup?,
        fromTop: Boolean,
        durationMs: Long,
    ) {
        showInternal(
            context,
            message,
            overlayRoot,
            if (fromTop) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            durationMs,
            layout = R.layout.toast_crisp,
            edgeAware = true,
            fromTop = fromTop,
        )
    }

    /**
     * Larger centred pill for gated hints (reference picker dim). [overlayRoot]
     * should be the dimmed content area so the message sits on the scrim.
     */
    fun showProminent(
        context: Context,
        message: CharSequence,
        overlayRoot: ViewGroup,
        durationMs: Long,
    ) {
        showInternal(
            context,
            message,
            overlayRoot,
            Gravity.CENTER,
            durationMs,
            layout = R.layout.toast_crisp_prominent,
            edgeAware = false,
            fromTop = false,
        )
    }

    @Suppress("LongParameterList")
    private fun showInternal(
        context: Context,
        message: CharSequence,
        overlayRoot: ViewGroup?,
        gravity: Int,
        durationMs: Long,
        layout: Int,
        edgeAware: Boolean,
        fromTop: Boolean,
    ) {
        val activity = context as? Activity
        if (activity == null || activity.isFinishing) return
        val root = overlayRoot ?: activity.findViewById(android.R.id.content) ?: return
        val existing = root.findViewWithTag<android.view.View>(TAG)
        if (existing != null) root.removeView(existing)
        val pill = activity.layoutInflater.inflate(layout, root, false)
        pill.tag = TAG
        pill.findViewById<TextView>(R.id.tvToast).text = message
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            this.gravity = gravity
            val m = (PAD_DP * activity.resources.displayMetrics.density).toInt()
            val edge = (EDGE_DP * activity.resources.displayMetrics.density).toInt()
            when {
                !edgeAware -> setMargins(m, m, m, m)
                fromTop -> setMargins(m, edge, m, m)
                else -> setMargins(m, m, m, edge)
            }
        }
        root.addView(pill, lp)
        pill.postDelayed({
            val parent = pill.parent as? ViewGroup
            parent?.removeView(pill)
        }, durationMs)
    }

    private const val TAG = "semper_crisp_toast"
    private const val SHORT_MS = 2000L
    private const val LONG_MS = 3500L
    private const val PAD_DP = 12
    private const val EDGE_DP = 72
}
