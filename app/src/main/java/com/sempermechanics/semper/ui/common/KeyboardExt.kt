package com.sempermechanics.semper.ui.common

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.google.android.material.button.MaterialButtonToggleGroup

/** Hides the soft keyboard showing for this view's window. */
fun View.hideKeyboard() {
    context.getSystemService(InputMethodManager::class.java)
        ?.hideSoftInputFromWindow(windowToken, 0)
}

/**
 * The wizard's numeric fields: on the keyboard's [imeAction] key (Done by
 * default), run [onDone], drop focus and hide the keyboard. Any other action
 * is left to the field.
 *
 * Fields that commit on focus loss pass nothing: dropping focus commits.
 * The settings fields also commit first, and then their focus listener
 * commits again, as before.
 */
fun EditText.commitOnDone(imeAction: Int = EditorInfo.IME_ACTION_DONE, onDone: () -> Unit = {}) {
    setOnEditorActionListener { _, actionId, _ ->
        if (actionId == imeAction) {
            onDone()
            clearFocus()
            hideKeyboard()
            true
        } else {
            false
        }
    }
}

/** Shows [text], unless the user is typing in this field (it has focus). */
fun EditText.showUnlessEditing(text: CharSequence) {
    if (!hasFocus()) setText(text)
}

/** Runs [onChecked] with the id of each button that becomes checked; unchecks are ignored. */
fun MaterialButtonToggleGroup.onButtonChecked(onChecked: (checkedId: Int) -> Unit) {
    addOnButtonCheckedListener { _, checkedId, isChecked ->
        if (isChecked) onChecked(checkedId)
    }
}
