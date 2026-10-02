package com.indicvision.semper.ui.common.dialog

import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R

/**
 * What to delete for analyses that are on the phone and in the cloud: one
 * full-width button per choice, then Cancel. Home and Settings both use it,
 * so the same choice reads the same everywhere.
 */
object DeleteChoiceDialog {

    data class Choice(val label: CharSequence, val onPick: () -> Unit)

    fun show(
        activity: AppCompatActivity,
        title: CharSequence,
        message: CharSequence,
        choices: List<Choice>,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_delete_choice, null)
        view.findViewById<TextView>(R.id.tvDeleteMessage).text = message
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        val container = view.findViewById<LinearLayout>(R.id.deleteChoices)
        choices.forEach { choice ->
            val button = LayoutInflater.from(activity)
                .inflate(R.layout.item_delete_choice, container, false) as MaterialButton
            button.text = choice.label
            button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            button.setOnClickListener {
                dialog.dismiss()
                choice.onPick()
            }
            container.addView(button)
        }
        dialog.show()
    }
}
