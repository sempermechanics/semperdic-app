package com.sempermechanics.semper.ui.common

import android.view.View

/**
 * Busy state for a screen with one spinner: this view (the progress
 * indicator) shows while [busy], and every view in [controls] is disabled
 * while it does.
 *
 * Idle, the spinner goes to [idleVisibility], which every caller states:
 * [View.INVISIBLE] keeps its space so the layout does not jump while it spins
 * (Terms, Seat required, Session limit); [View.GONE] drops it (Auth, Pending,
 * Admin). Pass it by name, after the controls.
 */
fun View.setBusy(busy: Boolean, vararg controls: View, idleVisibility: Int) {
    require(idleVisibility == View.INVISIBLE || idleVisibility == View.GONE) {
        "idleVisibility must be INVISIBLE or GONE, was $idleVisibility"
    }
    visibility = if (busy) View.VISIBLE else idleVisibility
    for (control in controls) control.isEnabled = !busy
}
