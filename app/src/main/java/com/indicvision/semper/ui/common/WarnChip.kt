package com.indicvision.semper.ui.common

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import com.indicvision.semper.R

/**
 * One `warn_chip_row` (the amber warning strip under a wizard input): its
 * text, its FAQ "i" button and whether it shows.
 *
 * The wizard repeated the same three steps for each chip — find `tvWarnText`
 * and set it, point `btnWarnFaq` at a FAQ page, flip `isVisible` — in
 * `StaticAnalysisActivity`, `AnalysisReadyGate`, `AnalysisWizardSlots` and
 * `SweepSetupHelper`. [openFaq] is what the button does with the page's URL
 * (the wizard asks first, through `FaqRedirect.confirm`).
 */
class WarnChip(
    val row: View,
    private val openFaq: (url: String) -> Unit,
) {
    private val text: TextView = row.findViewById(R.id.tvWarnText)
    private val faq: ImageButton = row.findViewById(R.id.btnWarnFaq)

    val isShown: Boolean get() = row.isVisible

    /** Points the "i" button at [url]; for a chip whose page never changes, call once. */
    fun setFaq(url: String) {
        faq.setOnClickListener { openFaq(url) }
    }

    /** Sets [message] and shows the chip; [faqUrl], when given, replaces the page. */
    fun show(message: CharSequence, faqUrl: String? = null) {
        text.text = message
        if (faqUrl != null) setFaq(faqUrl)
        row.isVisible = true
    }

    fun hide() {
        row.isVisible = false
    }

    /** [show] with [message], or [hide] when it is null. */
    fun showOrHide(message: CharSequence?, faqUrl: String? = null) {
        if (message == null) hide() else show(message, faqUrl)
    }
}
