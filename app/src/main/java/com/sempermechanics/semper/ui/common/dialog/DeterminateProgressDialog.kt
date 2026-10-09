package com.sempermechanics.semper.ui.common.dialog

import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.EtaEstimator
import com.sempermechanics.semper.ui.common.ProgressText
import kotlin.math.roundToInt

/**
 * A determinate progress dialog: a status line with the percent on its right
 * ("Frame 12 of 40 · heatmaps", "30.0%"), a horizontal bar, and the time left
 * under it ("About 35 s left", from [EtaEstimator]; blank until it has an answer).
 *
 * The bar spins until the first [update]: a job that has not reported yet has
 * no percent to show.
 *
 * [onCancel] is the Cancel button (stops the work). [onBackground] is Back or
 * a tap outside the dimmed area — the dialog closes, the work keeps going.
 *
 * Build it on the main thread; [update] hops to the main thread itself, so
 * generators running on a background dispatcher can call it directly.
 *
 * @param clock monotonic milliseconds for the time-left estimate; a test's to set.
 */
class DeterminateProgressDialog(
    private val activity: AppCompatActivity,
    title: CharSequence,
    private val onCancel: (() -> Unit)? = null,
    private val onBackground: (() -> Unit)? = null,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    private val res = activity.resources
    private val pad = res.getDimensionPixelSize(R.dimen.space_24)
    private val gap = res.getDimensionPixelSize(R.dimen.space_12)

    private val label = TextView(activity).apply {
        text = title
    }

    private val percentView = TextView(activity).apply {
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Semper_Numeric)
        gravity = Gravity.END
    }

    private val etaView = TextView(activity).apply {
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Semper_Numeric)
        isVisible = false
    }

    private val bar = LinearProgressIndicator(activity).apply {
        isIndeterminate = true
        max = BAR_MAX
    }

    private val eta = EtaEstimator()

    private var cancelledByButton = false

    private val statusRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            percentView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = gap },
        )
    }

    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(pad, pad, pad, pad)
        addView(statusRow, matchWidth())
        addView(bar, matchWidth().apply { topMargin = gap })
        addView(etaView, matchWidth().apply { topMargin = res.getDimensionPixelSize(R.dimen.space_8) })
    }

    private val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setView(content)
        .setCancelable(onCancel != null || onBackground != null)
        .apply {
            if (onBackground != null) {
                setOnCancelListener {
                    if (!cancelledByButton) onBackground.invoke()
                }
            }
        }
        .create()

    init {
        if (onCancel != null) {
            content.addView(
                MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    setText(R.string.action_cancel)
                    setOnClickListener {
                        cancelledByButton = true
                        onCancel.invoke()
                        dialog.dismiss()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = gap
                    gravity = Gravity.END
                },
            )
        }
    }

    val isShowing: Boolean get() = dialog.isShowing

    fun show() {
        dialog.setCanceledOnTouchOutside(onBackground != null)
        dialog.show()
    }

    /**
     * Sets the bar to [percent] (0–100, fractions kept to a tenth) and the
     * status line to [text] when given. Safe to call from any thread.
     */
    fun update(percent: Double, text: CharSequence? = null) {
        activity.runOnUiThread {
            if (!dialog.isShowing) return@runOnUiThread
            val left = eta.sample(percent / PERCENT_MAX, clock())
            bar.isIndeterminate = false
            bar.setProgressCompat((percent * BAR_PER_PERCENT).roundToInt().coerceIn(0, BAR_MAX), true)
            percentView.text = ProgressText.percent(res, percent)
            if (text != null) label.text = text
            val leftText = EtaEstimator.label(res, left)
            etaView.text = leftText
            etaView.isVisible = leftText != null
        }
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val PERCENT_MAX = 100.0

        /** Bar steps per percent: the bar moves in tenths, as the percent reads. */
        const val BAR_PER_PERCENT = 10.0
        const val BAR_MAX = 1000
    }
}
