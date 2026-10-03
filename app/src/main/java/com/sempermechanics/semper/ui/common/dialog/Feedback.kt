package com.sempermechanics.semper.ui.common.dialog

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * One entry point for a plain system toast.
 *
 * Every screen built `Toast.makeText(context, …, Toast.LENGTH_SHORT / LONG).show()`
 * by hand (66 sites). The text is resolved against the caller's [Context], so
 * it is read in that screen's configuration exactly as before; the toast itself
 * is built on the application context, so a toast raised from a lambda that
 * outlives its Activity (a coroutine finishing after `finish()`) neither leaks
 * the Activity nor depends on its window.
 *
 * Main thread only, like [Toast.show].
 */
object Feedback {

    /** Shows [res] for [Toast.LENGTH_SHORT], or [Toast.LENGTH_LONG] when [long]. */
    fun toast(context: Context, @StringRes res: Int, long: Boolean = false) {
        toast(context, context.getText(res), long)
    }

    /** Shows [text] for [Toast.LENGTH_SHORT], or [Toast.LENGTH_LONG] when [long]. */
    fun toast(context: Context, text: CharSequence, long: Boolean = false) {
        Toast.makeText(appContext(context), text, lengthOf(long)).show()
    }

    /** The [Toast] duration a [long] flag stands for. */
    internal fun lengthOf(long: Boolean): Int = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

    /** The application context, or [context] itself where it has none (a bare test context). */
    private fun appContext(context: Context): Context = context.applicationContext ?: context
}
