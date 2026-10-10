package com.sempermechanics.semper.ui.common.dialog

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.DialogDeleteChoiceBinding
import com.sempermechanics.semper.databinding.ItemDeleteChoiceBinding

/**
 * What to delete for analyses that are on the phone and in the cloud: one
 * full-width button per choice, then Cancel. Home and Settings both use it,
 * with the same [phoneCloudEverywhere] choices, so a choice reads the same
 * everywhere.
 */
object DeleteChoiceDialog {

    /** A button: [label], and under it a smaller, muted [hint] when there is one. */
    data class Choice(val label: CharSequence, val hint: CharSequence? = null, val onPick: () -> Unit)

    /** Hint text against the label's. */
    private const val HINT_SCALE = 0.85f

    /** From this phone (cloud stays), from the cloud (phone stays), everywhere. */
    fun phoneCloudEverywhere(
        context: Context,
        onPhone: () -> Unit,
        onCloud: () -> Unit,
        onEverywhere: () -> Unit,
    ): List<Choice> = listOf(
        Choice(
            context.getString(R.string.delete_choice_phone),
            context.getString(R.string.delete_choice_phone_hint),
            onPhone,
        ),
        Choice(
            context.getString(R.string.delete_choice_cloud),
            context.getString(R.string.delete_choice_cloud_hint),
            onCloud,
        ),
        Choice(context.getString(R.string.delete_choice_everywhere), onPick = onEverywhere),
    )

    fun show(
        activity: AppCompatActivity,
        title: CharSequence,
        message: CharSequence,
        choices: List<Choice>,
    ) {
        val inflater = LayoutInflater.from(activity)
        val view = DialogDeleteChoiceBinding.inflate(inflater)
        view.tvDeleteMessage.text = message
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(view.root)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        choices.forEach { choice ->
            val button = ItemDeleteChoiceBinding.inflate(inflater, view.deleteChoices, false).root
            button.text = buttonText(activity, choice)
            button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            button.setOnClickListener {
                dialog.dismiss()
                choice.onPick()
            }
            view.deleteChoices.addView(button)
        }
        dialog.show()
    }

    /** The label, then the hint on its own line, smaller and in the secondary text colour. */
    private fun buttonText(context: Context, choice: Choice): CharSequence {
        val hint = choice.hint ?: return choice.label
        val text = SpannableStringBuilder(choice.label).append('\n')
        val start = text.length
        text.append(hint)
        text.setSpan(RelativeSizeSpan(HINT_SCALE), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val muted = ContextCompat.getColor(context, R.color.text_secondary)
        text.setSpan(ForegroundColorSpan(muted), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }
}
