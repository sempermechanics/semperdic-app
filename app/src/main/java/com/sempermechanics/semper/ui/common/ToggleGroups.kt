package com.sempermechanics.semper.ui.common

import com.google.android.material.button.MaterialButtonToggleGroup

/** Runs [onChecked] with the id of each button that becomes checked; unchecks are ignored. */
fun MaterialButtonToggleGroup.onButtonChecked(onChecked: (checkedId: Int) -> Unit) {
    addOnButtonCheckedListener { _, checkedId, isChecked ->
        if (isChecked) onChecked(checkedId)
    }
}
