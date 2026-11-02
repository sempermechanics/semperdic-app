package com.sempermechanics.semper.ui.common

import android.content.Context

/**
 * A line for the user: a string and its format arguments, or a plural when
 * [quantity] is set. A ViewModel hands these out, and the screen resolves
 * them in its own language, so the rules that pick a line are JVM tests.
 */
data class Note(val res: Int, val args: List<Any> = emptyList(), val quantity: Int? = null) {
    /** The line in [context]'s language. */
    @Suppress("SpreadOperator") // a line's two or three arguments, once per tap
    fun text(context: Context): String = if (quantity != null) {
        context.resources.getQuantityString(res, quantity, *args.toTypedArray())
    } else {
        context.getString(res, *args.toTypedArray())
    }
}
