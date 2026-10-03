package com.sempermechanics.semper.ui.common

import android.view.View

/**
 * Converts a density-independent-pixel value to raw pixels for this view's
 * current display density. Shared by the custom analysis views so the
 * conversion lives in one place.
 */
fun View.dp(value: Float): Float = value * resources.displayMetrics.density
