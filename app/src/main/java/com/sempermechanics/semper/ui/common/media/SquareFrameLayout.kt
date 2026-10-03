package com.sempermechanics.semper.ui.common.media

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A [FrameLayout] that forces its measured height to match its measured width.
 *
 * Used for grid tiles instead of hand-computing a pixel size from the parent's
 * width: letting the grid's own layout manager measure the width (whatever it
 * actually is, including any remainder-pixel rounding) and squaring off the
 * height from that keeps the tile size in sync with wherever the layout
 * manager decided to put the column boundary, so there's no seam from a
 * mismatched manual calculation.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
