package com.indicvision.semper.field

/**
 * A region of interest in reference-image pixels: left [x], top [y], width [w],
 * height [h]. Immutable.
 *
 * The same four ints travel today as `roiX..roiH` (wizard view model,
 * `RunSpec`, `SessionRecord`, `SessionRecordSettings`, `ViewerArgs`, the
 * viewer), `finalRectX..finalRectH` (`BatchAnalysisParams`), an `[x, y, w, h]`
 * `IntArray` (`RoiResolveHelper`, `RunSpec.of`, the wizard's saved state), an
 * `android.graphics.Rect`, an `[l, t, r, b]` `FloatArray` (`HeatmapFit`),
 * `RoiData` (reports), the `ROI_X..ROI_H` extras and the `engine.roi` object of
 * `metadata.json`. This is a view over them: building one from any of those and
 * converting back gives the same values, so nothing persisted changes.
 *
 * No invariant is enforced: a restored record can hold a zero or off-image ROI,
 * and the helpers below say what each caller does with one ([clampTo],
 * [isCustomFor], [orFullFrame]).
 *
 * Plain JVM: the `Rect` / `RectF` conversions live in `RoiRects.kt` and the
 * wire forms in `RoiCodecs.kt`.
 */
data class Roi(val x: Int, val y: Int, val w: Int, val h: Int) {

    /** Exclusive right edge, `x + w`. */
    val right: Int get() = x + w

    /** Exclusive bottom edge, `y + h`. */
    val bottom: Int get() = y + h

    /** True when the ROI holds at least one [subset]-sized window; `RoiResolveHelper.resolve`'s final check. */
    fun fits(subset: Int): Boolean = w >= subset && h >= subset

    /**
     * The part of this ROI inside an image of [size], or null when none of it
     * is or the size is unknown. Same arithmetic as
     * `RoiResolveHelper.clipToImage`, including its overflow-safe `Long` edges.
     */
    fun clampTo(size: ImageSize): Roi? {
        if (size.width <= 0 || size.height <= 0) return null
        val left = x.coerceIn(0, size.width)
        val top = y.coerceIn(0, size.height)
        val r = (x.toLong() + w).coerceIn(left.toLong(), size.width.toLong()).toInt()
        val b = (y.toLong() + h).coerceIn(top.toLong(), size.height.toLong()).toInt()
        return if (r > left && b > top) Roi(left, top, r - left, b - top) else null
    }

    /**
     * True when this is a real sub-rectangle of an image of [size]: non-empty
     * and not the whole frame. The viewer's rule (`HeatmapFit.isCustomRoi`),
     * which decides whether a heatmap is cropped to the ROI.
     *
     * The wizard decides "custom" differently when the ROI editor returns
     * (`StaticAnalysisActivity.applyRoiResult`): a selection is custom unless
     * its width and height equal the image's. That is ![coversFrameOf].
     */
    fun isCustomFor(size: ImageSize): Boolean =
        w > 0 && h > 0 && (x > 0 || y > 0 || w < size.width || h < size.height)

    /**
     * True when this ROI is exactly as wide and tall as [size], wherever it
     * sits: the wizard's "no custom ROI" test.
     */
    fun coversFrameOf(size: ImageSize): Boolean = w == size.width && h == size.height

    /**
     * The region the subset recommendation samples, as
     * `StaticAnalysisActivity.currentSamplingRoi` picks it: null while the
     * image size is unknown, else this ROI when [hasCustomRoi] and non-empty,
     * else the whole frame.
     */
    fun orFullFrame(hasCustomRoi: Boolean, size: ImageSize): Roi? = when {
        !size.isKnown -> null
        hasCustomRoi && w > 0 && h > 0 -> this
        else -> full(size)
    }

    /** `[left, top, right, bottom]` as floats: `HeatmapFit.resolve`'s box. */
    fun toLtrb(): FloatArray = floatArrayOf(x.toFloat(), y.toFloat(), right.toFloat(), bottom.toFloat())

    /** `[x, y, w, h]`: the array `RoiResolveHelper` returns and the wizard's saved state holds. */
    fun toXywh(): IntArray = intArrayOf(x, y, w, h)

    companion object {

        /**
         * Slack [insetFullFrame] adds beyond half a subset. Pinned by a test to
         * `RoiResolveHelper.ROI_MARGIN_SLACK_PX`.
         */
        const val FULL_FRAME_SLACK_PX = 10

        private const val XYWH_SIZE = 4

        /** The whole image. */
        fun full(size: ImageSize): Roi = Roi(0, 0, size.width, size.height)

        /**
         * The whole image inset by `subset / 2 + `[FULL_FRAME_SLACK_PX] a side, so no
         * subset hangs off the edge. May come out empty or negative for a small
         * image; [fits] says whether it can be solved.
         */
        fun insetFullFrame(size: ImageSize, subset: Int): Roi {
            val margin = subset / 2 + FULL_FRAME_SLACK_PX
            return Roi(margin, margin, size.width - 2 * margin, size.height - 2 * margin)
        }

        /**
         * The rectangle the engine solves over, or null when it cannot hold one
         * subset: [drawn] clamped to the image when [hasCustomRoi], else
         * [insetFullFrame]. Equivalent to `RoiResolveHelper.resolve`.
         */
        fun forSolve(subset: Int, hasCustomRoi: Boolean, drawn: Roi, size: ImageSize): Roi? {
            val roi = if (hasCustomRoi) drawn.clampTo(size) else insetFullFrame(size, subset)
            return roi?.takeIf { it.fits(subset) }
        }

        /** From `[left, top, right, bottom]` edges. */
        fun fromLtrb(left: Int, top: Int, right: Int, bottom: Int): Roi = Roi(left, top, right - left, bottom - top)

        /**
         * From an `[x, y, w, h]` array, or null unless it holds exactly four
         * values, as the wizard's restore checks.
         */
        fun fromXywh(xywh: IntArray?): Roi? = xywh?.takeIf { it.size == XYWH_SIZE }?.let {
            val next = it.iterator()
            Roi(x = next.nextInt(), y = next.nextInt(), w = next.nextInt(), h = next.nextInt())
        }
    }
}
