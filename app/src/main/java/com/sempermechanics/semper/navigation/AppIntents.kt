package com.sempermechanics.semper.navigation

import android.content.Context
import android.content.Intent

/**
 * Intent factories for cross-layer navigation without `data`/`report` importing
 * concrete Activity classes under `ui`.
 */
object AppIntents {
    private const val SESSION_LIMIT =
        "com.sempermechanics.semper.ui.limit.SessionLimitActivity"

    private const val SEAT_REQUIRED =
        "com.sempermechanics.semper.ui.limit.SeatRequiredActivity"

    fun sessionLimit(context: Context): Intent = gate(context, SESSION_LIMIT)

    /** The "someone else has the seat" screen, for a floating institution license. */
    fun seatRequired(context: Context): Intent = gate(context, SEAT_REQUIRED)

    /**
     * SINGLE_TOP + CLEAR_TOP so the repeated ways a gate is raised — cold
     * start, a reconcile that newly finds it, the FAB, an upload rejection —
     * never stack duplicate instances.
     */
    private fun gate(context: Context, className: String): Intent =
        Intent().setClassName(context, className).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
}
